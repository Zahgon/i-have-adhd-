package io.github.ayghri.adhd.evals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.ayghri.adhd.util.JsonLines;
import io.github.ayghri.adhd.util.ProcResult;
import io.github.ayghri.adhd.util.ProcRunner;
import io.github.ayghri.adhd.util.PyKeyError;
import io.github.ayghri.adhd.util.PyOsError;
import io.github.ayghri.adhd.util.PyRuntimeError;
import io.github.ayghri.adhd.util.PyValueError;
import io.github.ayghri.adhd.util.PythonFloat;
import io.github.ayghri.adhd.util.PythonJson;
import io.github.ayghri.adhd.util.PythonStr;
import io.github.ayghri.adhd.util.ShlexJoin;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Port of {@code _condition_prompt} and {@code run_evaluations}. */
public final class EvalRunner {

    private EvalRunner() {}

    public static String conditionPrompt(String task, String condition, Path skillPath) {
        if ("baseline".equals(condition)) {
            return task;
        }
        if (skillPath == null) {
            throw new PyValueError("--condition-skill is required for the " + condition + " condition");
        }
        String instructions = JsonLines.readText(skillPath);
        return "Follow the response-style skill below while completing the task. "
                + "Do not discuss or quote the skill.\n\n"
                + "<response_style>\n" + instructions + "\n</response_style>\n\n"
                + "<task>\n" + task + "\n</task>";
    }

