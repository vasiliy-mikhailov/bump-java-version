package tech.mikhailov.bjv.agent;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * One chat completion, over the JDK's own HttpClient.
 *
 * <p>No framework on purpose. The deterministic harness does the tool work in this program, so a
 * model stage is a single (prompt in, text out) call, and a dependency that turns that into a
 * runtime with memory, tools and truncation policies would be surface area with nothing to hold.
 *
 * <p>The reply is read from {@code content} and, when content is empty, from {@code reasoning}:
 * the local reasoning models put their text there when the token cap lands mid-thought, and an
 * answer in the wrong field is still an answer.
 */
final class Llm {

    private static final Duration PATIENCE = Duration.ofMinutes(12);
    private static final int MAX_TOKENS = 16_000;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30)).build();
    private final String base;
    private final String key;
    private final String model;

    Llm(String base, String key, String model) {
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.key = key;
        this.model = model;
    }

    static Llm fromEnv() {
        String base = env("OC_BASE", "https://inference.mikhailov.tech/qwen-3.6-35b-a3b-awq/v1");
        String model = env("OC_MODEL", "qwen-3.6-35b-a3b-awq");
        String key = env("OC_KEY", "");
        return new Llm(base, key, model);
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }

    String ask(String system, String user) {
        String body = "{\"model\":\"" + model + "\",\"temperature\":0,\"max_tokens\":" + MAX_TOKENS
                + ",\"messages\":[{\"role\":\"system\",\"content\":\"" + Settlement.escape(system)
                + "\"},{\"role\":\"user\",\"content\":\"" + Settlement.escape(user) + "\"}]}";
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/chat/completions"))
                .timeout(PATIENCE)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + key)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new IllegalStateException("llm http " + resp.statusCode() + ": "
                        + resp.body().substring(0, Math.min(300, resp.body().length())));
            }
            String content = field(resp.body(), "content");
            return content.isBlank() ? field(resp.body(), "reasoning") : content;
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("llm: " + e.getMessage(), e);
        }
    }

    /**
     * The named string field of the FIRST choice's message, unescaped. Handwritten because the
     * response shape is fixed and a JSON library would be this module's only dependency.
     */
    static String field(String json, String name) {
        int at = json.indexOf("\"" + name + "\":");
        if (at < 0) {
            return "";
        }
        int i = json.indexOf('"', at + name.length() + 3);
        if (i < 0) {
            return "";
        }
        // "null" without quotes
        if (json.startsWith("null", at + name.length() + 3)) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (int p = i + 1; p < json.length(); p++) {
            char c = json.charAt(p);
            if (c == '\\' && p + 1 < json.length()) {
                char n = json.charAt(++p);
                switch (n) {
                    case 'n' -> out.append('\n');
                    case 't' -> out.append('\t');
                    case 'r' -> out.append('\r');
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case 'u' -> {
                        out.append((char) Integer.parseInt(json, p + 1, p + 5, 16));
                        p += 4;
                    }
                    default -> out.append(n);
                }
            } else if (c == '"') {
                break;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
