package tech.mikhailov.bjv.agent;

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
        List<String> running = liveLanes();
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
        boolean live = running.stream().anyMatch(name -> name.endsWith(shortSlug(slug)));
        return new Lane(slug, bump, repo, hop, state, stage, first, last,
                Math.max(attempts, 1), events, live, postponedReason(slug));
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
     * The container names currently running, so "live" is a fact rather than an inference.
     *
     * <p>A trace's last row says when it last WROTE, which is not the same as whether it is alive: a
     * lane thinking for forty minutes writes nothing and looks identical to one whose container
     * died an hour ago. Only the daemon knows, so it gets asked.
     */
    private List<String> liveLanes() {
        try {
            Shell.Output out = Shell.run(results, java.util.Map.of(),
                    java.time.Duration.ofSeconds(30), "docker", "ps", "--format", "{{.Names}}");
            if (!out.ok()) {
                return List.of();
            }
            return out.text().lines().filter(n -> n.startsWith("bjvagent_")).toList();
        } catch (IOException | InterruptedException unreachable) {
            Thread.currentThread().interrupt();
            return List.of();
        }
    }

    /** The launcher names containers after the manifest id, which is the slug's own tail. */
    static String shortSlug(String slug) {
        int under = slug.lastIndexOf("_rr_");
        return under < 0 ? slug : slug.substring(under + 1);
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
