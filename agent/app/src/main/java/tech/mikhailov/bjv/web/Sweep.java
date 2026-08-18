package tech.mikhailov.bjv.web;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * THE SWEEP AS ONE THING, which nothing inside a bump can see.
 *
 * <p>Every agent here reasons about the bump it lives in. That is right for the work and blind to
 * the failures that only show up across runs: a critic that answers in one word, a lane that has
 * written nothing for two hours, the same wall cleared forty times by hand because the deterministic
 * table never learned it. Those cost more than any single bad bump and no one is looking for them,
 * because looking requires standing outside.
 *
 * <p>This reads the results tree the way the dashboard does, and the reason it is separate from the
 * dashboard is that a page renders what happened while a supervisor has to decide what to do about
 * it.
 */
final class Sweep {

    /** One bump, as it looks from outside: where it is, how long it has been there, and whether it moved. */
    record Lane(String slug, String bump, String repo, String hop, String state, String stage,
                long startedAt, long lastEventAt, int attempts, int events, boolean live,
                String postponedWhy) {

        long ageMinutes(long now) {
            return lastEventAt <= 0 ? -1 : (now - lastEventAt) / 60_000;
        }

        long runningMinutes(long now) {
            return startedAt <= 0 ? -1 : (now - startedAt) / 60_000;
        }

        boolean settled() {
            return state != null && !state.isBlank() && !"bumping".equals(state);
        }

        boolean postponed() {
            return postponedWhy != null;
        }
    }

    private final Path results;

    Sweep(Path results) {
        this.results = results;
    }

    Path results() {
        return results;
    }

    /** Where a postponement is recorded, so the launcher and the supervisor agree on what it means. */
    Path postponedDir() {
        return results.resolve("postponed");
    }

    // ---- reading ----

