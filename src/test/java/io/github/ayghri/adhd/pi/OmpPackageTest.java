package io.github.ayghri.adhd.pi;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.ayghri.adhd.util.JsonLines;
import io.github.ayghri.adhd.util.RepoRoot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Port of {@code tests/test_omp_package.py}. */
final class OmpPackageTest {

    private static final Path ROOT = RepoRoot.resolve();

    private JsonNode packageJson;

    @BeforeEach
    void setUp() {
        packageJson = JsonLines.loads(JsonLines.readText(ROOT.resolve("package.json")));
    }

    @Test
    void ompAndPiExtensionManifestsAreDeclared() {
        assertEquals(
                JsonLines.loads("{\"extensions\": [\"./extensions/i-have-adhd.ts\"]}"),
                packageJson.get("omp"));
        assertEquals(
                JsonLines.loads(
                        "{\"extensions\": [\"./extensions/i-have-adhd.ts\"], \"skills\": [\"./skills\"]}"),
                packageJson.get("pi"));
    }

    @Test
    void skillRemainsExplicitlyInvoked() {
        String skill = JsonLines.readText(ROOT.resolve("skills").resolve("i-have-adhd").resolve("SKILL.md"));
        String frontmatter = splitOnce(skill);

        assertTrue(frontmatter.contains("name: i-have-adhd"), frontmatter);
        assertTrue(frontmatter.contains("disable-model-invocation: true"), frontmatter);
    }

    /**
     * Python's {@code skill.split("---\n", 2)[1]} keeps the remainder in a third element; Java's
     * {@code split(regex, 3)} does the same, so index 1 is the frontmatter in both languages.
     */
    private static String splitOnce(String skill) {
        String[] parts = skill.split(java.util.regex.Pattern.quote("---\n"), 3);
        return parts[1];
    }
}
