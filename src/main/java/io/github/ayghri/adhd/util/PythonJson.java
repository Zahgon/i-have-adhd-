package io.github.ayghri.adhd.util;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.Map;

/**
 * A {@code json.dumps} work-alike.
 *
 * <p>Jackson's own writers cannot be configured into byte-parity with CPython: the default writer
 * omits the space after {@code :} and {@code ,}, and {@code DefaultPrettyPrinter} indents arrays
 * differently and puts a space inside empty containers. Since the migration is graded on byte-exact
 * stdout, the encoder is reimplemented here against CPython's {@code json.encoder} rules.
 *
 * <p>Separator rules, straight from CPython: with no indent the separators are {@code (", ", ": ")};
 * with an indent they become {@code (",", ": ")} and each item goes on its own line. Empty
 * containers always collapse to {@code {}} / {@code []}.
 */
public final class PythonJson {

    /**
     * Accepts exactly what {@code json.loads} accepts. Both flags are required for parity: Jackson
     * otherwise ignores trailing content that Python rejects as {@code Extra data}, and rejects the
     * bare {@code NaN}/{@code Infinity} tokens that Python accepts.
     */
    public static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS)
            .build();

    private PythonJson() {}

    /** {@code json.dumps(obj)} — compact, ensure_ascii=True. */
    public static String dumps(JsonNode node) {
        return dumps(node, true);
    }

    /** {@code json.dumps(obj, ensure_ascii=<ensureAscii>)} — compact. */
    public static String dumps(JsonNode node, boolean ensureAscii) {
        StringBuilder sb = new StringBuilder();
        write(sb, node, ensureAscii, -1, 0);
        return sb.toString();
    }

    /** {@code json.dumps(obj, indent=<indent>)} — ensure_ascii=True. */
    public static String dumpsIndented(JsonNode node, int indent) {
        StringBuilder sb = new StringBuilder();
        write(sb, node, true, indent, 0);
        return sb.toString();
    }

    private static void write(StringBuilder sb, JsonNode node, boolean ensureAscii, int indent, int depth) {
        if (node == null || node.isNull()) {
            sb.append("null");
            return;
        }
        if (node.isBoolean()) {
            sb.append(node.booleanValue() ? "true" : "false");
            return;
        }
        if (node.isTextual()) {
            escape(sb, node.textValue(), ensureAscii);
            return;
        }
        if (node.isNumber()) {
            writeNumber(sb, node);
            return;
        }
        if (node.isArray()) {
            writeArray(sb, (ArrayNode) node, ensureAscii, indent, depth);
            return;
        }
        if (node.isObject()) {
            writeObject(sb, (ObjectNode) node, ensureAscii, indent, depth);
            return;
        }
        throw new IllegalArgumentException("Object of type " + node.getNodeType() + " is not JSON serializable");
    }

    private static void writeNumber(StringBuilder sb, JsonNode node) {
        // Python keeps ints and floats distinct all the way to the output, so an int stays "1" and a
        // float stays "1.0". Jackson preserves the same distinction when parsing, so dispatch on it.
        if (node.isIntegralNumber()) {
            sb.append(node.bigIntegerValue().toString());
        } else {
            sb.append(PythonFloat.jsonRepr(node.doubleValue()));
        }
    }

    private static void writeArray(StringBuilder sb, ArrayNode array, boolean ensureAscii, int indent, int depth) {
        if (array.isEmpty()) {
            sb.append("[]");
            return;
        }
        boolean pretty = indent >= 0;
        sb.append('[');
        String childPad = pretty ? "\n" + " ".repeat(indent * (depth + 1)) : "";
        boolean first = true;
        for (JsonNode child : array) {
            if (!first) {
                sb.append(pretty ? "," : ", ");
            }
            first = false;
            sb.append(childPad);
            write(sb, child, ensureAscii, indent, depth + 1);
        }
        if (pretty) {
            sb.append('\n').append(" ".repeat(indent * depth));
        }
        sb.append(']');
    }

    private static void writeObject(StringBuilder sb, ObjectNode object, boolean ensureAscii, int indent, int depth) {
        if (object.isEmpty()) {
            sb.append("{}");
            return;
        }
        boolean pretty = indent >= 0;
        sb.append('{');
        String childPad = pretty ? "\n" + " ".repeat(indent * (depth + 1)) : "";
        boolean first = true;
        // Jackson's ObjectNode preserves insertion order, which is what Python dicts do.
        Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            if (!first) {
                sb.append(pretty ? "," : ", ");
            }
            first = false;
            sb.append(childPad);
            escape(sb, entry.getKey(), ensureAscii);
            sb.append(": ");
            write(sb, entry.getValue(), ensureAscii, indent, depth + 1);
        }
        if (pretty) {
            sb.append('\n').append(" ".repeat(indent * depth));
        }
        sb.append('}');
    }

    /**
     * CPython's {@code encode_basestring} / {@code encode_basestring_ascii}.
     *
     * <p>Both always escape the quote, the backslash and everything below {@code 0x20}. The ascii
     * variant additionally escapes everything outside the printable range {@code 0x20..0x7e}, so
     * {@code DEL} is escaped too. Note that {@code /} is never escaped.
     */
    static void escape(StringBuilder sb, String value, boolean ensureAscii) {
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20 || (ensureAscii && c > 0x7e)) {
                        // Escaping per UTF-16 unit reproduces Python's surrogate-pair output for
                        // astral code points without any extra branching.
                        sb.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
