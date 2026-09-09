package io.github.ayghri.adhd.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers the CPython runtime shims that the migrated tooling leans on for its user-facing text.
 *
 * <p>The Python original never needed tests for these: {@code repr()}, {@code str(exc)} and
 * {@code json.dumps} were the interpreter's own behaviour. In Java every one of them is
 * hand-written, so the traceback spelling, the {@code [Errno N]} rendering and the JSON indentation
 * are all migration surface that can silently drift. These tests pin them down.
 */
final class PythonRuntimeCompatTest {

    /** {@code str(RuntimeError("boom"))} prints bare, but a traceback prints the qualified name. */
    @Test
    void exceptionsRenderPythonTracebackNames() {
        PyRuntimeError runtime = new PyRuntimeError("boom");
        assertEquals("RuntimeError: boom", runtime.toString());
        assertEquals("boom", runtime.getMessage());

        PyValueError value = new PyValueError("bad value");
        assertEquals("ValueError: bad value", value.toString());

        // KeyError is the odd one out: Python reprs the key, so a string key keeps its quotes.
        PyKeyError key = new PyKeyError("missing-runner");
        assertEquals("KeyError: 'missing-runner'", key.toString());
        assertEquals("'missing-runner'", key.getMessage());

        // The cause-carrying constructors must not disturb the rendering.
        PyValueError wrapped = new PyValueError("wrapped", runtime);
        assertEquals("ValueError: wrapped", wrapped.toString());
        assertSame(runtime, wrapped.getCause());
    }

    /** {@code OSError} subclasses carry an errno and the offending path, exactly as CPython does. */
    @Test
    void osErrorsCarryErrnoAndPath() {
        Path path = Path.of("/tmp/does-not-exist.jsonl");

        PyOsError missing = PyOsError.from(new NoSuchFileException(path.toString()), path);
        assertEquals(
                "FileNotFoundError: [Errno 2] No such file or directory: '/tmp/does-not-exist.jsonl'",
                missing.toString());

        PyOsError denied = PyOsError.from(new AccessDeniedException(path.toString()), path);
        assertEquals("PermissionError: [Errno 13] Permission denied: '/tmp/does-not-exist.jsonl'", denied.toString());

        // Anything else degrades to a plain OSError that reuses the Java detail message.
        PyOsError other = PyOsError.from(new IOException("Stale file handle"), path);
        assertEquals("OSError: Stale file handle", other.toString());

        // A detail-less IOException still has to produce something printable.
        PyOsError anonymous = PyOsError.from(new IOException(), path);
        assertEquals("OSError: IOException", anonymous.toString());
    }

    /** {@code assert cond} and {@code assert cond, msg} are two distinct AssertionError spellings. */
    @Test
    void assertHelperMatchesBareAndMessagedAsserts() {
        PyAssert.check(true);
        PyAssert.check(true, "never raised");

        PyAssert.PyAssertionError bare = assertThrows(PyAssert.PyAssertionError.class, () -> PyAssert.check(false));
        assertNull(bare.getMessage());
        assertEquals("AssertionError", bare.toString());

        PyAssert.PyAssertionError described =
                assertThrows(PyAssert.PyAssertionError.class, () -> PyAssert.check(false, "Rules were injected twice"));
        assertEquals("Rules were injected twice", described.getMessage());
        assertEquals("AssertionError: Rules were injected twice", described.toString());
    }

    /** {@code JSONDecodeError} exposes msg/pos/lineno/colno separately from {@code str(exc)}. */
    @Test
    void jsonDecodeErrorReportsLineAndColumn() {
        PyJsonDecodeError first = new PyJsonDecodeError("Expecting value", "{}", 0);
        assertEquals("Expecting value", first.msg());
        assertEquals(0, first.pos());
        assertEquals(1, first.lineno());
        assertEquals(1, first.colno());
        assertEquals("Expecting value: line 1 column 1 (char 0)", first.getMessage());
        assertEquals("json.decoder.JSONDecodeError: Expecting value: line 1 column 1 (char 0)", first.toString());

        // Position 8 sits on the second line, three characters in.
        String document = "{\n  \"a\":\n}";
        PyJsonDecodeError later = new PyJsonDecodeError("Expecting value", document, 8);
        assertEquals(2, later.lineno());
        assertEquals(7, later.colno());
        assertEquals("Expecting value: line 2 column 7 (char 8)", later.getMessage());

        // read_jsonl catches ValueError, so the subclassing is load-bearing, not decorative.
        assertTrue(PyValueError.class.isAssignableFrom(PyJsonDecodeError.class));
    }

