package io.github.ayghri.adhd.evals;

import java.nio.file.Path;
import java.util.List;

/**
 * Stand-in for the {@code argparse.Namespace} that {@code run_evaluations} receives.
 *
 * <p>Deliberately a mutable bag of public fields rather than a record: the ported tests flip
 * {@code allowUnmetered} on an existing instance and re-invoke, mirroring
 * {@code args.allow_unmetered = True}.
 */
public final class EvalOptions {

    public Path cases;
    public Path runnerConfig;
    public String runner;
    public String condition;
    public Path conditionSkill;

    /** Null when {@code --case} was never supplied, which is distinct from an empty list. */
    public List<String> caseFilter;

    public int trials = 3;
    public int retries = 2;
    public double budgetUsd = 25.0;
    public boolean allowUnmetered;
    public Path output;
    public boolean includeComparator;
    public Path scores;
}
