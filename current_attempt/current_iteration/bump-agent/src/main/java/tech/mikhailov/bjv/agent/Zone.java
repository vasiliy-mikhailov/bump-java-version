package tech.mikhailov.bjv.agent;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.sun.net.httpserver.HttpExchange;

/**
 * THIS TOOL AS A ZONE, MOUNTED WHEREVER A SHELL PUTS IT.
 *
 * <p>The sibling tool, fix-java-svace-markers, wrote the contract down first (its spec chapter 17)
 * and named this repository as the second tool that would implement it. This is that
 * implementation, field for field, because the whole value of a contract is that the second
 * implementer does not get to reinterpret it.
 *
 * <p>THE MECHANISM IS NEXT.JS MULTI-ZONES. The shell owns the public origin and rewrites a path
 * prefix here; this tool never assumes it owns {@code /}. Every zone is a complete application, so
 * versions need not match across zones and a zone that falls over does not take the shell with it.
 *
 * <p>THE BASE PATH IS CONFIGURATION, NOT A CONSTANT. A shell wanting this tool at {@code
 * /tools/bump} sets {@code BASE_PATH} and every asset URL and link follows, because the frontend is
 * built with the same value. A zone with a hard-coded prefix can be mounted one way, and the second
 * tool wanting that prefix cannot be mounted at all.
 *
 * <p>Empty by default, so the standalone deployment behaves exactly as it did.
 */
final class Zone {

    static final String ID = "bump-java-version";

    /** Where the shell mounted us, without a trailing slash. Empty means standalone. */
    static final String BASE = System.getenv().getOrDefault("BASE_PATH", "")
            .replaceAll("/+$", "");

    private Zone() {
    }

    /** The running image, which is also its Docker tag, so a shell can name the build it is on. */
    static String version() {
        String v = System.getenv().getOrDefault("BJV_VERSION", "");
        return v.isBlank() ? "dev" : v;
    }

    /**
     * The one document a shell needs, served by the tool so it describes the version actually
     * running rather than what someone wrote down once.
     *
     * <p>{@code nav} paths are RELATIVE to the base path: the shell prefixes them, and this tool
     * does not know what the shell's URL bar says. {@code badges} is how a count reaches the shell's
     * navigation without the shell knowing what a bump is — it polls the endpoint and reads the
     * field, so a third tool wanting a badge for something else needs no shell change.
     */
    static String manifest() {
        return "{"
                + "\"id\":\"" + ID + "\","
                + "\"name\":\"Bump Java version\","
                + "\"description\":\"Moves Java projects between LTS levels, and proves it kept "
                + "every test that passed.\","
                + "\"version\":" + Json.string(version()) + ","
                + "\"basePath\":" + Json.string(BASE.isEmpty() ? "/" : BASE) + ","
                + "\"assetPrefix\":" + Json.string(BASE.isEmpty() ? "" : BASE + "-static") + ","
                + "\"api\":" + Json.string(BASE + "/api") + ","
                + "\"health\":" + Json.string(BASE + "/api/health") + ","
                + "\"nav\":["
                + "{\"label\":\"Bumps\",\"path\":\"/\",\"badge\":\"running\"},"
                + "{\"label\":\"Settings\",\"path\":\"/settings\",\"badge\":null}"
                + "],"
                + "\"badges\":{\"running\":{\"endpoint\":\"/api/badges\",\"field\":\"running\"}}"
                + "}";
    }

    /**
     * The path a request is asking for, with the mount prefix removed.
     *
     * <p>Returns null when the request is not for this zone at all, which a shell should make
     * impossible but a misconfigured proxy will not.
     */
    static String within(HttpExchange x) {
        String path = x.getRequestURI().getPath();
        if (BASE.isEmpty()) {
            return path;
        }
        if (path.equals(BASE)) {
            return "/";
        }
        return path.startsWith(BASE + "/") ? path.substring(BASE.length()) : null;
    }

    /** One query parameter, decoded, or empty. */
    static String param(HttpExchange x, String name) {
        String raw = x.getRequestURI().getRawQuery();
        if (raw == null) {
            return "";
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8)
                    .equals(name)) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    static void json(HttpExchange x, String body) throws IOException {
        send(x, 200, "application/json; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    static void send(HttpExchange x, int code, String type, byte[] body) throws IOException {
        x.getResponseHeaders().add("Content-Type", type);
        // A zone is fetched by a page the shell served; nothing here is cacheable across a sweep.
        x.getResponseHeaders().add("Cache-Control", "no-store");
        x.sendResponseHeaders(code, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = x.getResponseBody()) {
                out.write(body);
            }
        }
    }

    /**
     * THE EXPORTED FRONTEND, SERVED FROM THE JAR.
     *
     * <p>The pages are a static export, so there is no node process beside the JVM: the container
     * stays single-process in an image that already runs arbitrary code from strangers'
     * repositories. The bundle is a resource, which also means the frontend and the backend cannot
     * be different versions of each other.
     *
     * <p>A path with no extension is a route, and every route resolves to its {@code index.html} —
     * the export uses trailing slashes, and a reader who typed one without is asking for the same
     * page.
     */
    static boolean serveStatic(HttpExchange x, String path) throws IOException {
        String want = path.equals("/") || path.isEmpty() ? "/index.html" : path;
        // Under a mount, assets arrive at <base>-static/_next/...; the shell strips its own prefix,
        // so what reaches here is the same tree either way.
        if (want.startsWith("/_next/") || want.contains("/_next/")) {
            want = want.substring(want.indexOf("/_next/"));
        }
        if (!want.contains(".")) {
            want = want.replaceAll("/+$", "") + "/index.html";
        }
        // No traversal reaches outside the bundle: this is a classpath read, and a resource name
        // containing .. simply does not resolve.
        String resource = "ui" + want;
        try (InputStream in = Zone.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                return false;
            }
            send(x, 200, contentType(want), in.readAllBytes());
            return true;
        }
    }

    private static String contentType(String path) {
        for (String[] pair : TYPES) {
            if (path.endsWith(pair[0])) {
                return pair[1];
            }
        }
        return "application/octet-stream";
    }

    private static final List<String[]> TYPES = List.of(
            new String[] {".html", "text/html; charset=utf-8"},
            new String[] {".js", "text/javascript; charset=utf-8"},
            new String[] {".css", "text/css; charset=utf-8"},
            new String[] {".json", "application/json; charset=utf-8"},
            new String[] {".svg", "image/svg+xml"},
            new String[] {".woff2", "font/woff2"},
            new String[] {".png", "image/png"},
            new String[] {".ico", "image/x-icon"},
            new String[] {".txt", "text/plain; charset=utf-8"});
}
