package io.github.ayghri.adhd.util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Resolves the repository root.
 *
 * <p>The Python scripts used {@code Path(__file__).resolve().parents[1]}, which has no faithful Java
 * equivalent: once the tooling is packaged as a jar there is no source file to anchor on. The
 * replacement is an explicit precedence chain, with a marker-file walk as the default so ordinary
 * invocations from anywhere inside the checkout keep working.
 */
public final class RepoRoot {

    public static final String PROPERTY = "adhd.root";
    public static final String ENV_VAR = "ADHD_REPO_ROOT";

    /** Files that together identify the checkout unambiguously. */
    private static final String[] MARKERS = {"evals/cases.jsonl", "skills/i-have-adhd/SKILL.md"};

    private RepoRoot() {}

    public static Path resolve() {
        String property = System.getProperty(PROPERTY);
        if (property != null && !property.isBlank()) {
            return verified(Paths.get(property).toAbsolutePath().normalize(), "system property " + PROPERTY);
        }

        String env = System.getenv(ENV_VAR);
        if (env != null && !env.isBlank()) {
            return verified(Paths.get(env).toAbsolutePath().normalize(), "environment variable " + ENV_VAR);
        }

        Path current = Paths.get("").toAbsolutePath().normalize();
        for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
            if (looksLikeRoot(candidate)) {
                return candidate;
            }
        }

        throw new PyRuntimeError(
                "Unable to locate the i-have-adhd repository root. Searched upward from "
                        + current
                        + " for a directory containing "
                        + String.join(" and ", MARKERS)
                        + ". Set -D"
                        + PROPERTY
                        + "=<path> or the "
                        + ENV_VAR
                        + " environment variable.");
    }

    private static Path verified(Path candidate, String source) {
        if (!looksLikeRoot(candidate)) {
            throw new PyRuntimeError(
                    "The " + source + " points at " + candidate + ", which is not an i-have-adhd checkout.");
        }
        return candidate;
    }

    private static boolean looksLikeRoot(Path candidate) {
        for (String marker : MARKERS) {
            if (!Files.isRegularFile(candidate.resolve(marker))) {
                return false;
            }
        }
        return true;
    }
}
