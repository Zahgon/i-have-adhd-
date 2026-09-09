package io.github.ayghri.adhd.pi;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.ayghri.adhd.util.JsonLines;
import io.github.ayghri.adhd.util.PyAssert;
import io.github.ayghri.adhd.util.PythonJson;

import java.nio.file.Path;
import java.util.List;

/** Port of {@code validate_package_manifest}. */
public final class ManifestValidator {

    public static final List<String> SHARED_MANIFEST_FIELDS =
            List.of("name", "version", "description", "license", "homepage");

    private ManifestValidator() {}

    public static void validatePackageManifest(Path root) {
        JsonNode packageJson = JsonLines.loads(JsonLines.readText(root.resolve("package.json")));
        JsonNode kimiJson = JsonLines.loads(JsonLines.readText(root.resolve("kimi.plugin.json")));

        for (String field : SHARED_MANIFEST_FIELDS) {
            PyAssert.check(
                    equalOrBothAbsent(packageJson.get(field), kimiJson.get(field)),
                    "package.json and kimi.plugin.json disagree on " + field);
        }

        PyAssert.check(matches(
                packageJson.get("pi"),
                "{\"extensions\": [\"./extensions/i-have-adhd.ts\"], \"skills\": [\"./skills\"]}"));
        PyAssert.check(matches(
                packageJson.get("omp"), "{\"extensions\": [\"./extensions/i-have-adhd.ts\"]}"));
    }

    private static boolean matches(JsonNode actual, String expectedJson) {
        return equalOrBothAbsent(actual, PythonJson.MAPPER.valueToTree(JsonLines.loads(expectedJson)));
    }

    /**
     * A missing key and an explicit JSON {@code null} both surface as Python's {@code None} from
     * {@code dict.get}, so they must compare equal here even though Jackson distinguishes
     * {@code null} from {@code NullNode}.
     */
    private static boolean equalOrBothAbsent(JsonNode left, JsonNode right) {
        boolean leftMissing = left == null || left.isNull();
        boolean rightMissing = right == null || right.isNull();
        if (leftMissing || rightMissing) {
            return leftMissing && rightMissing;
        }
        return left.equals(right);
    }
}