    /** {@code json.dumps} spells out-of-range floats as bare JavaScript literals. */
    @Test
    void jsonReprUsesPythonSpellingForNonFiniteFloats() {
        assertEquals("NaN", PythonFloat.jsonRepr(Double.NaN));
        assertEquals("Infinity", PythonFloat.jsonRepr(Double.POSITIVE_INFINITY));
        assertEquals("-Infinity", PythonFloat.jsonRepr(Double.NEGATIVE_INFINITY));
        assertEquals("1.5", PythonFloat.jsonRepr(1.5));
        assertEquals("0.1", PythonFloat.jsonRepr(0.1));

        // repr() is the finite-only spelling and must not leak the JSON tokens.
        assertEquals("nan", PythonFloat.repr(Double.NaN));
        assertEquals("inf", PythonFloat.repr(Double.POSITIVE_INFINITY));
    }

    /** {@code json.dumps(obj, indent=2)} — the shape {@code run_evals.py score} prints. */
    @Test
    void dumpsIndentedMatchesPythonIndentation() {
        ObjectNode root = PythonJson.MAPPER.createObjectNode();
        root.put("weighted_score", 4.25);
        ArrayNode reasons = root.putArray("reasons");
        reasons.add("first");
        reasons.add("second");
        root.putObject("empty");
        root.putArray("none");

        String expected = "{\n"
                + "  \"weighted_score\": 4.25,\n"
                + "  \"reasons\": [\n"
                + "    \"first\",\n"
                + "    \"second\"\n"
                + "  ],\n"
                + "  \"empty\": {},\n"
                + "  \"none\": []\n"
                + "}";
        assertEquals(expected, PythonJson.dumpsIndented(root, 2));

        // indent=0 still breaks lines, it just does not pad them.
        assertEquals("{\n\"weighted_score\": 4.25,\n\"reasons\": [\n\"first\",\n\"second\"\n],\n\"empty\": {},\n\"none\": []\n}",
                PythonJson.dumpsIndented(root, 0));

        // The compact spelling stays on one line with Python's ", " separators.
        assertEquals(
                "{\"weighted_score\": 4.25, \"reasons\": [\"first\", \"second\"], \"empty\": {}, \"none\": []}",
                PythonJson.dumps(root));
    }

    /** {@code shlex.join} is what the runner-failure message echoes back to the operator. */
    @Test
    void shlexJoinQuotesOnlyWhatNeedsIt() {
        assertEquals("''", ShlexJoin.quote(""));
        assertEquals("claude", ShlexJoin.quote("claude"));
        assertEquals("--output-format=stream-json", ShlexJoin.quote("--output-format=stream-json"));
        assertEquals("/tmp/evals/out.jsonl", ShlexJoin.quote("/tmp/evals/out.jsonl"));
        assertEquals("'two words'", ShlexJoin.quote("two words"));
        assertEquals("'it'\"'\"'s'", ShlexJoin.quote("it's"));

        assertEquals("", ShlexJoin.join(List.of()));
        assertEquals(
                "claude -p --model 'sonnet 4' ''",
                ShlexJoin.join(List.of("claude", "-p", "--model", "sonnet 4", "")));
    }

    /** An explicit {@code -Dadhd.root} must be validated, not trusted. */
    @Test
    void repoRootRejectsADirectoryThatIsNotACheckout(@TempDir Path notACheckout) {
        // Resolve the genuine checkout first, while the override is still unset.
        Path real = RepoRoot.resolve();
        String previous = System.getProperty(RepoRoot.PROPERTY);
        try {
            System.setProperty(RepoRoot.PROPERTY, notACheckout.toString());
            PyRuntimeError error = assertThrows(PyRuntimeError.class, RepoRoot::resolve);
            assertEquals(
                    "The system property adhd.root points at "
                            + notACheckout.toAbsolutePath().normalize()
                            + ", which is not an i-have-adhd checkout.",
                    error.getMessage());

            System.setProperty(RepoRoot.PROPERTY, real + "/./");
            assertEquals(real, RepoRoot.resolve());
        } finally {
            if (previous == null) {
                System.clearProperty(RepoRoot.PROPERTY);
            } else {
                System.setProperty(RepoRoot.PROPERTY, previous);
            }
        }
    }

