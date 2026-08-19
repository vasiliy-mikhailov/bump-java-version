package tech.mikhailov.bjv.web;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.Executors;

import tech.mikhailov.bjv.bump.Bom;
import tech.mikhailov.ratchet.config.Prompts;

/**
 * THE SERVER, WHICH IS ALL THAT IS LEFT OF A 1,604-LINE PAGE.
 *
 * <p>This was {@code Dashboard.main}, sixty lines of bootstrap on top of a class that rendered the
 * whole record as hand-built HTML. That page was the only way to read this corpus for months and it
 * earned the caution it was given: it stayed reachable under {@code /legacy} with a comment saying
 * it would go once somebody was sure nothing had been lost with it. The Next.js client now covers
 * the three pages it had, the API below it is the only thing that crosses the boundary, and nothing
 * in the client ever called {@code /legacy}, {@code /events} or {@code /feedback}.
 *
 * <p>So the page is gone and the bootstrap is here, where it can be read without scrolling past
 * fifteen hundred lines of markup to find it.
 *
 * <p>THE TOKEN WENT WITH THE FEEDBACK ENDPOINT, and that is worth saying rather than leaving to be
 * discovered. {@code BJV_DASH_TOKEN} gated exactly one thing, the old page's feedback form. The API
 * has never checked it: every write here, including the settings saves and the rerun button, is
 * gated by the proxy's basic_auth and by nothing else. Deleting the page did not weaken that, but
 * it does mean the variable now protects nothing, and deploy.sh still refuses to run without it.
 */
final class Serve {

    private Serve() {
    }

    public static void main(String[] args) throws IOException {
        Path results = Path.of(args.length > 0 ? args[0] : "results").toAbsolutePath().normalize();
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 8086;
        HttpServer http = HttpServer.create(new InetSocketAddress(port), 64);
        // Thread per request: the event stream holds its connection open for the whole poll, so a
        // fixed pool would be exhausted by readers watching rather than by work.
        http.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "dashboard");
            t.setDaemon(true);
            return t;
        }));
        Prompts.beside(results);
        Bom.beside(results);
        Api api = new Api(results);

        // ONE CONTEXT NOW. The frontend is a Next.js static export mounted wherever a shell puts it
        // (see Zone) and the API below it is the only thing that crosses the boundary. Root was
        // registered last and matched broadest, so that everything else could claim a path first;
        // there is nothing else to claim one.
        http.createContext("/", x -> {
            String path = Zone.within(x);
            if (path == null) {
                Zone.send(x, 404, "text/plain; charset=utf-8",
                        "not this zone".getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (api.handle(x, path)) {
                return;
            }
            if (Zone.serveStatic(x, path)) {
                return;
            }
            Zone.send(x, 404, "text/plain; charset=utf-8",
                    "no such page".getBytes(StandardCharsets.UTF_8));
        });
        http.start();

        // ONE CONTAINER, AND NO DOCKER SOCKET IN IT. The supervisor used to run beside this page in
        // its own container because it needed the daemon to stop a lane; a lane now stops itself
        // when it sees its own postponement, so the watcher needs nothing this server does not
        // already have. Sharing a process is only safe because of that: a public HTTP server in a
        // container that can reach the daemon would be a poor trade for one fewer container.
        Thread watcher = new Thread(() -> Supervisor.watch(results), "supervisor");
        watcher.setDaemon(true);
        watcher.start();
        System.out.println("serving :" + port + " over " + results);
    }
}
