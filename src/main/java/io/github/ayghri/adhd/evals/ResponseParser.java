package io.github.ayghri.adhd.evals;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.ayghri.adhd.util.JsonLines;
import io.github.ayghri.adhd.util.PythonJson;
import io.github.ayghri.adhd.util.PyValueError;
import io.github.ayghri.adhd.util.PythonValue;

/** Port of {@code _parse_response}. */
public final class ResponseParser {

    /**
     * {@code cost} stays a node rather than a {@code Double} so the written row can distinguish a
     * missing cost from an explicit {@code null} exactly as the source does.
     */
    public record ParsedResponse(String text, JsonNode usage, JsonNode cost) {

        public boolean hasCost() {
            return cost != null && !cost.isNull();
        }

        /** Python's {@code float(cost or 0)}. */
        public double costOrZero() {
            return PythonValue.isTruthy(cost) ? cost.doubleValue() : 0.0;
        }
    }

    private ResponseParser() {}

    public static ParsedResponse parseResponse(String output, String responseFormat) {
        switch (responseFormat) {
            case "text" -> {
                return new ParsedResponse(output.strip(), PythonJson.MAPPER.createObjectNode(), null);
            }
            case "claude-json" -> {
                JsonNode payload = JsonLines.loads(output);
                JsonNode usage = payload.get("usage");
                return new ParsedResponse(
                        PythonValue.str(payload.get("result")).strip(),
                        PythonValue.isTruthy(usage) ? usage : PythonJson.MAPPER.createObjectNode(),
                        payload.get("total_cost_usd"));
            }
            case "codex-jsonl" -> {
                JsonNode text = null;
                JsonNode usage = PythonJson.MAPPER.createObjectNode();
                for (String line : JsonLines.splitLines(output)) {
                    if (line.isBlank()) {
                        continue;
                    }
                    JsonNode event = JsonLines.loads(line);
                    JsonNode item = event.has("item") ? event.get("item") : PythonJson.MAPPER.createObjectNode();
                    if (isType(event, "item.completed") && isType(item, "agent_message") && item.has("text")) {
                        text = item.get("text");
                    }
                    if (isType(event, "turn.completed") && event.has("usage")) {
                        usage = event.get("usage");
                    }
                }
                return new ParsedResponse(
                        (text == null ? "" : PythonValue.str(text)).strip(), usage, null);
            }
            default -> throw new PyValueError("Unsupported response format: " + responseFormat);
        }
    }

    private static boolean isType(JsonNode node, String expected) {
        JsonNode type = node.get("type");
        return type != null && type.isTextual() && type.textValue().equals(expected);
    }
}
