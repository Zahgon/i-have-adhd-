package io.github.ayghri.adhd.util;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

/**
 * Reproduces the {@code OSError} family that Python raises when a path cannot be opened, including
 * the {@code [Errno N] message: 'path'} rendering that ends the traceback. Java's
 * {@link NoSuchFileException} carries only the path, so the errno and text are reattached here.
 */
public final class PyOsError extends RuntimeException {

    private final String typeName;

    private PyOsError(String typeName, String message) {
        super(message);
        this.typeName = typeName;
    }

    public static PyOsError from(IOException exc, Path path) {
        if (exc instanceof NoSuchFileException) {
            return new PyOsError("FileNotFoundError", render(2, "No such file or directory", path));
        }
        if (exc instanceof AccessDeniedException) {
            return new PyOsError("PermissionError", render(13, "Permission denied", path));
        }
        String detail = exc.getMessage() == null ? exc.getClass().getSimpleName() : exc.getMessage();
        return new PyOsError("OSError", detail);
    }

    private static String render(int errno, String description, Path path) {
        return "[Errno " + errno + "] " + description + ": " + PythonStr.repr(path.toString());
    }

    @Override
    public String toString() {
        return typeName + ": " + getMessage();
    }
}
