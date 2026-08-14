package tech.mikhailov.bjv.agent;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * JUST ENOUGH JSON TO ANSWER THE PAGES, and deliberately no more.
 *
 * <p>The record is JSONL and the responses are small trees, so a dependency for this would be a
 * dependency in an image that already runs arbitrary code from strangers' repositories. What it
 * cannot do is parse; nothing here needs to.
 *
 * <p>Escaping is {@link Settlement#escape}, which is the same function the record itself is written
 * with. Two escapers would eventually disagree about a control character, and the one place that
 * shows up is a trace body — which is exactly the field most likely to contain one.
 */
final class Json {

    private Json() {
    }

    static String string(String value) {
        return value == null ? "null" : "\"" + Settlement.escape(value) + "\"";
    }

    /** A string field, or JSON null when blank, which is what an absent optional means to a page. */
    static String optional(String value) {
        return value == null || value.isBlank() ? "null" : string(value);
    }

    static String field(String name, String rawValue) {
        return "\"" + name + "\":" + rawValue;
    }

    static String object(String... fields) {
        return "{" + String.join(",", fields) + "}";
    }

    static <T> String array(List<T> items, Function<T, String> each) {
        return "[" + items.stream().map(each).collect(Collectors.joining(",")) + "]";
    }

    static String map(Map<String, String> values) {
        return "{" + values.entrySet().stream()
                .map(e -> string(e.getKey()) + ":" + e.getValue())
                .collect(Collectors.joining(",")) + "}";
    }
}
