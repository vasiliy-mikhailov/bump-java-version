package tech.mikhailov.bjv.agent;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * WHAT THE BUMP DID TO THE PROJECT'S KNOWN VULNERABILITIES, measured rather than argued.
 *
 * <p>The scan is {@code jvmjob trivy}: it resolves each module's dependency jars and runs trivy
 * over them offline at CRITICAL and HIGH. The same command the corpus sweeps use, so a number
 * produced here is comparable with the 1439-repo baseline already on disk.
 *
 * <p>WHEN IT RUNS IS MOST OF WHETHER IT MEANS ANYTHING. Two rules, both learned by measuring:
 *
 * <p>The BEFORE scan runs after the baseline build and BEFORE the deterministic migration. The
 * migration applies recipes, floors and a target sweep, every one of which moves resolved
 * dependency versions, so a scan after it is not the project's prior state. It also has to follow
 * a build, because the collect step is offline and can only copy what the build already pulled
 * into the local repository.
 *
 * <p>The AFTER scan runs only where the gate went GREEN. On a failed bump the collect silently
 * copies whatever resolved before the build died, and the count falls because modules are missing
 * rather than because anything was fixed: across the corpus's own before/after pairs, one in seven
 * lost more than a tenth of its packages, and the largest apparent wins are dead builds. One repo
 * reads 897 tests to 0 with a 92% CVE reduction. A number like that is worse than no number, so a
 * bump that did not pass records NOT_MEASURED and the reason.
 *
 * <p>The accounting itself is a set difference over (module, package, CVE), computed here. A model
 * is asked to judge it, never to compute it: an agent rewarded for clearance it cannot cause,
 * reading a report whose commonest failure mode is a fake improvement, is an agent with a motive.
 */
final class Security {

    /** A scan, or an honest statement that there is no scan. */
    record Scan(boolean measured, String why, int total, int critical, int high,
                Map<String, Integer> perModule, List<Finding> findings, int packages,
                Set<String> resolved, List<Pkg> inventory) {

        static Scan notMeasured(String why) {
            return new Scan(false, why, -1, -1, -1, Map.of(), List.of(), -1, Set.of(), List.of());
        }

        /** The corpus's reward shape for security: 0.99 per outstanding CRITICAL or HIGH. */
        double multiplier() {
            return measured ? Math.pow(0.99, total) : Double.NaN;
        }
    }

    /**
     * One resolved dependency: what it is, which version arrived, and how much it costs.
     *
     * <p>The count alone says whether the number moved; this says WHAT moved. A bump that raises a
     * framework BOM and drags forty transitives with it, and a bump that raises nothing, both show
     * a flat count when the versions they resolved are never written down.
     */
    record Pkg(String module, String name, String version, int cves) {
        String key() {
            return module + "|" + name;
        }
    }

    /** One vulnerability, keyed the way the corpus keys it so counts are comparable. */
    record Finding(String module, String pkg, String version, String cve, String severity,
                   String fixed) {
        String key() {
            return module + "|" + pkg + "|" + cve;
        }
    }

    /** Before against after, once both exist. */
    record Delta(boolean valid, String why, int before, int after, int cleared, int remaining,
                 int introduced, List<String> clearedBy) {

        static Delta unknown(String why, int before, int after) {
            return new Delta(false, why, before, after, -1, -1, -1, List.of());
        }
    }

    private final Path ws;
    private final String hoptools;
    private final Trace trace;

    Security(Path ws, String hoptools, Trace trace) {
        this.ws = ws;
        this.hoptools = hoptools;
        this.trace = trace;
    }

    /**
     * Resolve and scan at the given JDK.
     *
     * <p>The image override is not optional: the default build image carries no trivy, and without
     * it every scan returns 127 with empty output, forever, silently.
     */
    Scan scan(String jdk, String phase) {
        Map<String, String> env = new HashMap<>(Runner.env(ws));
        env.put("BJV_IMAGE", "bjv-alljdk:trivy");
        try {
            // Trivy's contract is pure JSON on stdout, so this call must not merge stderr into it
            // the way a build call deliberately does.
            Shell.Output out = Shell.runSeparate(ws, env, Duration.ofSeconds(1900),
                    hoptools + "/jvm-run", jdk, "jvmjob", "trivy");
            if (!out.ok() && out.text().isBlank()) {
                return record(phase, Scan.notMeasured("scan exited " + out.code()
                        + " with no output"));
            }
            return record(phase, parse(out.text()));
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            return record(phase, Scan.notMeasured("scan failed: " + e.getMessage()));
        }
    }

    private Scan record(String phase, Scan s) {
        trace.applied("security-" + phase, s.measured()
                ? "CRITICAL+HIGH " + s.total() + " (critical " + s.critical() + ", high " + s.high()
                + ") over " + s.packages() + " packages in " + s.perModule().size() + " module(s)"
                : "NOT_MEASURED: " + s.why());
        if (s.measured()) {
            // The inventory as its own record, so a reader can see WHICH dependency moved rather
            // than only that the total did not. One line per package: module, name, version, CVEs.
            StringBuilder table = new StringBuilder();
            for (Pkg p : s.inventory()) {
                table.append(p.module()).append('\t').append(p.name()).append('\t')
                        .append(p.version()).append('\t').append(p.cves()).append('\n');
            }
            trace.applied("packages-" + phase, table.toString());
        }
        return s;
    }

