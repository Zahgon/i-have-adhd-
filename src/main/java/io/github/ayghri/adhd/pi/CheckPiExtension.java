package io.github.ayghri.adhd.pi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.ayghri.adhd.util.PyAssert;
import io.github.ayghri.adhd.util.PyRuntimeError;
import io.github.ayghri.adhd.util.PythonJson;
import io.github.ayghri.adhd.util.ProcResult;
import io.github.ayghri.adhd.util.ProcRunner;
import io.github.ayghri.adhd.util.RepoRoot;
import io.github.ayghri.adhd.util.Which;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Port of {@code scripts/check_pi_extension.py}. */
public final class CheckPiExtension {

    private static final String RELOAD_PROBE_SOURCE =
            """
            import { existsSync } from "node:fs";
            import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";
            const passthroughProbeFlag = __PASSTHROUGH_PROBE_FLAG__;
            export default function (pi: ExtensionAPI) {
              pi.registerCommand("reload-probe", {
                description: "Reload Pi for smoke testing",
                handler: async (_args, ctx) => { await ctx.reload(); },
              });
              pi.on("input", async (event) => {
                if (!existsSync(passthroughProbeFlag) || event.text.trim().toLowerCase() !== "normal mode") {
                  return { action: "continue" };
                }
                pi.appendEntry("passthrough-probe", { seen: true });
                return { action: "handled" };
              });
            }
            """;

    private CheckPiExtension() {}

    /**
     * Stands in for the {@code SystemExit} argparse raises: the status has to unwind to {@code main}
     * rather than be applied in place, because a parser that calls {@code System.exit} itself cannot
     * be exercised by a test without taking the whole JVM down with it.
     */
    static final class CliExit extends RuntimeException {
        private final int status;

        CliExit(int status) {
            super(null, null, false, false);
            this.status = status;
        }

        int status() {
            return status;
        }
    }

    public static void main(String[] args) {
        try {
            run(parseRuntime(args, System.out, System.err), RepoRoot.resolve(), System.out);
        } catch (CliExit exit) {
            System.exit(exit.status());
        } catch (RuntimeException | AssertionError exc) {
            exc.printStackTrace(System.err);
            System.exit(1);
        }
    }

