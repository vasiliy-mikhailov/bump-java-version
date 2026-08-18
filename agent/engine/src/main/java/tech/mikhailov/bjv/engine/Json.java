package tech.mikhailov.bjv.engine;

import java.util.LinkedHashMap;

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
public final class Json {

    private Json() {
    }

    public static String string(String value) {
        return value == null ? "null" : "\"" + Settlement.escape(value) + "\"";
    }

    /** A string field, or JSON null when blank, which is what an absent optional means to a page. */
    public static String optional(String value) {
        return value == null || value.isBlank() ? "null" : string(value);
    }

    public static String field(String name, String rawValue) {
        return "\"" + name + "\":" + rawValue;
    }

    public static String object(String... fields) {
        return "{" + String.join(",", fields) + "}";
    }

    public static <T> String array(List<T> items, Function<T, String> each) {
        return "[" + items.stream().map(each).collect(Collectors.joining(",")) + "]";
    }

    public static String map(Map<String, String> values) {
        return "{" + values.entrySet().stream()
                .map(e -> string(e.getKey()) + ":" + e.getValue())
                .collect(Collectors.joining(",")) + "}";
    }
    /** Tolerant of both quoted strings and bare numbers/booleans; the two files differ on that. */
    public static Map<String, String> row(String jsonl) {
        Map<String, String> out = new LinkedHashMap<>();
        int i = 1;
        while (i < jsonl.length() - 1) {
            int k1 = jsonl.indexOf('"', i);
            int k2 = k1 < 0 ? -1 : jsonl.indexOf('"', k1 + 1);
            if (k2 < 0) {
                break;
            }
            String key = jsonl.substring(k1 + 1, k2);
            int colon = jsonl.indexOf(':', k2);
            if (colon < 0) {
                break;
            }
            int scan = colon + 1;
            while (scan < jsonl.length() && jsonl.charAt(scan) == ' ') {
                scan++;
            }
            if (scan < jsonl.length() && jsonl.charAt(scan) == '"') {
                StringBuilder v = new StringBuilder();
                int p = scan + 1;
                while (p < jsonl.length()) {
                    char ch = jsonl.charAt(p);
                    if (ch == '\\' && p + 1 < jsonl.length()) {
                        char n = jsonl.charAt(++p);
                        switch (n) {
                            case 'n' -> v.append('\n');
                            case 't' -> v.append('\t');
                            case 'r' -> v.append('\r');
                            case 'u' -> {
                                v.append((char) Integer.parseInt(jsonl, p + 1, p + 5, 16));
                                p += 4;
                            }
                            default -> v.append(n);
                        }
                    } else if (ch == '"') {
                        break;
                    } else {
                        v.append(ch);
                    }
                    p++;
                }
                out.put(key, v.toString());
                i = p + 1;
            } else {
                int stop = scan;
                while (stop < jsonl.length() && ",}".indexOf(jsonl.charAt(stop)) < 0) {
                    stop++;
                }
                out.put(key, jsonl.substring(scan, stop).trim());
                i = stop + 1;
            }
        }
        return out;
    }
}
