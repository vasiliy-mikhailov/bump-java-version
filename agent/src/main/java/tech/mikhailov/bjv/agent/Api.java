package tech.mikhailov.bjv.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import com.deepagents.langchain4j.subagents.SubAgentDefinition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.sun.net.httpserver.HttpExchange;

/**
 * THE RECORD AS JSON, which is all the frontend needs and all it gets.
 *
 * <p>The dashboard used to build HTML in Java: 1580 lines in which a colour, a layout decision and a
 * fact about a bump were the same expression, so none of the three could be changed without reading
 * the other two. What crosses this boundary now is only the facts. A verdict travels as
 * {@code FAIL_test_conservation}, never as a colour — the moment a server sends a tone it has made a
 * design decision that cannot be tested for, and the page stops being the only place that knows what
 * a state looks like.
 *
 * <p>The shapes are declared once, in the frontend's {@code @bjv/types}, and are ADDITIVE ONLY: a
 * reader's open tab will be older than the server as often as not.
 */
final class Api {

    private final Path results;

    Api(Path results) {
        this.results = results;
    }

    /** Route within the zone. Returns false when nothing here answers, so static serving can try. */
    boolean handle(HttpExchange x, String path) throws IOException {
        switch (path) {
            case "/.well-known/microfrontend.json" -> Zone.json(x, Zone.manifest());
            case "/api/health" -> health(x);
            case "/api/badges" -> badges(x);
            case "/api/bumps" -> Zone.json(x, bumps(Zone.param(x, "since")));
            case "/api/summary" -> Zone.json(x, summary());
            case "/api/live" -> live(x, Zone.param(x, "slug"), Zone.param(x, "have"));
            case "/api/rerun" -> Zone.json(x, rerun(Zone.param(x, "slug")));
            case "/api/security" -> Zone.json(x, security());
            case "/api/bump" -> Zone.json(x, bump(Zone.param(x, "slug")));
            case "/api/settings" -> Zone.json(x, settings(Zone.param(x, "hop")));
            case "/api/settings/prompt" -> prompt(x);
            case "/api/settings/run" -> run(x);
            case "/api/settings/model" -> Zone.json(x, model());
            case "/api/settings/subject" -> Zone.json(x, subject());
            case "/api/settings/registry" -> registry(x);
            case "/api/settings/supervisor" -> Zone.json(x, supervisor());
            case "/api/settings/bom" -> bomFile(x);
            default -> {
                return false;
            }
        }
        return true;
    }

