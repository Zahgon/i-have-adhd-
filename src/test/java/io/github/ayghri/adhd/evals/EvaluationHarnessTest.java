package io.github.ayghri.adhd.evals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.ayghri.adhd.util.JsonLines;
import io.github.ayghri.adhd.util.PyJsonDecodeError;
import io.github.ayghri.adhd.util.PyRuntimeError;
import io.github.ayghri.adhd.util.PyValueError;
import io.github.ayghri.adhd.util.PythonFloat;
import io.github.ayghri.adhd.util.PythonJson;
import io.github.ayghri.adhd.util.RepoRoot;
import io.github.ayghri.adhd.util.Which;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Port of {@code tests/test_run_evals.py}. */
final class EvaluationHarnessTest {

    private static final Path ROOT = RepoRoot.resolve();

    @Test
    void caseCatalogIsValidAndBalanced() {
        List<JsonNode> cases = JsonLines.readJsonl(ROOT.resolve("evals").resolve("cases.jsonl"));
        List<String> errors = CaseValidator.validateCases(cases);

        assertEquals(List.of(), errors);
        assertTrue(cases.size() >= 12, "expected at least 12 cases, got " + cases.size());

        Set<String> categories = new HashSet<>();
        cases.forEach(node -> categories.add(node.get("category").textValue()));
        assertTrue(categories.size() >= 8, "expected at least 8 categories, got " + categories.size());
    }

    @Test
    void scoreSummaryAppliesWeightsAndReleaseGates() {
        List<JsonNode> scores =
                List.of(scoreRow("direct-answer", "baseline", 3), scoreRow("direct-answer", "candidate", 4));

        JsonNode summary = ScoreSummarizer.summarizeScores(scores);

        assertEquals(3.0, summary.get("conditions").get("baseline").get("weighted_score").doubleValue(), 1e-7);
        assertEquals(4.0, summary.get("conditions").get("candidate").get("weighted_score").doubleValue(), 1e-7);
        assertTrue(summary.get("release_gate").get("passed").booleanValue());
    }

    @Test
    void candidateBlockerFailsReleaseGate() {
        ObjectNode baseline = scoreRow("dangerous-action", "baseline", 5);
        ObjectNode candidate = scoreRow("dangerous-action", "candidate", 5);
        candidate.put("blocker", true);

        JsonNode summary = ScoreSummarizer.summarizeScores(List.of(baseline, candidate));

        assertFalse(summary.get("release_gate").get("passed").booleanValue());
        assertTrue(joinReasons(summary).contains("blocking"), "reasons were: " + joinReasons(summary));
    }

    @Test
    void conditionsJudgedOnDifferentCasesAreRejected() {
        List<JsonNode> rows = List.of(
                scoreRow("destructive-action", "baseline", 2),
                scoreRow("medical-boundary", "baseline", 2),
                scoreRow("direct-answer", "candidate", 5));

        PyValueError error = assertThrows(PyValueError.class, () -> ScoreSummarizer.summarizeScores(rows));
        assertTrue(error.getMessage().contains("not judged on the same rows"), error.getMessage());
    }

    @Test
    void duplicateScoreRowsAreRejected() {
        List<JsonNode> rows = List.of(
                scoreRow("direct-answer", "baseline", 3),
                scoreRow("direct-answer", "candidate", 4),
                scoreRow("direct-answer", "candidate", 5));

        PyValueError error = assertThrows(PyValueError.class, () -> ScoreSummarizer.summarizeScores(rows));
        assertTrue(error.getMessage().contains("duplicate score rows"), error.getMessage());
    }

    @Test
    void duplicateCaseIdsAreRejected() {
        ObjectNode caseNode = PythonJson.MAPPER.createObjectNode();
        caseNode.put("id", "duplicate");
        caseNode.put("category", "direct-answer");
        caseNode.put("prompt", "What is 2 + 2?");
        caseNode.put("risk", "low");
        ArrayNode criteria = caseNode.putArray("criteria");
        criteria.add("Answers 4.");

        List<String> errors = CaseValidator.validateCases(List.of(caseNode, caseNode.deepCopy()));

        assertTrue(errors.stream().anyMatch(error -> error.contains("Duplicate")), "errors were: " + errors);
    }

    @Test
    void jsonlLoaderReportsInvalidRows(@TempDir Path tmp) throws Exception {
        Path path = tmp.resolve("bad.jsonl");
        Files.writeString(path, "{\"id\": \"ok\"}\nnot-json\n", StandardCharsets.UTF_8);

        PyValueError error = assertThrows(PyValueError.class, () -> JsonLines.readJsonl(path));
        assertTrue(error.getMessage().contains("line 2"), error.getMessage());
    }

