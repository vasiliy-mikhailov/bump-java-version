package tech.mikhailov.bjv.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import tech.mikhailov.ratchet.record.Json;

/**
 * WHAT THE SCAN RESOLVED, BEFORE AND AFTER, AND EVERY WAY THE PAGES COUNT IT.
 *
 * <p>The scan stage writes {@code module<TAB>name<TAB>version<TAB>cves} rows onto the trace. One
 * bump's page reads them to show its dependency table; the security page reads every bump's and
 * adds them up. Both used to parse and fold them their own way, and the folds are the part that is
 * easy to get wrong: which duplicates are duplicates, and which are genuinely two facts.
 *
 * <p>OCCURRENCES AND DISTINCT ARE BOTH KEPT, because they answer different questions and each alone
 * has misled. The scan reports a finding once per module that resolves the dependency, so
 * occurrences inflate by 1.67x overall and 15.5x on a seventeen-module project: ranking by that
 * ranks by module count. Every number this corpus has already published is an occurrence count, so
 * neither can be dropped without making the two incomparable.
 *
 * <p>AN AFTER THAT WAS NEVER TAKEN IS NOT ZERO. The after scan runs on a green gate and nowhere
 * else, so most bumps have no after inventory at all, and summing an empty map to 0 put
 * "CRITICAL+HIGH 337 -> 0" on the page of a bump that had cleared nothing.
 */
final class Inventory {

    private Inventory() {
    }

    /** {@code module<TAB>name<TAB>version<TAB>cves} rows, as the scan stage records them. */
    static Map<String, String[]> of(List<Map<String, String>> events, String stage) {
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
     * THE DEPENDENCIES, WITH THE MODULE THAT RESOLVED EACH ONE.
     *
     * <p>The module column is the fix for rows that looked like a rendering bug: the scan reports a
     * finding once per module, so a six-module project emitted the same package six times with no
     * way to tell the rows apart. They were six different facts wearing one label.
     */
    static String packages(List<Map<String, String>> events) {
        Map<String, String[]> before = of(events, "packages-before");
        Map<String, String[]> after = of(events, "packages-after");
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
                    Json.field("cvesBefore", String.valueOf(b == null ? 0 : (int) Results.num(b[1]))),
                    // NULL WHEN THE AFTER SCAN DID NOT SEE IT, not zero. Zero renders green
                    // as "cleared", so a bump with no after scan at all reported every
                    // vulnerable dependency as fixed: assertj-core 1 -> 0 on a project that
                    // had not been scanned once. It also covers the honest case of a
                    // dependency the bump removed, which is likewise not "zero CVEs".
                    Json.field("cvesAfter",
                            a == null ? "null" : String.valueOf((int) Results.num(a[1])))));
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
    static String cves(List<Map<String, String>> events) {
        Map<String, String[]> before = of(events, "packages-before");
        Map<String, String[]> after = of(events, "packages-after");
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

    static int total(Map<String, String[]> inventory) {
        return inventory.values().stream().mapToInt(v -> (int) Results.num(v[1])).sum();
    }

    /** One count per (package, version), however many modules resolved it. */
    static int distinct(Map<String, String[]> inventory) {
        Map<String, Integer> once = new LinkedHashMap<>();
        for (Map.Entry<String, String[]> e : inventory.entrySet()) {
            String name = e.getKey().contains("|")
                    ? e.getKey().substring(e.getKey().indexOf('|') + 1) : e.getKey();
            once.put(name + "@" + e.getValue()[0], (int) Results.num(e.getValue()[1]));
        }
        return once.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** Distinct counts per package name, summed over the versions of it this bump resolved. */
    static void fold(Map<String, String[]> inventory, Map<String, int[]> into, int slot) {
        Map<String, Integer> once = new LinkedHashMap<>();
        for (Map.Entry<String, String[]> e : inventory.entrySet()) {
            String name = e.getKey().contains("|")
                    ? e.getKey().substring(e.getKey().indexOf('|') + 1) : e.getKey();
            once.put(name + "@" + e.getValue()[0], (int) Results.num(e.getValue()[1]));
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
    static Map<String, int[]> pairs(Map<String, String[]> before, Map<String, String[]> after) {
        Set<String> keys = new LinkedHashSet<>(before.keySet());
        keys.addAll(after.keySet());
        Map<String, int[]> once = new LinkedHashMap<>();
        for (String key : keys) {
            String name = key.contains("|") ? key.substring(key.indexOf('|') + 1) : key;
            String[] b = before.get(key);
            String[] a = after.get(key);
            once.put(name + "\u0000" + (b == null ? "" : b[0]) + "\u0000" + (a == null ? "" : a[0]),
                    new int[] {b == null ? 0 : (int) Results.num(b[1]),
                            a == null ? 0 : (int) Results.num(a[1])});
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
}
