package io.github.ayghri.adhd.util;

/**
 * Reproduces CPython's {@code repr(float)} formatting.
 *
 * <p>Java's {@link Double#toString} and Python's {@code repr} both emit the shortest decimal string
 * that round-trips (Java has done so since JDK 19, which implements Raffaello Giulietti's algorithm),
 * but they disagree on how that digit string is laid out:
 *
 * <pre>
 *   value      Python repr      Double.toString
 *   1e-5       1e-05            1.0E-5
 *   1e16       1e+16            1.0E16
 *   1e15       1000000000000000.0
 *                               1.0E15
 *   0.0001     0.0001           1.0E-4
 * </pre>
 *
 * <p>The strategy is therefore to take Java's (correct) shortest digits, decompose them into a
 * {@code (digits, decpt)} pair where {@code value == 0.<digits> * 10^decpt}, and re-render using
 * CPython's {@code format_float_short} rules for {@code 'r'} mode: use exponential notation when
 * {@code decpt <= -4 || decpt > 16}, otherwise fixed notation, always keeping a {@code .0} suffix on
 * integral values.
 */
public final class PythonFloat {

    private PythonFloat() {}

    /** Python's {@code repr()} of a float. */
    public static String repr(double value) {
        if (Double.isNaN(value)) {
            return "nan";
        }
        if (Double.isInfinite(value)) {
            return value > 0 ? "inf" : "-inf";
        }

        String sign = "";
        double magnitude = value;
        // Copysign catches -0.0, whose repr is "-0.0" in Python.
        if (Math.copySign(1.0, value) < 0) {
            sign = "-";
            magnitude = -value;
        }

        Decomposed d = decompose(magnitude);
        return sign + render(d.digits, d.decpt);
    }

    /**
     * How {@code json.dumps} writes a float. Python's JSON encoder uses {@code float.__repr__} for
     * finite values but spells the non-finite ones {@code NaN}/{@code Infinity}/{@code -Infinity}
     * (allow_nan defaults to true), which is not what {@code repr} produces.
     */
    public static String jsonRepr(double value) {
        if (Double.isNaN(value)) {
            return "NaN";
        }
        if (Double.isInfinite(value)) {
            return value > 0 ? "Infinity" : "-Infinity";
        }
        return repr(value);
    }

    /**
     * Python's {@code format(value, '.<digits>f')}.
     *
     * <p>Not interchangeable with {@code String.format("%.4f", ...)}: Java's Formatter rounds
     * HALF_UP while Python rounds HALF_EVEN, so a double that is exactly representable at the
     * rounding digit diverges. {@code 0.15625} is a reachable example, formatting as {@code 0.1562}
     * in Python and {@code 0.1563} in Java. Constructing the BigDecimal from the double gives the
     * exact binary value, which is the same thing Python rounds.
     */
    public static String formatFixed(double value, int digits) {
        if (Double.isNaN(value)) {
            return "nan";
        }
        if (Double.isInfinite(value)) {
            return value > 0 ? "inf" : "-inf";
        }
        return new java.math.BigDecimal(value)
                .setScale(digits, java.math.RoundingMode.HALF_EVEN)
                .toPlainString();
    }

    private record Decomposed(String digits, int decpt) {}

    /**
     * Splits a non-negative finite double into significant digits (no leading or trailing zeros) and
     * a decimal exponent such that {@code value == 0.<digits> * 10^decpt}.
     */
    private static Decomposed decompose(double magnitude) {
        String java = Double.toString(magnitude);

        String mantissa;
        int exponent;
        int e = java.indexOf('E');
        if (e >= 0) {
            mantissa = java.substring(0, e);
            exponent = Integer.parseInt(java.substring(e + 1));
        } else {
            mantissa = java;
            exponent = 0;
        }

        int dot = mantissa.indexOf('.');
        String intPart = dot < 0 ? mantissa : mantissa.substring(0, dot);
        String fracPart = dot < 0 ? "" : mantissa.substring(dot + 1);

        String raw = intPart + fracPart;
        // decpt counts digits to the left of the decimal point, then shifts by the exponent.
        int decpt = intPart.length() + exponent;

        int firstSignificant = 0;
        while (firstSignificant < raw.length() && raw.charAt(firstSignificant) == '0') {
            firstSignificant++;
            decpt--;
        }
        if (firstSignificant == raw.length()) {
            // The value is zero; Python renders it as "0.0".
            return new Decomposed("0", 1);
        }

        int lastSignificant = raw.length();
        while (lastSignificant > firstSignificant && raw.charAt(lastSignificant - 1) == '0') {
            lastSignificant--;
        }

        return new Decomposed(raw.substring(firstSignificant, lastSignificant), decpt);
    }

    private static String render(String digits, int decpt) {
        // CPython format_float_short, 'r' mode: exponential when decpt <= -4 or decpt > 16.
        if (decpt <= -4 || decpt > 16) {
            StringBuilder sb = new StringBuilder();
            sb.append(digits.charAt(0));
            if (digits.length() > 1) {
                sb.append('.').append(digits, 1, digits.length());
            }
            int exp = decpt - 1;
            sb.append('e').append(exp < 0 ? '-' : '+');
            String magnitude = Integer.toString(Math.abs(exp));
            if (magnitude.length() < 2) {
                sb.append('0');
            }
            sb.append(magnitude);
            return sb.toString();
        }

        if (decpt <= 0) {
            return "0." + "0".repeat(-decpt) + digits;
        }
        if (decpt >= digits.length()) {
            return digits + "0".repeat(decpt - digits.length()) + ".0";
        }
        return digits.substring(0, decpt) + "." + digits.substring(decpt);
    }
}
