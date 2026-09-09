package io.github.ayghri.adhd.util;

import com.fasterxml.jackson.databind.JsonNode;

/** Python value semantics that have no JsonNode counterpart. */
public final class PythonValue {

    private PythonValue() {}

    /**
     * Python's truthiness, as used by the {@code payload.get("usage", {}) or {}} idiom: empty
     * containers, empty strings, zero and {@code None} are all falsy, unlike Java where only a
     * missing reference is.
     */
    public static boolean isTruthy(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return false;
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isNumber()) {
            return node.doubleValue() != 0.0;
        }
        if (node.isTextual()) {
            return !node.textValue().isEmpty();
        }
        if (node.isContainerNode()) {
            return !node.isEmpty();
        }
        return true;
    }

    /**
     * Python's {@code str()}. The surprising cases are the ones that matter here: {@code str(None)}
     * is {@code "None"} and {@code str(True)} is {@code "True"}, so a runner returning a null or
     * boolean {@code result} field produces those literal words rather than an empty string.
     */
    public static String str(JsonNode node) {
        if (node == null || node.isMissingNode()) {
            return "";
        }
        if (node.isNull()) {
            return "None";
        }
        if (node.isTextual()) {
            return node.textValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue() ? "True" : "False";
        }
        if (node.isIntegralNumber()) {
            return node.bigIntegerValue().toString();
        }
        if (node.isNumber()) {
            return PythonFloat.repr(node.doubleValue());
        }
        return PythonJson.dumps(node, false);
    }

    /**
     * Python's {@code isinstance(value, int)}, which is true for booleans because {@code bool}
     * subclasses {@code int}. Jackson keeps the two node types apart, so the check is spelled out
     * wherever the source relied on the Python behaviour.
     */
    public static boolean isPythonInt(JsonNode node) {
        return node != null && (node.isIntegralNumber() || node.isBoolean());
    }

    /** Python's {@code isinstance(value, (int, float))} — again including booleans. */
    public static boolean isPythonNumber(JsonNode node) {
        return node != null && (node.isNumber() || node.isBoolean());
    }

    /** The numeric value Python would see, mapping {@code True}/{@code False} to 1 and 0. */
    public static double numericValue(JsonNode node) {
        if (node.isBoolean()) {
            return node.booleanValue() ? 1.0 : 0.0;
        }
        return node.doubleValue();
    }
}
