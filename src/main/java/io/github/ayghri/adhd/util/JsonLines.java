package io.github.ayghri.adhd.util;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Port of {@code read_jsonl}. */
public final class JsonLines {

    private JsonLines() {}

    /**
     * Reads a JSONL file into one node per non-blank line.
     *
     * <p>Line numbers are 1-based and count blank lines, matching {@code enumerate(..., start=1)}
     * over the full {@code splitlines()} result.
     */
    public static List<JsonNode> readJsonl(Path path) {
        List<JsonNode> rows = new ArrayList<>();
        int number = 0;
        for (String line : splitLines(readText(path))) {
            number++;
            if (line.isBlank()) {
                continue;
            }
            JsonNode row;
            try {
                row = loads(line);
            } catch (PyJsonDecodeError exc) {
                throw new PyValueError(path + ": line " + number + ": " + exc.msg(), exc);
            }
            if (row == null || !row.isObject()) {
                throw new PyValueError(path + ": line " + number + ": expected a JSON object");
            }
            rows.add(row);
        }
        return rows;
    }

    /**
     * Port of {@code json.loads}.
     *
     * <p>Trailing content is detected here rather than delegated to Jackson's
     * {@code FAIL_ON_TRAILING_TOKENS}: Jackson tokenizes the trailing bytes first and reports
     * whatever lexical complaint it hits ("Unrecognized token"), while Python reports the generic
     * {@code Extra data} anchored at the first non-space character after the value.
     */
    public static JsonNode loads(String text) {
        if (text.startsWith("\uFEFF")) {
            throw new PyJsonDecodeError("Unexpected UTF-8 BOM (decode using utf-8-sig)", text, 0);
        }
        JsonNode node;
        long consumed;
        try (JsonParser parser = PythonJson.MAPPER.createParser(text)) {
            node = PythonJson.MAPPER.readTree(parser);
            if (node == null) {
                throw new PyJsonDecodeError("Expecting value", text, text.length());
            }
            consumed = parser.currentLocation().getCharOffset();
        } catch (JsonProcessingException exc) {
            throw new PyJsonDecodeError(decodeErrorMessage(exc), text, offsetOf(exc, text));
        } catch (IOException exc) {
            throw new PyJsonDecodeError("Expecting value", text, 0);
        }
        int extra = (int) consumed;
        while (extra < text.length() && Character.isWhitespace(text.charAt(extra))) {
            extra++;
        }
        if (extra < text.length()) {
            throw new PyJsonDecodeError("Extra data", text, extra);
        }
        return node;
    }

    private static int offsetOf(JsonProcessingException exc, String text) {
        if (exc.getLocation() == null) {
            return 0;
        }
        long offset = exc.getLocation().getCharOffset();
        if (offset < 0) {
            return 0;
        }
        return (int) Math.min(offset, text.length());
    }

    public static String readText(Path path) {
        try {
            return universalNewlines(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException exc) {
            throw PyOsError.from(exc, path);
        }
    }

    /**
     * Python opens text files with {@code newline=None}, so {@code read_text} rewrites CRLF and lone
     * CR to LF before any caller sees them. {@link Files#readString} does not, which would otherwise
     * leak a stray CR into the prompt built from a CRLF skill file.
     */
    private static String universalNewlines(String text) {
        if (text.indexOf('\r') < 0) {
            return text;
        }
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    /**
     * Python's {@code str.splitlines} breaks on many more boundaries than {@code String.split("\n")},
     * and critically it returns an empty list for an empty string rather than one empty element.
     */
    public static List<String> splitLines(String text) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            boolean crlf = c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n';
            if (c == '\n' || c == '\r' || c == '\u000b' || c == '\f' || c == '\u001c' || c == '\u001d'
                    || c == '\u001e' || c == '\u0085' || c == '\u2028' || c == '\u2029') {
                lines.add(text.substring(start, i));
                i += crlf ? 2 : 1;
                start = i;
                continue;
            }
            i++;
        }
        if (start < text.length()) {
            lines.add(text.substring(start));
        }
        return lines;
    }

    /**
     * Best-effort translation of Jackson parse failures into the {@code msg} text that Python's
     * {@code JSONDecodeError} would carry, since the source interpolates {@code exc.msg} verbatim.
     * The mapped cases cover what malformed JSONL realistically produces; anything unrecognised falls
     * back to Python's own catch-all, {@code Expecting value}.
     */
    private static String decodeErrorMessage(JsonProcessingException exc) {
        String raw = exc.getOriginalMessage() == null ? "" : exc.getOriginalMessage().toLowerCase(Locale.ROOT);
        if (raw.contains("trailing token")) {
            return "Extra data";
        }
        if (raw.contains("was expecting a colon")) {
            return "Expecting ':' delimiter";
        }
        if (raw.contains("was expecting comma")) {
            return "Expecting ',' delimiter";
        }
        if (raw.contains("was expecting double-quote to start field name")) {
            return "Expecting property name enclosed in double quotes";
        }
        if (raw.contains("closing quote") || raw.contains("in vALUE_STRING".toLowerCase(Locale.ROOT))) {
            return "Unterminated string starting at";
        }
        return "Expecting value";
    }
}
