package io.github.ayghri.adhd.util;

/**
 * Mirrors Python's {@code KeyError}, whose {@code str()} is the repr of the missing key rather than
 * a sentence. Indexing a missing runner name propagates this to the top level, so the final
 * traceback line has to read {@code KeyError: 'ghost'} to stay faithful.
 */
public final class PyKeyError extends RuntimeException {

    public PyKeyError(String key) {
        super(PythonStr.repr(key));
    }

    @Override
    public String toString() {
        return "KeyError: " + getMessage();
    }
}
