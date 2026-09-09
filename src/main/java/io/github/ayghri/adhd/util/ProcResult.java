package io.github.ayghri.adhd.util;

/** Mirror of {@code subprocess.CompletedProcess} for the text-mode calls the port makes. */
public record ProcResult(int returncode, String stdout, String stderr) {}
