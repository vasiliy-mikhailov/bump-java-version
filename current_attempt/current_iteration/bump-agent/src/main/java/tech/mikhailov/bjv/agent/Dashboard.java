package tech.mikhailov.bjv.agent;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Explore each bump: the settlements list, the full trace behind any of them, and the feedback form
 * that turns a traced (prompt, reply) pair into a labelled training example.
 *
 * <p>One process, no framework, no build step for the UI: the JDK's own HttpServer and three pages
 * of handwritten HTML. The dashboard is a READER — the only thing it ever writes is a feedback row,
 * because a dashboard that can edit its subject is a second orchestrator nobody audits.
 */
public final class Dashboard {

    public static void main(String[] args) throws IOException {
        Path results = Path.of(args.length > 0 ? args[0] : "results");
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 8086;
        HttpServer http = HttpServer.create(new InetSocketAddress(port), 0);
        Dashboard d = new Dashboard(results);
        http.createContext("/", d::home);
        http.createContext("/bump", d::bump);
        http.createContext("/feedback", d::feedback);
        http.start();
        System.out.println("dashboard on :" + port + " over " + results.toAbsolutePath());
    }

    private final Path results;

    private Dashboard(Path results) {
        this.results = results;
    }

    /** The settlements, latest row per bump, newest first. */
    private void home(HttpExchange x) throws IOException {
        Map<String, Map<String, String>> last = new LinkedHashMap<>();
        Path f = results.resolve("settlements.jsonl");
        if (Files.exists(f)) {
            for (String line : Files.readAllLines(f)) {
                Map<String, String> row = row(line);
                last.put(row.getOrDefault("bump", "?"), row);
            }
        }
        StringBuilder b = page("bumps");
        b.append("<h1>bumps</h1><table><tr><th>bump</th><th>state</th><th>baseline</th>")
                .append("<th>gate</th><th>when</th></tr>");
        List<Map.Entry<String, Map<String, String>>> rows = new ArrayList<>(last.entrySet());
        for (int i = rows.size() - 1; i >= 0; i--) {
            Map<String, String> r = rows.get(i).getValue();
            String slug = rows.get(i).getKey().replaceAll("[^A-Za-z0-9]+", "_");
            b.append("<tr><td><a href=\"/bump?slug=").append(slug).append("&key=")
                    .append(esc(rows.get(i).getKey())).append("\">").append(esc(rows.get(i).getKey()))
                    .append("</a></td><td class=s-").append(esc(r.getOrDefault("state", "")))
                    .append(">").append(esc(r.getOrDefault("state", ""))).append("</td><td>")
                    .append(flag(r.get("baseline"))).append("</td><td>").append(flag(r.get("gate")))
                    .append("</td><td>").append(when(r.get("at"))).append("</td></tr>");
        }
        send(x, b.append("</table>").toString());
    }

    /** One bump's whole trace, each asked event with its feedback form. */
    private void bump(HttpExchange x) throws IOException {
        Map<String, String> q = query(x);
        String slug = q.getOrDefault("slug", "");
        Path f = results.resolve(slug).resolve("trace.jsonl");
        StringBuilder b = page(slug);
        b.append("<p><a href=\"/\">&larr; bumps</a></p><h1>").append(esc(q.getOrDefault("key", slug)))
                .append("</h1>");
        if (!Files.exists(f)) {
            send(x, b.append("<p>no trace</p>").toString());
            return;
        }
        int asked = 0;
        for (String line : Files.readAllLines(f)) {
            Map<String, String> r = row(line);
            String kind = r.getOrDefault("kind", "");
            switch (kind) {
                case "asked" -> {
                    b.append(section("asked: " + r.get("agent"), when(r.get("at"))));
                    b.append("<details><summary>prompt</summary><pre>")
                            .append(esc(r.get("prompt"))).append("</pre></details>");
                    b.append("<pre class=reply>").append(esc(r.get("reply"))).append("</pre>");
                    // The form carries the pair itself, so the corpus row is self-contained.
                    b.append("<form method=post action=/feedback>")
                            .append(hidden("bump", q.getOrDefault("key", slug)))
                            .append(hidden("agent", r.get("agent")))
                            .append(hidden("event", String.valueOf(asked)))
                            .append(hidden("prompt", r.get("prompt")))
                            .append(hidden("reply", r.get("reply")))
                            .append("<input name=note placeholder=\"what was wrong or right about "
                                    + "this reply\" size=80> <button>file feedback</button></form>");
                    asked++;
                }
                case "built" -> b.append(section("built: " + r.get("phase"), when(r.get("at"))))
                        .append("<pre>").append("infra=").append(r.get("infra"))
                        .append(" passed=").append(r.get("passed")).append("\n")
                        .append(esc(r.get("summary"))).append("</pre>");
                case "applied" -> b.append(section("applied: " + r.get("stage"), when(r.get("at"))))
                        .append("<pre>").append(esc(r.get("what"))).append("</pre>");
                case "settled" -> b.append(section("settled: " + r.get("state"), when(r.get("at"))))
                        .append("<pre>").append(esc(r.get("because"))).append("</pre>");
                case "priced" -> b.append(section("priced: " + r.get("minutes") + " minutes",
                                when(r.get("at"))))
                        .append("<pre>").append(esc(r.get("itemisation"))).append("</pre>");
                case "failed" -> b.append(section("failed", when(r.get("at"))))
                        .append("<pre>").append(esc(r.get("stack"))).append("</pre>");
                case "progress" -> b.append("<p class=prog>").append(esc(r.get("note")))
                        .append("</p>");
                default -> {
                }
            }
        }
        send(x, b.toString());
    }

