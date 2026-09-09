package io.github.ayghri.adhd.hooks;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.ayghri.adhd.util.JsonLines;
import io.github.ayghri.adhd.util.ProcResult;
import io.github.ayghri.adhd.util.ProcRunner;
import io.github.ayghri.adhd.util.PythonJson;
import io.github.ayghri.adhd.util.RepoRoot;
import io.github.ayghri.adhd.util.Which;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Port of {@code tests/test_always_on_hooks.py}. */
final class AlwaysOnHookTest {

    private static final Path ROOT = RepoRoot.resolve();
    private static final Pattern LAUNCHER = Pattern.compile("^node( --input-type=module)? -e \"");

    @TempDir
    Path tempDir;

    private Path pluginRoot;
    private Path configDir;

    @BeforeEach
    void setUp() throws IOException {
        pluginRoot = tempDir.resolve("plugin with spaces");
        copyTree(ROOT.resolve("hooks"), pluginRoot.resolve("hooks"));
        copyTree(ROOT.resolve("skills"), pluginRoot.resolve("skills"));
        configDir = tempDir.resolve("claude config");
        Files.createDirectories(configDir);
    }

    private record Runtime(String name, List<String> command) {}

    private List<Runtime> runtimes() {
        List<Runtime> runtimes = new ArrayList<>();
        String node = Which.which("node");
        if (node != null) {
            runtimes.add(new Runtime("node", List.of(node, hook("always-on.mjs"))));
        }
        String sh = Which.which("sh");
        if (sh != null) {
            runtimes.add(new Runtime("sh", List.of(sh, hook("always-on.sh"))));
        }
        String powershell = Which.which("pwsh");
        if (powershell == null) {
            powershell = Which.which("powershell");
        }
        if (powershell != null) {
            runtimes.add(new Runtime(
                    "powershell",
                    List.of(
                            powershell,
                            "-NoProfile",
                            "-ExecutionPolicy",
                            "Bypass",
                            "-File",
                            hook("always-on.ps1"))));
        }
        return runtimes;
    }

    private String hook(String name) {
        return pluginRoot.resolve("hooks").resolve(name).toString();
    }

    private ProcResult runHook(List<String> command) {
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("CLAUDE_CONFIG_DIR", configDir.toString());
        return ProcRunner.run(command, null, env, null);
    }

    private ProcResult runCodexHook(Path pluginRootOverride) {
        // The Codex hook declared in hooks.json is a `node -e "..."` one-liner, so
        // these cases cannot run without node on PATH; without this guard they fail
        // with exit 127 instead of reporting the missing prerequisite. runtimes()
        // above already filters the Claude hooks the same way, and OpenCodePluginTest
        // guards its node-dependent cases identically.
        assumeTrue(Which.which("node") != null, "node is required to run the Codex hook");

        JsonNode config = JsonLines.loads(JsonLines.readText(ROOT.resolve("hooks").resolve("hooks.json")));
        JsonNode hook = config.get("hooks").get("SessionStart").get(0).get("hooks").get(0);
        Path root = pluginRootOverride != null ? pluginRootOverride : pluginRoot;

        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("CLAUDE_CONFIG_DIR", configDir.toString());
        env.put("CLAUDE_PLUGIN_ROOT", root.toString());
        env.put("PLUGIN_ROOT", root.toString());

        var payload = PythonJson.MAPPER.createObjectNode();
        payload.put("session_id", "test-session");
        payload.put("cwd", pluginRoot.toString());
        payload.put("hook_event_name", "SessionStart");
        payload.put("source", "startup");

        return ProcRunner.runShell(hook.get("command").textValue(), null, env, PythonJson.dumps(payload));
    }

    /**
     * The banner embeds the flag path. On Windows the sh runtime joins it with "/" while node and
     * PowerShell join with "\"; both name the same file, so unify separators (and newlines) before
     * comparing runtimes.
     */
    private static String normalize(String stdout) {
        return stdout.replace("\r\n", "\n").replace("\\", "/");
    }