    @Test
    void unmeteredRunnerIsRejectedBeforeAnyCall(@TempDir Path tmp) throws Exception {
        String node = Which.which("node");
        assumeTrue(node != null, "node is required to run the stub runner");

        Path marker = tmp.resolve("ran");
        Path output = tmp.resolve("out.jsonl");
        Path runnerConfig = tmp.resolve("runners.json");
        Files.writeString(runnerConfig, stubRunnerConfig(node, marker), StandardCharsets.UTF_8);

        EvalOptions args = new EvalOptions();
        args.cases = ROOT.resolve("evals").resolve("cases.jsonl");
        args.runnerConfig = runnerConfig;
        args.runner = "stub";
        args.condition = "baseline";
        args.caseFilter = List.of("direct-answer");
        args.trials = 1;
        args.retries = 0;
        args.budgetUsd = 1.0;
        args.allowUnmetered = false;
        args.output = output;

        PyRuntimeError error = assertThrows(PyRuntimeError.class, () -> runEvaluations(args));
        assertTrue(error.getMessage().contains("never reports dollar cost"), error.getMessage());

        assertFalse(Files.exists(marker), "runner was invoked before the rejection");
        assertFalse(Files.exists(output));

        args.allowUnmetered = true;
        assertEquals(0, runEvaluations(args));
        assertTrue(Files.exists(marker));
    }

    @Test
    void conditionPromptEmbedsTheSkillFileVerbatim(@TempDir Path tmp) throws Exception {
        Path skill = tmp.resolve("SKILL.md");
        Files.writeString(skill, "---\nname: fixture\n---\n\nRULE ONE.\n\n", StandardCharsets.UTF_8);

        String prompt = EvalRunner.conditionPrompt("Do the thing.", "candidate", skill);

        assertTrue(
                prompt.contains("<response_style>\n---\nname: fixture\n---\n\nRULE ONE.\n\n\n</response_style>"),
                "skill file must be embedded byte-for-byte, got: " + prompt);
    }

    @Test
    void completedKeysSupportResumingPartialRuns() {
        ObjectNode row = PythonJson.MAPPER.createObjectNode();
        row.put("case_id", "direct-answer");
        row.put("trial", 1);
        row.put("condition", "baseline");
        row.put("runner", "claude");

        assertEquals(
                Set.of(new CompletedKey("direct-answer", 1, "baseline", "claude")),
                CompletedKey.completedKeys(List.of(row)));
    }

    @Test
    void weightedScoreReproducesCompensatedSummation() {
        ObjectNode baseline = scoreRow("alpha", "baseline", 3);
        baseline.put("correctness", true);

        JsonNode summary = ScoreSummarizer.summarizeScores(List.of(baseline, scoreRow("alpha", "candidate", 4)));
        double weighted = summary.get("conditions").get("baseline").get("weighted_score").doubleValue();

        // CPython's sum() applies Neumaier compensation; a naive Java loop returns 2.3 here.
        assertEquals("2.3000000000000003", PythonFloat.repr(weighted));
    }

    @Test
    void jsonLoadsReportsPythonDecodeMessages() {
        PyJsonDecodeError extra = assertThrows(PyJsonDecodeError.class, () -> JsonLines.loads("{\"id\": \"ok\"} trailing"));
        assertEquals("Extra data", extra.msg());
        assertEquals("Extra data: line 1 column 14 (char 13)", extra.getMessage());

        PyJsonDecodeError bom = assertThrows(PyJsonDecodeError.class, () -> JsonLines.loads("\uFEFF{}"));
        assertEquals("Unexpected UTF-8 BOM (decode using utf-8-sig)", bom.msg());
    }

    @Test
    void topLevelOptionDoesNotSatisfyTheSubcommand() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code;
        try (PrintStream outStream = new PrintStream(out, true, StandardCharsets.UTF_8);
                PrintStream errStream = new PrintStream(err, true, StandardCharsets.UTF_8)) {
            code = RunEvals.run(new String[] {"--zzz"}, outStream, errStream, ROOT);
        }
        assertEquals(2, code);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("the following arguments are required: command"),
                err.toString(StandardCharsets.UTF_8));
    }

    private static int runEvaluations(EvalOptions args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try (PrintStream outStream = new PrintStream(out, true, StandardCharsets.UTF_8);
                PrintStream errStream = new PrintStream(err, true, StandardCharsets.UTF_8)) {
            return EvalRunner.runEvaluations(args, ROOT, outStream, errStream);
        }
    }

    private static String stubRunnerConfig(String node, Path marker) {
        ObjectNode script = PythonJson.MAPPER.createObjectNode();
        ArrayNode command = script.putArray("command");
        command.add(node);
        command.add("-e");
        command.add("require('fs').writeFileSync(process.argv[1], ''); console.log('hi')");
        command.add(marker.toString());
        script.put("response_format", "text");

        ObjectNode config = PythonJson.MAPPER.createObjectNode();
        config.set("stub", script);
        return PythonJson.dumps(config);
    }

    private static String joinReasons(JsonNode summary) {
        StringBuilder joined = new StringBuilder();
        for (JsonNode reason : summary.get("release_gate").get("reasons")) {
            if (joined.length() > 0) {
                joined.append(' ');
            }
            joined.append(reason.textValue());
        }
        return joined.toString();
    }

    private static ObjectNode scoreRow(String caseId, String condition, int value) {
        ObjectNode row = PythonJson.MAPPER.createObjectNode();
        row.put("case_id", caseId);
        row.put("trial", 1);
        row.put("condition", condition);
        row.put("correctness", value);
        row.put("autonomy", value);
        row.put("actionability", value);
        row.put("safety", value);
        row.put("concision", value);
        row.put("blocker", false);
        row.put("notes", "fixture");
        return row;
    }
}
