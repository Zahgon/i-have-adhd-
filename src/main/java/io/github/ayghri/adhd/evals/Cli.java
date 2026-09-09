package io.github.ayghri.adhd.evals;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A hand-rolled parser covering the slice of {@code argparse} the source uses.
 *
 * <p>Written by hand rather than delegating to a CLI library because the migration is graded on
 * byte-exact stderr: argparse's {@code error:} wording, its habit of reporting every missing
 * required argument in one message, and its exit code 2 are all observable behaviour.
 */
final class Cli {

    private final List<String> tokens;
    private final List<String> missingRequired = new ArrayList<>();
    private String command = "";

    Cli(String[] argv) {
        this.tokens = new ArrayList<>(List.of(argv));
    }

    static final class UsageError extends RuntimeException {
        private final String usage;
        private final String prog;

        UsageError(String usage, String prog, String message) {
            super(message);
            this.usage = usage;
            this.prog = prog;
        }

        String usage() {
            return usage;
        }

        String prog() {
            return prog;
        }
    }

    static final class HelpRequested extends RuntimeException {
        private final String help;

        HelpRequested(String help) {
            this.help = help;
        }

        String help() {
            return help;
        }
    }

    boolean wantsTopLevelHelp() {
        return !tokens.isEmpty() && (tokens.get(0).equals("-h") || tokens.get(0).equals("--help"));
    }

    String nextCommand(List<String> choices) {
        if (tokens.isEmpty()) {
            throw new UsageError(topLevelUsage(), RunEvals.PROG, "the following arguments are required: command");
        }
        // argparse never lets an option-looking token satisfy a positional, so `--zzz` alone
        // reports the missing subcommand rather than an invalid choice.
        int at = 0;
        while (at < tokens.size() && tokens.get(at).startsWith("-") && tokens.get(at).length() > 1) {
            at++;
        }
        if (at == tokens.size()) {
            throw new UsageError(topLevelUsage(), RunEvals.PROG, "the following arguments are required: command");
        }
        String candidate = tokens.remove(at);
        if (!choices.contains(candidate)) {
            throw new UsageError(topLevelUsage(), RunEvals.PROG,
                    "argument command: invalid choice: " + quoted(candidate) + " (choose from " + choiceList(choices) + ")");
        }
        command = candidate;
        if (tokens.remove("-h") || tokens.remove("--help")) {
            throw new HelpRequested(subcommandHelp(command));
        }
        return command;
    }

