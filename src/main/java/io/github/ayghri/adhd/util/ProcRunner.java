package io.github.ayghri.adhd.util;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Port of {@code subprocess.run(..., capture_output=True, text=True)}. */
public final class ProcRunner {

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");

    private ProcRunner() {}

    public static ProcResult run(List<String> command, Path cwd) {
        return run(command, cwd, null, null);
    }

    public static ProcResult run(List<String> command, Path cwd, Map<String, String> env, String input) {
        return execute(command, cwd, env, input);
    }

    /**
     * Equivalent of {@code subprocess.run(..., shell=True)}, which Java has no direct support for.
     * The command string is handed to the platform shell exactly as Python would hand it to
     * {@code /bin/sh} (or {@code cmd.exe} on Windows).
     */
    public static ProcResult runShell(String command, Path cwd, Map<String, String> env, String input) {
        List<String> argv = WINDOWS ? List.of("cmd.exe", "/c", command) : List.of("/bin/sh", "-c", command);
        return execute(argv, cwd, env, input);
    }

    private static ProcResult execute(List<String> command, Path cwd, Map<String, String> env, String input) {
        Path outFile = null;
        Path errFile = null;
        try {
            // Redirecting to files rather than pipes removes any chance of the classic
            // fill-the-pipe-buffer deadlock when a child writes heavily to both streams.
            outFile = Files.createTempFile("adhd-stdout-", ".txt");
            errFile = Files.createTempFile("adhd-stderr-", ".txt");

            ProcessBuilder builder = new ProcessBuilder(command);
            if (cwd != null) {
                builder.directory(cwd.toFile());
            }
            if (env != null) {
                builder.environment().clear();
                builder.environment().putAll(env);
            }
            builder.redirectOutput(outFile.toFile());
            builder.redirectError(errFile.toFile());

            Process process = builder.start();
            try (OutputStream stdin = process.getOutputStream()) {
                if (input != null) {
                    stdin.write(input.getBytes(StandardCharsets.UTF_8));
                }
            } catch (IOException ignored) {
                // A child that exits without reading stdin gives EPIPE; Python swallows it too.
            }

            int code = process.waitFor();
            return new ProcResult(code, readUniversal(outFile), readUniversal(errFile));
        } catch (IOException exc) {
            throw new UncheckedIOException(exc);
        } catch (InterruptedException exc) {
            Thread.currentThread().interrupt();
            throw new PyRuntimeError("Interrupted while waiting for " + command, exc);
        } finally {
            deleteQuietly(outFile);
            deleteQuietly(errFile);
        }
    }

    /**
     * {@code text=True} wraps the pipes in a TextIOWrapper with {@code newline=None}, which enables
     * universal-newline translation. Reproducing it keeps output identical across platforms.
     */
    private static String readUniversal(Path path) throws IOException {
        String raw = Files.readString(path, StandardCharsets.UTF_8);
        return raw.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Temp-file cleanup is best effort.
        }
    }
}
