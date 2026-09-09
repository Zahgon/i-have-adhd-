package io.github.ayghri.adhd.util;

/**
 * Stands in for Python's {@code ValueError}.
 *
 * <p>The distinction from {@link PyRuntimeError} is load-bearing: the ported tests assert on the
 * exception type as well as the message, mirroring {@code assertRaisesRegex(ValueError, ...)}.
 */
public class PyValueError extends RuntimeException {
    public PyValueError(String message) {
        super(message);
    }

    public PyValueError(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * {@code printStackTrace} renders this as its header line, so overriding it makes an uncaught
     * failure terminate with the same {@code ValueError: <message>} text Python's traceback ends on.
     */
    @Override
    public String toString() {
        return "ValueError: " + getMessage();
    }
}
