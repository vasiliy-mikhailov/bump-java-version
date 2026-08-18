package tech.mikhailov.bjv.web;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tech.mikhailov.bjv.engine.Json;

/**
 * THE WHOLE CORPUS, NOT JUST THE PART THAT HAS STARTED.
 *
 * <p>This listed settlements only, so a sweep of 1439 repositories showed four rows: the ones a
 * lane had already picked up. Everything still waiting was invisible, and the page that exists to
 * answer "how far has this got" could not show the denominator.
 *
 * <p>{@code run.sh} has written {@code queue.tsv} for the dashboard all along, with a comment
 * saying why — "a page built only from settlements can never show the work that has not started
 * yet" — and nothing read it. So the queue is the list, and a settlement overlays the row it
 * belongs to. A repository with no settlement is queued, which is a state like any other.
 *
 * <p>One row is one bump, and everything a reader sorts, filters or watches on is a field of it.
 * The record page renders the same row through {@link #summary(Map)}, so the detail a reader
 * arrives at cannot disagree with the line they clicked.
 */
final class Corpus {

    private final Results results;
    private final Compliance compliance;

    Corpus(Path results) {
        this.results = new Results(results);
        this.compliance = new Compliance(this.results);
    }

    /** A count for the shell's navigation, without the shell knowing what a bump is. */
    String badges() {
        long running = results.settlements().values().stream()
                .filter(r -> "bumping".equals(r.getOrDefault("state", ""))).count();
        return Json.object(Json.field("running", String.valueOf(running)));
    }

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
    String overview() throws IOException {
        long events = 0;
        long last = 0;
        try (var dirs = Files.list(results.dir())) {
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
    String bumps(String since) {
        long mark;
        try {
            mark = since == null || since.isBlank() ? 0L : Long.parseLong(since.trim());
        } catch (NumberFormatException notANumber) {
            mark = 0L;
        }
        return bumps(mark);
    }

    private String bumps(long mark) {
        Map<String, Map<String, String>> settled = results.settlements();
        Map<String, Map<String, String>> all = new LinkedHashMap<>();

        for (String row : Results.lines(results.dir().resolve("queue.tsv"))) {
            String[] c = row.strip().split("\\s+");
            if (c.length < 5) {
                continue;
            }
            String key = c[1] + "|" + c[2] + "|" + c[3] + "|" + c[4];
            all.put(Results.slug(key), Map.of("bump", key, "state", "queued", "at", "0"));
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
    String summary(Map<String, String> r) {
        String[] parts = r.getOrDefault("bump", "").split("\\|");
        String because = r.getOrDefault("because", "");
        String slug = Results.slug(r.getOrDefault("bump", ""));
        return Json.object(
                Json.field("slug", Json.string(Results.slug(r.getOrDefault("bump", "")))),
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
                Json.field("bomMet", compliance.of(slug, "met")),
                Json.field("bomMissed", compliance.of(slug, "missed")),
                // WHERE IT STOOD BEFORE, so a reader can tell a repository this harness raised
                // from one that arrived at the floor already owing it nothing.
                Json.field("bomMetBefore", compliance.of(slug, "metBefore")),
                Json.field("bomMissedBefore", compliance.of(slug, "missedBefore")),
                // ONLY THE FLOORS JUDGEABLE ON BOTH SIDES. The after-scan runs on a green gate and
                // the before-scan does not, so the raw totals are not comparable and subtracting
                // them reports a missing measurement as work done.
                Json.field("bomPairApplied", compliance.of(slug, "pairApplied")),
                Json.field("bomPairMissedBefore", compliance.of(slug, "pairMissedBefore")),
                Json.field("bomPairMissedAfter", compliance.of(slug, "pairMissedAfter")),
                Json.field("bomOutstanding", Json.optional(compliance.of(slug, "outstanding"))),
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

    /** The corpus: everything queued, plus anything settled that the queue no longer lists. */
    private int bumpCount() {
        Set<String> all = new LinkedHashSet<>(results.settlements().keySet());
        for (String row : Results.lines(results.dir().resolve("queue.tsv"))) {
            String[] c = row.strip().split("\\s+");
            if (c.length >= 5) {
                all.add(Results.slug(c[1] + "|" + c[2] + "|" + c[3] + "|" + c[4]));
            }
        }
        return all.size();
    }

    /** What the sweep is working through: the queue, and what it is made of. */
    String subject() {
        List<String> queue = Results.lines(results.dir().resolve("queue.tsv"));
        Map<String, Integer> hops = new LinkedHashMap<>();
        for (String row : queue) {
            String[] c = row.split("\t");
            if (c.length >= 5) {
                hops.merge(c[3] + " → " + c[4], 1, Integer::sum);
            }
        }
        return Json.object(
                Json.field("queued", String.valueOf(queue.size())),
                Json.field("settled", String.valueOf(results.settlements().size())),
                Json.field("hops", Json.map(hops.entrySet().stream().collect(
                        LinkedHashMap::new, (m, e) -> m.put(e.getKey(), String.valueOf(e.getValue())),
                        Map::putAll))));
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
    private final Map<String, Long> began = new ConcurrentHashMap<>();

    /**
     * WHEN THIS BUMP FIRST WROTE ANYTHING, cached, but NEVER cached as nought.
     *
     * <p>This used computeIfAbsent, which memoises whatever the function returns, and the function
     * returns nought when the trace file is not there yet. A page load a few seconds after a sweep
     * starts finds most lanes still cloning and no trace written, so nought was remembered for the
     * rest of the process: the column read "-" for the whole run while the traces beneath it grew to
     * hundreds of lines. Observed on a fresh sweep, where three of sixteen rows showed a duration
     * and the other thirteen never would have.
     *
     * <p>A missing measurement is not a measurement of nought, which is the most expensive recurring
     * mistake in this corpus and the reason compliance reports minus one rather than zero per cent.
     * Here it wore a cache. So the answer is only remembered once there is one: a bump that has not
     * written yet is re-read next time, which costs one stat of a file the caller is about to read
     * anyway.
     */
    private long startedAt(String slug) {
        Long known = began.get(slug);
        if (known != null && known != 0L) {
            return known;
        }
        Path trace = results.trace(slug);
        if (!Files.isRegularFile(trace)) {
            return 0L;
        }
        long at;
        try (var lines = Files.lines(trace)) {
            at = lines.findFirst().map(l -> Results.num(Json.row(l).get("at"))).orElse(0L);
        } catch (IOException | RuntimeException unreadable) {
            return 0L;
        }
        if (at != 0L) {
            began.put(slug, at);
        }
        return at;
    }

    /**
     * THE TIMESTAMP THE ROW CARRIES, so that anything ordering or filtering by it agrees with what
     * the page displays. Cheap: one stat of a file the caller is about to read anyway.
     */
    private long shownAt(Map<String, String> r) {
        return lastEventAt(Results.slug(r.getOrDefault("bump", "")), Results.num(r.get("at")));
    }

    private long lastEventAt(String slug, long fallback) {
        Path trace = results.trace(slug);
        try {
            return Files.isRegularFile(trace)
                    ? Files.getLastModifiedTime(trace).toMillis() : fallback;
        } catch (IOException unreadable) {
            return fallback;
        }
    }

    /** slug -> (mtime, lines), so an unchanged trace is never read twice. */
    private final Map<Path, long[]> counts = new ConcurrentHashMap<>();

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
        Path trace = results.trace(slug);
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
    private final Map<String, String> priced = new ConcurrentHashMap<>();

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
    private String humanMinutes(String slug) {
        String known = priced.get(slug);
        if (known != null) {
            return known;
        }
        Path trace = results.trace(slug);
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
    private static final Pattern TESTS = Pattern.compile("(\\d+) tests conserved");
    private static final Pattern CVES_BEFORE = Pattern.compile("CRITICAL\\+HIGH (\\d+) ->");
    private static final Pattern CVES_AFTER = Pattern.compile("CRITICAL\\+HIGH \\d+ -> (\\d+)");

    /** The captured number, or JSON null: absent is not zero, and a page must be able to tell. */
    private static String number(String text, Pattern pattern) {
        Matcher m = pattern.matcher(text == null ? "" : text);
        return m.find() ? m.group(1) : "null";
    }
}