    public static int runEvaluations(EvalOptions args, Path root, PrintStream out, PrintStream err) {
        List<JsonNode> cases = JsonLines.readJsonl(args.cases);
        List<String> errors = CaseValidator.validateCases(cases);
        if (!errors.isEmpty()) {
            throw new PyValueError(String.join("\n", errors));
        }

        if (args.caseFilter != null && !args.caseFilter.isEmpty()) {
            Set<String> known = new LinkedHashSet<>();
            for (JsonNode item : cases) {
                known.add(item.get("id").textValue());
            }
            Set<String> unknown = new TreeSet<>(PythonStr.CODE_POINT_ORDER);
            unknown.addAll(args.caseFilter);
            unknown.removeAll(known);
            if (!unknown.isEmpty()) {
                throw new PyValueError("--case matched no evaluation case: " + String.join(", ", unknown));
            }
        }

        JsonNode config = JsonLines.loads(JsonLines.readText(args.runnerConfig));
        JsonNode runner = config.get(args.runner);
        if (runner == null) {
            throw new PyKeyError(args.runner);
        }
        List<String> command = new ArrayList<>();
        for (JsonNode part : runner.get("command")) {
            command.add(part.asText());
        }
        JsonNode formatNode = runner.get("response_format");
        String responseFormat = formatNode == null ? "text" : formatNode.asText();

        // Ordering is load-bearing: this guard must reject before any file is created or any
        // process is spawned, which the ported unmetered test asserts on directly.
        if (!"claude-json".equals(responseFormat) && !args.allowUnmetered) {
            throw new PyRuntimeError("The " + PythonStr.repr(responseFormat)
                    + " response format never reports dollar cost; rerun with --allow-unmetered"
                    + " only when the provider has a separate hard spending cap.");
        }

        Set<CompletedKey> done = new LinkedHashSet<>();
        double reportedCost = 0.0;
        if (Files.exists(args.output)) {
            List<JsonNode> prior = JsonLines.readJsonl(args.output);
            done = CompletedKey.completedKeys(prior);
            for (JsonNode row : prior) {
                JsonNode condition = row.get("condition");
                JsonNode rowRunner = row.get("runner");
                boolean sameCondition = condition != null && condition.asText().equals(args.condition);
                boolean sameRunner = rowRunner != null && rowRunner.asText().equals(args.runner);
                if (sameCondition && sameRunner) {
                    JsonNode cost = row.get("cost_usd");
                    reportedCost += cost == null || cost.isNull() ? 0.0 : cost.doubleValue();
                }
            }
        }

        if (args.budgetUsd <= 0 || args.budgetUsd > 25) {
            throw new PyValueError("--budget-usd must be greater than 0 and no more than 25");
        }

        try {
            Path parent = args.output.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (BufferedWriter handle = Files.newBufferedWriter(
                    args.output, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {

                for (int trial = 1; trial <= args.trials; trial++) {
                    for (JsonNode item : cases) {
                        String caseId = item.get("id").textValue();
                        if (args.caseFilter != null && !args.caseFilter.isEmpty()
                                && !args.caseFilter.contains(caseId)) {
                            continue;
                        }

                        CompletedKey key = new CompletedKey(caseId, trial, args.condition, args.runner);
                        if (done.contains(key)) {
                            out.println("skip completed " + args.condition + " trial " + trial + ": " + caseId);
                            continue;
                        }

                        double remaining = args.budgetUsd - reportedCost;
                        if (remaining <= 0) {
                            err.println("Budget exhausted; stopping.");
                            return 2;
                        }

                        String prompt = conditionPrompt(
                                item.get("prompt").asText(), args.condition, args.conditionSkill);

                        List<String> invocation = new ArrayList<>(command);
                        JsonNode budgetFlag = runner.get("budget_flag");
                        if (budgetFlag != null && !budgetFlag.isNull() && !budgetFlag.asText().isEmpty()) {
                            invocation.add(budgetFlag.asText());
                            invocation.add(PythonFloat.formatFixed(remaining, 4));
                        }
                        invocation.add(prompt);

                        ProcResult completed = invokeWithRetries(invocation, root, args.retries);
                        if (completed.returncode() != 0) {
                            throw new PyRuntimeError("Runner failed after " + (args.retries + 1)
                                    + " attempts (" + ShlexJoin.join(invocation.subList(0, invocation.size() - 1))
                                    + "):\n" + failureDetail(completed, responseFormat));
                        }

                        ResponseParser.ParsedResponse parsed =
                                ResponseParser.parseResponse(completed.stdout(), responseFormat);
                        if (!parsed.hasCost() && !args.allowUnmetered) {
                            throw new PyRuntimeError("Runner did not report dollar cost; rerun with"
                                    + " --allow-unmetered only when the provider has a separate hard"
                                    + " spending cap.");
                        }
                        reportedCost += parsed.costOrZero();

                        ObjectNode row = PythonJson.MAPPER.createObjectNode();
                        row.put("case_id", caseId);
                        row.put("trial", trial);
                        row.put("condition", args.condition);
                        row.put("runner", args.runner);
                        row.put("response", parsed.text());
                        row.set("usage", parsed.usage());
                        row.set("cost_usd", parsed.cost());

                        handle.write(PythonJson.dumps(row, false));
                        handle.write("\n");
                        handle.flush();
                        out.println(args.condition + " trial " + trial + ": " + caseId);
                    }
                }
            }
        } catch (IOException exc) {
            throw PyOsError.from(exc, args.output);
        }

        out.println("Reported cost: $" + PythonFloat.formatFixed(reportedCost, 4));
        return 0;
    }

    private static ProcResult invokeWithRetries(List<String> invocation, Path root, int retries) {
        ProcResult completed = null;
        for (int attempt = 0; attempt <= retries; attempt++) {
            completed = ProcRunner.run(invocation, root);
            if (completed.returncode() == 0) {
                break;
            }
            if (attempt < retries) {
                sleepSeconds(Math.min(1L << attempt, 5L));
            }
        }
        if (completed == null) {
            throw new AssertionError("retry loop produced no result");
        }
        return completed;
    }

    private static String failureDetail(ProcResult completed, String responseFormat) {
        String detail = completed.stderr().strip();
        if (detail.isEmpty()) {
            detail = completed.stdout().strip();
        }
        if (!completed.stdout().strip().isEmpty()) {
            try {
                String parsedText = ResponseParser.parseResponse(completed.stdout(), responseFormat).text();
                if (!parsedText.isEmpty()) {
                    detail = parsedText;
                }
            } catch (PyValueError ignored) {
                // Matches Python's `except (ValueError, json.JSONDecodeError)`: an unparseable body
                // simply leaves the raw stderr/stdout detail in place.
            }
        }
        return detail;
    }

    private static void sleepSeconds(long seconds) {
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException exc) {
            Thread.currentThread().interrupt();
            throw new PyRuntimeError("Interrupted while backing off between runner attempts", exc);
        }
    }
}
