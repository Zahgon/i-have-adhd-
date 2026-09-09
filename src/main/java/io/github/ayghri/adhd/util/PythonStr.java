package io.github.ayghri.adhd.util;

import java.util.Comparator;

/** String helpers whose Java defaults differ from Python's. */
public final class PythonStr {

    private PythonStr() {}

    /**
     * Python's {@code repr()} of a string, used wherever the source formats with {@code !r}.
     *
     * <p>Python prefers single quotes and only switches to double quotes when the value contains a
     * single quote but no double quote. Printable non-ASCII characters are left raw (Python 3
     * behaviour); control characters become {@code \\xNN}.
     */
    public static String repr(String value) {
        char quote = value.indexOf('\'') >= 0 && value.indexOf('"') < 0 ? '"' : '\'';
        StringBuilder sb = new StringBuilder();
        sb.append(quote);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == quote || c == '\\') {
                sb.append('\\').append(c);
            } else if (c == '\n') {
                sb.append("\\n");
            } else if (c == '\r') {
                sb.append("\\r");
            } else if (c == '\t') {
                sb.append("\\t");
            } else if (c < 0x20 || c == 0x7f) {
                sb.append(String.format(java.util.Locale.ROOT, "\\x%02x", (int) c));
            } else {
                sb.append(c);
            }
        }
        sb.append(quote);
        return sb.toString();
    }

    /**
     * Python's {@code sorted()} orders strings by Unicode code point. Java's natural {@code String}
     * ordering compares UTF-16 units, which puts unpaired-surrogate-range characters before
     * supplementary ones and so disagrees with Python above {@code U+FFFF}. Every place the source
     * called {@code sorted()} on strings uses this comparator instead.
     */
    public static final Comparator<String> CODE_POINT_ORDER = (a, b) -> {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            int ca = a.codePointAt(i);
            int cb = b.codePointAt(j);
            if (ca != cb) {
                return Integer.compare(ca, cb);
            }
            i += Character.charCount(ca);
            j += Character.charCount(cb);
        }
        return Integer.compare(a.length() - i, b.length() - j);
    };
}
