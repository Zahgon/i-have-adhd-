package io.github.ayghri.adhd.evals;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.ayghri.adhd.util.PythonValue;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** The {@code (case_id, trial, condition, runner)} resume key from {@code completed_keys}. */
public record CompletedKey(String caseId, long trial, String condition, String runner) {

    /**
     * Rows that do not match the expected shape are dropped silently, exactly as the source does.
     *
     * <p>The {@code trial} check uses {@link PythonValue#isPythonInt}, so a JSON boolean is accepted
     * and folded to 0/1. That is what Python does, since {@code bool} subclasses {@code int}; no
     * real catalog exercises it, but diverging here would be a silent resume bug.
     */
    public static Set<CompletedKey> completedKeys(List<JsonNode> rows) {
        Set<CompletedKey> keys = new LinkedHashSet<>();
        for (JsonNode row : rows) {
            JsonNode caseId = row.get("case_id");
            JsonNode trial = row.get("trial");
            JsonNode condition = row.get("condition");
            JsonNode runner = row.get("runner");
            if (caseId != null && caseId.isTextual()
                    && PythonValue.isPythonInt(trial)
                    && condition != null && condition.isTextual()
                    && runner != null && runner.isTextual()) {
                keys.add(new CompletedKey(
                        caseId.textValue(),
                        (long) PythonValue.numericValue(trial),
                        condition.textValue(),
                        runner.textValue()));
            }
        }
        return keys;
    }
}
