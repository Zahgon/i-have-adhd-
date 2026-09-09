package io.github.ayghri.adhd.hooks;

import io.github.ayghri.adhd.util.ProcResult;
import io.github.ayghri.adhd.util.ProcRunner;
import io.github.ayghri.adhd.util.RepoRoot;
import io.github.ayghri.adhd.util.Which;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Port of {@code tests/test_opencode_plugin.py}. Mirrors {@link AlwaysOnHookTest} for the OpenCode
 * server plugin: the always-on flag gates injection, and frontmatter stripping matches the hooks.
 */
final class OpenCodePluginTest {

    private static final Path ROOT = RepoRoot.resolve();

    @TempDir
    Path tempDir;

    private Path pluginRoot;
    private Path configDir;

    @BeforeEach
    void setUp() throws IOException {
        assumeTrue(Which.which("node") != null, "node is required for the OpenCode plugin");

        pluginRoot = tempDir.resolve("plugin");
        AlwaysOnHookTest.copyTree(ROOT.resolve(".opencode"), pluginRoot.resolve(".opencode"));
        AlwaysOnHookTest.copyTree(ROOT.resolve("skills"), pluginRoot.resolve("skills"));
        configDir = tempDir.resolve("config");
        Files.createDirectories(configDir.resolve("opencode"));
    }

    private ProcResult runPlugin() {
        Map<String, String> env = AlwaysOnHookTest.environment();
        env.put("XDG_CONFIG_HOME", configDir.toString());
        List<String> command = List.of(
                "node",
                ROOT.resolve("tests").resolve("opencode_plugin_driver.mjs").toString(),
                pluginRoot.resolve(".opencode").resolve("plugins").resolve("i-have-adhd.mjs").toString());
        return ProcRunner.run(command, null, env, null);
    }

    private void optIn() throws IOException {
        AlwaysOnHookTest.touch(configDir.resolve("opencode").resolve(".i-have-adhd-always"));
    }

    private void writeSkill(String text) throws IOException {
        Files.writeString(
                pluginRoot.resolve("skills").resolve("i-have-adhd").resolve("SKILL.md"),
                text,
                StandardCharsets.UTF_8);
    }

    @Test
    void silentWithoutOptInFlag() {
        ProcResult result = runPlugin();
        assertEquals(0, result.returncode(), result.stderr());
        assertEquals("", result.stdout());
    }

    @Test
    void stripsFrontmatterWithTrailingWhitespace() throws IOException {
        writeSkill("---   \nname: fixture\n--- \t\nFixture body.\n");
        optIn();

        ProcResult result = runPlugin();

        assertEquals(0, result.returncode(), result.stderr());
        assertFalse(result.stdout().contains("name: fixture"), result.stdout());
        assertTrue(result.stdout().contains("\n\nFixture body."), result.stdout());
    }

    @Test
    void keepsContentWhenFrontmatterIsUnclosed() throws IOException {
        writeSkill("---\nname: fixture\nFixture body, fence never closed.\n");
        optIn();

        ProcResult result = runPlugin();

        assertEquals(0, result.returncode(), result.stderr());
        assertTrue(result.stdout().contains("Fixture body, fence never closed."), result.stdout());
    }
}
