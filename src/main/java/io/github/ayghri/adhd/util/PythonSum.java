package io.github.ayghri.adhd.util;

/**
 * CPython's {@code sum()} over floats, which is NOT a naive accumulation.
 *
 * <p>Since CPython 3.12 the {@code builtin_sum} fast path applies Neumaier compensated summation
 * once it encounters a float. A plain {@code for} loop in Java therefore produces a different
 * {@code double} than the source program for the very same inputs, and because the results are
 * printed with shortest-round-trip formatting the difference is directly visible in stdout: the
 * evaluation harness prints {@code 2.3000000000000003} where a naive loop yields {@code 2.3}.
 *
 * <p>Do not "simplify" this into {@code DoubleStream.sum()} or a {@code +=} loop. Both are
 * arithmetically reasonable and both silently break output parity.
 */
public final class PythonSum {

    private PythonSum() {}

    /** Mirrors the compensated accumulation in CPython's {@code builtin_sum}. */
    public static double sum(Iterable<Double> values) {
        double result = 0.0;
        double compensation = 0.0;
        for (double value : values) {
            double total = result + value;
            if (Math.abs(result) >= Math.abs(value)) {
                compensation += (result - total) + value;
            } else {
                compensation += (value - total) + result;
            }
            result = total;
        }
        return result + compensation;
    }
}