    /**
     * Before against after, with a validity check that no model could perform from a summary.
     *
     * <p>A count that fell because six modules failed to resolve looks exactly like a count that
     * fell because six libraries were lifted. The only thing that tells them apart is whether the
     * same modules and roughly the same package count are present in both scans, so that is
     * checked here and the delta is marked UNKNOWN when it is not.
     */
    static Delta compare(Scan before, Scan after) {
        if (!before.measured() || !after.measured()) {
            return Delta.unknown(!before.measured() ? "no before scan: " + before.why()
                    : "no after scan: " + after.why(),
                    before.total(), after.total());
        }
        // WHICH MODULES RESOLVED, not which had findings. A module whose last vulnerability was
        // fixed drops out of perModule, and reading that as a module that failed to resolve calls
        // every real clearance an artefact. Resolution evidence is the packages that were scanned.
        Set<String> lostModules = new LinkedHashSet<>(before.resolved());
        lostModules.removeAll(after.resolved());
        boolean collapsed = before.packages() > 0
                && after.packages() < before.packages() * 0.9;
        if (!lostModules.isEmpty() || collapsed) {
            return Delta.unknown("the after scan resolved less than the before scan"
                    + (lostModules.isEmpty() ? "" : "; modules missing: " + lostModules)
                    + (collapsed ? "; packages " + before.packages() + " -> " + after.packages()
                    : ""), before.total(), after.total());
        }
        Set<String> was = new LinkedHashSet<>();
        before.findings().forEach(f -> was.add(f.key()));
        Set<String> now = new LinkedHashSet<>();
        after.findings().forEach(f -> now.add(f.key()));
        List<String> cleared = new ArrayList<>();
        for (Finding f : before.findings()) {
            if (!now.contains(f.key())) {
                cleared.add(f.pkg() + " " + f.version() + " (" + f.cve() + ")");
            }
        }
        int introduced = (int) after.findings().stream().filter(f -> !was.contains(f.key())).count();
        return new Delta(true, "", before.total(), after.total(), cleared.size(),
                after.total() - introduced, introduced,
                cleared.size() > 20 ? cleared.subList(0, 20) : cleared);
    }

    /** The worst offenders, for a brief: a model must never be handed a megabyte of report. */
    static String digest(Scan s, int howMany) {
        if (!s.measured()) {
            return "NOT_MEASURED: " + s.why();
        }
        Map<String, int[]> byPackage = new LinkedHashMap<>();
        Map<String, String> fixedFor = new LinkedHashMap<>();
        for (Finding f : s.findings()) {
            byPackage.computeIfAbsent(f.pkg() + " " + f.version(), k -> new int[1])[0]++;
            if (f.fixed() != null && !f.fixed().isBlank()) {
                fixedFor.putIfAbsent(f.pkg() + " " + f.version(), f.fixed());
            }
        }
        List<Map.Entry<String, int[]>> ranked = new ArrayList<>(byPackage.entrySet());
        ranked.sort((a, b) -> Integer.compare(b.getValue()[0], a.getValue()[0]));
        StringBuilder b = new StringBuilder("CRITICAL+HIGH " + s.total() + " (critical "
                + s.critical() + ", high " + s.high() + ") across " + s.perModule().size()
                + " module(s), " + s.packages() + " packages scanned.\nWorst packages:\n");
        for (int i = 0; i < Math.min(howMany, ranked.size()); i++) {
            String gav = ranked.get(i).getKey();
            b.append("  ").append(gav).append(" — ").append(ranked.get(i).getValue()[0])
                    .append(" CVE(s)")
                    .append(fixedFor.containsKey(gav) ? "; trivy says fixed in "
                            + fixedFor.get(gav) : "; no fixed version known")
                    .append('\n');
        }
        return b.toString();
    }

    // ---- parsing: the report is around a megabyte, so this walks it rather than loading a tree ----

