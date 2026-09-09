package io.github.ayghri.adhd.util;

/**
 * Port of {@code json.decoder.JSONDecodeError}.
 *
 * <p>It extends {@link PyValueError} because in Python {@code JSONDecodeError} subclasses
 * {@code ValueError}; code that catches {@code ValueError} — including the runner-failure
 * diagnostic path — must keep catching this.
 *
 * <p>Two spellings of the message are observable and they are not the same. {@code read_jsonl}
 * interpolates only {@code exc.msg} ("Expecting value"), whereas an uncaught decode error prints
 * {@code str(exc)} ("Expecting value: line 1 column 1 (char 0)"). {@link #msg()} and
 * {@link #getMessage()} keep the two apart.
 */
public final class PyJsonDecodeError extends PyValueError {

    private final String msg;
    private final int pos;
    private final int lineno;
    private final int colno;

    public PyJsonDecodeError(String msg, String doc, int pos) {
        super(format(msg, doc, pos));
        this.msg = msg;
        this.pos = pos;
        this.lineno = lineOf(doc, pos);
        this.colno = columnOf(doc, pos);
    }

    private static int lineOf(String doc, int pos) {
        int line = 1;
        for (int i = 0; i < pos && i < doc.length(); i++) {
            if (doc.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static int columnOf(String doc, int pos) {
        int start = doc.lastIndexOf('\n', Math.min(pos, doc.length()) - 1);
        return pos - start;
    }

    private static String format(String msg, String doc, int pos) {
        return msg + ": line " + lineOf(doc, pos) + " column " + columnOf(doc, pos) + " (char " + pos + ")";
    }

    /** The bare reason, without the position suffix. */
    public String msg() {
        return msg;
    }

    public int pos() {
        return pos;
    }

    public int lineno() {
        return lineno;
    }

    public int colno() {
        return colno;
    }

    @Override
    public String toString() {
        return "json.decoder.JSONDecodeError: " + getMessage();
    }
}
