package io.github.ayghri.adhd.evals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertLinesMatch;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.ayghri.adhd.util.JsonLines;
import io.github.ayghri.adhd.util.PyRuntimeError;
import io.github.ayghri.adhd.util.PythonJson;
import io.github.ayghri.adhd.util.RepoRoot;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the {@code run_evals.py} command line down to its argparse-compatible spelling.
 *
 * <p>The Python original inherited usage strings, abbreviation matching, {@code --opt=value}
 * handling and the exact wording of every error from argparse itself. None of that survives a
 * migration for free, so operators' muscle memory — and any script wrapping the CLI — is only
 * protected by tests that drive {@link RunEvals#run} the same way a shell would.
 */
final class CliSurfaceTest {

    private static final Path ROOT = RepoRoot.resolve();

    private record Invocation(int code, String out, String err) {}

    private static Invocation cli(String... argv) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code;
        try (PrintStream outStream = new PrintStream(out, true, StandardCharsets.UTF_8);
                PrintStream errStream = new PrintStream(err, true, StandardCharsets.UTF_8)) {
            code = RunEvals.run(argv, outStream, errStream, ROOT);
        }
        return new Invocation(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private static Path writeCases(Path dir, String... rows) {
        return write(dir.resolve("cases.jsonl"), String.join("\n", rows) + "\n");
    }

    private static Path write(Path target, String body) {
        try {
            Files.createDirectories(target.toAbsolutePath().getParent());
            Files.writeString(target, body, StandardCharsets.UTF_8);
        } catch (IOException exc) {
            throw new UncheckedIOException(exc);
        }
        return target;
    }

    private static String caseRow(String id, String risk) {
        return "{\"id\": \"" + id + "\", \"category\": \"focus\", \"prompt\": \"say hi\", \"risk\": \"" + risk
                + "\", \"criteria\": [\"is short\"]}";
    }

    private static String scoreRow(String caseId, int trial, String condition, double value, boolean blocker) {
        ObjectNode row = PythonJson.MAPPER.createObjectNode();
        row.put("case_id", caseId);
        row.put("trial", trial);
        row.put("condition", condition);
        for (String metric : ScoreSummarizer.WEIGHTS.keySet()) {
            row.put(metric, value);
        }
        row.put("blocker", blocker);
        row.put("notes", "");
        return PythonJson.dumps(row);
    }

    @Test
    void topLevelHelpIsPrintedForBothHelpSpellings() {
        Invocation shortForm = cli("-h");
        assertEquals(0, shortForm.code());
        assertEquals("", shortForm.err());
        assertTrue(shortForm.out().startsWith("usage: run_evals.py [-h] {validate,plan,score,run} ..."), shortForm.out());
        assertTrue(shortForm.out().contains("Validate, run, and score paired response-quality evaluations."),
                shortForm.out());

        assertEquals(shortForm.out(), cli("--help").out());
    }

    @Test
    void eachSubcommandHasItsOwnHelpScreen() {
        for (String command : List.of("validate", "plan", "score", "run")) {
            Invocation viaShortFlag = cli(command, "-h");
            assertEquals(0, viaShortFlag.code(), command);
            assertTrue(viaShortFlag.out().startsWith("usage: run_evals.py " + command), viaShortFlag.out());
            assertTrue(viaShortFlag.out().contains("-h, --help"), viaShortFlag.out());

            // `--help` reaches the parser one stage later, through the abbreviation pass.
            assertEquals(viaShortFlag.out(), cli(command, "--help").out(), command);
        }

        assertTrue(cli("plan", "-h").out().contains("--include-comparator"));
        assertTrue(cli("run", "-h").out().contains("--allow-unmetered"));
    }

    @Test
    void missingAndUnknownCommandsFailTheArgparseWay() {
        Invocation empty = cli();
        assertEquals(2, empty.code());
        assertEquals("", empty.out());
        assertLinesMatch(
                List.of(
                        "usage: run_evals.py [-h] {validate,plan,score,run} ...",
                        "run_evals.py: error: the following arguments are required: command"),
                empty.err().lines().toList());

        assertEquals(2, cli("--verbose").code());
        assertTrue(cli("--verbose").err().contains("the following arguments are required: command"));

        Invocation unknown = cli("bogus");
        assertEquals(2, unknown.code());
        assertTrue(
                unknown.err().contains(
                        "run_evals.py: error: argument command: invalid choice: 'bogus'"
                                + " (choose from 'validate', 'plan', 'score', 'run')"),
                unknown.err());
    }

    @Test
    void validateAcceptsAGoodCatalogAndReportsEveryProblemInABadOne(@TempDir Path dir) {
        Path good = writeCases(dir, caseRow("focus-1", "low"), caseRow("focus-2", "high"));
        Invocation ok = cli("validate", "--cases", good.toString());
        assertEquals(0, ok.code(), ok.err());
        assertEquals("Evaluation cases are valid.\n", ok.out());

        Path bad = write(dir.resolve("bad.jsonl"),
                "{\"id\": \"only-id\"}\n"
                        + caseRow("dup", "low") + "\n"
                        + caseRow("dup", "sideways") + "\n"
                        + "{\"id\": \"\", \"category\": \"c\", \"prompt\": \"p\", \"risk\": \"low\","
                        + " \"criteria\": []}\n");
        Invocation broken = cli("validate", "--cases", bad.toString());
        assertEquals(1, broken.code());
        assertLinesMatch(
                List.of(
                        "ERROR: Case 1: missing fields: category, criteria, prompt, risk",
                        "ERROR: Duplicate case id: dup",
                        "ERROR: Case dup: risk must be low, medium, or high",
                        "ERROR: Case 4: id must be a non-empty string",
                        "ERROR: Case : criteria must be a non-empty list"),
                broken.err().lines().toList());
    }

    @Test
    void planEmitsOneRowPerTrialCaseAndCondition(@TempDir Path dir) {
        Path cases = writeCases(dir, caseRow("a", "low"), caseRow("b", "medium"));

        Invocation paired = cli("plan", "--cases", cases.toString(), "--trials", "2");
        assertEquals(0, paired.code(), paired.err());
        List<String> rows = paired.out().lines().toList();
        assertEquals(8, rows.size(), paired.out());
        assertEquals("{\"case_id\": \"a\", \"trial\": 1, \"condition\": \"baseline\"}", rows.get(0));
        assertEquals("{\"case_id\": \"b\", \"trial\": 2, \"condition\": \"candidate\"}", rows.get(7));

        // argparse accepts any unambiguous prefix, so `--tri` and `--include-comp` must work too.
        Invocation abbreviated =
                cli("plan", "--cases", cases.toString(), "--tri", "2", "--include-comp");
        assertEquals(0, abbreviated.code(), abbreviated.err());
        assertEquals(12, abbreviated.out().lines().count());
        assertTrue(abbreviated.out().contains("\"condition\": \"comparator\""));

        Invocation notANumber = cli("plan", "--cases", cases.toString(), "--trials", "many");
        assertEquals(2, notANumber.code());
        assertTrue(notANumber.err().contains("argument --trials: invalid int value: 'many'"), notANumber.err());
    }

    @Test
    void scoreTakesAPositionalPathAndPrintsAnIndentedSummary(@TempDir Path dir) {
        Path scores = write(dir.resolve("scores.jsonl"),
                scoreRow("a", 1, "baseline", 3.0, false) + "\n"
                        + scoreRow("a", 1, "candidate", 5.0, false) + "\n");

        Invocation summary = cli("score", scores.toString());
        assertEquals(0, summary.code(), summary.err());
        assertTrue(summary.out().startsWith("{\n  \"weights\": {\n"), summary.out());

        JsonNode parsed = JsonLines.loads(summary.out());
        assertTrue(parsed.path("release_gate").path("passed").booleanValue(), summary.out());
        assertEquals(3.0, parsed.path("conditions").path("baseline").path("weighted_score").doubleValue());
        assertEquals(5.0, parsed.path("conditions").path("candidate").path("weighted_score").doubleValue());

        Invocation missing = cli("score");
        assertEquals(2, missing.code());
        assertTrue(missing.err().contains("the following arguments are required: scores"), missing.err());
    }

    @Test
    void optionSyntaxFollowsArgparse(@TempDir Path dir) {
        Path cases = writeCases(dir, caseRow("a", "low"));

        Invocation valueless = cli("validate", "--cases");
        assertEquals(2, valueless.code());
        assertTrue(valueless.err().contains("argument --cases: expected one argument"), valueless.err());

        Invocation joined = cli("validate", "--cases=" + cases);
        assertEquals(0, joined.code(), joined.err());
        assertEquals("Evaluation cases are valid.\n", joined.out());

        Invocation leftovers = cli("validate", "--cases", cases.toString(), "stray");
        assertEquals(2, leftovers.code());
        assertLinesMatch(
                List.of(
                        "usage: run_evals.py [-h] {validate,plan,score,run} ...",
                        "run_evals.py: error: unrecognized arguments: stray"),
                leftovers.err().lines().toList());

        Invocation ambiguous = cli("run", "--c", "x", "--output", dir.resolve("o.jsonl").toString());
        assertEquals(2, ambiguous.code());
        assertTrue(
                ambiguous.err().contains(
                        "ambiguous option: --c could match --cases, --condition, --condition-skill, --case"),
                ambiguous.err());
    }

    @Test
    void runValidatesItsRequiredArgumentsBeforeSpawningAnything(@TempDir Path dir) {
        Invocation bare = cli("run");
        assertEquals(2, bare.code());
        assertTrue(
                bare.err().contains("the following arguments are required: --runner, --condition, --output"),
                bare.err());

        Path output = dir.resolve("out.jsonl");
        Invocation badCondition =
                cli("run", "--runner", "stub", "--condition", "sideways", "--output", output.toString());
        assertEquals(2, badCondition.code());
        assertTrue(
                badCondition.err().contains("argument --condition: invalid choice: 'sideways'"
                        + " (choose from 'baseline', 'candidate', 'comparator')"),
                badCondition.err());

        Invocation badBudget = cli("run", "--runner", "stub", "--condition", "baseline",
                "--out", output.toString(), "--budget-usd", "free");
        assertEquals(2, badBudget.code());
        assertTrue(badBudget.err().contains("argument --budget-usd: invalid float value: 'free'"), badBudget.err());
    }

    @Test
    void runInvokesTheRunnerOncePerSelectedCaseAndRecordsCost(@TempDir Path dir) {
        Path cases = writeCases(dir, caseRow("keep", "low"), caseRow("drop", "low"));
        Path promptEcho = dir.resolve("prompt.txt");
        Path output = dir.resolve("responses.jsonl");
        Path config = write(dir.resolve("runners.json"), stubRunner(
                "printf '%s' \"$2\" > \"$1\";"
                        + " printf '{\"result\":\"stub answer\",\"total_cost_usd\":0.02,"
                        + "\"usage\":{\"input_tokens\":11}}\\n'",
                promptEcho));

        Invocation run = cli("run", "--cases", cases.toString(), "--runner-config", config.toString(),
                "--runner", "stub", "--condition", "baseline", "--case", "keep",
                "--trials", "2", "--budget-usd", "1.5", "--out", output.toString());

        assertEquals(0, run.code(), run.err());
        assertLinesMatch(
                List.of("baseline trial 1: keep", "baseline trial 2: keep", "Reported cost: $0.0400"),
                run.out().lines().toList());
        assertEquals("say hi", readString(promptEcho));

        List<JsonNode> rows = JsonLines.readJsonl(output);
        assertEquals(2, rows.size());
        assertEquals("stub answer", rows.get(0).get("response").textValue());
        assertEquals(0.02, rows.get(0).get("cost_usd").doubleValue());
        assertEquals(11, rows.get(1).get("usage").get("input_tokens").intValue());

        // Re-running the same command must resume rather than pay twice.
        Invocation resumed = cli("run", "--cases", cases.toString(), "--runner-config", config.toString(),
                "--runner", "stub", "--condition", "baseline", "--case", "keep",
                "--trials", "2", "--budget-usd", "1.5", "--out", output.toString());
        assertEquals(0, resumed.code(), resumed.err());
        assertLinesMatch(
                List.of(
                        "skip completed baseline trial 1: keep",
                        "skip completed baseline trial 2: keep",
                        "Reported cost: $0.0400"),
                resumed.out().lines().toList());
        assertEquals(2, JsonLines.readJsonl(output).size());
    }

    @Test
    void runRetriesThenReportsWhatTheRunnerPrinted(@TempDir Path dir) {
        Path cases = writeCases(dir, caseRow("a", "low"));
        Path attempts = dir.resolve("attempts.txt");
        Path config = write(dir.resolve("runners.json"), stubRunner(
                "printf 'x' >> \"$1\"; printf 'runner exploded\\n' >&2; exit 3", attempts));

        PyRuntimeError failure = assertThrows(PyRuntimeError.class, () ->
                cli("run", "--cases", cases.toString(), "--runner-config", config.toString(),
                        "--runner", "stub", "--condition", "baseline", "--retries", "1",
                        "--out", dir.resolve("out.jsonl").toString()));

        assertTrue(failure.getMessage().startsWith("Runner failed after 2 attempts ("), failure.getMessage());
        assertTrue(failure.getMessage().endsWith("):\nrunner exploded"), failure.getMessage());
        assertEquals("xx", readString(attempts), "the runner must be retried exactly `--retries` extra times");
    }

    @Test
    void unknownCommandHelperCarriesTheProgramAndUsage() {
        Cli cli = new Cli(new String[] {"validate"});
        assertEquals("validate", cli.nextCommand(List.of("validate", "plan", "score", "run")));

        Cli.UsageError error = cli.usageError("unknown command");
        assertEquals("unknown command", error.getMessage());
        assertEquals("run_evals.py validate", error.prog());
        assertTrue(error.usage().startsWith("usage: run_evals.py validate"), error.usage());
    }

    @Test
    void rowKeysOrderNumericTrialsBeforeTextualOnes() {
        List<RowKey> keys = new ArrayList<>(List.of(
                RowKey.of(row("beta", "1")),
                RowKey.of(row("alpha", "\"later\"")),
                RowKey.of(row("alpha", "2")),
                RowKey.of(row("alpha", "1"))));
        keys.sort(RowKey.ORDER);

        assertEquals(
                List.of("alpha/trial 1", "alpha/trial 2", "alpha/trial later", "beta/trial 1"),
                keys.stream().map(RowKey::describe).toList());

        // Python's numeric tower makes 1, 1.0 and True the same Counter key.
        assertEquals(RowKey.of(row("alpha", "1")), RowKey.of(row("alpha", "1.0")));
        assertEquals(RowKey.of(row("alpha", "1")).hashCode(), RowKey.of(row("alpha", "true")).hashCode());
    }

    @Test
    void codexJsonlStreamsAreReducedToTheAgentMessage() {
        String stream = "{\"type\": \"turn.started\"}\n"
                + "\n"
                + "{\"type\": \"item.completed\", \"item\": {\"type\": \"reasoning\", \"text\": \"ignored\"}}\n"
                + "{\"type\": \"item.completed\", \"item\": {\"type\": \"agent_message\", \"text\": \"final\"}}\n"
                + "{\"type\": \"turn.completed\", \"usage\": {\"input_tokens\": 4}}\n";

        ResponseParser.ParsedResponse parsed = ResponseParser.parseResponse(stream, "codex-jsonl");
        assertEquals("final", parsed.text());
        assertEquals(4, parsed.usage().get("input_tokens").intValue());
        assertFalse(parsed.hasCost(), "codex never reports dollar cost");
        assertEquals(0.0, parsed.costOrZero());
    }

    private static ObjectNode row(String caseId, String trialJson) {
        ObjectNode node = PythonJson.MAPPER.createObjectNode();
        node.put("case_id", caseId);
        node.set("trial", JsonLines.loads(trialJson));
        return node;
    }

    private static String stubRunner(String script, Path scratch) {
        ObjectNode config = PythonJson.MAPPER.createObjectNode();
        ObjectNode stub = config.putObject("stub");
        stub.putArray("command")
                .add("/bin/sh")
                .add("-c")
                .add(script)
                .add("adhd-stub")
                .add(scratch.toString());
        stub.put("response_format", "claude-json");
        return PythonJson.dumps(config, false);
    }

    private static String readString(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException exc) {
            throw new UncheckedIOException(exc);
        }
    }

    @Test
    void everySubcommandUsageIsDistinct() {
        List<String> usages = new ArrayList<>();
        for (String command : List.of("validate", "plan", "score", "run")) {
            String help = cli(command, "-h").out();
            usages.add(help.lines().findFirst().orElseThrow());
        }
        assertEquals(usages.size(), usages.stream().distinct().count(), usages.toString());
        assertNotNull(Cli.topLevelUsage());
        assertEquals("usage: run_evals.py [-h] {validate,plan,score,run} ...", Cli.topLevelUsage());
    }
}
