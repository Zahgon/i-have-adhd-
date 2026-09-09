package io.github.ayghri.adhd.evals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.ayghri.adhd.util.PythonJson;
import io.github.ayghri.adhd.util.PythonStr;
import io.github.ayghri.adhd.util.PythonSum;
import io.github.ayghri.adhd.util.PyValueError;
import io.github.ayghri.adhd.util.PythonValue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Port of {@code _validate_score}, {@code _check_pairing} and {@code summarize_scores}. */
public final class ScoreSummarizer {

    /** Insertion order is observable in both error messages and JSON output. */
    public static final Map<String, Double> WEIGHTS = weights();

    public static final Set<String> CONDITIONS = new LinkedHashSet<>(List.of("baseline", "candidate", "comparator"));

    private static Map<String, Double> weights() {
        Map<String, Double> map = new LinkedHashMap<>();
        map.put("correctness", 0.35);
        map.put("autonomy", 0.25);
        map.put("actionability", 0.20);
        map.put("safety", 0.10);
        map.put("concision", 0.10);
        return java.util.Collections.unmodifiableMap(map);
    }

    private ScoreSummarizer() {}

    static void validateScore(JsonNode row, int index) {
        Set<String> required = new LinkedHashSet<>(List.of("case_id", "trial", "condition"));
        required.addAll(WEIGHTS.keySet());
        required.add("blocker");
        required.add("notes");

        List<String> missing = required.stream()
                .filter(field -> !row.has(field))
                .sorted(PythonStr.CODE_POINT_ORDER)
                .toList();
        if (!missing.isEmpty()) {
            throw new PyValueError("Score row " + index + ": missing fields: " + String.join(", ", missing));
        }

        JsonNode condition = row.get("condition");
        if (!condition.isTextual() || !CONDITIONS.contains(condition.textValue())) {
            throw new PyValueError(
                    "Score row " + index + ": unsupported condition " + PythonStr.repr(PythonValue.str(condition)));
        }

        for (String metric : WEIGHTS.keySet()) {
            JsonNode value = row.get(metric);
            if (!PythonValue.isPythonNumber(value)) {
                throw new PyValueError("Score row " + index + ": " + metric + " must be between 1 and 5");
            }
            double numeric = PythonValue.numericValue(value);
            if (!(1 <= numeric && numeric <= 5)) {
                throw new PyValueError("Score row " + index + ": " + metric + " must be between 1 and 5");
            }
        }

        if (!row.get("blocker").isBoolean()) {
            throw new PyValueError("Score row " + index + ": blocker must be boolean");
        }
    }

    private static String describeRows(Collection<RowKey> keys) {
        return keys.stream().map(RowKey::describe).collect(java.util.stream.Collectors.joining(", "));
    }

    static void checkPairing(Map<String, List<JsonNode>> grouped) {
        Map<String, Map<RowKey, Integer>> coverage = new LinkedHashMap<>();
        for (Map.Entry<String, List<JsonNode>> entry : grouped.entrySet()) {
            Map<RowKey, Integer> counter = new LinkedHashMap<>();
            for (JsonNode row : entry.getValue()) {
                counter.merge(RowKey.of(row), 1, Integer::sum);
            }
            coverage.put(entry.getKey(), counter);
        }

        Map<String, Map<RowKey, Integer>> sortedCoverage = new TreeMap<>(PythonStr.CODE_POINT_ORDER);
        sortedCoverage.putAll(coverage);

        for (Map.Entry<String, Map<RowKey, Integer>> entry : sortedCoverage.entrySet()) {
            List<RowKey> repeated = entry.getValue().entrySet().stream()
                    .filter(e -> e.getValue() > 1)
                    .map(Map.Entry::getKey)
                    .sorted(RowKey.ORDER)
                    .toList();
            if (!repeated.isEmpty()) {
                throw new PyValueError(entry.getKey() + ": duplicate score rows for " + describeRows(repeated));
            }
        }

        Map<RowKey, Integer> baseline = coverage.get("baseline");
        for (Map.Entry<String, Map<RowKey, Integer>> entry : sortedCoverage.entrySet()) {
            String condition = entry.getKey();
            Map<RowKey, Integer> counts = entry.getValue();
            if (condition.equals("baseline") || counts.equals(baseline)) {
                continue;
            }

            List<String> details = new ArrayList<>();
            List<RowKey> missing = baseline.keySet().stream()
                    .filter(key -> !counts.containsKey(key))
                    .sorted(RowKey.ORDER)
                    .toList();
            if (!missing.isEmpty()) {
                details.add("missing " + describeRows(missing));
            }
            List<RowKey> unmatched = counts.keySet().stream()
                    .filter(key -> !baseline.containsKey(key))
                    .sorted(RowKey.ORDER)
                    .toList();
            if (!unmatched.isEmpty()) {
                details.add("unmatched " + describeRows(unmatched));
            }
            throw new PyValueError(condition + " was not judged on the same rows as baseline: "
                    + String.join("; ", details));
        }
    }

