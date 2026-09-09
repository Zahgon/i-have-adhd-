package io.github.ayghri.adhd.pi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.ayghri.adhd.util.JsonLines;
import io.github.ayghri.adhd.util.PyAssert;
import io.github.ayghri.adhd.util.PyRuntimeError;
import io.github.ayghri.adhd.util.PythonJson;
import io.github.ayghri.adhd.util.RepoRoot;
import io.github.ayghri.adhd.util.ShlexJoin;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises the {@code check_pi_extension.py} port against a scripted agent.
 *
 * <p>The real smoke test drives an installed {@code pi} binary, which no build machine is
 * guaranteed to have. Replaying a recorded line-delimited JSON transcript over the same
 * {@link RpcClient} keeps the request ordering, the toggle assertions and the process lifecycle
 * under test without depending on the agent being installed.
 */
final class PiExtensionCheckTest {

    private static final Path ROOT = RepoRoot.resolve();

    private static final String RULES = "{\"type\": \"custom_message\", \"customType\": \"i-have-adhd-rules\"}";
    private static final String DISABLED = "{\"type\": \"custom_message\", \"customType\": \"i-have-adhd-disabled\"}";
    private static final String COMMAND_LIST = "[{\"name\": \"i-have-adhd\"}, {\"name\": \"skill:i-have-adhd\"}]";

    @Test
    void argumentParsingMirrorsArgparse() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        assertEquals("pi", CheckPiExtension.parseRuntime(new String[0], stream(out), stream(err)));
        assertEquals("omp", CheckPiExtension.parseRuntime(new String[] {"--runtime", "omp"}, stream(out), stream(err)));
        assertEquals("omp", CheckPiExtension.parseRuntime(new String[] {"--runtime=omp"}, stream(out), stream(err)));
        assertEquals("", out.toString(StandardCharsets.UTF_8) + err.toString(StandardCharsets.UTF_8));

        for (String spelling : List.of("-h", "--help")) {
            ByteArrayOutputStream help = new ByteArrayOutputStream();
            CheckPiExtension.CliExit exit = assertThrows(CheckPiExtension.CliExit.class,
                    () -> CheckPiExtension.parseRuntime(new String[] {spelling}, stream(help), stream(err)));
            assertEquals(0, exit.status(), spelling);
            assertEquals(
                    List.of(
                            "usage: check_pi_extension.py [-h] [--runtime {pi,omp}]",
                            "",
                            "Smoke-test the i-have-adhd extension without a model request.",
                            "",
                            "options:",
                            "  -h, --help          show this help message and exit",
                            "  --runtime {pi,omp}  Agent runtime to exercise (default: pi)"),
                    help.toString(StandardCharsets.UTF_8).lines().toList());
        }