    /** {@code shutil.which} resolves against PATH and rejects non-executables. */
    @Test
    void whichResolvesAgainstPathAndExplicitPaths() {
        String shell = Which.which("sh");
        assertNotNull(shell, "a POSIX shell must exist on the test host");
        assertTrue(shell.endsWith("sh"), shell);

        // A name containing a separator is treated as a path, never searched on PATH.
        assertNull(Which.which("./definitely-not-on-disk"));
        assertNull(Which.which("adhd-command-that-does-not-exist"));
    }

    /** Python's truthiness and {@code str()} rules differ from Java's for every JSON node type. */
    @Test
    void pythonValueMirrorsInterpreterCoercions() {
        JsonNode parsed = JsonLines.loads("{\"t\": true, \"i\": 7, \"f\": 1.0, \"s\": \"x\", \"n\": null}");

        assertEquals("True", PythonValue.str(parsed.get("t")));
        assertEquals("7", PythonValue.str(parsed.get("i")));
        assertEquals("1.0", PythonValue.str(parsed.get("f")));
        assertEquals("x", PythonValue.str(parsed.get("s")));
        assertEquals("None", PythonValue.str(parsed.get("n")));

        // bool is a subclass of int in Python, so both predicates accept it.
        assertTrue(PythonValue.isPythonInt(parsed.get("t")));
        assertTrue(PythonValue.isPythonNumber(parsed.get("t")));
        assertEquals(1.0, PythonValue.numericValue(parsed.get("t")));
        assertTrue(PythonValue.isTruthy(parsed.get("i")));
        assertEquals(7.0, PythonValue.numericValue(parsed.get("i")));
    }

    /**
     * {@code subprocess.run(..., shell=True)} has no Java counterpart, so the shim hands the command
     * to {@code /bin/sh -c} itself. Metacharacter handling, the exit status, the stream split, stdin
     * and the environment replacement are therefore all hand-written migration surface.
     */
    @Test
    void runShellHandsTheCommandToThePlatformShell(@TempDir Path scratch) throws IOException {
        ProcResult piped = ProcRunner.runShell("printf 'ab\\n' | tr 'a-z' 'A-Z'", null, null, null);
        assertEquals(0, piped.returncode());
        assertEquals("AB\n", piped.stdout());
        assertEquals("", piped.stderr());

        ProcResult failed = ProcRunner.runShell("printf 'to stderr\\n' >&2; exit 3", null, null, null);
        assertEquals(3, failed.returncode());
        assertEquals("", failed.stdout());
        assertEquals("to stderr\n", failed.stderr());

        ProcResult newlines = ProcRunner.runShell("printf 'a\\r\\nb\\rc'", null, null, null);
        assertEquals("a\nb\nc", newlines.stdout());

        ProcResult echoed = ProcRunner.runShell("cat", null, null, "from stdin\n");
        assertEquals("from stdin\n", echoed.stdout());

        Files.writeString(scratch.resolve("marker.txt"), "found\n");
        assertEquals("found\n", ProcRunner.runShell("cat marker.txt", scratch, null, null).stdout());

        // Surefire injects ADHD_STUB_API_KEY, so an inherited variable going missing proves the
        // supplied environment replaced the parent's rather than extending it.
        assertNotNull(System.getenv("ADHD_STUB_API_KEY"));
        Map<String, String> env = Map.of("PATH", System.getenv("PATH"), "ADHD_SHELL_PROBE", "visible");
        ProcResult scoped = ProcRunner.runShell(
                "printf '%s/%s' \"$ADHD_SHELL_PROBE\" \"${ADHD_STUB_API_KEY:-unset}\"", null, env, null);
        assertEquals("visible/unset", scoped.stdout());
    }
}