    List<Lane> lanes() {
        List<Lane> out = new ArrayList<>();
        if (!Files.isDirectory(results)) {
            return out;
        }
        List<String> running = claimed();
        try (var dirs = Files.list(results)) {
            for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                String slug = dir.getFileName().toString();
                if (slug.startsWith(".") || slug.equals("claims") || slug.equals("postponed")) {
                    continue;
                }
                Path trace = dir.resolve("trace.jsonl");
                if (!Files.isReadable(trace)) {
                    continue;
                }
                out.add(read(slug, trace, running));
            }
        } catch (IOException unreadable) {
            return out;
        }
        out.sort(Comparator.comparing(Lane::repo, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    private Lane read(String slug, Path trace, List<String> running) {
        String bump = "";
        String state = "";
        String stage = "";
        long first = 0;
        long last = 0;
        int attempts = 0;
        int events = 0;
        try (var lines = Files.lines(trace)) {
            for (String row : lines.toList()) {
                events++;
                long at = number(row, "at");
                if (at > 0) {
                    first = first == 0 ? at : first;
                    last = at;
                }
                if (bump.isEmpty()) {
                    bump = text(row, "bump");
                }
                String s = text(row, "stage");
                if (!s.isBlank()) {
                    stage = s;
                }
                String settledState = text(row, "state");
                if (!settledState.isBlank() && row.contains("\"kind\":\"settled\"")) {
                    state = settledState;
                }
                if (row.contains("survey:")) {
                    attempts++;
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            // A trace being appended to while it is read is normal; take what parsed.
        }
        String[] parts = bump.split("\\|");
        String repo = parts.length > 0 ? parts[0] : slug;
        String hop = parts.length > 3 ? parts[2] + "->" + parts[3] : "";
        boolean live = running.contains(slug);
        return new Lane(slug, bump, repo, hop, state, stage, first, last,
                Math.max(attempts, 1), events, live, postponedReason(slug));
    }

    /**
     * IS THIS BUMP SET ASIDE, ASKED THE WAY THE LAUNCHER ASKS IT.
     *
     * <p>run.sh tests {@code [ -e "$RESULTS/postponed/$1" ]} (run.sh:115), which is existence and
     * not regularity, so this asks exactly that and nothing stricter. A reader that called a marker
     * absent because it was a directory, a symlink or a socket would disagree with the process the
     * marker exists to stop, and the disagreement would show up as a button that reported nothing
     * had happened while a lane was already stopping. {@link #postponedReason} stays stricter
     * because it goes on to read the file's contents.
     */
    boolean postponed(String slug) {
        return Files.exists(postponedDir().resolve(slug));
    }

    /** The reason a bump was set aside, or null if it was not. */
    String postponedReason(String slug) {
        Path marker = postponedDir().resolve(slug);
        if (!Files.isRegularFile(marker)) {
            return null;
        }
        try {
            String why = Files.readString(marker).strip();
            return why.isEmpty() ? "no reason recorded" : why;
        } catch (IOException unreadable) {
            return "unreadable";
        }
    }

    void postpone(String slug, String why) throws IOException {
        Files.createDirectories(postponedDir());
        Files.writeString(postponedDir().resolve(slug), why);
    }

    boolean resume(String slug) throws IOException {
        return Files.deleteIfExists(postponedDir().resolve(slug));
    }

    /**
     * Which bumps are in flight, from the claims the launcher heartbeats.
     *
     * <p>NOTHING HERE ASKS THE DOCKER DAEMON, and that is the point. A watcher that could stop a
     * lane needed the socket, and a socket in the same container as a page on the public internet
     * is a bad trade for one fewer container. The lane heartbeats its own claim every thirty
     * seconds and stops itself when a postponement appears, so liveness is a file's age and the
     * kill belongs to the process that was already holding the container name.
     *
     * <p>A claim older than the heartbeat is a lane that died without releasing it, which reads as
     * not running -- the same conclusion the daemon would have given, from a cheaper question.
     */
    private static final long HEARTBEAT_STALE_MS = 180_000;

    private List<String> claimed() {
        Path claims = results.resolve("claims");
        if (!Files.isDirectory(claims)) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        List<String> held = new ArrayList<>();
        try (var files = Files.list(claims)) {
            for (Path f : files.toList()) {
                if (now - Files.getLastModifiedTime(f).toMillis() < HEARTBEAT_STALE_MS) {
                    held.add(f.getFileName().toString());
                }
            }
        } catch (IOException unreadable) {
            return List.of();
        }
        return held;
    }

    // ---- the digest a supervisor reads ----

    /**
     * The sweep in a page, ordered so the things worth acting on are at the top.
     *
     * <p>A supervisor that has to read 1439 rows to notice one stalled lane will not notice it. The
     * running lanes come first with their ages, then whatever was recently settled, then the counts.
     */
    String digest(long now) {
        List<Lane> lanes = lanes();
        StringBuilder out = new StringBuilder();

        List<Lane> live = lanes.stream().filter(Lane::live).toList();
        out.append("RUNNING NOW (").append(live.size()).append(")\n");
        if (live.isEmpty()) {
            out.append("  nothing is running\n");
        }
        for (Lane l : live) {
            out.append(String.format(Locale.ROOT,
                    "  %-46s %-7s stage=%-16s running=%dm silent=%dm attempts=%d%n",
                    l.repo(), l.hop(), l.stage(), l.runningMinutes(now), l.ageMinutes(now),
                    l.attempts()));
        }

        List<Lane> stuck = lanes.stream()
                .filter(l -> !l.live() && !l.settled() && !l.postponed())
                .toList();
        if (!stuck.isEmpty()) {
            out.append("\nSTARTED AND NEITHER RUNNING NOR SETTLED (").append(stuck.size())
                    .append(") -- these died without filing a verdict\n");
            for (Lane l : stuck.stream().limit(15).toList()) {
                out.append(String.format(Locale.ROOT, "  %-46s %-7s last stage=%-16s silent=%dm%n",
                        l.repo(), l.hop(), l.stage(), l.ageMinutes(now)));
            }
        }

        List<Lane> postponed = lanes.stream().filter(Lane::postponed).toList();
        if (!postponed.isEmpty()) {
            out.append("\nPOSTPONED (").append(postponed.size()).append(")\n");
            for (Lane l : postponed.stream().limit(15).toList()) {
                out.append("  ").append(l.repo()).append("  ").append(l.postponedWhy()).append('\n');
            }
        }

        List<Lane> settled = lanes.stream().filter(Lane::settled)
                .sorted(Comparator.comparingLong(Lane::lastEventAt).reversed()).toList();
        out.append("\nMOST RECENTLY SETTLED (of ").append(settled.size()).append(")\n");
        for (Lane l : settled.stream().limit(12).toList()) {
            out.append(String.format(Locale.ROOT, "  %-46s %-7s %-22s took=%dm attempts=%d%n",
                    l.repo(), l.hop(), l.state(), (l.lastEventAt() - l.startedAt()) / 60_000,
                    l.attempts()));
        }

        out.append("\nVERDICTS SO FAR\n");
        settled.stream().collect(java.util.stream.Collectors.groupingBy(Lane::state,
                        java.util.TreeMap::new, java.util.stream.Collectors.counting()))
                .forEach((s, n) -> out.append("  ").append(n).append("  ").append(s).append('\n'));
        return out.toString();
    }

    // ---- the smallest json reading that works on rows this process wrote ----

    static String text(String row, String key) {
        String needle = "\"" + key + "\":\"";
        int at = row.indexOf(needle);
        if (at < 0) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (int i = at + needle.length(); i < row.length(); i++) {
            char c = row.charAt(i);
            if (c == '\\' && i + 1 < row.length()) {
                out.append(row.charAt(++i));
                continue;
            }
            if (c == '"') {
                break;
            }
            out.append(c);
        }
        return out.toString();
    }

    static long number(String row, String key) {
        String value = text(row, key);
        try {
            return value.isBlank() ? 0 : Long.parseLong(value);
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }
}