    /** The one write. Appends to feedback/feedback.jsonl and returns to the bump. */
    private void feedback(HttpExchange x) throws IOException {
        if (!"POST".equals(x.getRequestMethod())) {
            x.sendResponseHeaders(405, -1);
            return;
        }
        Map<String, String> form = form(new String(x.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8));
        new Feedback(form.getOrDefault("bump", ""), form.getOrDefault("agent", ""),
                Integer.parseInt(form.getOrDefault("event", "0")), form.getOrDefault("note", ""),
                Instant.now().toString(), form.getOrDefault("prompt", ""),
                form.getOrDefault("reply", ""))
                .appendTo(results.resolve("feedback").resolve("feedback.jsonl"));
        x.getResponseHeaders().add("Location", "/bump?slug="
                + form.getOrDefault("bump", "").replaceAll("[^A-Za-z0-9]+", "_"));
        x.sendResponseHeaders(303, -1);
    }

    // ---- small helpers; the flat-string JSON these files use needs no library to read back ----

    static Map<String, String> row(String jsonl) {
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
            char c = jsonl.charAt(colon + 1);
            if (c == '"') {
                StringBuilder v = new StringBuilder();
                int p = colon + 2;
                while (p < jsonl.length()) {
                    char ch = jsonl.charAt(p);
                    if (ch == '\\' && p + 1 < jsonl.length()) {
                        char n = jsonl.charAt(++p);
                        v.append(switch (n) {
                            case 'n' -> '\n';
                            case 't' -> '\t';
                            case 'r' -> '\r';
                            default -> n;
                        });
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
                int end = jsonl.indexOf(',', colon);
                if (end < 0) {
                    end = jsonl.length() - 1;
                }
                out.put(key, jsonl.substring(colon + 1, end).replace("}", "").trim());
                i = end + 1;
            }
        }
        return out;
    }

    private static Map<String, String> query(HttpExchange x) {
        return form(x.getRequestURI().getRawQuery() == null ? "" : x.getRequestURI().getRawQuery());
    }

    private static Map<String, String> form(String encoded) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String pair : encoded.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    private static StringBuilder page(String title) {
        return new StringBuilder("<!doctype html><meta charset=utf-8><title>").append(esc(title))
                .append("</title><style>")
                .append("body{font:14px/1.5 -apple-system,sans-serif;margin:2rem auto;max-width:70rem;padding:0 1rem}")
                .append("table{border-collapse:collapse;width:100%}td,th{border:1px solid #ccc;padding:.3rem .6rem;text-align:left}")
                .append("pre{background:#f6f6f6;padding:.6rem;overflow-x:auto;white-space:pre-wrap}")
                .append(".reply{border-left:3px solid #46a}")
                .append(".prog{color:#666;font-style:italic}")
                .append(".s-green{background:#e6f4e6}.s-infra{background:#fdeaea}")
                .append("h2{margin:1.2rem 0 .2rem}small{color:#888}")
                .append("</style>");
    }

    private static String section(String title, String at) {
        return "<h2>" + esc(title) + " <small>" + at + "</small></h2>";
    }

    private static String hidden(String name, String value) {
        return "<input type=hidden name=" + name + " value=\"" + esc(value) + "\">";
    }

    private static String flag(String v) {
        return "true".equals(v) ? "&#10003;" : "&#10007;";
    }

    private static String when(String millis) {
        try {
            return Instant.ofEpochMilli(Long.parseLong(millis)).toString().replace("T", " ")
                    .substring(0, 19);
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static void send(HttpExchange x, String html) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        x.sendResponseHeaders(200, bytes.length);
        try (var out = x.getResponseBody()) {
            out.write(bytes);
        }
    }
}
