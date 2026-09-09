package io.github.ayghri.adhd.pi;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Port of {@code build_isolated_env}. */
public final class IsolatedEnv {

    /**
     * Tokens that do not follow the {@code *_API_KEY} naming convention and so have to be named
     * explicitly. Leaking either one would let the smoke test issue a real, billable model request.
     */
    private static final Set<String> EXTRA_SECRET_KEYS = Set.of("ANTHROPIC_AUTH_TOKEN", "OPENAI_ACCESS_TOKEN");

    private IsolatedEnv() {}

    public static Map<String, String> build(Path agentDir) {
        Map<String, String> env = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
            String key = entry.getKey();
            if (key.endsWith("_API_KEY") || EXTRA_SECRET_KEYS.contains(key)) {
                continue;
            }
            env.put(key, entry.getValue());
        }
        env.put("PI_CODING_AGENT_DIR", agentDir.toString());
        env.put("PI_SKIP_VERSION_CHECK", "1");
        env.put("PI_TELEMETRY", "0");
        return env;
    }
}