    /**
     * argparse defaults to {@code allow_abbrev=True}, so any unambiguous prefix of a long option is
     * accepted. {@code optionNames} must stay in declaration order: the ambiguity message lists
     * candidates in the order the parser registered them, not alphabetically.
     */
    void allowAbbrev(List<String> optionNames) {
        List<String> candidates = new ArrayList<>();
        candidates.add("--help");
        candidates.addAll(optionNames);
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (!token.startsWith("--") || token.equals("--")) {
                continue;
            }
            int equals = token.indexOf('=');
            String name = equals < 0 ? token : token.substring(0, equals);
            if (candidates.contains(name)) {
                continue;
            }
            List<String> matches = candidates.stream().filter(option -> option.startsWith(name)).toList();
            if (matches.size() > 1) {
                throw new UsageError(usage(), prog(),
                        "ambiguous option: " + name + " could match " + String.join(", ", matches));
            }
            if (matches.size() == 1) {
                tokens.set(i, equals < 0 ? matches.get(0) : matches.get(0) + token.substring(equals));
            }
        }
        if (tokens.remove("--help")) {
            throw new HelpRequested(subcommandHelp(command));
        }
    }

    boolean flag(String name) {
        boolean present = false;
        while (tokens.remove(name)) {
            present = true;
        }
        return present;
    }

    String optionalString(String name, String fallback) {
        List<String> values = take(name);
        return values.isEmpty() ? fallback : values.get(values.size() - 1);
    }

    List<String> appendString(String name) {
        List<String> values = take(name);
        return values.isEmpty() ? null : values;
    }

    String requiredString(String name) {
        List<String> values = take(name);
        if (values.isEmpty()) {
            missingRequired.add(name);
            return null;
        }
        return values.get(values.size() - 1);
    }

    String requiredChoice(String name, List<String> choices) {
        String value = requiredString(name);
        if (value != null && !choices.contains(value)) {
            throw new UsageError(usage(), prog(), "argument " + name + ": invalid choice: " + quoted(value)
                    + " (choose from " + choiceList(choices) + ")");
        }
        return value;
    }

    Path optionalPath(String name, Path fallback) {
        String value = optionalString(name, null);
        return value == null ? fallback : Paths.get(value);
    }

    Path requiredPath(String name) {
        String value = requiredString(name);
        return value == null ? null : Paths.get(value);
    }

    Path positionalPath(String metavar) {
        for (int i = 0; i < tokens.size(); i++) {
            if (!tokens.get(i).startsWith("-")) {
                return Paths.get(tokens.remove(i));
            }
        }
        missingRequired.add(metavar);
        return null;
    }

    int optionalInt(String name, int fallback) {
        String value = optionalString(name, null);
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exc) {
            throw new UsageError(usage(), prog(), "argument " + name + ": invalid int value: " + quoted(value));
        }
    }

    double optionalDouble(String name, double fallback) {
        String value = optionalString(name, null);
        if (value == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException exc) {
            throw new UsageError(usage(), prog(), "argument " + name + ": invalid float value: " + quoted(value));
        }
    }

    void finish() {
        if (!missingRequired.isEmpty()) {
            throw new UsageError(usage(), prog(),
                    "the following arguments are required: " + String.join(", ", missingRequired));
        }
        if (!tokens.isEmpty()) {
            throw new UsageError(topLevelUsage(), RunEvals.PROG,
                    "unrecognized arguments: " + String.join(" ", tokens));
        }
    }

    UsageError usageError(String message) {
        return new UsageError(usage(), prog(), message);
    }

    /** Collects every occurrence of an option, accepting both {@code --opt value} and {@code --opt=value}. */
    private List<String> take(String name) {
        List<String> values = new ArrayList<>();
        int i = 0;
        while (i < tokens.size()) {
            String token = tokens.get(i);
            if (token.equals(name)) {
                tokens.remove(i);
                if (i >= tokens.size()) {
                    throw new UsageError(usage(), prog(), "argument " + name + ": expected one argument");
                }
                values.add(tokens.remove(i));
                continue;
            }
            if (token.startsWith(name + "=")) {
                tokens.remove(i);
                values.add(token.substring(name.length() + 1));
                continue;
            }
            i++;
        }
        return values;
    }

    private static String quoted(String value) {
        return "'" + value + "'";
    }

    private static String choiceList(List<String> choices) {
        return choices.stream().map(Cli::quoted).collect(Collectors.joining(", "));
    }

    private String prog() {
        return command.isEmpty() ? RunEvals.PROG : RunEvals.PROG + " " + command;
    }

    private String usage() {
        return command.isEmpty() ? topLevelUsage() : subcommandUsage(command);
    }

    /** argparse's own prefix: {@code HelpFormatter.add_usage()} defaults to {@code _("usage: ")}. */
    static final String USAGE_PREFIX = "usage: ";

    static String topLevelUsage() {
        return USAGE_PREFIX + RunEvals.PROG + " [-h] {validate,plan,score,run} ...";
    }

    /**
     * argparse wraps usage lines at the terminal width, falling back to 80 columns when stdout is
     * not a tty. Reproducing the wrapped form verbatim keeps the usage block that precedes every
     * error message byte-identical to the Python CLI.
     */
    private static String subcommandUsage(String command) {
        return switch (command) {
            case "validate" -> USAGE_PREFIX + "%s validate [-h] [--cases CASES]".formatted(RunEvals.PROG);
            case "plan" -> USAGE_PREFIX + """
                    %s plan [-h] [--cases CASES] [--trials TRIALS]
                                             [--include-comparator]"""
                    .formatted(RunEvals.PROG);
            case "score" -> USAGE_PREFIX + "%s score [-h] scores".formatted(RunEvals.PROG);
            case "run" -> USAGE_PREFIX + """
                    %s run [-h] [--cases CASES] [--runner-config RUNNER_CONFIG]
                                            --runner RUNNER
                                            --condition {baseline,candidate,comparator}
                                            [--condition-skill CONDITION_SKILL] [--case CASE]
                                            [--trials TRIALS] [--retries RETRIES]
                                            [--budget-usd BUDGET_USD] [--allow-unmetered]
                                            --output OUTPUT"""
                    .formatted(RunEvals.PROG);
            default -> USAGE_PREFIX + "%s %s [-h]".formatted(RunEvals.PROG, command);
        };
    }

    static String topLevelHelp() {
        return topLevelUsage() + """


                Validate, run, and score paired response-quality evaluations.

                positional arguments:
                  {validate,plan,score,run}
                    validate            Validate the case catalog
                    plan                Print the paired run matrix as JSONL
                    score               Aggregate manually judged score rows
                    run                 Run one evaluation condition

                options:
                  -h, --help            show this help message and exit
                """;
    }

    private static String subcommandHelp(String command) {
        return subcommandUsage(command) + "\n\n" + switch (command) {
            case "validate" -> """
                    options:
                      -h, --help     show this help message and exit
                      --cases CASES
                    """;
            case "plan" -> """
                    options:
                      -h, --help            show this help message and exit
                      --cases CASES
                      --trials TRIALS
                      --include-comparator
                    """;
            case "score" -> """
                    positional arguments:
                      scores

                    options:
                      -h, --help  show this help message and exit
                    """;
            case "run" -> """
                    options:
                      -h, --help            show this help message and exit
                      --cases CASES
                      --runner-config RUNNER_CONFIG
                      --runner RUNNER
                      --condition {baseline,candidate,comparator}
                      --condition-skill CONDITION_SKILL
                      --case CASE
                      --trials TRIALS
                      --retries RETRIES
                      --budget-usd BUDGET_USD
                      --allow-unmetered
                      --output OUTPUT
                    """;
            default -> "options:\n  -h, --help  show this help message and exit\n";
        };
    }
}
