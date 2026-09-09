package io.github.ayghri.adhd.pi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.ayghri.adhd.util.JsonLines;
import io.github.ayghri.adhd.util.PyRuntimeError;
import io.github.ayghri.adhd.util.PythonJson;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/** Line-delimited JSON-RPC client for the {@code pi}/{@code omp} agent, ported from {@code RpcClient}. */
public final class RpcClient implements AutoCloseable {

    public static final int RPC_TIMEOUT_SECONDS = 30;

    /** Marks end-of-stream on the queue, standing in for the {@code None} sentinel Python enqueues. */
    private static final Object EOF = new Object();

    public record RpcResponse(JsonNode response, List<JsonNode> events) {}

    private final Process process;
    private final Path stderrFile;
    private final Writer stdin;
    private final BlockingQueue<Object> lines = new LinkedBlockingQueue<>();

    public RpcClient(String executable, Map<String, String> env, Path cwd, List<String> args) {
        List<String> command = new ArrayList<>(List.of(executable, "--mode", "rpc"));
        command.addAll(args);
        try {
            stderrFile = Files.createTempFile("adhd-rpc-stderr-", ".txt");
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(cwd.toFile());
            builder.environment().clear();
            builder.environment().putAll(env);
            builder.redirectError(stderrFile.toFile());
            process = builder.start();
        } catch (IOException exc) {
            throw new UncheckedIOException(exc);
        }
        stdin = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);

        Thread reader = new Thread(this::pumpStdout, "adhd-rpc-stdout");
        reader.setDaemon(true);
        reader.start();
    }

    private void pumpStdout() {
        try (BufferedReader out =
                new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = out.readLine()) != null) {
                lines.put(line);
            }
        } catch (IOException | InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } finally {
            try {
                lines.put(EOF);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public RpcResponse request(String requestId, ObjectNode command) {
        ObjectNode payload = PythonJson.MAPPER.createObjectNode();
        payload.put("id", requestId);
        payload.setAll(command);
        write(PythonJson.dumps(payload) + "\n");

        List<JsonNode> events = new ArrayList<>();
        while (true) {
            JsonNode event = nextEvent(
                    events,
                    "Agent RPC did not respond within " + RPC_TIMEOUT_SECONDS + " seconds",
                    "Agent RPC exited before responding: ");
            if ("response".equals(text(event, "type")) && requestId.equals(text(event, "id"))) {
                return new RpcResponse(event, events);
            }
        }
    }

    public List<JsonNode> eventsUntil(Predicate<JsonNode> predicate) {
        List<JsonNode> events = new ArrayList<>();
        while (true) {
            JsonNode event = nextEvent(
                    events,
                    "Agent RPC did not emit the expected event within " + RPC_TIMEOUT_SECONDS + " seconds",
                    "Agent RPC exited before emitting the expected event: ");
            if (predicate.test(event)) {
                return events;
            }
        }
    }

    private JsonNode nextEvent(List<JsonNode> events, String timeoutMessage, String exitPrefix) {
        Object line;
        try {
            line = lines.poll(RPC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException exc) {
            Thread.currentThread().interrupt();
            throw new PyRuntimeError(timeoutMessage, exc);
        }
        if (line == null) {
            throw new PyTimeoutError(timeoutMessage);
        }
        if (line == EOF) {
            throw new PyRuntimeError(exitPrefix + readStderr());
        }
        JsonNode event = JsonLines.loads((String) line);
        events.add(event);
        return event;
    }

    private void write(String payload) {
        try {
            stdin.write(payload);
            stdin.flush();
        } catch (IOException exc) {
            throw new UncheckedIOException(exc);
        }
    }

    private String readStderr() {
        try {
            return Files.readString(stderrFile, StandardCharsets.UTF_8);
        } catch (IOException exc) {
            return "";
        }
    }

    @Override
    public void close() {
        try {
            stdin.close();
        } catch (IOException ignored) {
            // The agent may already have exited and closed the pipe.
        }

        int returnCode;
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroy();
                process.waitFor(5, TimeUnit.SECONDS);
            }
            returnCode = process.exitValue();
        } catch (InterruptedException exc) {
            Thread.currentThread().interrupt();
            throw new PyRuntimeError("Interrupted while closing the agent RPC", exc);
        }

        String stderr = readStderr();
        try {
            Files.deleteIfExists(stderrFile);
        } catch (IOException ignored) {
            // Best-effort cleanup.
        }

        // Python reports a SIGTERM kill as -15; the JVM reports the shell convention 128+15=143.
        // Both spellings are accepted so a normal terminate() is not mistaken for a crash.
        if (returnCode != 0 && returnCode != -15 && returnCode != 143) {
            throw new PyRuntimeError("Agent RPC exited with " + returnCode + ": " + stderr);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    /** Stands in for Python's {@code TimeoutError}. */
    public static final class PyTimeoutError extends RuntimeException {
        public PyTimeoutError(String message) {
            super(message);
        }

        @Override
        public String toString() {
            return "TimeoutError: " + getMessage();
        }
    }
}
