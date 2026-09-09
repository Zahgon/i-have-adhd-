package io.github.ayghri.adhd.util;

/** Stands in for Python's {@code RuntimeError}. See {@link PyValueError} for why the type matters. */
public class PyRuntimeError extends RuntimeException {
    public PyRuntimeError(String message) {
        super(message);
    }

    public PyRuntimeError(String message, Throwable cause) {
        super(message, cause);
    }

    /** See {@link PyValueError#toString()} — matches the last line of Python's traceback. */
    @Override
    public String toString() {
        return "RuntimeError: " + getMessage();
    }
}