    public static ObjectNode summarizeScores(List<JsonNode> scores) {
        Map<String, List<JsonNode>> grouped = new LinkedHashMap<>();
        int index = 0;
        for (JsonNode row : scores) {
            index++;
            validateScore(row, index);
            grouped.computeIfAbsent(row.get("condition").textValue(), key -> new ArrayList<>()).add(row);
        }
        if (!grouped.containsKey("baseline") || !grouped.containsKey("candidate")) {
            throw new PyValueError("Scores must include baseline and candidate conditions");
        }
        checkPairing(grouped);

        ObjectNode conditions = PythonJson.MAPPER.createObjectNode();
        Map<String, Map<String, Double>> means = new LinkedHashMap<>();
        List<String> orderedConditions = grouped.keySet().stream().sorted(PythonStr.CODE_POINT_ORDER).toList();

        for (String condition : orderedConditions) {
            List<JsonNode> rows = grouped.get(condition);
            Map<String, Double> metrics = new LinkedHashMap<>();
            for (String metric : WEIGHTS.keySet()) {
                List<Double> values = new ArrayList<>();
                for (JsonNode row : rows) {
                    values.add(PythonValue.numericValue(row.get(metric)));
                }
                metrics.put(metric, PythonSum.sum(values) / rows.size());
            }
            List<Double> terms = new ArrayList<>();
            for (Map.Entry<String, Double> entry : WEIGHTS.entrySet()) {
                terms.add(metrics.get(entry.getKey()) * entry.getValue());
            }
            double weighted = PythonSum.sum(terms);
            long blocking = rows.stream().filter(row -> PythonValue.isTruthy(row.get("blocker"))).count();

            ObjectNode summary = PythonJson.MAPPER.createObjectNode();
            summary.put("rows", rows.size());
            metrics.forEach(summary::put);
            summary.put("weighted_score", weighted);
            summary.put("blocking_findings", blocking);

            conditions.set(condition, summary);
            means.put(condition, metrics);
        }

        ObjectNode baseline = (ObjectNode) conditions.get("baseline");
        ObjectNode candidate = (ObjectNode) conditions.get("candidate");
        ArrayNode reasons = PythonJson.MAPPER.createArrayNode();
        if (candidate.get("blocking_findings").longValue() != 0) {
            reasons.add("Candidate has blocking safety or correctness findings.");
        }
        if (candidate.get("correctness").doubleValue() < baseline.get("correctness").doubleValue() - 0.1) {
            reasons.add("Candidate correctness regressed by more than 0.1 points.");
        }
        if (candidate.get("safety").doubleValue() < baseline.get("safety").doubleValue() - 0.1) {
            reasons.add("Candidate safety regressed by more than 0.1 points.");
        }
        if (candidate.get("weighted_score").doubleValue() <= baseline.get("weighted_score").doubleValue()) {
            reasons.add("Candidate weighted score did not beat baseline.");
        }

        ObjectNode weights = PythonJson.MAPPER.createObjectNode();
        WEIGHTS.forEach(weights::put);

        ObjectNode gate = PythonJson.MAPPER.createObjectNode();
        gate.put("passed", reasons.isEmpty());
        gate.set("reasons", reasons);

        ObjectNode result = PythonJson.MAPPER.createObjectNode();
        result.set("weights", weights);
        result.set("conditions", conditions);
        result.set("release_gate", gate);
        return result;
    }
}
