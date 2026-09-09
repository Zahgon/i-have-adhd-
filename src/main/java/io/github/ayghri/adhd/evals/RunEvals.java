package io.github.ayghri.adhd.evals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.ayghri.adhd.util.JsonLines;
import io.github.ayghri.adhd.util.PyValueError;
import io.github.ayghri.adhd.util.PythonJson;
import io.github.ayghri.adhd.util.RepoRoot;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Command line entry point, mirroring the {@code argparse} surface of {@code run_evals.py}. */
public final class RunEvals {

    static final String PROG = "run_evals.py";
    private static final List<String> COMMANDS = List.of("validate", "plan", "score", "run");
    private static final List<String> CONDITION_CHOICES = List.of("baseline", "candidate", "comparator");

    private RunEvals() {}

    public static void main(String[] argv) {
        int code;
        try {
            code = run(argv, System.out, System.err, RepoRoot.resolve());
        } catch (RuntimeException exc) {
            exc.printStackTrace(System.err);
            code = 1;
        }
        System.out.flush();
        System.err.flush();
        System.exit(code);
    }

    public static int run(String[] argv, PrintStream out, PrintStream err, Path root) {
        try {
            return dispatch(argv, out, err, root);
        } catch (Cli.HelpRequested exc) {
            out.print(exc.help());
            return 0;
        } catch (Cli.UsageError exc) {
            err.println(exc.usage());
            err.println(exc.prog() + ": error: " + exc.getMessage());
            return 2;
        }
    }

    private static int dispatch(String[] argv, PrintStream out, PrintStream err, Path root) {
        Cli cli = new Cli(argv);
        if (cli.wantsTopLevelHelp()) {
            out.print(Cli.topLevelHelp());
            return 0;
        }

        String command = cli.nextCommand(COMMANDS);
        Path defaultCases = root.resolve("evals").resolve("cases.jsonl");

        switch (command) {
            case "validate" -> {
                EvalOptions options = new EvalOptions();
                cli.allowAbbrev(List.of("--cases"));
                options.cases = cli.optionalPath("--cases", defaultCases);
                cli.finish();
                return validate(options, out, err);
            }
            case "plan" -> {
                EvalOptions options = new EvalOptions();
                cli.allowAbbrev(List.of("--cases", "--trials", "--include-comparator"));
                options.cases = cli.optionalPath("--cases", defaultCases);
                options.trials = cli.optionalInt("--trials", 3);
                options.includeComparator = cli.flag("--include-comparator");
                cli.finish();
                return plan(options, out);
            }
            case "score" -> {
                EvalOptions options = new EvalOptions();
                cli.allowAbbrev(List.of());
                options.scores = cli.positionalPath("scores");
                cli.finish();
                return score(options, out);
            }
            case "run" -> {
                EvalOptions options = new EvalOptions();
                cli.allowAbbrev(List.of("--cases", "--runner-config", "--runner", "--condition",
                        "--condition-skill", "--case", "--trials", "--retries", "--budget-usd",
                        "--allow-unmetered", "--output"));
                options.cases = cli.optionalPath("--cases", defaultCases);
                options.runnerConfig = cli.optionalPath(
                        "--runner-config", root.resolve("evals").resolve("runners.example.json"));
                options.runner = cli.requiredString("--runner");
                options.condition = cli.requiredChoice("--condition", CONDITION_CHOICES);
                options.conditionSkill = cli.optionalPath("--condition-skill", null);
                options.caseFilter = cli.appendString("--case");
                options.trials = cli.optionalInt("--trials", 3);
                options.retries = cli.optionalInt("--retries", 2);
                options.budgetUsd = cli.optionalDouble("--budget-usd", 25.0);
                options.allowUnmetered = cli.flag("--allow-unmetered");
                options.output = cli.requiredPath("--output");
                cli.finish();
                return EvalRunner.runEvaluations(options, root, out, err);
            }
            default -> throw cli.usageError("unknown command");
        }
    }

    static int validate(EvalOptions options, PrintStream out, PrintStream err) {
        List<String> errors = CaseValidator.validateCases(JsonLines.readJsonl(options.cases));
        if (!errors.isEmpty()) {
            for (String message : errors) {
                err.println("ERROR: " + message);
            }
            return 1;
        }
        out.println("Evaluation cases are valid.");
        return 0;
    }

    static int plan(EvalOptions options, PrintStream out) {
        List<JsonNode> cases = JsonLines.readJsonl(options.cases);
        List<String> errors = CaseValidator.validateCases(cases);
        if (!errors.isEmpty()) {
            throw new PyValueError(String.join("\n", errors));
        }

        List<String> conditions = new ArrayList<>(List.of("baseline", "candidate"));
        if (options.includeComparator) {
            conditions.add("comparator");
        }

        for (int trial = 1; trial <= options.trials; trial++) {
            for (JsonNode item : cases) {
                for (String condition : conditions) {
                    ObjectNode row = PythonJson.MAPPER.createObjectNode();
                    row.put("case_id", item.get("id").textValue());
                    row.put("trial", trial);
                    row.put("condition", condition);
                    out.println(PythonJson.dumps(row));
                }
            }
        }
        return 0;
    }

    static int score(EvalOptions options, PrintStream out) {
        JsonNode summary = ScoreSummarizer.summarizeScores(JsonLines.readJsonl(options.scores));
        out.println(PythonJson.dumpsIndented(summary, 2));
        return 0;
    }
}