    @Test
    void hookIsSilentWithoutOptInFlag() {
        List<Runtime> runtimes = runtimes();
        assertFalse(runtimes.isEmpty(), "no hook runtime is available");

        for (Runtime runtime : runtimes) {
            ProcResult result = runHook(runtime.command());
            assertEquals(0, result.returncode(), runtime.name());
            assertEquals("", result.stdout(), runtime.name());
            assertEquals("", result.stderr(), runtime.name());
        }
    }

    @Test
    void runtimesStripFrontmatterWithTrailingWhitespace() throws IOException {
        writeSkill("---   \nname: fixture\n--- \t\nFixture body.\n");
        touch(configDir.resolve(".i-have-adhd-always"));

        Set<String> outputs = new HashSet<>();
        for (Runtime runtime : runtimes()) {
            ProcResult result = runHook(runtime.command());
            assertEquals(0, result.returncode(), runtime.name());
            assertEquals("", result.stderr(), runtime.name());
            String normalized = normalize(result.stdout());
            assertFalse(normalized.contains("name: fixture"), runtime.name());
            assertTrue(normalized.contains("\n\nFixture body.\n"), runtime.name());
            outputs.add(normalized);
        }

        assertEquals(1, outputs.size());
    }

    @Test
    void runtimesKeepContentWhenFrontmatterIsUnclosed() throws IOException {
        writeSkill("---\nname: fixture\nFixture body, fence never closed.\n");
        touch(configDir.resolve(".i-have-adhd-always"));

        Set<String> outputs = new HashSet<>();
        for (Runtime runtime : runtimes()) {
            ProcResult result = runHook(runtime.command());
            assertEquals(0, result.returncode(), runtime.name());
            assertEquals("", result.stderr(), runtime.name());
            String normalized = normalize(result.stdout());
            assertTrue(normalized.contains("Fixture body, fence never closed."), runtime.name());
            outputs.add(normalized);
        }

        assertEquals(1, outputs.size());
    }

    @Test
    void codexCommandRunsTheHookInsteadOfParsingSessionJson() throws IOException {
        touch(configDir.resolve(".i-have-adhd-always"));

        ProcResult result = runCodexHook(null);

        assertEquals(0, result.returncode(), result.stderr());
        assertEquals("", result.stderr());
        assertTrue(result.stdout().contains("ADHD MODE ACTIVE (always-on)"), result.stdout());
    }

    @Test
    void codexCommandIsSilentWithoutOptInFlag() {
        ProcResult result = runCodexHook(null);

        assertEquals(0, result.returncode(), result.stderr());
        assertEquals("", result.stderr());
        assertEquals("", result.stdout());
    }

    @Test
    void codexCommandSwallowsMissingPluginErrors() {
        ProcResult result = runCodexHook(pluginRoot.resolve("missing plugin"));

        assertEquals(0, result.returncode(), result.stderr());
        assertEquals("", result.stderr());
        assertEquals("", result.stdout());
    }

    @Test
    void hookUsesASharedClaudeAndCodexLauncher() {
        JsonNode config = JsonLines.loads(JsonLines.readText(ROOT.resolve("hooks").resolve("hooks.json")));
        JsonNode hook = config.get("hooks").get("SessionStart").get(0).get("hooks").get(0);

        assertFalse(hook.has("args"));
        String command = hook.get("command").textValue();
        assertTrue(LAUNCHER.matcher(command).find(), command);
        assertTrue(command.contains("process.env.CLAUDE_PLUGIN_ROOT"));
        assertTrue(command.contains("process.env.PLUGIN_ROOT"));
        assertTrue(command.contains("await import"));
        assertTrue(command.contains(".catch"));
    }

    private void writeSkill(String text) throws IOException {
        Path path = pluginRoot.resolve("skills").resolve("i-have-adhd").resolve("SKILL.md");
        Files.writeString(path, text, StandardCharsets.UTF_8);
    }

    static void touch(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        if (!Files.exists(path)) {
            Files.createFile(path);
        }
    }

    static void copyTree(Path source, Path target) throws IOException {
        try (Stream<Path> walk = Files.walk(source)) {
            for (Path path : walk.toList()) {
                Path destination = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination);
                }
            }
        }
    }

    static Map<String, String> environment() {
        return new LinkedHashMap<>(System.getenv());
    }
}