    static String parseRuntime(String[] args, PrintStream out, PrintStream err) {
        String runtime = "pi";
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("-h") || arg.equals("--help")) {
                out.println("usage: check_pi_extension.py [-h] [--runtime {pi,omp}]");
                out.println();
                out.println("Smoke-test the i-have-adhd extension without a model request.");
                out.println();
                out.println("options:");
                out.println("  -h, --help          show this help message and exit");
                out.println("  --runtime {pi,omp}  Agent runtime to exercise (default: pi)");
                throw new CliExit(0);
            } else if (arg.equals("--runtime")) {
                if (i + 1 >= args.length) {
                    throw usageError(err, "argument --runtime: expected one argument");
                }
                runtime = args[++i];
            } else if (arg.startsWith("--runtime=")) {
                runtime = arg.substring("--runtime=".length());
            } else {
                throw usageError(err, "unrecognized arguments: " + arg);
            }
        }
        if (!runtime.equals("pi") && !runtime.equals("omp")) {
            throw usageError(
                    err, "argument --runtime: invalid choice: '" + runtime + "' (choose from 'pi', 'omp')");
        }
        return runtime;
    }

    private static CliExit usageError(PrintStream err, String message) {
        err.println("usage: check_pi_extension.py [-h] [--runtime {pi,omp}]");
        err.println("check_pi_extension.py: error: " + message);
        return new CliExit(2);
    }

    public static void run(String runtime, Path root, PrintStream out) {
        ManifestValidator.validatePackageManifest(root);

        String executable = Which.which(runtime);
        if (executable == null) {
            throw new PyRuntimeError(runtime + " executable is not available");
        }

        Path agentDir = createTempDirectory("i-have-adhd-" + runtime + "-");
        try {
            Map<String, String> env = IsolatedEnv.build(agentDir);
            if (runtime.equals("omp")) {
                env.remove("PI_CODING_AGENT_DIR");
            } else {
                ProcResult install = ProcRunner.run(List.of(executable, "install", root.toString()), root, env, null);
                if (install.returncode() != 0) {
                    throw new PyRuntimeError(
                            "Command '[" + executable + ", install, " + root + "]' returned non-zero exit status "
                                    + install.returncode() + ".");
                }
            }

            List<String> extensionArgs = runtime.equals("pi")
                    ? List.of()
                    : List.of("--no-extensions", "-e", root.toString());

            Path reloadProbe = agentDir.resolve("reload-probe.ts");
            Path passthroughProbeFlag = agentDir.resolve("passthrough-probe-enabled");
            writeString(reloadProbe, RELOAD_PROBE_SOURCE.replace(
                    "__PASSTHROUGH_PROBE_FLAG__",
                    PythonJson.dumps(PythonJson.MAPPER.getNodeFactory().textNode(passthroughProbeFlag.toString()))));

            List<String> clientArgs = new ArrayList<>();
            clientArgs.add("--no-session");
            clientArgs.addAll(extensionArgs);
            clientArgs.add("-e");
            clientArgs.add(reloadProbe.toString());
            clientArgs.add("--adhd");

            RpcClient client = new RpcClient(executable, env, root, clientArgs);
            if (runtime.equals("omp")) {
                try {
                    checkOmpStartup(client);
                } finally {
                    client.close();
                }
                out.println("omp extension smoke test passed");
                return;
            }

            try {
                checkPiSession(client, passthroughProbeFlag);
            } finally {
                client.close();
            }

            touch(agentDir.resolve(".i-have-adhd-always"));
            List<String> alwaysOnArgs = new ArrayList<>();
            alwaysOnArgs.add("--no-session");
            alwaysOnArgs.addAll(extensionArgs);
            RpcClient alwaysOn = new RpcClient(executable, env, root, alwaysOnArgs);
            try {
                RpcClient.RpcResponse state = alwaysOn.request("always-on", command("get_state"));
                PyAssert.check(EventHelpers.anyStatusContains(state.events(), "ADHD ON"));
            } finally {
                alwaysOn.close();
            }
        } finally {
            deleteRecursively(agentDir);
        }

        out.println(runtime + " extension smoke test passed");
    }

    static void checkOmpStartup(RpcClient client) {
        List<JsonNode> startupEvents =
                client.eventsUntil(event -> EventHelpers.is(event, "type", "available_commands_update"));
        JsonNode commandEvent = startupEvents.get(startupEvents.size() - 1);
        Set<String> names = commandNames(commandEvent.get("commands"));
        PyAssert.check(names.contains("i-have-adhd"));
        PyAssert.check(names.contains("skill:i-have-adhd"));
        PyAssert.check(EventHelpers.anyStatusContains(startupEvents, "ADHD ON"));
    }

    static void checkPiSession(RpcClient client, Path passthroughProbeFlag) {
        RpcClient.RpcResponse commands = client.request("commands", command("get_commands"));
        Set<String> names = commandNames(commands.response().get("data").get("commands"));
        PyAssert.check(names.contains("i-have-adhd"));
        PyAssert.check(names.contains("skill:i-have-adhd"));
        PyAssert.check(EventHelpers.anyStatusContains(commands.events(), "ADHD ON"));

        JsonNode entries = client.request("entries-startup", command("get_entries")).response();
        PyAssert.check(EventHelpers.messageCount(entries, "i-have-adhd-rules") == 1);
        PyAssert.check(EventHelpers.messageCount(entries, "i-have-adhd-disabled") == 0);

        RpcClient.RpcResponse reloaded = client.request("reload-enabled", prompt("/reload-probe"));
        checkSuccess(reloaded.response());
        PyAssert.check(EventHelpers.anyStatusContains(reloaded.events(), "ADHD ON"));

        entries = client.request("entries-reloaded", command("get_entries")).response();
        PyAssert.check(EventHelpers.messageCount(entries, "i-have-adhd-rules") == 1,
                "Rules were injected twice for one active session");

        RpcClient.RpcResponse toggledOff = client.request("toggle-off", prompt("/i-have-adhd"));
        checkSuccess(toggledOff.response());
        PyAssert.check(EventHelpers.statusTexts(toggledOff.events()).contains(null));

        entries = client.request("entries-toggled-off", command("get_entries")).response();
        PyAssert.check(!EventHelpers.latestEnabled(entries));
        PyAssert.check(EventHelpers.messageCount(entries, "i-have-adhd-disabled") == 1);

        RpcClient.RpcResponse reloadDisabled = client.request("reload-disabled", prompt("/reload-probe"));
        checkSuccess(reloadDisabled.response());
        PyAssert.check(!EventHelpers.anyStatusContains(reloadDisabled.events(), "ADHD ON"));

        entries = client.request("entries-reload-disabled", command("get_entries")).response();
        PyAssert.check(EventHelpers.messageCount(entries, "i-have-adhd-rules") == 1);
        PyAssert.check(EventHelpers.messageCount(entries, "i-have-adhd-disabled") == 1);

        RpcClient.RpcResponse toggledOn = client.request("toggle-on", prompt("/i-have-adhd"));
        checkSuccess(toggledOn.response());
        PyAssert.check(EventHelpers.anyStatusContains(toggledOn.events(), "ADHD ON"));

        entries = client.request("entries-toggled-on", command("get_entries")).response();
        PyAssert.check(EventHelpers.latestEnabled(entries));
        PyAssert.check(EventHelpers.messageCount(entries, "i-have-adhd-rules") == 2);

        checkSuccess(client.request("explicit-off", prompt("/i-have-adhd off")).response());

        RpcClient.RpcResponse skill = client.request("skill-alias", prompt("/skill:i-have-adhd"));
        checkSuccess(skill.response());
        PyAssert.check(skill.events().stream().noneMatch(event -> EventHelpers.is(event, "type", "agent_start")));

        entries = client.request("entries-enabled", command("get_entries")).response();
        PyAssert.check(EventHelpers.latestEnabled(entries));
        PyAssert.check(EventHelpers.messageCount(entries, "i-have-adhd-rules") == 3);

        RpcClient.RpcResponse stopped = client.request("stop-phrase", prompt("normal mode"));
        checkSuccess(stopped.response());
        PyAssert.check(EventHelpers.statusTexts(stopped.events()).contains(null));

        entries = client.request("entries-stopped", command("get_entries")).response();
        PyAssert.check(!EventHelpers.latestEnabled(entries));

        touch(passthroughProbeFlag);
        checkSuccess(client.request("disabled-passthrough", prompt("normal mode")).response());

        entries = client.request("entries-passthrough", command("get_entries")).response();
        PyAssert.check(
                EventHelpers.entries(entries).stream().anyMatch(entry -> EventHelpers.is(entry, "type", "custom")
                        && EventHelpers.is(entry, "customType", "passthrough-probe")
                        && entry.path("data").path("seen").isBoolean()
                        && entry.path("data").path("seen").booleanValue()),
                "Disabled mode swallowed ordinary input");
    }

    private static void checkSuccess(JsonNode response) {
        JsonNode success = response.get("success");
        PyAssert.check(success != null && success.isBoolean() && success.booleanValue());
    }

    private static Set<String> commandNames(JsonNode commands) {
        Set<String> names = new HashSet<>();
        if (commands != null && commands.isArray()) {
            for (JsonNode entry : commands) {
                names.add(entry.get("name").textValue());
            }
        }
        return names;
    }

    private static ObjectNode command(String type) {
        ObjectNode node = PythonJson.MAPPER.createObjectNode();
        node.put("type", type);
        return node;
    }

    private static ObjectNode prompt(String message) {
        ObjectNode node = command("prompt");
        node.put("message", message);
        return node;
    }

    static Path createTempDirectory(String prefix) {
        try {
            return Files.createTempDirectory(prefix);
        } catch (IOException exc) {
            throw new UncheckedIOException(exc);
        }
    }

    static void writeString(Path path, String content) {
        try {
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException exc) {
            throw new UncheckedIOException(exc);
        }
    }

    private static void touch(Path path) {
        try {
            if (!Files.exists(path)) {
                Files.createFile(path);
            } else {
                Files.setLastModifiedTime(path, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
            }
        } catch (IOException exc) {
            throw new UncheckedIOException(exc);
        }
    }

    static void deleteRecursively(Path root) {
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best-effort cleanup, matching TemporaryDirectory's default behaviour.
                }
            });
        } catch (IOException ignored) {
            // The directory may already be gone.
        }
    }
}