        assertEquals(
                "check_pi_extension.py: error: argument --runtime: expected one argument",
                usageFailure(new String[] {"--runtime"}));
        assertEquals(
                "check_pi_extension.py: error: unrecognized arguments: --colour",
                usageFailure(new String[] {"--colour"}));
        assertEquals(
                "check_pi_extension.py: error: argument --runtime: invalid choice: 'node'"
                        + " (choose from 'pi', 'omp')",
                usageFailure(new String[] {"--runtime", "node"}));
    }

    @Test
    void smokeTestRefusesARuntimeThatIsNotInstalled() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PyRuntimeError missing = assertThrows(PyRuntimeError.class,
                () -> CheckPiExtension.run("adhd-runtime-that-is-not-installed", ROOT, stream(out)));

        assertEquals("adhd-runtime-that-is-not-installed executable is not available", missing.getMessage());
        assertEquals("RuntimeError: adhd-runtime-that-is-not-installed executable is not available",
                missing.toString());
        assertEquals("", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void packageManifestsMustAgreeWithEachOther(@TempDir Path dir) {
        ManifestValidator.validatePackageManifest(ROOT);

        Path checkout = manifests(dir.resolve("mismatched"),
                "{\"name\": \"a\", \"version\": \"1.0.0\","
                        + " \"pi\": {\"extensions\": [\"./extensions/i-have-adhd.ts\"], \"skills\": [\"./skills\"]},"
                        + " \"omp\": {\"extensions\": [\"./extensions/i-have-adhd.ts\"]}}",
                "{\"name\": \"a\", \"version\": \"2.0.0\"}");
        PyAssert.PyAssertionError drift = assertThrows(PyAssert.PyAssertionError.class,
                () -> ManifestValidator.validatePackageManifest(checkout));
        assertEquals("package.json and kimi.plugin.json disagree on version", drift.getMessage());

        Path missingPi = manifests(dir.resolve("no-pi-block"),
                "{\"name\": \"a\", \"omp\": {\"extensions\": [\"./extensions/i-have-adhd.ts\"]}}",
                "{\"name\": \"a\", \"description\": null}");
        assertThrows(PyAssert.PyAssertionError.class, () -> ManifestValidator.validatePackageManifest(missingPi));

        Path nullsAreAbsent = manifests(dir.resolve("explicit-nulls"),
                "{\"name\": \"a\", \"homepage\": null,"
                        + " \"pi\": {\"extensions\": [\"./extensions/i-have-adhd.ts\"], \"skills\": [\"./skills\"]},"
                        + " \"omp\": {\"extensions\": [\"./extensions/i-have-adhd.ts\"]}}",
                "{\"name\": \"a\"}");
        ManifestValidator.validatePackageManifest(nullsAreAbsent);
        assertEquals(
                List.of("name", "version", "description", "license", "homepage"),
                ManifestValidator.SHARED_MANIFEST_FIELDS);
    }

    @Test
    void isolatedEnvironmentDropsCredentialsAndPinsTheAgentDirectory(@TempDir Path dir) {
        Map<String, String> env = IsolatedEnv.build(dir);

        assertEquals(dir.toString(), env.get("PI_CODING_AGENT_DIR"));
        assertEquals("1", env.get("PI_SKIP_VERSION_CHECK"));
        assertEquals("0", env.get("PI_TELEMETRY"));
        assertEquals(System.getenv("PATH"), env.get("PATH"));
        assertEquals(
                List.of(),
                env.keySet().stream()
                        .filter(key -> key.endsWith("_API_KEY") || key.equals("ANTHROPIC_AUTH_TOKEN")
                                || key.equals("OPENAI_ACCESS_TOKEN"))
                        .toList(),
                "credentials must never reach the agent under test");
        assertTrue(System.getenv().containsKey("ADHD_STUB_API_KEY"),
                "surefire must seed a credential for the filter to have something to strip");
    }

    @Test
    void eventHelpersReadTheAgentEventStream() {
        List<JsonNode> events = parseAll(
                status("ADHD ON"),
                foreignStatus("ADHD ON"),
                status(null),
                "{\"type\": \"agent_start\"}");

        assertEquals(Arrays.asList("ADHD ON", null), EventHelpers.statusTexts(events));
        assertTrue(EventHelpers.anyStatusContains(events, "ADHD ON"));
        assertFalse(EventHelpers.anyStatusContains(events, "ADHD OFF"));
        assertTrue(EventHelpers.is(events.get(3), "type", "agent_start"));
        assertFalse(EventHelpers.is(events.get(3), "type", "agent_stop"));

        JsonNode entries = parse(entriesResponse("id", RULES, RULES, DISABLED, state(false), state(true)));
        assertEquals(5, EventHelpers.entries(entries).size());
        assertEquals(2, EventHelpers.messageCount(entries, "i-have-adhd-rules"));
        assertEquals(1, EventHelpers.messageCount(entries, "i-have-adhd-disabled"));
        assertTrue(EventHelpers.latestEnabled(entries), "the last persisted state wins");

        assertEquals(List.of(), EventHelpers.entries(parse("{\"type\": \"response\"}")));
        assertEquals(0, EventHelpers.messageCount(parse("{\"data\": {\"entries\": \"nope\"}}"), "x"));
        PyAssert.PyAssertionError unset = assertThrows(PyAssert.PyAssertionError.class,
                () -> EventHelpers.latestEnabled(parse(entriesResponse("id", RULES))));
        assertEquals("No persisted i-have-adhd state found", unset.getMessage());
    }

    @Test
    void rpcClientPairsResponsesWithTheEventsThatPrecedeThem(@TempDir Path dir) {
        Path agent = stubAgent(dir, jsonl(
                status("ADHD ON"),
                ok("first"),
                "{\"type\": \"available_commands_update\", \"commands\": " + COMMAND_LIST + "}",
                ok("second")));

        try (RpcClient client = new RpcClient(agent.toString(), IsolatedEnv.build(dir), dir, List.of("--adhd"))) {
            RpcClient.RpcResponse first = client.request("first", command("get_state"));
            assertTrue(first.response().get("success").booleanValue());
            assertEquals(2, first.events().size(), "the trailing response is part of the event window");
            assertTrue(EventHelpers.anyStatusContains(first.events(), "ADHD ON"));

            List<JsonNode> untilUpdate =
                    client.eventsUntil(event -> EventHelpers.is(event, "type", "available_commands_update"));
            assertEquals(1, untilUpdate.size());
            assertEquals(2, untilUpdate.get(0).get("commands").size());

            RpcClient.RpcResponse second = client.request("second", command("get_state"));
            assertEquals("second", second.response().get("id").textValue());
            assertEquals(1, second.events().size());
        }
    }

    @Test
    void rpcClientSurfacesAnAgentThatDiesBeforeResponding(@TempDir Path dir) {
        Path agent = script(dir, "head -n 1 > /dev/null\nprintf 'agent crashed\\n' >&2\nexit 7\n");
        RpcClient client = new RpcClient(agent.toString(), IsolatedEnv.build(dir), dir, List.of());

        PyRuntimeError died = assertThrows(PyRuntimeError.class, () -> client.request("x", command("get_state")));
        assertEquals("Agent RPC exited before responding: agent crashed\n", died.getMessage());

        PyRuntimeError exit = assertThrows(PyRuntimeError.class, client::close);
        assertEquals("Agent RPC exited with 7: agent crashed\n", exit.getMessage());

        assertEquals("TimeoutError: Agent RPC did not respond within 30 seconds",
                new RpcClient.PyTimeoutError("Agent RPC did not respond within 30 seconds").toString());
        assertEquals(30, RpcClient.RPC_TIMEOUT_SECONDS);
    }

    @Test
    void ompStartupNeedsBothCommandsAndTheOnStatus(@TempDir Path dir) {
        Path agent = stubAgent(dir, jsonl(
                status("ADHD ON"),
                "{\"type\": \"available_commands_update\", \"commands\": " + COMMAND_LIST + "}"));
        try (RpcClient client = new RpcClient(agent.toString(), IsolatedEnv.build(dir), dir, List.of())) {
            CheckPiExtension.checkOmpStartup(client);
        }

        Path quiet = stubAgent(dir.resolve("quiet"), jsonl(
                "{\"type\": \"available_commands_update\", \"commands\": [{\"name\": \"i-have-adhd\"}]}"));
        try (RpcClient client = new RpcClient(quiet.toString(), IsolatedEnv.build(dir), dir, List.of())) {
            assertThrows(PyAssert.PyAssertionError.class, () -> CheckPiExtension.checkOmpStartup(client));
        }
    }

    @Test
    void piSessionWalksTheWholeToggleScript(@TempDir Path dir) {
        Path flag = dir.resolve("passthrough-probe-enabled");
        Path agent = stubAgent(dir, piTranscript());

        try (RpcClient client = new RpcClient(agent.toString(), IsolatedEnv.build(dir), dir, List.of())) {
            CheckPiExtension.checkPiSession(client, flag);
        }
        assertTrue(Files.exists(flag), "the passthrough probe is armed by touching its flag file");

        Path twice = stubAgent(dir.resolve("double-inject"),
                piTranscript().replace(entriesResponse("entries-reloaded", RULES),
                        entriesResponse("entries-reloaded", RULES, RULES)));
        try (RpcClient client = new RpcClient(twice.toString(), IsolatedEnv.build(dir), dir, List.of())) {
            PyAssert.PyAssertionError doubled = assertThrows(PyAssert.PyAssertionError.class,
                    () -> CheckPiExtension.checkPiSession(client, dir.resolve("other-flag")));
            assertEquals("Rules were injected twice for one active session", doubled.getMessage());
        }
    }

    @Test
    void scratchDirectoriesAreCreatedWrittenAndRemoved() {
        Path scratch = CheckPiExtension.createTempDirectory("i-have-adhd-test-");
        assertTrue(Files.isDirectory(scratch));

        Path nested = scratch.resolve("nested");
        CheckPiExtension.writeString(scratch.resolve("probe.ts"), "export default 1;\n");
        assertThrows(UncheckedIOException.class, () -> CheckPiExtension.writeString(nested.resolve("x.ts"), "x"));

        CheckPiExtension.deleteRecursively(scratch);
        assertFalse(Files.exists(scratch), "the agent directory must not outlive the smoke test");
        CheckPiExtension.deleteRecursively(scratch);
    }

    private static String piTranscript() {
        return jsonl(
                status("ADHD ON"),
                response("commands", "\"data\": {\"commands\": " + COMMAND_LIST + "}"),
                entriesResponse("entries-startup", RULES),
                status("ADHD ON"),
                ok("reload-enabled"),
                entriesResponse("entries-reloaded", RULES),
                status(null),
                ok("toggle-off"),
                entriesResponse("entries-toggled-off", RULES, DISABLED, state(false)),
                foreignStatus("ADHD ON"),
                ok("reload-disabled"),
                entriesResponse("entries-reload-disabled", RULES, DISABLED),
                status("ADHD ON"),
                ok("toggle-on"),
                entriesResponse("entries-toggled-on", RULES, RULES, state(true)),
                ok("explicit-off"),
                ok("skill-alias"),
                entriesResponse("entries-enabled", RULES, RULES, RULES, state(true)),
                status(null),
                ok("stop-phrase"),
                entriesResponse("entries-stopped", state(false)),
                ok("disabled-passthrough"),
                entriesResponse("entries-passthrough",
                        "{\"type\": \"custom\", \"customType\": \"passthrough-probe\", \"data\": {\"seen\": true}}"));
    }

    private static String usageFailure(String[] args) {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        CheckPiExtension.CliExit exit = assertThrows(CheckPiExtension.CliExit.class,
                () -> CheckPiExtension.parseRuntime(args, stream(new ByteArrayOutputStream()), stream(err)));
        assertEquals(2, exit.status());
        List<String> lines = err.toString(StandardCharsets.UTF_8).lines().toList();
        assertEquals("usage: check_pi_extension.py [-h] [--runtime {pi,omp}]", lines.get(0));
        return lines.get(1);
    }

    private static PrintStream stream(ByteArrayOutputStream sink) {
        return new PrintStream(sink, true, StandardCharsets.UTF_8);
    }

    private static ObjectNode command(String type) {
        ObjectNode node = PythonJson.MAPPER.createObjectNode();
        node.put("type", type);
        return node;
    }

    private static String jsonl(String... lines) {
        return String.join("\n", lines) + "\n";
    }

    private static String status(String text) {
        return "{\"type\": \"extension_ui_request\", \"method\": \"setStatus\", \"statusKey\": \"i-have-adhd\","
                + " \"statusText\": " + (text == null ? "null" : "\"" + text + "\"") + "}";
    }

    private static String foreignStatus(String text) {
        return "{\"type\": \"extension_ui_request\", \"method\": \"setStatus\", \"statusKey\": \"other\","
                + " \"statusText\": \"" + text + "\"}";
    }

    private static String response(String id, String body) {
        return "{\"type\": \"response\", \"id\": \"" + id + "\", " + body + "}";
    }

    private static String ok(String id) {
        return response(id, "\"success\": true");
    }

    private static String entriesResponse(String id, String... entries) {
        return response(id, "\"data\": {\"entries\": [" + String.join(", ", entries) + "]}");
    }

    private static String state(boolean enabled) {
        return "{\"type\": \"custom\", \"customType\": \"i-have-adhd-state\", \"data\": {\"enabled\": "
                + enabled + "}}";
    }

    private static JsonNode parse(String line) {
        return JsonLines.loads(line);
    }

    private static List<JsonNode> parseAll(String... lines) {
        List<JsonNode> nodes = new ArrayList<>();
        for (String line : lines) {
            nodes.add(JsonLines.loads(line));
        }
        return nodes;
    }

    private static Path manifests(Path root, String packageJson, String kimiJson) {
        try {
            Files.createDirectories(root);
            Files.writeString(root.resolve("package.json"), packageJson, StandardCharsets.UTF_8);
            Files.writeString(root.resolve("kimi.plugin.json"), kimiJson, StandardCharsets.UTF_8);
        } catch (IOException exc) {
            throw new UncheckedIOException(exc);
        }
        return root;
    }

    private static Path stubAgent(Path dir, String transcript) {
        try {
            Files.createDirectories(dir);
            Path transcriptFile = Files.createTempFile(dir, "transcript-", ".jsonl");
            Files.writeString(transcriptFile, transcript, StandardCharsets.UTF_8);
            return script(dir, "cat " + ShlexJoin.quote(transcriptFile.toString()) + "\ncat > /dev/null\n");
        } catch (IOException exc) {
            throw new UncheckedIOException(exc);
        }
    }

    private static Path script(Path dir, String body) {
        try {
            Files.createDirectories(dir);
            Path file = Files.createTempFile(dir, "stub-agent-", ".sh");
            Files.writeString(file, "#!/bin/sh\n" + body, StandardCharsets.UTF_8);
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
            return file;
        } catch (IOException exc) {
            throw new UncheckedIOException(exc);
        }
    }
}
