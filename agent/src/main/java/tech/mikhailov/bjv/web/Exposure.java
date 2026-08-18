package tech.mikhailov.bjv.web;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import tech.mikhailov.bjv.agent.Json;

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
final class Exposure {

    private final Results results;

    Exposure(Path results) {
        this.results = new Results(results);
    }

    private record Measured(String slug, String repo, int from, int to, int before, int after,
                            int occurrencesBefore, int occurrencesAfter, Map<String, int[]> byName,
                            Map<String, int[]> byPair) {
    }

    /** slug -> its aggregation. A settled bump's trace does not change, so this is computed once. */
    private final Map<String, Measured> measured = new ConcurrentHashMap<>();

    String report() {
        List<Measured> all = new ArrayList<>();
        for (Map.Entry<String, Map<String, String>> e : results.settlements().entrySet()) {
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

    private Measured measure(String slug, Map<String, String> settled) {
        Measured known = measured.get(slug);
        if (known != null) {
            return known;
        }
        List<Map<String, String>> events = Results.lines(results.trace(slug)).stream()
                .map(Json::row).toList();
        Map<String, String[]> before = Inventory.of(events, "packages-before");
        Map<String, String[]> after = Inventory.of(events, "packages-after");
        if (after.isEmpty()) {
            return null;
        }
        Map<String, int[]> byName = new LinkedHashMap<>();
        Inventory.fold(before, byName, 0);
        Inventory.fold(after, byName, 1);
        Map<String, int[]> byPair = Inventory.pairs(before, after);
        String[] parts = settled.getOrDefault("bump", "").split("\\|");
        Measured m = new Measured(slug,
                parts.length > 0 ? parts[0] : "",
                parts.length > 2 ? (int) Results.num(parts[2]) : 0,
                parts.length > 3 ? (int) Results.num(parts[3]) : 0,
                Inventory.distinct(before), Inventory.distinct(after),
                Inventory.total(before), Inventory.total(after), byName, byPair);
        measured.put(slug, m);
        return m;
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
}
