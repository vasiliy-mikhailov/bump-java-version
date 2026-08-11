package tech.mikhailov.bjv.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dashboard is served on a public name, so the things a localhost tool gets for free are
 * asserted here: a slug cannot be a path, an unauthenticated append cannot reach the corpus, and
 * what a trace contains cannot become markup.
 */
class TheDashboardIsPubliclyReachableTest {

    @Test
    void aSlugThatIsAPathIsRefused(@TempDir Path dir) throws Exception {
        // The secret sits where a traversal would land, under a name the handler appends anyway.
        Path elsewhere = dir.resolve("elsewhere");
        Files.createDirectories(elsewhere);
        Files.writeString(elsewhere.resolve("trace.jsonl"),
                "{\"kind\":\"asked\",\"agent\":\"x\",\"prompt\":\"SECRET\",\"reply\":\"\"}\n");
        Path results = dir.resolve("results");
        Files.createDirectories(results);

        try (Served served = Served.on(results, null)) {
            for (String attack : new String[]{"../elsewhere", "..%2Felsewhere",
                    elsewhere.toString(), "a/../../elsewhere"}) {
                String body = served.get("/bump?slug=" + URLEncoder.encode(attack,
                        StandardCharsets.UTF_8));
                assertFalse(body.contains("SECRET"),
                        "slug '" + attack + "' reached outside the results directory");
                assertTrue(body.contains("not a bump key"), "expected a refusal for " + attack);
            }
        }
    }

    @Test
    void feedbackWithoutTheTokenIsRefusedAndWritesNothing(@TempDir Path results) throws Exception {
        Files.createDirectories(results);
        try (Served served = Served.on(results, "s3cret")) {
            assertEquals(403, served.post("/feedback", "bump=r&agent=fixer&event=0&note=hi"));
            assertFalse(Files.exists(results.resolve("feedback").resolve("feedback.jsonl")));

            assertEquals(303, served.post("/feedback",
                    "token=s3cret&bump=r&agent=fixer&event=0&note=hi"));
            assertTrue(Files.readString(results.resolve("feedback").resolve("feedback.jsonl"))
                    .contains("\"note\":\"hi\""));
        }
    }

    @Test
    void anOversizedNoteIsRefusedBeforeItIsRead(@TempDir Path results) throws Exception {
        Files.createDirectories(results);
        try (Served served = Served.on(results, null)) {
            assertEquals(413, served.post("/feedback", "note=" + "A".repeat(70_000)));
        }
    }

    @Test
    void aTraceThatContainsMarkupIsRenderedAsText(@TempDir Path results) throws Exception {
        Path bump = results.resolve("repo_sha_11_17");
        Files.createDirectories(bump);
        // A model reply, or a repository's own source, is allowed to contain anything at all.
        Files.writeString(bump.resolve("trace.jsonl"),
                "{\"kind\":\"asked\",\"agent\":\"fixer\",\"prompt\":\"p\","
                        + "\"reply\":\"<script>alert(1)</script> \\\" ' onerror=x\"}\n");
        try (Served served = Served.on(results, null)) {
            String body = served.get("/bump?slug=repo_sha_11_17");
            assertFalse(body.contains("<script>alert(1)</script>"), "reply was rendered as markup");
            assertTrue(body.contains("&lt;script&gt;"));
            assertTrue(body.contains("&#39;"), "single quotes must be escaped too");
        }
    }

    @Test
    void aCraftedTraceCannotBreakOutOfAnAttribute(@TempDir Path results) throws Exception {
        Path bump = results.resolve("crafted");
        Files.createDirectories(bump);
        // A trace is not necessarily written by us: a repository checkout can ship a trace.jsonl,
        // and these fields are the two that were appended without escaping.
        Files.writeString(bump.resolve("trace.jsonl"),
                "{\"kind\":\"built\",\"phase\":\"gate\",\"infra\":\"<img src=x onerror=alert(1)>\","
                        + "\"passed\":\"false\",\"summary\":\"ok\"}\n");
        Files.writeString(results.resolve("settlements.jsonl"),
                "{\"at\":\"1\",\"bump\":\"crafted\",\"state\":\"green onmouseover=alert(1)\","
                        + "\"because\":\"x\",\"baseline\":true,\"gate\":true}\n");
        try (Served served = Served.on(results, null)) {
            String detail = served.get("/bump?slug=crafted");
            assertFalse(detail.contains("<img src=x"), "the built row was rendered as markup");

            String home = served.get("/");
            assertFalse(home.contains("s-green onmouseover=alert(1)>"),
                    "the state value escaped its attribute");
            assertTrue(home.contains("class=\"s-"), "the attribute must be quoted");
        }
    }

    /** The dashboard, started on a free port for one test and stopped after it. */
    private static final class Served implements AutoCloseable {
        private final Process ignored = null;
        private final int port;
        private final com.sun.net.httpserver.HttpServer http;

        private Served(com.sun.net.httpserver.HttpServer http, int port) {
            this.http = http;
            this.port = port;
        }

        static Served on(Path results, String token) throws Exception {
            var ctor = Dashboard.class.getDeclaredConstructor(Path.class, String.class);
            ctor.setAccessible(true);
            Object dash = ctor.newInstance(results.toAbsolutePath().normalize(), token);
            var http = com.sun.net.httpserver.HttpServer.create(
                    new java.net.InetSocketAddress("127.0.0.1", 0), 8);
            for (String route : new String[]{"/", "/bump", "/feedback"}) {
                String method = route.equals("/") ? "home"
                        : route.equals("/bump") ? "bump" : "feedback";
                var handler = Dashboard.class.getDeclaredMethod(method,
                        com.sun.net.httpserver.HttpExchange.class);
                handler.setAccessible(true);
                http.createContext(route, exchange -> {
                    try {
                        handler.invoke(dash, exchange);
                    } catch (ReflectiveOperationException e) {
                        throw new IOException(e);
                    }
                });
            }
            http.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
            http.start();
            return new Served(http, http.getAddress().getPort());
        }

        String get(String path) throws IOException {
            HttpURLConnection c = open(path, "GET");
            try (var in = c.getResponseCode() < 400 ? c.getInputStream() : c.getErrorStream()) {
                return in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }

        int post(String path, String body) throws IOException {
            HttpURLConnection c = open(path, "POST");
            c.setDoOutput(true);
            c.setInstanceFollowRedirects(false);
            c.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
            return c.getResponseCode();
        }

        private HttpURLConnection open(String path, String method) throws IOException {
            var c = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + path).toURL()
                    .openConnection();
            c.setRequestMethod(method);
            return c;
        }

        @Override
        public void close() {
            http.stop(0);
        }
    }
}
