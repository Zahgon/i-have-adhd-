package io.github.ayghri.adhd.evals;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.ayghri.adhd.util.PythonStr;
import io.github.ayghri.adhd.util.PythonValue;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Port of {@code validate_cases}. */
public final class CaseValidator {

    private static final Set<String> REQUIRED_FIELDS = Set.of("id", "category", "prompt", "risk", "criteria");
    private static final Set<String> VALID_RISKS = Set.of("low", "medium", "high");

    private CaseValidator() {}

    /** Accumulates every problem rather than throwing, matching the source. */
    public static List<String> validateCases(List<JsonNode> cases) {
        List<String> errors = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        int index = 0;
        for (JsonNode evalCase : cases) {
            index++;

            List<String> missing = REQUIRED_FIELDS.stream()
                    .filter(field -> !evalCase.has(field))
                    .sorted(PythonStr.CODE_POINT_ORDER)
                    .toList();
            if (!missing.isEmpty()) {
                errors.add("Case " + index + ": missing fields: " + String.join(", ", missing));
                continue;
            }

            JsonNode idNode = evalCase.get("id");
            String caseId = PythonValue.str(idNode);
            if (!idNode.isTextual() || idNode.textValue().isEmpty()) {
                errors.add("Case " + index + ": id must be a non-empty string");
            } else if (seen.contains(caseId)) {
                errors.add("Duplicate case id: " + caseId);
            } else {
                seen.add(caseId);
            }

            JsonNode risk = evalCase.get("risk");
            if (!risk.isTextual() || !VALID_RISKS.contains(risk.textValue())) {
                errors.add("Case " + caseId + ": risk must be low, medium, or high");
            }

            JsonNode criteria = evalCase.get("criteria");
            if (!criteria.isArray() || criteria.isEmpty()) {
                errors.add("Case " + caseId + ": criteria must be a non-empty list");
            }
        }
        return errors;
    }
}
