package io.github.ayghri.adhd.util;

/**
 * Port of Python's {@code assert} statement.
 *
 * <p>A plain {@code assert} keyword would be wrong here: the JVM disables assertions unless {@code -ea}
 * is passed, whereas the smoke test relies on every check running. These always run.
 */
public final class PyAssert {

    private PyAssert() {}

    public static void check(boolean condition) {
        if (!condition) {
            throw new PyAssertionError(null);
        }
    }

    public static void check(boolean condition, String message) {
        if (!condition) {
            throw new PyAssertionError(message);
        }
    }

    /** Renders as {@code AssertionError: msg} so an uncaught failure reads like Python's. */
    public static final class PyAssertionError extends AssertionError {
        /**
         * Delegates to the {@code (String, Throwable)} constructor deliberately. {@code AssertionError}
         * has no {@code (String)} overload, so {@code super(message)} would bind to {@code (Object)} and
         * turn a null message into the literal text {@code "null"}, where Python prints a bare
         * {@code AssertionError} for a message-less assert.
         */
        public PyAssertionError(String message) {
            super(message, null);
        }

        @Override
        public String toString() {
            return getMessage() == null ? "AssertionError" : "AssertionError: " + getMessage();
        }
    }
}