    /**
     * IT REPORTS ON THE RECORD, NOT ON THE MODEL ENDPOINT.
     *
     * <p>The dashboard is worth serving when inference is unreachable: the whole record is still
     * readable and that is most of what anybody comes for. A health check that went red because a
     * model was down would have a shell hiding a tool that was working.
     */
    private void health(HttpExchange x) throws IOException {
        boolean ok = Files.isDirectory(results);
        Zone.send(x, ok ? 200 : 503, "application/json; charset=utf-8",
                (ok
                        ? Json.object(Json.field("ok", "true"),
                                Json.field("version", Json.string(Zone.version())))
                        : Json.object(Json.field("ok", "false"),
                                Json.field("why", Json.string("the results directory is not readable"))))
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** A count for the shell's navigation, without the shell knowing what a bump is. */
    private void badges(HttpExchange x) throws IOException {
        long running = settlements().values().stream()
                .filter(r -> "bumping".equals(r.getOrDefault("state", ""))).count();
        Zone.json(x, Json.object(Json.field("running", String.valueOf(running))));
    }

    /**
     * The latest settlement per bump, keyed by slug. A bump settles more than once as it runs.
     *
     * <p>NOT EVERY ROW IN THAT FILE IS A BUMP. The supervisor writes its own rows there — its last
     * one on this corpus is an authentication failure against the model endpoint — and rendering
     * those as repositories put a bump called "supervisor" at the top of the table, verdict
     * `bumping`, forever. A bump key is {@code repo|sha|from|to} and anything else is another kind
     * of row that happens to share the file.
     */
    private Map<String, Map<String, String>> settlements() {
        Map<String, Map<String, String>> latest = new LinkedHashMap<>();
        for (String line : lines(results.resolve("settlements.jsonl"))) {
            Map<String, String> r = Json.row(line);
            String bump = r.getOrDefault("bump", "");
            if (isBump(bump)) {
                latest.put(slug(bump), r);
            }
        }
        return latest;
    }

    /** Four fields, and the last two are the hop, which is what makes it a bump and not a note. */
    static boolean isBump(String key) {
        String[] parts = key.split("\\|");
        if (parts.length < 4) {
            return false;
        }
        try {
            Integer.parseInt(parts[2].trim());
            Integer.parseInt(parts[3].trim());
            return true;
        } catch (NumberFormatException notAHop) {
            return false;
        }
    }

    /**
     * THE WHOLE CORPUS, NOT JUST THE PART THAT HAS STARTED.
     *
     * <p>This listed settlements only, so a sweep of 1439 repositories showed four rows: the ones a
     * lane had already picked up. Everything still waiting was invisible, and the page that exists
     * to answer "how far has this got" could not show the denominator.
     *
     * <p>{@code run.sh} has written {@code queue.tsv} for the dashboard all along, with a comment
     * saying why — "a page built only from settlements can never show the work that has not started
     * yet" — and nothing read it. So the queue is the list, and a settlement overlays the row it
     * belongs to. A repository with no settlement is queued, which is a state like any other.
     */
    /**
     * HOW BIG THIS IS AND WHEN IT LAST MOVED, which is what a reader checks first.
     *
     * <p>COUNTED ONCE PER CHANGE, NOT ONCE PER REQUEST. A trace runs to thousands of lines and there
     * are as many traces as bumps that have started; re-reading them all to put a number in a header
     * would make the page slower the longer the sweep ran, which is exactly backwards. Each file is
     * counted when its modification time moves and remembered otherwise.
     *
     * <p>The last-event time is the newest of those modification times and costs nothing to read: a
     * sweep that has stopped is the thing this number exists to reveal, and a page that had to open
     * every file to notice would be the last thing to find out.
     */
    private String summary() throws IOException {
        long events = 0;
        long last = 0;
        try (var dirs = Files.list(results)) {
            for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                Path trace = dir.resolve("trace.jsonl");
                if (!Files.isRegularFile(trace)) {
                    continue;
                }
                long at = Files.getLastModifiedTime(trace).toMillis();
                last = Math.max(last, at);
                events += counted(trace, at);
            }
        }
        return Json.object(
                Json.field("bumps", String.valueOf(bumpCount())),
                Json.field("events", String.valueOf(events)),
                Json.field("lastEventAt", String.valueOf(last)));
    }

    /**
     * WHEN A BUMP FIRST SPOKE, AND WHEN IT LAST DID. Both read from the trace, which is the record
     * of activity; the settlement is not.
     *
     * <p>The "last event" column used to be the settlement's timestamp, and a settlement is written
     * when a bump STARTS and when it ends. So a bump grinding away for an hour showed the moment it
     * began, and the one question the column exists to answer — is this thing still moving — was the
     * one it could not answer.
     *
     * <p>Started is the first line of the trace and never changes once written, so it is remembered
     * outright. Last is the file's modification time, which costs nothing and is the truth: an agent
     * mid-answer has not written a settlement and never will until it finishes.
     */
    private final Map<String, Long> began = new java.util.concurrent.ConcurrentHashMap<>();

    private long startedAt(String slug) {
        return began.computeIfAbsent(slug, key -> {
            Path trace = results.resolve(key).resolve("trace.jsonl");
            if (!Files.isRegularFile(trace)) {
                return 0L;
            }
            try (var lines = Files.lines(trace)) {
                return lines.findFirst().map(l -> num(Json.row(l).get("at"))).orElse(0L);
            } catch (IOException | RuntimeException unreadable) {
                return 0L;
            }
        });
    }

    /**
     * THE TIMESTAMP THE ROW CARRIES, so that anything ordering or filtering by it agrees with what
     * the page displays. Cheap: one stat of a file the caller is about to read anyway.
     */
    private long shownAt(Map<String, String> r) {
        return lastEventAt(slug(r.getOrDefault("bump", "")), num(r.get("at")));
    }

    private long lastEventAt(String slug, long fallback) {
        Path trace = results.resolve(slug).resolve("trace.jsonl");
        try {
            return Files.isRegularFile(trace)
                    ? Files.getLastModifiedTime(trace).toMillis() : fallback;
        } catch (IOException unreadable) {
            return fallback;
        }
    }

    /** slug -> (mtime, lines), so an unchanged trace is never read twice. */
    private final Map<Path, long[]> counts = new java.util.concurrent.ConcurrentHashMap<>();

    private long counted(Path trace, long at) throws IOException {
        long[] known = counts.get(trace);
        if (known != null && known[0] == at) {
            return known[1];
        }
        long lines;
        try (var stream = Files.lines(trace)) {
            lines = stream.count();
        } catch (java.io.UncheckedIOException partial) {
            // A trace being appended to right now can hold a half-written line. It will be counted
            // on the next look; a page that threw here would be a page that breaks while working.
            return known == null ? 0 : known[1];
        }
        counts.put(trace, new long[] {at, lines});
        return lines;
    }

    /** How many events this bump has written, or 0 for one that has not started. */
    private long events(String slug) {
        Path trace = results.resolve(slug).resolve("trace.jsonl");
        if (!Files.isRegularFile(trace)) {
            return 0;
        }
        try {
            return counted(trace, Files.getLastModifiedTime(trace).toMillis());
        } catch (IOException unreadable) {
            return 0;
        }
    }

    /** slug -> the estimator's minutes. Written once when a bump settles, so it never changes. */
    private final Map<String, String> priced = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * WHAT THE SAME WORK WOULD HAVE COST A PERSON, as the estimator triad priced it.
     *
     * <p>Read from the trace rather than the settlement, because the settlement records the verdict
     * and this is not one. The estimator plans the distinct pieces of work that LANDED, prices them,
     * and a verifier checks the price against the log; what lands here is the number that survived
     * that. It is an estimate and the page says so by putting it in its own column rather than
     * adding it to anything measured.
     *
     * <p>Cached without an mtime, unlike {@link #counted}: price() runs once, at the end, and a
     * bump that has been priced is a bump that has finished. Only settled bumps are looked up at
     * all, so a sweep of 1439 mostly-queued rows costs nothing here.
     */
    /**
     * THE COMPLIANCE FILE, READ ONCE AND JOINED BY BUMP.
     *
     * <p>It sits beside settlements.jsonl rather than inside it, because a live sweep appends to
     * that file and two readers take the last line per bump to decide what state it is in. A
     * measurement does not belong in the path of a verdict.
     *
     * <p>Last line wins, so a bump measured again against a changed bill of materials shows the
     * newer number while the older one stays on the record.
     */
    private Map<String, Map<String, String>> compliance() {
        Map<String, Map<String, String>> byBump = new LinkedHashMap<>();
        Path file = results.resolve("bom.jsonl");
        if (!Files.isRegularFile(file)) {
            return byBump;
        }
        try {
            for (String line : Files.readAllLines(file, java.nio.charset.StandardCharsets.UTF_8)) {
                Map<String, String> row = Json.row(line);
                String bump = row.get("bump");
                if (bump != null && !bump.isBlank()) {
                    byBump.put(bump.replaceAll("[^A-Za-z0-9]+", "_"), row);
                }
            }
        } catch (IOException unreadable) {
            return byBump;
        }
        return byBump;
    }

    private Map<String, Map<String, String>> held;
    private long heldAt = -1;

    /**
     * One field of one bump's compliance, or null when it was never measured.
     *
     * <p>Cached, because forty-five bumps times three fields is a hundred and thirty-five reads of
     * one file to render one table. KEYED ON THE FILE'S OWN CLOCK, because this object outlives a
     * request: a cache that never expires would hold the numbers as they were when the dashboard
     * started and go on showing them while a sweep wrote new ones, which is worse than showing
     * none. The file only grows, so its timestamp is a complete description of its contents.
     */
    private synchronized String bom(String slug, String field) {
        long stamp = 0;
        try {
            Path file = results.resolve("bom.jsonl");
            stamp = Files.isRegularFile(file) ? Files.getLastModifiedTime(file).toMillis() : 0;
        } catch (IOException unreadable) {
            stamp = 0;
        }
        if (held == null || stamp != heldAt) {
            held = compliance();
            heldAt = stamp;
        }
        Map<String, String> row = held.get(slug);
        if (row == null) {
            return null;
        }
        String value = row.get(field);
        if (value == null || value.isBlank()) {
            return field.equals("outstanding") ? null : null;
        }
        return field.equals("outstanding") ? Json.string(value) : value;
    }

    /**
     * READ AND WRITE ONE HOP'S LIST AS A FILE, which is what it is.
     *
     * <p>The rows go out parsed for the table; this goes out raw, because the thing being edited is
     * a file whose comments are half of what it says, and a round trip through records and back
     * would quietly drop them.
     *
     * <p>A save that cannot be parsed is refused here rather than accepted and thrown at the next
     * bump to discover. Loading throws on a malformed row on purpose, and that throw would land
     * inside a lane rather than on the page of whoever typed it.
     */
    private void bomFile(HttpExchange x) throws IOException {
        String hop = Zone.param(x, "hop");
        String part = Zone.param(x, "part");
        if (!hop.matches("\\d+-\\d+")) {
            Zone.json(x, Json.object(Json.field("saved", "false"),
                    Json.field("why", Json.string("that is not a hop"))));
            return;
        }
        if (x.getRequestMethod().equalsIgnoreCase("GET")) {
            // BOTH HALVES IN ONE ANSWER. They are read together and edited together, and a page
            // that had to ask twice would show one of them stale for a moment.
            Hop asked = hopOf(hop);
            Zone.json(x, Json.object(
                        Json.field("hop", Json.string(asked.from() + " \u2192 " + asked.to())),
                        Json.field("name", Json.string(hop)),
                        Json.field("files", Json.array(Bom.parts(), name -> {
                            Bom.Source source = Bom.textFor(asked, name);
                            return Json.object(
                                    Json.field("part", Json.string(name)),
                                    Json.field("title", Json.string(name.equals("hardens")
                                            ? "what hardens the result"
                                            : "what enables the bump")),
                                    Json.field("about", Json.string(name.equals("hardens")
                                            ? "Polish on a project that already builds and tests "
                                            + "green at the target, where the patch releases carry "
                                            + "the CVE fixes. None of it can be raised until the "
                                            + "JDK has moved, and none of it is load-bearing for "
                                            + "the move."
                                            : "What makes the bump possible at all. A Lombok that "
                                            + "cannot read the new class file kills javac before "
                                            + "anything else runs. Below one of these the bump "
                                            + "does not happen, so they move before the JDK does.")),
                                    Json.field("rows",
                                            String.valueOf(Bom.of(asked, name).size())),
                                    Json.field("text", Json.string(source.text())),
                                    Json.field("edited", String.valueOf(source.edited())));
                    }))));
            return;
        }
        if (!Bom.parts().contains(part)) {
            Zone.json(x, Json.object(Json.field("saved", "false"),
                    Json.field("why", Json.string("a save names which half it is"))));
            return;
        }
        String body = new String(x.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        String key = Bom.key(hopOf(hop), part);
        try {
            if (body.isBlank()) {
                // AN EMPTY SAVE IS A REVERT, said once rather than needing its own button: the
                // built-in was never gone, so throwing the edit away restores it.
                Bom.revert(key);
            } else {
                Bom.save(key, body);
            }
            Bom.Source now = Bom.textFor(key);
            Zone.json(x, Json.object(Json.field("saved", "true"),
                    Json.field("edited", String.valueOf(now.edited())),
                    Json.field("rows", String.valueOf(Bom.of(hopOf(hop), part).size()))));
        } catch (IOException | RuntimeException refused) {
            Zone.json(x, Json.object(Json.field("saved", "false"),
                    Json.field("why", Json.string(String.valueOf(refused.getMessage())))));
        }
    }

    private String humanMinutes(String slug) {
        String known = priced.get(slug);
        if (known != null) {
            return known;
        }
        Path trace = results.resolve(slug).resolve("trace.jsonl");
        if (!Files.isRegularFile(trace)) {
            return "null";
        }
        try (var lines = Files.lines(trace)) {
            String found = lines.filter(l -> l.contains("\"kind\":\"priced\""))
                    .map(l -> Json.row(l).getOrDefault("minutes", ""))
                    .filter(m -> m.matches("\\d+"))
                    .reduce((first, last) -> last)
                    .orElse("");
            if (found.isEmpty()) {
                return "null";
            }
            priced.put(slug, found);
            return found;
        } catch (IOException | RuntimeException unreadable) {
            return "null";
        }
    }

    /**
     * THE PAGE STOPS ASKING AND THE SERVER STARTS TELLING.
     *
     * <p>Every view here was a poll. The list refetched a delta every fifteen seconds, which is a
     * fifteen-second lie on a column whose whole job is saying whether a bump is still moving, and
     * the record did not poll at all: a reader watching a lane work had to press refresh to see the
     * next thing it did.
     *
     * <p>SERVER-SENT EVENTS RATHER THAN A SOCKET, deliberately. Everything on these pages travels
     * one way, and this is the server the dashboard already runs: com.sun's HttpServer cannot
     * upgrade a connection, so a real WebSocket means replacing it and rewriting every handler that
     * takes an HttpExchange. SSE needs no dependency, no second port, nothing added to the proxy,
     * and the browser reconnects on its own when a connection drops. If this ever needs to carry
     * traffic the other way, the plumbing below is what a socket would have used anyway.
     *
     * <p>WITH A SLUG it tails that bump's trace and sends each new event in the shape the record
     * already renders. WITHOUT ONE it watches the whole results tree and says only THAT something
     * moved, because the list page holds 1439 rows and knows how to fetch its own delta; pushing
     * the rows down this channel would be the megabyte-a-tick problem in a new place.
     */
    private void live(HttpExchange x, String slug, String have) throws IOException {
        x.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
        x.getResponseHeaders().add("Cache-Control", "no-store");
        // A PROXY THAT BUFFERS AN EVENT STREAM DELIVERS IT ALL AT THE END, which looks exactly like
        // a server that never sent anything.
        x.getResponseHeaders().add("X-Accel-Buffering", "no");
        x.sendResponseHeaders(200, 0);
        Path trace = slug == null || slug.isBlank() ? null
                : results.resolve(slug).resolve("trace.jsonl");
        // WHAT THE READER ALREADY HAS. The page loads the record from /api/bump and then subscribes;
        // streaming from byte zero would put every one of those events on the screen a second time.
        // The trace is append-only, so the line the caller has counted to is a stable place to
        // start, and unlike a byte offset the page can compute it without knowing the file.
        long from = trace == null ? 0 : after(trace, have);
        long lastSeen = -1;
        long beat = System.currentTimeMillis();
        try (java.io.OutputStream out = x.getResponseBody()) {
            // BOUNDED, because a browser tab left open for a week should not pin a thread for a
            // week. The client reconnects by itself, so an hour is a ceiling and not a limit on
            // how long a reader may watch.
            long until = System.currentTimeMillis() + java.time.Duration.ofHours(1).toMillis();
            while (System.currentTimeMillis() < until) {
                if (trace != null) {
                    from = tail(out, trace, from);
                } else {
                    long moved = movedAt();
                    if (moved != lastSeen) {
                        lastSeen = moved;
                        write(out, "changed", summary());
                    }
                }
                if (System.currentTimeMillis() - beat > 20_000) {
                    // A COMMENT LINE KEEPS THE CONNECTION ALIVE without being an event, which is
                    // what stops an idle proxy closing a stream that is simply waiting.
                    out.write(": still here\n\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    out.flush();
                    beat = System.currentTimeMillis();
                }
                Thread.sleep(1000);
            }
        } catch (IOException clientWentAway) {
            // The ordinary end of one of these: a tab closed. Not worth a line in the log.
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * The byte offset just past the first {@code have} lines, or the start when that is not a
     * number. Not the end of the file: a caller that has counted 40 lines must still receive the
     * 41st if the lane wrote it between its fetch and its subscription, which is the race that
     * makes "start at the end" quietly lossy.
     */
    private static long after(Path trace, String have) {
        int lines;
        try {
            lines = have == null || have.isBlank() ? 0 : Integer.parseInt(have.trim());
        } catch (NumberFormatException notANumber) {
            return 0;
        }
        if (lines <= 0 || !Files.isRegularFile(trace)) {
            return 0;
        }
        long offset = 0;
        int seen = 0;
        try (var reader = Files.newBufferedReader(trace, java.nio.charset.StandardCharsets.UTF_8)) {
            String line;
            while (seen < lines && (line = reader.readLine()) != null) {
                offset += line.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 1;
                seen += 1;
            }
        } catch (IOException unreadable) {
            return 0;
        }
        return offset;
    }

    /** New lines since {@code from}, sent as they arrive; returns where to read from next. */
    private long tail(java.io.OutputStream out, Path trace, long from) throws IOException {
        if (!Files.isRegularFile(trace)) {
            return from;
        }
        long size = Files.size(trace);
        if (size <= from) {
            // TRUNCATED OR REPLACED, which happens when a run root is cleared under a reader. Start
            // again rather than seeking past the end of a new file.
            return size < from ? 0 : from;
        }
        try (var channel = java.nio.channels.FileChannel.open(trace,
                java.nio.file.StandardOpenOption.READ)) {
            channel.position(from);
            byte[] buffer = new byte[(int) Math.min(size - from, 1 << 20)];
            int read = channel.read(java.nio.ByteBuffer.wrap(buffer));
            if (read <= 0) {
                return from;
            }
            String text = new String(buffer, 0, read, java.nio.charset.StandardCharsets.UTF_8);
            int lastBreak = text.lastIndexOf('\n');
            if (lastBreak < 0) {
                // A PARTIAL LINE IS NOT AN EVENT. The writer is appending right now; wait for it.
                return from;
            }
            for (String line : text.substring(0, lastBreak).split("\n")) {
                if (!line.isBlank()) {
                    write(out, "trace", event(Json.row(line)));
                }
            }
            return from + text.substring(0, lastBreak + 1)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        }
    }

    /** The newest write anywhere under results, which is what "something moved" means. */
    private long movedAt() {
        long newest = 0;
        try (var dirs = Files.list(results)) {
            for (Path dir : dirs.toList()) {
                Path trace = dir.resolve("trace.jsonl");
                if (Files.isRegularFile(trace)) {
                    newest = Math.max(newest, Files.getLastModifiedTime(trace).toMillis());
                }
            }
            Path settled = results.resolve("settlements.jsonl");
            if (Files.isRegularFile(settled)) {
                newest = Math.max(newest, Files.getLastModifiedTime(settled).toMillis());
            }
        } catch (IOException unreadable) {
            return newest;
        }
        return newest;
    }

    private static void write(java.io.OutputStream out, String name, String json)
            throws IOException {
        // ONE LINE PER FIELD, and the data line must not contain a newline of its own: the format
        // ends an event at a blank line, so an embedded one truncates the payload.
        out.write(("event: " + name + "\ndata: " + json.replace("\n", " ") + "\n\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        out.flush();
    }

    /**
     * ASK FOR A BUMP TO BE RUN AGAIN.
     *
     * <p>A settled bump is skipped forever, which is right while the harness is unchanged and wrong
     * the moment it is not. The floors, the prompts and the tools all moved today, so a verdict
     * from this morning was reached by an agent that no longer exists. Until now the only way to
     * revisit one was to hand-edit settlements.jsonl on the host.
     *
     * <p>AN APPEND, NOT A REWRITE. Both readers of that file already take the LAST line for a bump
     * and ignore what came before, so requeueing is one line added rather than a file rewritten
     * underneath a sweep that is appending to it. A rewrite would race, and the window is precisely
     * the moment a lane settles.
     *
     * <p>"requeued" rather than "bumping", which would also have worked because it is the one state
     * run.sh treats as unfinished. A bump waiting for a lane is not a bump in flight, and this
     * corpus has spent the day removing numbers that were true only by accident. The page reads it
     * as queued, which is what it is.
     */
    /**
     * Is this bump already waiting in the drainer's queue?
     *
     * <p>ASKING TWICE IS A FAIR THING TO DO. A requeued bump waits for a free lane and a lane runs
     * for hours, so the honest answer to a second click is "it is already queued", not a second row
     * in the manifest for the same work. The queue is two files: what has not been picked up yet,
     * and the batch a drainer has taken but not finished.
     */
    private boolean alreadyQueued(String slug) {
        Path root = results.getParent() == null ? results : results.getParent();
        List<Path> queues = new ArrayList<>();
        queues.add(results.resolve("rerun.tsv"));
        try (var batches = Files.list(root)) {
            batches.filter(p -> p.getFileName().toString().startsWith("rerun-batch"))
                    .forEach(queues::add);
        } catch (IOException unreadable) {
            // A queue that cannot be read is not evidence of an empty one, but refusing every
            // rerun on that basis would be worse than the duplicate row it prevents.
        }
        for (Path queue : queues) {
            try {
                if (Files.isRegularFile(queue) && Files.readAllLines(queue).stream()
                        .anyMatch(l -> l.startsWith(slug + "\t"))) {
                    return true;
                }
            } catch (IOException unreadable) {
                continue;
            }
        }
        return false;
    }

    private String rerun(String slug) {
        if (slug == null || slug.isBlank()) {
            return Json.object(Json.field("queued", "false"),
                    Json.field("why", Json.string("no slug given")));
        }
        Map<String, String> settled = settlements().get(slug);
        if (settled == null) {
            return Json.object(Json.field("queued", "false"),
                    Json.field("why", Json.string("nothing settled under that slug")));
        }
        String bump = settled.getOrDefault("bump", "");
        String[] parts = bump.split("\\|");
        if (parts.length < 4) {
            return Json.object(Json.field("queued", "false"),
                    Json.field("why", Json.string("that settlement names no hop")));
        }
        if (alreadyQueued(slug)) {
            // TRUE, AND NOT A REFUSAL. The work is going to happen; a second row for it would only
            // make a lane discover it was already in flight and skip it.
            return Json.object(Json.field("queued", "true"),
                    Json.field("repo", Json.string(parts[0])),
                    Json.field("hop", Json.string(parts[2] + " -> " + parts[3])),
                    Json.field("was", Json.string("already waiting for a lane")));
        }
        try {
            Files.writeString(results.resolve("rerun.tsv"),
                    slug + "\t" + parts[0] + "\t" + parts[1] + "\t" + parts[2] + "\t" + parts[3]
                            + "\n",
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
            Files.writeString(results.resolve("settlements.jsonl"),
                    "{\"at\":\"" + System.currentTimeMillis() + "\",\"bump\":\"" + bump
                            + "\",\"kind\":\"settled\",\"state\":\"requeued\"}\n",
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException couldNotWrite) {
            return Json.object(Json.field("queued", "false"),
                    Json.field("why", Json.string(String.valueOf(couldNotWrite.getMessage()))));
        }
        return Json.object(
                Json.field("queued", "true"),
                Json.field("repo", Json.string(parts[0])),
                Json.field("hop", Json.string(parts[2] + " -> " + parts[3])),
                Json.field("was", Json.string(settled.getOrDefault("state", ""))));
    }

    /** The corpus: everything queued, plus anything settled that the queue no longer lists. */
    private int bumpCount() {
        Set<String> all = new LinkedHashSet<>(settlements().keySet());
        for (String row : lines(results.resolve("queue.tsv"))) {
            String[] c = row.strip().split("\\s+");
            if (c.length >= 5) {
                all.add(slug(c[1] + "|" + c[2] + "|" + c[3] + "|" + c[4]));
            }
        }
        return all.size();
    }

    /**
     * THE WHOLE CORPUS, OR ONLY WHAT HAS MOVED SINCE {@code since}.
     *
     * <p>The list is 1439 rows and about a megabyte, so the page loads it once and polls the
     * summary instead. That left the "last event" column reading a timestamp frozen at page load
     * while the clock beside it advanced, so the column did not merely lag: it drifted further from
     * the truth the longer a tab stayed open, and only a manual refresh corrected it.
     *
     * <p>Polling the whole thing to fix that would send a megabyte every fifteen seconds to update
     * six rows. A delta sends the six. Two things qualify: anything whose last event is newer than
     * the caller's high-water mark, and anything still running, because a lane mid-answer has not
     * written since the last poll and is exactly the row a reader is watching.
     *
     * <p>The mark is the newest {@code at} the caller already holds, not a wall clock. The page's
     * clock and this one belong to different machines, and a delta keyed on client time drops rows
     * whenever those disagree.
     */
    private String bumps(String since) {
        long mark;
        try {
            mark = since == null || since.isBlank() ? 0L : Long.parseLong(since.trim());
        } catch (NumberFormatException notANumber) {
            mark = 0L;
        }
        return bumps(mark);
    }

    private String bumps(long mark) {
        Map<String, Map<String, String>> settled = settlements();
        Map<String, Map<String, String>> all = new LinkedHashMap<>();

        for (String row : lines(results.resolve("queue.tsv"))) {
            String[] c = row.strip().split("\\s+");
            if (c.length < 5) {
                continue;
            }
            String key = c[1] + "|" + c[2] + "|" + c[3] + "|" + c[4];
            all.put(slug(key), Map.of("bump", key, "state", "queued", "at", "0"));
        }
        // A settlement is the fresher fact about a row the queue also holds, and it is also the
        // only fact about a row the queue does not: a manifest can be swapped mid-sweep.
        all.putAll(settled);

        List<Map<String, String>> rows = new ArrayList<>(all.values());
        if (mark > 0) {
            rows.removeIf(r -> shownAt(r) <= mark
                    && !"bumping".equals(r.getOrDefault("state", "")));
        }
        // THE ORDER THE SWEEP TAKES THEM IN, which is the one order a reader can hold in their
        // head. It sorted by whatever had moved most recently, so the running bumps floated to the
        // top and the table rearranged itself as the sweep worked: a repository you were looking
        // at moved because a different one finished.
        //
        // This is run.sh's comparator, character for character (run.sh:233):
        //     LC_ALL=C sort -t TAB -k2,2f -k2,2 -k4,4n
        // repository case-folded, then case-sensitive to break the ties the fold creates, then the
        // source JDK numerically. Case-folded because that is what alphabetical means to a reader:
        // a plain byte sort puts every capital-initial repository before every lowercase one, and
        // aartiPl/tablevis sat at row 524 in the queue while the page showed it fourteenth. Same
        // corpus, two orders, and the sweep looked like it was skipping rows it had not reached.
        //
        // The delta filter above is untouched. It decides which rows moved, not where they go, and
        // the page replaces them in place precisely so that the server's order survives a refresh.
        rows.sort((a, b) -> {
            String[] x = a.getOrDefault("bump", "").split("\\|");
            String[] y = b.getOrDefault("bump", "").split("\\|");
            String rx = x.length > 0 ? x[0] : "";
            String ry = y.length > 0 ? y[0] : "";
            int byName = rx.compareToIgnoreCase(ry);
            if (byName != 0) {
                return byName;
            }
            int exact = rx.compareTo(ry);
            if (exact != 0) {
                return exact;
            }
            return Integer.compare(hopFrom(x), hopFrom(y));
        });
        return Json.array(rows, this::summary);
    }

    /** The source JDK of a split bump key, or zero when it has none to compare on. */
    private static int hopFrom(String[] parts) {
        try {
            return parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }

    /** One row of the corpus. `repo|sha|from|to` is the bump key the whole harness is keyed by. */
    private String summary(Map<String, String> r) {
        String[] parts = r.getOrDefault("bump", "").split("\\|");
        String because = r.getOrDefault("because", "");
        String slug = slug(r.getOrDefault("bump", ""));
        return Json.object(
                Json.field("slug", Json.string(slug(r.getOrDefault("bump", "")))),
                Json.field("repo", Json.string(parts.length > 0 ? parts[0] : "")),
                Json.field("sha", Json.string(parts.length > 1 ? parts[1] : "")),
                Json.field("from", parts.length > 2 ? parts[2] : "0"),
                Json.field("to", parts.length > 3 ? parts[3] : "0"),
                // REQUEUED READS AS QUEUED. The state exists so the runner knows the old
                // verdict no longer counts; a reader already has a word for waiting.
                Json.field("verdict", Json.string("requeued".equals(r.get("state"))
                        ? "queued" : r.getOrDefault("state", "bumping"))),
                Json.field("because", Json.optional(because)),
                Json.field("baselineGreen", String.valueOf("true".equals(r.get("baseline")))),
                Json.field("gateGreen", String.valueOf("true".equals(r.get("gate")))),
                Json.field("preTests", number(because, TESTS)),
                Json.field("cvesBefore", number(because, CVES_BEFORE)),
                Json.field("cvesAfter", number(because, CVES_AFTER)),
                Json.field("startedAt", String.valueOf(startedAt(slug))),
                Json.field("at", String.valueOf(shownAt(r))),
                Json.field("events", String.valueOf(events(slug))),
                Json.field("humanMinutes", humanMinutes(slug)),
                // A GREEN GATE IS NOT COMPLIANCE. The verdict says the project builds under the
                // target and kept its tests; this says how much of what the target actually needs
                // it reached. Null where nothing was measured, which is not nought per cent.
                Json.field("bomMet", bom(slug, "met")),
                Json.field("bomMissed", bom(slug, "missed")),
                // WHERE IT STOOD BEFORE, so a reader can tell a repository this harness raised
                // from one that arrived at the floor already owing it nothing.
                Json.field("bomMetBefore", bom(slug, "metBefore")),
                Json.field("bomMissedBefore", bom(slug, "missedBefore")),
                // ONLY THE FLOORS JUDGEABLE ON BOTH SIDES. The after-scan runs on a green gate and
                // the before-scan does not, so the raw totals are not comparable and subtracting
                // them reports a missing measurement as work done.
                Json.field("bomPairApplied", bom(slug, "pairApplied")),
                Json.field("bomPairMissedBefore", bom(slug, "pairMissedBefore")),
                Json.field("bomPairMissedAfter", bom(slug, "pairMissedAfter")),
                Json.field("bomOutstanding", Json.optional(bom(slug, "outstanding"))),
                // WHICH PIPELINE PRODUCED THIS ROW, so that a difference between two bumps
                // can be told from a difference between two harnesses. A sweep runs for a
                // fortnight and the harness changes daily, so a row settled a week ago was
                // not produced by the program answering this request. Null on everything
                // that settled before the stamp existed, which is most of the corpus and
                // will stay that way: absent is the ordinary case here, not a fault.
                Json.field("commit", Json.optional(r.get("commit"))),
                Json.field("image", Json.optional(r.get("image"))),
                // THE IMAGE IS NOT THE PIPELINE, which is why the two hashes are here beside
                // a commit that looks like it says everything. Prompt and bill-of-materials
                // edits live in a store beside the results, OUTSIDE the image, so a commit
                // or an image alone calls two runs the same pipeline exactly when one of
                // them was edited from the settings page. See Version.
                Json.field("prompts", Json.optional(r.get("prompts"))),
                Json.field("boms", Json.optional(r.get("boms"))));
    }

    /**
     * THE NUMBERS ARE READ BACK OUT OF THE SENTENCE, and that is worth saying out loud.
     *
     * <p>{@code Settlement.note} records the account as prose — "148 tests conserved, effective
     * target 21; CRITICAL+HIGH 1 -> 1" — and not as fields, so the list either parses that sentence
     * or reads every bump's trace to build one table. On a corpus of 1439 the second is a directory
     * walk per page load, which is why the columns were empty rather than expensive.
     *
     * <p>Parsing prose is the weaker half of a trade and it is reversible: the honest fix is for the
     * settlement to carry {@code preTests}, {@code cvesBefore} and {@code cvesAfter} as fields, at
     * which point this reads them instead and old rows keep working through here. Until then a
     * changed sentence turns a column back into a dash, which is a visible failure rather than a
     * wrong number.
     */
    private static final java.util.regex.Pattern TESTS =
            java.util.regex.Pattern.compile("(\\d+) tests conserved");
    private static final java.util.regex.Pattern CVES_BEFORE =
            java.util.regex.Pattern.compile("CRITICAL\\+HIGH (\\d+) ->");
    private static final java.util.regex.Pattern CVES_AFTER =
            java.util.regex.Pattern.compile("CRITICAL\\+HIGH \\d+ -> (\\d+)");

    /** The captured number, or JSON null: absent is not zero, and a page must be able to tell. */
    private static String number(String text, java.util.regex.Pattern pattern) {
        java.util.regex.Matcher m = pattern.matcher(text == null ? "" : text);
        return m.find() ? m.group(1) : "null";
    }

    /**
     * One bump: its chain, everything it did, and what it moved.
     *
     * <p>The chain is {@link Bump#stages} with each step's speaking count filled in from the trace,
     * so the strip is walked off the same tree the harness runs. It used to be a hand-typed copy in
     * the dashboard, and then a declaration beside the program; both went stale, which is how the
     * page came to advertise a stage that had been deleted.
     */
    private String bump(String slug) {
        List<String> raw = lines(results.resolve(slug).resolve("trace.jsonl"));
        List<Map<String, String>> events = raw.stream().map(Json::row).toList();

        Map<String, Integer> spoke = new LinkedHashMap<>();
        for (Map<String, String> e : events) {
            if ("asked".equals(e.get("kind"))) {
                spoke.merge(e.getOrDefault("agent", ""), 1, Integer::sum);
            }
        }
        Map<String, String> settled = settlements().getOrDefault(slug, Map.of());

        return Json.object(
                Json.field("summary", summary(settled.isEmpty()
                        ? Map.of("bump", unslug(events), "state", "bumping") : settled)),
                Json.field("chain", Json.array(Bump.stages(), s -> Json.object(
                        Json.field("title", Json.string(s.title())),
                        Json.field("within", Json.string(s.within())),
                        Json.field("steps", Json.array(s.steps(), step -> Json.object(
                                Json.field("name", Json.string(step.name())),
                                Json.field("role", Json.string(step.role())),
                                Json.field("agent", String.valueOf(step.agent())),
                                Json.field("spoke",
                                        String.valueOf(spoke.getOrDefault(step.name(), 0))))))))),
                Json.field("events", Json.array(events, this::event)),
                Json.field("packages", packages(events)),
                Json.field("cves", cves(events)));
    }

    private String event(Map<String, String> e) {
        String kind = e.getOrDefault("kind", "");
        // The BODY is whichever field this kind of event carries it in. A page that had to know
        // which is which per kind would be a second copy of the record's shape.
        String text = first(e, "note", "what", "reply", "result", "summary", "content", "thinking",
                "itemisation");
        // AN EXCHANGE CARRIES NO SINGLE BODY. It is the wire: what went, what came back, what it
        // cost. Composing the line here rather than in the page keeps the rule that a kind's shape
        // is known in one place, and gives a reader the numbers the curated events never had.
        if ("exchange".equals(kind)) {
            // TWO EVENTS, IN THE ORDER THEY HAPPENED. The request is written when it is sent and
            // the response when it returns, so a seventeen-second call no longer files its own
            // prompt after the reasoning that prompt produced.
            String error = e.getOrDefault("error", "");
            boolean outbound = "to".equals(e.getOrDefault("direction", "back"));
            text = outbound
                    ? "→ sent, " + e.getOrDefault("messages", "0") + " message(s)\n\n"
                            + e.getOrDefault("sent", "")
                    : (error.isBlank() ? "← " : "← FAILED " + error + " · ")
                            + e.getOrDefault("in", "0") + " in / " + e.getOrDefault("out", "0")
                            + " out tokens · " + e.getOrDefault("ms", "0") + "ms"
                            + (e.getOrDefault("finish", "").isBlank() ? ""
                                    : " · " + e.get("finish"))
                            + (e.getOrDefault("tools", "").isBlank() ? ""
                                    : " · asked for " + e.get("tools"))
                            + (e.getOrDefault("got", "").isBlank() ? ""
                                    : "\n\n" + e.get("got"));
        }
        return Json.object(
                Json.field("at", String.valueOf(num(e.get("at")))),
                Json.field("kind", Json.string(kind)),
                Json.field("agent", Json.optional(e.getOrDefault("agent", ""))),
                Json.field("stage", Json.optional(e.getOrDefault("stage", ""))),
                Json.field("tool", Json.optional(e.getOrDefault("tool", ""))),
                Json.field("inTokens", String.valueOf((int) num(e.get("in")))),
                Json.field("outTokens", String.valueOf((int) num(e.get("out")))),
                Json.field("ms", String.valueOf((int) num(e.get("ms")))),
                Json.field("text", Json.string(text)));
    }

    /**
     * THE DEPENDENCIES, WITH THE MODULE THAT RESOLVED EACH ONE.
     *
     * <p>The module column is the fix for rows that looked like a rendering bug: the scan reports a
     * finding once per module, so a six-module project emitted the same package six times with no
     * way to tell the rows apart. They were six different facts wearing one label.
     */
    private String packages(List<Map<String, String>> events) {
        Map<String, String[]> before = inventory(events, "packages-before");
        Map<String, String[]> after = inventory(events, "packages-after");
        Set<String> all = new LinkedHashSet<>(before.keySet());
        all.addAll(after.keySet());
        List<String> rows = new ArrayList<>();
        for (String key : all) {
            String[] b = before.get(key);
            String[] a = after.get(key);
            String[] parts = key.split("\\|", 2);
            rows.add(Json.object(
                    Json.field("module", Json.string(parts.length > 0 ? parts[0] : "")),
                    Json.field("name", Json.string(parts.length > 1 ? parts[1] : key)),
                    Json.field("versionBefore", Json.optional(b == null ? "" : b[0])),
                    Json.field("versionAfter", Json.optional(a == null ? "" : a[0])),
                    Json.field("cvesBefore", String.valueOf(b == null ? 0 : (int) num(b[1]))),
                    // NULL WHEN THE AFTER SCAN DID NOT SEE IT, not zero. Zero renders green
                    // as "cleared", so a bump with no after scan at all reported every
                    // vulnerable dependency as fixed: assertj-core 1 -> 0 on a project that
                    // had not been scanned once. It also covers the honest case of a
                    // dependency the bump removed, which is likewise not "zero CVEs".
                    Json.field("cvesAfter",
                            a == null ? "null" : String.valueOf((int) num(a[1])))));
        }
        return "[" + String.join(",", rows) + "]";
    }

    /**
     * The counts, occurrence-based AND distinct.
     *
     * <p>Both, because they answer different questions and each alone has misled. The occurrence
     * count is what every number this corpus has already reported was measured in; the distinct
     * count is what a reader means by "how many vulnerabilities". Measured here, occurrences inflate
     * by 1.67x overall and 15.5x on a seventeen-module project, which makes the headline
     * incomparable between repositories: ranking by it ranks by module count.
     */
    private String cves(List<Map<String, String>> events) {
        Map<String, String[]> before = inventory(events, "packages-before");
        Map<String, String[]> after = inventory(events, "packages-after");
        // AN AFTER THAT WAS NEVER TAKEN IS NOT ZERO. The after scan runs only on a green
        // gate, so most bumps have no after inventory at all, and summing an empty map to 0
        // put "CRITICAL+HIGH 337 -> 0" on the page of a bump that had cleared nothing.
        boolean measured = !after.isEmpty();
        return Json.object(
                Json.field("before", String.valueOf(total(before))),
                Json.field("after", measured ? String.valueOf(total(after)) : "null"),
                Json.field("distinctBefore", String.valueOf(distinct(before))),
                Json.field("distinctAfter", measured ? String.valueOf(distinct(after)) : "null"));
    }

    /** The destinations of one package, best outcome first, already rendered as objects. */
    private static List<String> versionsOf(String name, Map<String, int[]> pairAcc,
                                           Map<String, Integer> pairBumps) {
        List<Map.Entry<String, int[]>> mine = pairAcc.entrySet().stream()
                .filter(e -> e.getKey().startsWith(name + "\u0000"))
                // A pair that was never vulnerable at either end explains nothing and there are
                // thousands of them.
                .filter(e -> e.getValue()[0] > 0 || e.getValue()[1] > 0)
                .sorted((a, b) -> {
                    int ca = a.getValue()[0] - a.getValue()[1];
                    int cb = b.getValue()[0] - b.getValue()[1];
                    return cb - ca != 0 ? cb - ca : b.getValue()[1] - a.getValue()[1];
                })
                .toList();
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, int[]> e : mine) {
            String[] p = e.getKey().split("\u0000", -1);
            out.add(Json.object(
                    Json.field("to", Json.optional(p.length > 1 ? p[1] : "")),
                    Json.field("before", String.valueOf(e.getValue()[0])),
                    Json.field("after", String.valueOf(e.getValue()[1])),
                    Json.field("bumps", String.valueOf(pairBumps.getOrDefault(e.getKey(), 0)))));
        }
        return out;
    }

    private static int total(Map<String, String[]> inventory) {
        return inventory.values().stream().mapToInt(v -> (int) num(v[1])).sum();
    }

    /** One count per (package, version), however many modules resolved it. */
    private static int distinct(Map<String, String[]> inventory) {
        Map<String, Integer> once = new LinkedHashMap<>();
        for (Map.Entry<String, String[]> e : inventory.entrySet()) {
            String name = e.getKey().contains("|")
                    ? e.getKey().substring(e.getKey().indexOf('|') + 1) : e.getKey();
            once.put(name + "@" + e.getValue()[0], (int) num(e.getValue()[1]));
        }
        return once.values().stream().mapToInt(Integer::intValue).sum();
    }

    /**
     * WHERE THE CORPUS'S CLEARED VULNERABILITIES ACTUALLY WENT.
     *
     * <p>The list page can say 2,354 -> 1,806 because every row carries its own two numbers. It
     * cannot say WHICH dependency accounts for the difference, because that lives one level down,
     * in each bump's own inventory. This reads those and adds them up two ways: by package, which
     * answers "what did raising these frameworks actually fix", and by bump, which answers "which
     * repositories did the work".
     *
     * <p>ONLY BUMPS WITH AN AFTER INVENTORY, for the same reason the totals on the list page count
     * only bumps with both numbers: the after scan runs on a green gate and nowhere else, so a
     * bump without one has a before and no comparison, and counting it would credit the corpus
     * with clearing a project that never finished.
     *
     * <p>DISTINCT, NOT OCCURRENCES. The scan reports a finding once per module that resolves the
     * dependency, which inflates by 1.67x overall and 15.5x on a seventeen-module project. Summing
     * occurrences across the corpus would rank packages by how many modules happen to use them.
     */
    private record Measured(String slug, String repo, int from, int to, int before, int after,
                            int occurrencesBefore, int occurrencesAfter, Map<String, int[]> byName,
                            Map<String, int[]> byPair) {
    }

    /** slug -> its aggregation. A settled bump's trace does not change, so this is computed once. */
    private final Map<String, Measured> measured = new java.util.concurrent.ConcurrentHashMap<>();

    private Measured measure(String slug, Map<String, String> settled) {
        Measured known = measured.get(slug);
        if (known != null) {
            return known;
        }
        List<Map<String, String>> events =
                lines(results.resolve(slug).resolve("trace.jsonl")).stream()
                        .map(Json::row).toList();
        Map<String, String[]> before = inventory(events, "packages-before");
        Map<String, String[]> after = inventory(events, "packages-after");
        if (after.isEmpty()) {
            return null;
        }
        Map<String, int[]> byName = new LinkedHashMap<>();
        fold(before, byName, 0);
        fold(after, byName, 1);
        Map<String, int[]> byPair = pairs(before, after);
        String[] parts = settled.getOrDefault("bump", "").split("\\|");
        Measured m = new Measured(slug,
                parts.length > 0 ? parts[0] : "",
                parts.length > 2 ? (int) num(parts[2]) : 0,
                parts.length > 3 ? (int) num(parts[3]) : 0,
                distinct(before), distinct(after), total(before), total(after), byName, byPair);
        measured.put(slug, m);
        return m;
    }

    /** Distinct counts per package name, summed over the versions of it this bump resolved. */
    private static void fold(Map<String, String[]> inventory, Map<String, int[]> into, int slot) {
        Map<String, Integer> once = new LinkedHashMap<>();
        for (Map.Entry<String, String[]> e : inventory.entrySet()) {
            String name = e.getKey().contains("|")
                    ? e.getKey().substring(e.getKey().indexOf('|') + 1) : e.getKey();
            once.put(name + "@" + e.getValue()[0], (int) num(e.getValue()[1]));
        }
        for (Map.Entry<String, Integer> e : once.entrySet()) {
            String name = e.getKey().substring(0, e.getKey().lastIndexOf('@'));
            into.computeIfAbsent(name, k -> new int[2])[slot] += e.getValue();
        }
    }

    /**
     * WHAT EACH DEPENDENCY ENDED UP AT, IN THIS BUMP.
     *
     * <p>A package line saying "tomcat-embed-core 238 -> 81" is the corpus's answer and not an
     * explanation: it does not say which version is the one that fixed it. The DESTINATION does —
     * everything that reached 10.1.55 carries nothing, everything still sitting on 10.1.20 carries
     * all of it — and that is the level a reader can act on, because the destination is the thing
     * they can go and set.
     *
     * <p>THE SOURCE IS DROPPED DELIBERATELY. Keeping it turned tomcat into thirteen rows to make
     * one point: seven separate versions all landing on 10.1.55 and clearing everything, which is
     * one fact written seven ways. By destination it is six rows and the same conclusion.
     *
     * <p>TWO STEPS, and the order matters. The module collapse has to happen first, on the full
     * (name, from, to), because the scan reports one finding per module that resolves the
     * dependency and those are duplicates rather than additions. Only then can the sources be
     * summed into their destination, which are genuinely different projects arriving at the same
     * place. Collapsing straight to (name, to) would silently discard every source but one.
     */
    private static Map<String, int[]> pairs(Map<String, String[]> before,
                                            Map<String, String[]> after) {
        Set<String> keys = new LinkedHashSet<>(before.keySet());
        keys.addAll(after.keySet());
        Map<String, int[]> once = new LinkedHashMap<>();
        for (String key : keys) {
            String name = key.contains("|") ? key.substring(key.indexOf('|') + 1) : key;
            String[] b = before.get(key);
            String[] a = after.get(key);
            once.put(name + "\u0000" + (b == null ? "" : b[0]) + "\u0000" + (a == null ? "" : a[0]),
                    new int[] {b == null ? 0 : (int) num(b[1]), a == null ? 0 : (int) num(a[1])});
        }
        Map<String, int[]> byDestination = new LinkedHashMap<>();
        for (Map.Entry<String, int[]> e : once.entrySet()) {
            String[] p = e.getKey().split("\u0000", -1);
            String to = p.length > 2 ? p[2] : "";
            int[] acc = byDestination.computeIfAbsent(
                    (p.length > 0 ? p[0] : "") + "\u0000" + to, k -> new int[2]);
            acc[0] += e.getValue()[0];
            acc[1] += e.getValue()[1];
        }
        return byDestination;
    }

    private String security() {
        List<Measured> all = new ArrayList<>();
        for (Map.Entry<String, Map<String, String>> e : settlements().entrySet()) {
            String state = e.getValue().getOrDefault("state", "");
            if (state.isEmpty() || "bumping".equals(state) || "queued".equals(state)) {
                continue;
            }
            Measured m = measure(e.getKey(), e.getValue());
            if (m != null) {
                all.add(m);
            }
        }
        Map<String, int[]> byName = new LinkedHashMap<>();
        Map<String, Integer> bumpsPer = new LinkedHashMap<>();
        Map<String, int[]> pairAcc = new LinkedHashMap<>();
        Map<String, Integer> pairBumps = new LinkedHashMap<>();
        int before = 0;
        int after = 0;
        int occBefore = 0;
        int occAfter = 0;
        for (Measured m : all) {
            before += m.before();
            after += m.after();
            occBefore += m.occurrencesBefore();
            occAfter += m.occurrencesAfter();
            for (Map.Entry<String, int[]> e : m.byName().entrySet()) {
                int[] acc = byName.computeIfAbsent(e.getKey(), k -> new int[2]);
                acc[0] += e.getValue()[0];
                acc[1] += e.getValue()[1];
                bumpsPer.merge(e.getKey(), 1, Integer::sum);
            }
            for (Map.Entry<String, int[]> e : m.byPair().entrySet()) {
                int[] acc = pairAcc.computeIfAbsent(e.getKey(), k -> new int[2]);
                acc[0] += e.getValue()[0];
                acc[1] += e.getValue()[1];
                pairBumps.merge(e.getKey(), 1, Integer::sum);
            }
        }
        // ONLY WHAT WAS EVER VULNERABLE. 2,883 packages resolve across this corpus and all but a
        // couple of hundred carry no finding at any point; sending them is a megabyte of rows
        // reading 0 -> 0, and a table nobody can scan is the same as no table.
        List<Map.Entry<String, int[]>> pkgs = new ArrayList<>(byName.entrySet().stream()
                .filter(e -> e.getValue()[0] > 0 || e.getValue()[1] > 0).toList());
        // Best outcome first, exactly as the per-bump table orders: cleared, then what is left.
        pkgs.sort((a, b) -> {
            int ca = a.getValue()[0] - a.getValue()[1];
            int cb = b.getValue()[0] - b.getValue()[1];
            return cb - ca != 0 ? cb - ca
                    : b.getValue()[1] - a.getValue()[1] != 0 ? b.getValue()[1] - a.getValue()[1]
                            : a.getKey().compareTo(b.getKey());
        });
        List<Measured> bumps = new ArrayList<>(all);
        bumps.sort((a, b) -> (b.before() - b.after()) - (a.before() - a.after()));
        int removed = before - after;
        return Json.object(
                Json.field("before", String.valueOf(before)),
                Json.field("after", String.valueOf(after)),
                Json.field("removed", String.valueOf(removed)),
                Json.field("measured", String.valueOf(all.size())),
                // THE LIST PAGE'S NUMBERS, so the two can be reconciled rather than argued about.
                // Its tallies are parsed out of each settlement's sentence, which records
                // OCCURRENCES; everything else here is distinct. Same corpus, different unit, and
                // a reader who saw 2,354 there and 1,366 here with no explanation would be right
                // to distrust both.
                Json.field("occurrencesBefore", String.valueOf(occBefore)),
                Json.field("occurrencesAfter", String.valueOf(occAfter)),
                Json.field("rate", before == 0 ? "null"
                        : String.valueOf(Math.round((removed * 100.0f) / before))),
                Json.field("byPackage", Json.array(pkgs, e -> Json.object(
                        Json.field("name", Json.string(e.getKey())),
                        Json.field("before", String.valueOf(e.getValue()[0])),
                        Json.field("after", String.valueOf(e.getValue()[1])),
                        Json.field("bumps",
                                String.valueOf(bumpsPer.getOrDefault(e.getKey(), 0))),
                        Json.field("versions",
                                Json.array(versionsOf(e.getKey(), pairAcc, pairBumps), v -> v))))),
                Json.field("byBump", Json.array(bumps, m -> Json.object(
                        Json.field("slug", Json.string(m.slug())),
                        Json.field("repo", Json.string(m.repo())),
                        Json.field("from", String.valueOf(m.from())),
                        Json.field("to", String.valueOf(m.to())),
                        Json.field("before", String.valueOf(m.before())),
                        Json.field("after", String.valueOf(m.after()))))));
    }

    /** {@code module<TAB>name<TAB>version<TAB>cves} rows, as the scan stage records them. */
    private Map<String, String[]> inventory(List<Map<String, String>> events, String stage) {
        Map<String, String[]> out = new LinkedHashMap<>();
        for (Map<String, String> e : events) {
            if (!stage.equals(e.get("stage"))) {
                continue;
            }
            for (String line : e.getOrDefault("what", "").split("\n")) {
                String[] p = line.split("\t");
                if (p.length >= 4) {
                    out.put(p[0] + "|" + p[1], new String[] {p[2], p[3]});
                }
            }
        }
        return out;
    }

    /**
     * SAVE OR REVERT ONE PROMPT.
     *
     * <p>An edit replaces the built-in entirely; there is no merge, because a prompt half from the
     * code and half from a box is a prompt nobody can read in one place — and reading it in one
     * place is how anybody works out why an agent did what it did.
     *
     * <p>The reply is the state AFTER the write, read back from the store, so the page shows what
     * landed rather than what was sent.
     */
    private void prompt(HttpExchange x) throws IOException {
        Path root = (results.getParent() == null ? results : results.getParent()).resolve("prompts");
        String body = new String(x.getRequestBody().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        String name = field(body, "name");
        Hop hop = hopOf(field(body, "hop"));
        boolean reverting = body.contains("\"revert\"") && body.contains("true");

        if (name.isBlank()) {
            Zone.send(x, 400, "application/json; charset=utf-8",
                    Json.object(Json.field("why", Json.string("no agent named")))
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return;
        }
        if (reverting) {
            Prompts.revert(root, name, hop);
        } else {
            String text = field(body, "text");
            if (text.isBlank()) {
                // A SAVE OF NOTHING IS A REVERT SPELLED WRONG. An agent handed an empty prompt does
                // something arbitrary, and the reader who cleared the box meant "use the built-in".
                Zone.send(x, 400, "application/json; charset=utf-8",
                        Json.object(Json.field("why", Json.string(
                                "an empty prompt is not a prompt; use revert to go back to the "
                                        + "code's own")))
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                return;
            }
            Prompts.save(root, name, hop, text);
        }
        Zone.json(x, Json.object(
                Json.field("name", Json.string(name)),
                Json.field("edited", String.valueOf(Prompts.edited(root, name, hop))),
                Json.field("prompt", Json.string(Prompts.edited(root, name, hop)
                        ? Prompts.override(name, hop) : ""))));
    }

    /** One JSON string field out of a small body. The bodies here are three fields deep. */
    private static String field(String body, String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + name + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(body);
        if (!m.find()) {
            return "";
        }
        return m.group(1).replace("\\n", "\n").replace("\\t", "\t")
                .replace("\\r", "\r").replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static Hop hopOf(String hop) {
        String[] pair = (hop.isBlank() ? "17-21" : hop).split("-");
        return new Hop(Integer.parseInt(pair[0]),
                Integer.parseInt(pair.length > 1 ? pair[1] : pair[0]));
    }

    /** Every prompt for one hop, in the order the chain reaches them. */
    private String settings(String hop) {
        String[] pair = (hop.isBlank() ? "17-21" : hop).split("-");
        Hop h = new Hop(Integer.parseInt(pair[0]), Integer.parseInt(pair.length > 1 ? pair[1] : pair[0]));
        Map<String, String> stageOf = new LinkedHashMap<>();
        Map<String, String> roleOf = new LinkedHashMap<>();
        // THE CHAIN IS A PROGRAM AND IT HAS BLOCKS. modules and the repair campaign each run a
        // sub-chain between their planner and their verifier, and a flat list of agents says
        // nothing about that: it reads as a row of stages rather than two blocks with bodies. The
        // nesting is the tree's own now, walked rather than declared.
        Map<String, String> withinOf = new LinkedHashMap<>();
        // What the loop itself is, so the body can be shown between a header and a closer rather
        // than merely indented under a name.
        Map<String, String> loopOf = new LinkedHashMap<>();
        // How often a stage runs, which nesting does not say and a reader will otherwise guess.
        Map<String, String> repeatsOf = new LinkedHashMap<>();
        // Which bill of materials the stage works from, so the two pages connect.
        Map<String, String> readsOf = new LinkedHashMap<>();
        List<Shape.Stage> shape = Bump.stages();
        for (Shape.Stage s : shape) {
            for (Shape.Step step : s.steps()) {
                stageOf.put(step.name(), s.title());
                roleOf.put(step.name(), step.role());
                withinOf.put(step.name(), s.within());
                repeatsOf.put(step.name(), s.repeats());
                readsOf.put(step.name(), s.reads());
                if (!step.agent()) {
                    loopOf.put(s.title(), step.name());
                }
            }
        }
        Path root = (results.getParent() == null ? results : results.getParent())
                .resolve("prompts");
        // IN THE ORDER THE BUMP REACHES THEM, WHICH IS NOT THE ORDER THE FACTORY HANDS THEM BACK.
        // Measured on the live page: after-pins arrived after the repair agents, and
        // modules-verifier after that, so the settings page drew the module block missing its third
        // pass and the loop closing in the wrong place. The order is the tree's, walked above; the
        // factory's order is an accident of how the methods happen to be listed, and nothing should
        // depend on it.
        List<String> order = Shape.agentNames(shape);
        List<SubAgentDefinition> defined = new ArrayList<>(Agents.forHop(h, results));
        // ON THE STEM, BECAUSE THE TREE CANNOT NAME A PLATFORM. Fourteen of these agents exist once
        // per platform and are named before-pins-planner@spring-boot and so on, while the shape is
        // walked before any module has been looked at and can only ever say before-pins-planner.
        // Sorting on the full name would send all forty-two of them to the end of the page, in the
        // order the factory happens to build them, which is the failure the sort exists to prevent.
        // The sort is stable, so one agent's three platforms stay in the order definitions() lists
        // them.
        defined.sort(java.util.Comparator.comparingInt(d -> {
            int at = order.indexOf(Agents.stem(d.name()));
            // An agent the chain does not name goes last rather than first, so a new one shows up
            // as obviously unplaced instead of quietly heading the list.
            return at < 0 ? order.size() : at;
        }));
        return Json.array(defined, d -> {
            // BOTH TEXTS TRAVEL. The page cannot offer a revert without something to revert TO, and
            // a reader comparing an edit to the built-in should not have to redeploy to see it.
            boolean edited = Prompts.edited(root, d.name(), h);
            // WHERE IT SITS IS THE STEM'S, WHAT IT SAYS IS ITS OWN. The three platforms of one
            // agent run in the same stage, in the same role, as often as each other; what differs
            // is the text, and an edit is stored against the full name because the three are edited
            // apart.
            String stem = Agents.stem(d.name());
            return Json.object(
                    Json.field("name", Json.string(d.name())),
                    Json.field("role", Json.string(roleOf.getOrDefault(stem, "doer"))),
                    Json.field("stage", Json.string(stageOf.getOrDefault(stem, ""))),
                    // The stage this one runs inside, empty at the top level.
                    Json.field("within", Json.string(withinOf.getOrDefault(stem, ""))),
                    Json.field("repeats", Json.string(repeatsOf.getOrDefault(stem, ""))),
                    Json.field("reads", Json.string(readsOf.getOrDefault(stem, ""))),
                    // HOW MANY ROWS THAT LIST ACTUALLY HAS, counted from the file rather than
                    // written down beside it. A number typed into a page is a number that goes
                    // stale the first time somebody edits the list it describes.
                    Json.field("pins", String.valueOf(
                            readsOf.getOrDefault(stem, "").isEmpty() ? 0
                                    : Bom.of(h, readsOf.get(stem)).size())),
                    // The deterministic step that IS the loop, on the stage that owns one.
                    Json.field("loop", Json.string(
                            loopOf.getOrDefault(stageOf.getOrDefault(stem, ""), ""))),
                    Json.field("description", Json.string(d.description())),
                    Json.field("builtIn", Json.string(d.systemPrompt())),
                    Json.field("edited", String.valueOf(edited)),
                    Json.field("prompt", Json.string(
                            edited ? Prompts.override(d.name(), h) : d.systemPrompt())));
        });
    }

    /**
     * HOW MANY BUMPS RUN AT ONCE, and the one setting on this page that is genuinely live.
     *
     * <p>{@code run.sh} re-reads {@code max_lanes} at the top of every round rather than at launch,
     * so a sweep starving the GPU can be throttled without stopping it. That is what makes this
     * writable when nothing else here is: the mechanism already existed and was reachable only by
     * someone with a shell on the box.
     *
     * <p>THE SERVER CLAMPS, and the reply is what it kept rather than what was sent. A page that
     * echoed the request would show 40 lanes on a box that will run 16, and the reader would not
     * find out until the sweep did not speed up.
     */
    private void run(HttpExchange x) throws IOException {
        Path lanes = results.getParent() == null ? results.resolve("max_lanes")
                : results.getParent().resolve("max_lanes");
        if ("POST".equalsIgnoreCase(x.getRequestMethod())) {
            String body = new String(x.getRequestBody().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            java.util.regex.Matcher m =
                    java.util.regex.Pattern.compile("\"lanes\"\\s*:\\s*(\\d+)").matcher(body);
            if (m.find()) {
                int kept = Math.max(1, Math.min(16, Integer.parseInt(m.group(1))));
                Files.writeString(lanes, kept + "\n");
            }
        }
        String now = Files.isRegularFile(lanes)
                ? Files.readString(lanes).trim() : "";
        Zone.json(x, Json.object(
                Json.field("lanes", now.matches("\\d+") ? now : "null"),
                Json.field("min", "1"),
                Json.field("max", "16"),
                Json.field("turns", Json.string(envOr("BJV_MODULE_TURNS", "3"))),
                // THE CEILING THAT ACTUALLY BINDS, and it was served nowhere. Turns and steps are
                // per module; this is the whole bump's allowance, and it is what stops a
                // twenty-module repository ordering seven hundred repair steps.
                Json.field("repairBudget", Json.string(envOr("BJV_REPAIR_BUDGET", "192"))),
                Json.field("steps", Json.string(envOr("BJV_STEPS", "6"))),
                Json.field("hangGuardMinutes", Json.string(envOr("BJV_HANG_GUARD", ""))),
                // WHERE DEPENDENCIES COME FROM, ON A PAGE, because until now it was knowable only
                // by reading settings.xml on the host. Maven learns the mirror from that file and
                // never needed it named anywhere else; Gradle cannot read a Maven settings file, so
                // the moment a recipe has to run under Gradle the URL has to exist as configuration
                // rather than as a line inside a file handed to one build tool.
                //
                // Read-only here, like the model endpoint beside it. A repository URL that a web
                // page can rewrite is a supply chain a web page can redirect.
                Json.field("repository", Json.string(envOr("BJV_REPO_URL", ""))),
                Json.field("mavenSettings", Json.string(envOr("BJV_SETTINGS", ""))),
                Json.field("mavenCache", Json.string(envOr("BJV_M2", ""))),
                Json.field("gradleCache", Json.string(envOr("BJV_GRADLE_RO", ""))),
                Json.field("gradleDists", Json.string(envOr("BJV_GRADLE_DISTS", ""))),
                Json.field("offline", String.valueOf(!envOr("BJV_SETTINGS", "").isBlank()))));
    }

    /**
     * THE ENDPOINT, AND DELIBERATELY NOT THE KEY.
     *
     * <p>The sibling tool renders its API key into this page, with the reveal and copy buttons that
     * cannot work otherwise, and its own mount contract calls that out as the part a shell author
     * has to read twice: defensible for one person behind their own proxy, not on a portal several
     * developers reach. This tool is the second one mounted, so it takes the other side of that
     * trade — whether a key is SET travels, and the key never does. There is no reveal button
     * because there is nothing behind it.
     */
    private String model() {
        String key = System.getenv().getOrDefault("OC_KEY", "");
        return Json.object(
                Json.field("keySet", String.valueOf(!key.isBlank())),
                // NO DEFAULT. These were the author's own endpoint, the same pins removed from
                // Model, and a page that invents a plausible value for unset configuration is a
                // page that hides a broken deployment.
                Json.field("model", Json.string(envOr("OC_MODEL", ""))),
                Json.field("endpoint", Json.string(envOr("OC_BASE", ""))),
                Json.field("patienceMinutes", Json.string(envOr("BJV_PATIENCE_MINUTES", "240"))));
    }

    /**
     * UPLOAD A REGISTRY, AND BE TOLD WHAT IT DID.
     *
     * <p>The body is the file's text, not a multipart form: the page reads the file and posts what
     * is in it, so one path serves both the upload button and the paste box, and no multipart parser
     * has to exist in a container that already runs strangers' code.
     *
     * <p>WHAT COMES BACK IS WHAT LANDED. The count of rows accepted, the count actually new, and
     * every line that would not parse with its number and reason. A loader that answers "ok" to a
     * file half of which it discarded is the failure this is built against.
     *
     * <p>A GET says where it would go and whether a sweep is live, because "this takes effect on the
     * next round" and "this takes effect the next time somebody launches a sweep" are different
     * promises and the reader deserves to know which one they are getting.
     */
    private void registry(HttpExchange x) throws IOException {
        Path root = results.getParent() == null ? results : results.getParent();
        Path active = root.resolve("active_manifest");
        Path target = null;
        if (Files.isRegularFile(active)) {
            // A BASENAME, RESOLVED AGAINST THE RUN ROOT. run.sh runs on the host and this runs in a
            // container that mounts the same directory somewhere else, so a path written by one
            // does not resolve for the other — and the failure is silent: the upload lands in a
            // file no sweep is reading and reports success.
            String name = Files.readString(active).trim();
            Path named = root.resolve(name.contains("/")
                    ? name.substring(name.lastIndexOf('/') + 1) : name);
            if (Files.isRegularFile(named)) {
                target = named;
            }
        }
        // No sweep running: the upload still has somewhere to go, and the next launch can be
        // pointed at it. Refusing the upload because nothing is running would mean a registry can
        // only be loaded onto a machine that is already busy.
        Path fallback = root.resolve("manifest.uploaded.tsv");

        if (!"POST".equalsIgnoreCase(x.getRequestMethod())) {
            Zone.json(x, Json.object(
                    Json.field("sweepLive", String.valueOf(target != null)),
                    Json.field("target", Json.string(
                            (target == null ? fallback : target).getFileName().toString()))));
            return;
        }

        String text = new String(x.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        Registry.Parsed parsed = Registry.parse(text);
        int added = 0;
        if (!parsed.empty()) {
            added = Registry.mergeInto(target == null ? fallback : target, parsed.rows());
            // Beside the manifest, never inside results/: that directory is what the page serves.
            Registry.recordOrigins(root.resolve("origins.tsv"), parsed.rows());
            Registry.recordKeys(root.resolve("credentials.tsv"), parsed.rows());
        }
        Zone.json(x, Json.object(
                Json.field("accepted", String.valueOf(parsed.rows().size())),
                Json.field("added", String.valueOf(added)),
                // COUNTS ONLY. No response here or anywhere carries a key's value.
                Json.field("keyed", String.valueOf(parsed.keyed())),
                Json.field("unpinned", String.valueOf(parsed.unpinned())),
                Json.field("sweepLive", String.valueOf(target != null)),
                Json.field("target", Json.string(
                        (target == null ? fallback : target).getFileName().toString())),
                Json.field("rejected", Json.array(parsed.rejected(), r -> Json.object(
                        Json.field("line", String.valueOf(r.line())),
                        Json.field("text", Json.string(r.text())),
                        Json.field("why", Json.string(r.why())))))));
    }

    /** What the sweep is working through: the queue, and what it is made of. */
    private String subject() {
        List<String> queue = lines(results.resolve("queue.tsv"));
        Map<String, Integer> hops = new LinkedHashMap<>();
        for (String row : queue) {
            String[] c = row.split("\t");
            if (c.length >= 5) {
                hops.merge(c[3] + " → " + c[4], 1, Integer::sum);
            }
        }
        return Json.object(
                Json.field("queued", String.valueOf(queue.size())),
                Json.field("settled", String.valueOf(settlements().size())),
                Json.field("hops", Json.map(hops.entrySet().stream().collect(
                        LinkedHashMap::new, (m, e) -> m.put(e.getKey(), String.valueOf(e.getValue())),
                        Map::putAll))));
    }

    /** The watcher that sees what one bump cannot, and what it has found. */
    private String supervisor() {
        List<String> findings = lines(results.resolve("findings.jsonl"));
        List<String> postponed = lines(results.resolve("postponed"));
        return Json.object(
                Json.field("everyMinutes", Json.string(envOr("BJV_SUPERVISOR_MINUTES", "20"))),
                Json.field("findings", String.valueOf(findings.size())),
                Json.field("postponed", String.valueOf(postponed.size())),
                Json.field("latest", Json.array(
                        findings.subList(Math.max(0, findings.size() - 8), findings.size()),
                        line -> {
                            Map<String, String> r = Json.row(line);
                            return Json.object(
                                    Json.field("at", String.valueOf(num(r.get("at")))),
                                    Json.field("bump", Json.string(r.getOrDefault("bump", ""))),
                                    Json.field("kind", Json.string(r.getOrDefault("kind", ""))),
                                    Json.field("what", Json.string(first(r, "what", "note"))),
                                    Json.field("held",
                                            String.valueOf("true".equals(r.get("held")))));
                        })));
    }

    private static String envOr(String name, String fallback) {
        String v = System.getenv().getOrDefault(name, "");
        return v.isBlank() ? fallback : v;
    }

    private static String first(Map<String, String> row, String... keys) {
        for (String k : keys) {
            String v = row.get(k);
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return "";
    }

    /** The directory name a bump's trace lives under: the key with everything unsafe flattened. */
    private static String slug(String bump) {
        return bump.replaceAll("[^A-Za-z0-9]+", "_");
    }

    /** A bump that has not settled yet still has a key, in every event it wrote. */
    private static String unslug(List<Map<String, String>> events) {
        return events.stream().map(e -> e.getOrDefault("bump", "")).filter(s -> !s.isBlank())
                .findFirst().orElse("");
    }

    private static long num(String s) {
        try {
            return s == null || s.isBlank() ? 0 : Long.parseLong(s.trim());
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }

    private static List<String> lines(Path p) {
        try {
            return Files.isRegularFile(p) ? Files.readAllLines(p) : List.of();
        } catch (IOException unreadable) {
            return List.of();
        }
    }
}
