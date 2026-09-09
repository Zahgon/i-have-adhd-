package io.github.ayghri.adhd.util;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Port of Python's {@code shlex.join} / {@code shlex.quote}. */
public final class ShlexJoin {

    /**
     * Python's {@code shlex._find_unsafe} is compiled with {@code re.ASCII}, so {@code \w} is exactly
     * {@code [A-Za-z0-9_]}. Java's {@code \w} is ASCII-only by default too (it would only widen under
     * UNICODE_CHARACTER_CLASS), so the two agree without further flags.
     */
    private static final Pattern UNSAFE = Pattern.compile("[^\\w@%+=:,./-]");

    private ShlexJoin() {}

    public static String join(List<String> parts) {
        return parts.stream().map(ShlexJoin::quote).collect(Collectors.joining(" "));
    }

    public static String quote(String value) {
        if (value.isEmpty()) {
            return "''";
        }
        if (!UNSAFE.matcher(value).find()) {
            return value;
        }
        // Wrap in single quotes and splice any embedded single quote back in as '"'"'.
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
}
