package tech.mikhailov.bjv.web;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import com.sun.net.httpserver.HttpExchange;

import tech.mikhailov.bjv.agent.Json;

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
final class Feed {

    private final Results results;
    private final Corpus corpus;

    Feed(Path results, Corpus corpus) {
        this.results = new Results(results);
        this.corpus = corpus;
    }

    void open(HttpExchange x, String slug, String have) throws IOException {
        x.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
        x.getResponseHeaders().add("Cache-Control", "no-store");
        // A PROXY THAT BUFFERS AN EVENT STREAM DELIVERS IT ALL AT THE END, which looks exactly like
        // a server that never sent anything.
        x.getResponseHeaders().add("X-Accel-Buffering", "no");
        x.sendResponseHeaders(200, 0);
        Path trace = slug == null || slug.isBlank() ? null : results.trace(slug);
        // WHAT THE READER ALREADY HAS. The page loads the record from /api/bump and then subscribes;
        // streaming from byte zero would put every one of those events on the screen a second time.
        // The trace is append-only, so the line the caller has counted to is a stable place to
        // start, and unlike a byte offset the page can compute it without knowing the file.
        long from = trace == null ? 0 : after(trace, have);
        long lastSeen = -1;
        long beat = System.currentTimeMillis();
        try (OutputStream out = x.getResponseBody()) {
            // BOUNDED, because a browser tab left open for a week should not pin a thread for a
            // week. The client reconnects by itself, so an hour is a ceiling and not a limit on
            // how long a reader may watch.
            long until = System.currentTimeMillis() + Duration.ofHours(1).toMillis();
            while (System.currentTimeMillis() < until) {
                if (trace != null) {
                    from = tail(out, trace, from);
                } else {
                    long moved = movedAt();
                    if (moved != lastSeen) {
                        lastSeen = moved;
                        write(out, "changed", corpus.overview());
                    }
                }
                if (System.currentTimeMillis() - beat > 20_000) {
                    // A COMMENT LINE KEEPS THE CONNECTION ALIVE without being an event, which is
                    // what stops an idle proxy closing a stream that is simply waiting.
                    out.write(": still here\n\n".getBytes(StandardCharsets.UTF_8));
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
        try (var reader = Files.newBufferedReader(trace, StandardCharsets.UTF_8)) {
            String line;
            while (seen < lines && (line = reader.readLine()) != null) {
                offset += line.getBytes(StandardCharsets.UTF_8).length + 1;
                seen += 1;
            }
        } catch (IOException unreadable) {
            return 0;
        }
        return offset;
    }

    /** New lines since {@code from}, sent as they arrive; returns where to read from next. */
    private static long tail(OutputStream out, Path trace, long from) throws IOException {
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
            String text = new String(buffer, 0, read, StandardCharsets.UTF_8);
            int lastBreak = text.lastIndexOf('\n');
            if (lastBreak < 0) {
                // A PARTIAL LINE IS NOT AN EVENT. The writer is appending right now; wait for it.
                return from;
            }
            for (String line : text.substring(0, lastBreak).split("\n")) {
                if (!line.isBlank()) {
                    write(out, "trace", Detail.event(Json.row(line)));
                }
            }
            return from + text.substring(0, lastBreak + 1)
                    .getBytes(StandardCharsets.UTF_8).length;
        }
    }

    /** The newest write anywhere under results, which is what "something moved" means. */
    private long movedAt() {
        long newest = 0;
        try (var dirs = Files.list(results.dir())) {
            for (Path dir : dirs.toList()) {
                Path trace = dir.resolve("trace.jsonl");
                if (Files.isRegularFile(trace)) {
                    newest = Math.max(newest, Files.getLastModifiedTime(trace).toMillis());
                }
            }
            Path settled = results.dir().resolve("settlements.jsonl");
            if (Files.isRegularFile(settled)) {
                newest = Math.max(newest, Files.getLastModifiedTime(settled).toMillis());
            }
        } catch (IOException unreadable) {
            return newest;
        }
        return newest;
    }

    private static void write(OutputStream out, String name, String json) throws IOException {
        // ONE LINE PER FIELD, and the data line must not contain a newline of its own: the format
        // ends an event at a blank line, so an embedded one truncates the payload.
        out.write(("event: " + name + "\ndata: " + json.replace("\n", " ") + "\n\n")
                .getBytes(StandardCharsets.UTF_8));
        out.flush();
    }
}
