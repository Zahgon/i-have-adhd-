package io.github.ayghri.adhd.pi;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.ayghri.adhd.util.PyAssert;

import java.util.ArrayList;
import java.util.List;

/** Ports of the {@code status_texts}, {@code message_count} and {@code latest_enabled} helpers. */
public final class EventHelpers {

    private EventHelpers() {}

    /**
     * Returns the status text of every {@code setStatus} request the extension made.
     *
     * <p>Nulls are deliberately kept in the list: the smoke test asserts {@code None in
     * status_texts(...)} to prove that toggling off *clears* the status rather than merely omitting
     * the event, so dropping them would silently weaken the check.
     */
    public static List<String> statusTexts(List<JsonNode> events) {
        List<String> texts = new ArrayList<>();
        for (JsonNode event : events) {
            if (is(event, "type", "extension_ui_request")
                    && is(event, "method", "setStatus")
                    && is(event, "statusKey", "i-have-adhd")) {
                JsonNode text = event.get("statusText");
                texts.add(text == null || !text.isTextual() ? null : text.textValue());
            }
        }
        return texts;
    }

    public static boolean anyStatusContains(List<JsonNode> events, String needle) {
        for (String text : statusTexts(events)) {
            if (text != null && text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    public static int messageCount(JsonNode entriesResponse, String customType) {
        int total = 0;
        for (JsonNode entry : entries(entriesResponse)) {
            if (is(entry, "type", "custom_message") && is(entry, "customType", customType)) {
                total++;
            }
        }
        return total;
    }

    public static boolean latestEnabled(JsonNode entriesResponse) {
        List<JsonNode> states = new ArrayList<>();
        for (JsonNode entry : entries(entriesResponse)) {
            if (is(entry, "type", "custom") && is(entry, "customType", "i-have-adhd-state")) {
                JsonNode data = entry.get("data");
                states.add(data == null ? null : data.get("enabled"));
            }
        }
        JsonNode last = states.isEmpty() ? null : states.get(states.size() - 1);
        PyAssert.check(last != null && last.isBoolean(), "No persisted i-have-adhd state found");
        return last.booleanValue();
    }

    public static List<JsonNode> entries(JsonNode entriesResponse) {
        List<JsonNode> found = new ArrayList<>();
        JsonNode data = entriesResponse.get("data");
        JsonNode entries = data == null ? null : data.get("entries");
        if (entries != null && entries.isArray()) {
            entries.forEach(found::add);
        }
        return found;
    }

    static boolean is(JsonNode node, String field, String expected) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() && expected.equals(value.textValue());
    }
}