    /**
     * Pull every vulnerability out of a trivy JSON report.
     *
     * <p>Deliberately not a general parser: only the objects inside a {@code Vulnerabilities} array
     * matter, and each carries flat string fields. A missing {@code Results} key is a real and
     * common outcome (nothing resolved) and reports zero findings against zero packages, which the
     * validity check upstream then reads correctly rather than as a clean project.
     */
    static Scan parse(String json) {
        if (json == null || json.isBlank()) {
            return Scan.notMeasured("empty report");
        }
        if (!json.contains("\"Results\"") || json.contains("\"Results\": null")
                || json.contains("\"Results\":null")) {
            return Scan.notMeasured("report has no Results: nothing resolved to scan");
        }
        List<Finding> findings = new ArrayList<>();
        Map<String, Integer> perModule = new LinkedHashMap<>();
        Set<String> packages = new LinkedHashSet<>();
        Set<String> resolved = new LinkedHashSet<>();
        Map<String, Pkg> inventory = new LinkedHashMap<>();
        int critical = 0;
        int high = 0;
        for (String target : arrayObjects(json, "\"Results\"")) {
            for (String v : arrayObjects(target, "\"Vulnerabilities\"")) {
                String severity = string(v, "Severity");
                if (!severity.equals("CRITICAL") && !severity.equals("HIGH")) {
                    continue;
                }
                String path = string(v, "PkgPath");
                Finding f = new Finding(moduleOf(path), string(v, "PkgName"),
                        string(v, "InstalledVersion"), string(v, "VulnerabilityID"), severity,
                        string(v, "FixedVersion"));
                findings.add(f);
                perModule.merge(f.module(), 1, Integer::sum);
                if (severity.equals("CRITICAL")) {
                    critical++;
                } else {
                    high++;
                }
            }
            // The Target is the jar the packages were read from, so it carries the module even
            // when an individual package entry does not.
            String targetPath = string(target, "Target");
            for (String p : arrayObjects(target, "\"Packages\"")) {
                packages.add(string(p, "Name") + ":" + string(p, "Version"));
                String fp = string(p, "FilePath");
                String module = moduleOf(fp.isBlank() ? targetPath : fp);
                resolved.add(module);
                Pkg pkg = new Pkg(module, string(p, "Name"), string(p, "Version"), 0);
                inventory.putIfAbsent(pkg.key(), pkg);
            }
        }
        // Attribute the findings back onto the packages, so one row carries both facts.
        Map<String, Integer> cvesFor = new LinkedHashMap<>();
        for (Finding f : findings) {
            cvesFor.merge(f.module() + "|" + f.pkg(), 1, Integer::sum);
        }
        List<Pkg> full = new ArrayList<>();
        inventory.forEach((k, pkg) -> full.add(new Pkg(pkg.module(), pkg.name(), pkg.version(),
                cvesFor.getOrDefault(k, 0))));
        // A vulnerable package trivy reported without a Packages entry still belongs in the list.
        cvesFor.forEach((k, n) -> {
            if (!inventory.containsKey(k)) {
                Finding any = findings.stream().filter(x -> (x.module() + "|" + x.pkg()).equals(k))
                        .findFirst().orElse(null);
                if (any != null) {
                    full.add(new Pkg(any.module(), any.pkg(), any.version(), n));
                }
            }
        });
        full.sort((x, y) -> x.cves() != y.cves() ? Integer.compare(y.cves(), x.cves())
                : x.name().compareTo(y.name()));
        return new Scan(true, "", critical + high, critical, high, perModule, findings,
                packages.size(), resolved, full);
    }

    /** Which module a jar belongs to, from its path. The corpus attributes findings the same way. */
    static String moduleOf(String pkgPath) {
        for (String marker : List.of("/target/dependency/", "/build/deps/")) {
            int at = pkgPath.indexOf(marker);
            if (at > 0) {
                return pkgPath.substring(0, at);
            }
            if (pkgPath.startsWith(marker.substring(1))) {
                return ".";
            }
        }
        return ".";
    }

    /** Every top-level object inside the named array, by balanced braces. */
    private static List<String> arrayObjects(String json, String key) {
        List<String> out = new ArrayList<>();
        int at = json.indexOf(key);
        if (at < 0) {
            return out;
        }
        int open = json.indexOf('[', at);
        if (open < 0) {
            return out;
        }
        int depth = 0;
        int start = -1;
        boolean inString = false;
        for (int i = open; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '"' -> inString = true;
                case '{' -> {
                    if (depth++ == 0) {
                        start = i;
                    }
                }
                case '}' -> {
                    if (--depth == 0 && start >= 0) {
                        out.add(json.substring(start, i + 1));
                        start = -1;
                    }
                }
                case ']' -> {
                    if (depth == 0) {
                        return out;
                    }
                }
                default -> {
                }
            }
        }
        return out;
    }

    /** A flat string field of this object, empty when absent or not a string. */
    private static String string(String obj, String field) {
        int at = obj.indexOf('"' + field + '"');
        if (at < 0) {
            return "";
        }
        int colon = obj.indexOf(':', at + field.length() + 2);
        if (colon < 0) {
            return "";
        }
        int i = colon + 1;
        while (i < obj.length() && Character.isWhitespace(obj.charAt(i))) {
            i++;
        }
        if (i >= obj.length() || obj.charAt(i) != '"') {
            return "";
        }
        StringBuilder b = new StringBuilder();
        for (int p = i + 1; p < obj.length(); p++) {
            char c = obj.charAt(p);
            if (c == '\\' && p + 1 < obj.length()) {
                b.append(obj.charAt(++p));
            } else if (c == '"') {
                break;
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }
}
