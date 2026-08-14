package tech.mikhailov.bjv.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * WHAT A BUMP HAS TO BE TRUE TO PASS — the scorer, ported from the corpus's own gate so the chain
 * and the sweeps measure the same thing.
 *
 * <p>Three conditions, in the order that makes their failures distinguishable: the project builds
 * under the target JDK, every test that passed before still passes after, and the effective bytecode
 * target actually reached the target. A green build proves only the first. Each was learned by
 * paying for its absence, and every quirk below is a false verdict the corpus produced once.
 */
final class Gate {

    /** The verdict, in the corpus's own vocabulary so results are comparable across harnesses. */
    record Verdict(String state, int preTests, int lost, int effectiveTarget, List<String> missing) {

        /** A verdict with nothing missing, which is every verdict except a conservation failure. */
        Verdict(String state, int preTests, int lost, int effectiveTarget) {
            this(state, preTests, lost, effectiveTarget, List.of());
        }

        boolean pass() {
            return "PASS".equals(state);
        }
    }

    private Gate() {
    }

    static Verdict decide(Set<String> pre, Set<String> post, boolean built, int effectiveTarget,
                          int target) {
        if (pre.isEmpty()) {
            return new Verdict("NO_BASELINE_NOTESTS", 0, 0, effectiveTarget);
        }
        if (!built) {
            return new Verdict("FAIL_build_post", pre.size(), 0, effectiveTarget);
        }
        List<String> missing = missing(pre, post);
        if (!missing.isEmpty()) {
            return new Verdict("FAIL_test_conservation", pre.size(), missing.size(), effectiveTarget,
                    missing);
        }
        if (effectiveTarget == -1) {
            // Built, but nothing inspectable: the bump is unverifiable and must never silently pass.
            return new Verdict("FAIL_no_main_bytecode", pre.size(), 0, -1);
        }
        if (effectiveTarget < target) {
            return new Verdict("FAIL_target_not_bumped", pre.size(), 0, effectiveTarget);
        }
        return new Verdict("PASS", pre.size(), 0, effectiveTarget);
    }

    // ---- the passing-test set ----

    /**
     * Every test that PASSED, as {@code fqcn#name}.
     *
     * <p>The class name comes from the {@code TEST-<fqcn>.xml} FILENAME, not the attribute: surefire
     * 3.x writes a parametrized display name into {@code classname}, and cross-class parameter cases
     * then collide inside the set.
     *
     * <p>Control characters are escaped injectively. JUnit 5 display names carry literal newlines,
     * and a set that is compared line-by-line splits one such entry into fragments, each counted as
     * a lost test. Folding them to a space instead would merge two genuinely different names and
     * hide a real loss, so the fold has to be reversible.
     */
    static Set<String> passing(Path root) throws IOException {
        return passing(root, List.of());
    }

    /**
     * The same, stopping where another module begins.
     *
     * <p>A module's verdict is read off the reactor build that already ran, by walking one directory
     * lower rather than by building anything again. Walking a parent descends into its children, so
     * without this a parent would be credited with every test its modules ran and no module could be
     * told apart from the aggregate. That aggregate is exactly what the repo-level gate already
     * measures, and measuring it twice is not a per-module answer.
     */
    static Set<String> passing(Path root, List<Path> stopAt) throws IOException {
        Set<String> out = new LinkedHashSet<>();
        if (!Files.isDirectory(root)) {
            return out;
        }
        List<Path> reports = new ArrayList<>();
        try (var s = Files.walk(root)) {
            s.filter(Files::isRegularFile)
                    .filter(p -> !within(p, stopAt))
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.startsWith("TEST-") && n.endsWith(".xml");
                    })
                    .forEach(reports::add);
        }
        var factory = DocumentBuilderFactory.newInstance();
        for (Path report : reports) {
            String name = report.getFileName().toString();
            String fqcn = name.substring(5, name.length() - 4);
            try {
                var doc = factory.newDocumentBuilder().parse(report.toFile());
                NodeList cases = doc.getElementsByTagName("testcase");
                for (int i = 0; i < cases.getLength(); i++) {
                    Element tc = (Element) cases.item(i);
                    if (didNotPass(tc)) {
                        continue;
                    }
                    String cls = fqcn.isBlank() ? tc.getAttribute("classname") : fqcn;
                    out.add(escapeControls(cls + "#" + tc.getAttribute("name")));
                }
            } catch (Exception unparseable) {
                // A half-written report is not evidence of a lost test; skip it and say so.
                System.err.println("gate: skipped unparseable " + report + ": "
                        + unparseable.getMessage());
            }
        }
        return out;
    }

    private static boolean didNotPass(Element testcase) {
        NodeList children = testcase.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node c = children.item(i);
            String tag = c.getNodeName();
            if (tag.equals("failure") || tag.equals("error") || tag.equals("skipped")) {
                return true;
            }
        }
        return false;
    }

    private static String escapeControls(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            if (c <= 0x1f || c == 0x7f) {
                b.append(String.format("\\x%02x", (int) c));
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    // ---- conservation ----

    private static final Pattern VOLATILE = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
                    + "|@[0-9a-f]{6,}|\\b[0-9a-f]{6,}\\b");

    /**
     * How many pre-pass tests are missing afterwards, matched in three widening passes: exact, then
     * with the method's parameter and display decoration stripped, then with clearly volatile tokens
     * (UUIDs, identity hashes) removed.
     *
     * <p>The widening stops there deliberately. Stripping ALL digits would bucket {@code testCase1},
     * {@code testCase2} and {@code testCase3} together, so dropping one of them would match another
     * and disappear.
     */
    static int lost(Set<String> pre, Set<String> post) {
        return missing(pre, post).size();
    }

    /**
     * WHICH tests stopped passing, not how many.
     *
     * <p>The count alone is unactionable. A troubleshooter told "4 of 7 tests no longer pass" has to
     * guess at what broke, and one did: it invented a bean for a captcha library, wrote {@code new}
     * against an interface, and then reported the resulting compile error as proof the dependency
     * was incompatible with the JDK. The set was computed all along and thrown away at the door.
     */
    static List<String> missing(Set<String> pre, Set<String> post) {
        Map<String, Integer> exact = counted(post);
        List<String> unmatched = new ArrayList<>();
        for (String p : pre) {
            if (take(exact, p)) {
                continue;
            }
            unmatched.add(p);
        }
        Map<String, Integer> byName = new HashMap<>();
        exact.forEach((k, c) -> {
            if (c > 0) {
                byName.merge(normalise(k), c, Integer::sum);
            }
        });
        List<String> residue = new ArrayList<>();
        for (String p : unmatched) {
            if (!take(byName, normalise(p))) {
                residue.add(p);
            }
        }
        Map<String, Integer> stable = new HashMap<>();
        byName.forEach((k, c) -> {
            if (c > 0) {
                stable.merge(VOLATILE.matcher(k).replaceAll(""), c, Integer::sum);
            }
        });
        List<String> gone = new ArrayList<>();
        for (String p : residue) {
            if (!take(stable, VOLATILE.matcher(normalise(p)).replaceAll(""))) {
                gone.add(p);
            }
        }
        Collections.sort(gone);
        return gone;
    }

    private static Map<String, Integer> counted(Set<String> items) {
        Map<String, Integer> m = new HashMap<>();
        items.forEach(i -> m.merge(i, 1, Integer::sum));
        return m;
    }

    private static boolean take(Map<String, Integer> counts, String key) {
        Integer c = counts.get(key);
        if (c == null || c <= 0) {
            return false;
        }
        counts.put(key, c - 1);
        return true;
    }

    /**
     * The stable identity of a test: its class, and its method with any per-case decoration removed.
     *
     * <p>THE SEPARATOR IS THE FIRST HASH, NOT THE LAST. A key is {@code fqcn#name} and a class name
     * cannot contain a hash, but a NAME can: Spock renders a parameterised feature as
     * {@code creates date intervals [timeRange: LAST_6_MONTHS, start: 2020-04-10, #0]}. Splitting on
     * the last hash took the iteration marker for the separator, so every parameterised case in the
     * project collapsed to {@code 0]} or {@code 1]} — colliding across unrelated specs, and losing
     * the class entirely.
     *
     * <p>THE CLASS STAYS IN THE KEY. Two specs may each have a feature called "converts correctly",
     * and a method name alone would let one spec's loss be covered by the other's survivor.
     *
     * <p>What this deliberately does NOT distinguish is one iteration of a parameterised feature
     * from another, because their rendered parameters are not stable: this project's bump moved the
     * Gradle wrapper from 6.8.1 to 7.6, the runner changed with it, and 23 of 47 tests were reported
     * lost that had in fact all passed. Iterations are told apart by COUNT instead — four before and
     * four after is conserved, four before and three after is one lost — which is what the caller's
     * multiset matching already provides.
     */
    private static String normalise(String entry) {
        int sep = entry.indexOf('#');
        String cls = sep < 0 ? "" : entry.substring(0, sep);
        String method = sep < 0 ? entry : entry.substring(sep + 1);
        int cut = method.length();
        for (char stop : new char[]{'(', '[', '{'}) {
            int at = method.indexOf(stop);
            if (at >= 0 && at < cut) {
                cut = at;
            }
        }
        return cls + "#" + method.substring(0, cut).strip().toLowerCase();
    }

    // ---- the effective bytecode target ----

    private static final Pattern GRADLE_CLASSES =
            Pattern.compile("/classes/(java|kotlin|scala|groovy)/(main|test)/");
    private static final Pattern KMP_CLASSES = Pattern.compile("/classes/kotlin/jvm/(main|test)/");
    private static final List<String> MAVEN_MAIN = List.of("/target/classes/", "/build/classes/main/");

    /**
     * The MINIMUM class-file major across every compiled MAIN class, less 44, so it reads as a Java
     * release. One unraised module fails the bump while the build stays green, which is the whole
     * reason this is measured rather than inferred from the build's exit code.
     *
     * <p>TEST classes never contribute: a test tree left at the old level is not what the bump
     * promises. Scala classes never contribute either, because scalac caps its own output (2.12
     * hard-caps at major 52 on any JDK) and no Java version bump can lift it — a mixed repo whose
     * every Java class reached the target would otherwise be dragged down to Scala's cap. Kotlin and
     * Groovy DO contribute: their target is controllable, so an unbumped one is a real failure.
     */
    static int effectiveTarget(Path root) throws IOException {
        return effectiveTarget(root, List.of());
    }

    /** The same, stopping where another module begins. See {@link #passing(Path, List)}. */
    static int effectiveTarget(Path root, List<Path> stopAt) throws IOException {
        List<Integer> controllable = new ArrayList<>();
        List<Integer> scala = new ArrayList<>();
        try (var walk = Files.walk(root)) {
            for (Path f : walk.filter(Files::isRegularFile)
                    .filter(p -> !within(p, stopAt)).toList()) {
                String name = f.getFileName().toString();
                if (!name.endsWith(".class") || name.equals("module-info.class")) {
                    continue;
                }
                String dir = f.getParent().toString().replace('\\', '/') + "/";
                if (dir.contains("/META-INF/versions/") || dir.contains("/buildSrc/")
                        || dir.contains("/build-logic/")) {
                    continue;
                }
                Matcher gradle = GRADLE_CLASSES.matcher(dir);
                Matcher kmp = KMP_CLASSES.matcher(dir);
                String lang = null;
                String role = null;
                if (gradle.find()) {
                    lang = gradle.group(1);
                    role = gradle.group(2);
                } else if (kmp.find()) {
                    lang = "kotlin";
                    role = kmp.group(1);
                }
                boolean isMain = "main".equals(role)
                        || MAVEN_MAIN.stream().anyMatch(dir::contains);
                if (!isMain) {
                    continue;
                }
                byte[] bytes;
                try {
                    bytes = Files.readAllBytes(f);
                } catch (IOException unreadable) {
                    continue;
                }
                int major = major(bytes);
                if (major < 0) {
                    continue;
                }
                boolean isScala = "scala".equals(lang)
                        || ((lang == null || lang.equals("java")) && compiledByScalac(bytes));
                (isScala ? scala : controllable).add(major);
            }
        }
        List<Integer> pool = controllable.isEmpty() ? scala : controllable;
        return pool.isEmpty() ? -1 : pool.stream().mapToInt(Integer::intValue).min().orElse(-1) - 44;
    }

    private static int major(byte[] b) {
        if (b.length < 8 || (b[0] & 0xff) != 0xca || (b[1] & 0xff) != 0xfe
                || (b[2] & 0xff) != 0xba || (b[3] & 0xff) != 0xbe) {
            return -1;
        }
        return ((b[6] & 0xff) << 8) | (b[7] & 0xff);
    }

    /**
     * Whether the class carries a scalac attribute. Needed for Maven, where Java and Scala classes
     * co-locate in {@code target/classes} and cannot be told apart by path.
     */
    static boolean compiledByScalac(byte[] b) {
        if (major(b) < 0 || b.length < 10) {
            return false;
        }
        int count = ((b[8] & 0xff) << 8) | (b[9] & 0xff);
        int i = 10;
        for (int slot = 1; slot < count && i < b.length; slot++) {
            int tag = b[i] & 0xff;
            if (tag == 1) {
                int len = ((b[i + 1] & 0xff) << 8) | (b[i + 2] & 0xff);
                String text = new String(b, i + 3, Math.min(len, b.length - i - 3),
                        java.nio.charset.StandardCharsets.UTF_8);
                if (text.equals("Scala") || text.equals("ScalaSig")
                        || text.equals("ScalaInlineInfo")) {
                    return true;
                }
                i += 3 + len;
            } else {
                Integer size = CONSTANT_SIZE.get(tag);
                if (size == null) {
                    return false;
                }
                i += 1 + size;
                if (tag == 5 || tag == 6) {
                    slot++;
                }
            }
        }
        return false;
    }

    /** Whether a path lies under any of the directories a walk was told to stop at. */
    private static boolean within(Path p, List<Path> stopAt) {
        if (stopAt.isEmpty()) {
            return false;
        }
        Path here = p.normalize();
        return stopAt.stream().anyMatch(here::startsWith);
    }

    /**
     * WHICH MODULE IS BEHIND, which the repo-level verdict cannot say.
     *
     * <p>{@link #effectiveTarget(Path)} is a single minimum across the whole tree, so one module
     * left on the old target reads as an unbumped repository and the evidence points nowhere. This
     * is the same two reads, per module, off the output the one reactor build already wrote. No
     * second build happens here and none should: builds are the expensive part of a bump.
     */
    record ModuleState(Modules.Module module, Set<String> passing, int effectiveTarget, int lost,
                       List<String> missing) {

        /** A module is clear when it kept its tests and reached the target it was asked for. */
        boolean ok(int target) {
            return lost == 0 && (effectiveTarget == -1 || effectiveTarget >= target);
        }

        String describe(int target) {
            String where = module.isRoot() ? "root" : module.path();
            String reached = effectiveTarget == -1 ? "no class files" : String.valueOf(effectiveTarget);
            return where + "  target " + reached + (effectiveTarget >= target || effectiveTarget == -1
                    ? "" : " (below " + target + ")")
                    + ", " + passing.size() + " passing"
                    + (lost == 0 ? "" : ", lost " + lost + ": " + String.join(", ",
                            missing.subList(0, Math.min(3, missing.size()))));
        }
    }

    /**
     * Read every module's own state, with each walk stopping where the next module begins.
     *
     * <p>The {@code baseline} argument is the same read taken before the bump, so a module's lost
     * tests are its own rather than the repository's. A module absent from the baseline map is new
     * to this run and has nothing to conserve.
     */
    static List<ModuleState> perModule(Path ws, Map<String, Set<String>> baseline)
            throws IOException {
        List<Modules.Module> all = Modules.of(ws);
        List<ModuleState> out = new ArrayList<>();
        for (Modules.Module m : all) {
            List<Path> nested = all.stream()
                    .filter(x -> !x.path().equals(m.path()))
                    .filter(x -> m.isRoot() || x.path().startsWith(m.path() + "/"))
                    .map(x -> x.in(ws).normalize())
                    .toList();
            Path root = m.in(ws);
            Set<String> now = passing(root, nested);
            Set<String> was = baseline.getOrDefault(m.path(), Set.of());
            List<String> missing = was.stream().filter(t -> !now.contains(t)).toList();
            out.add(new ModuleState(m, now, effectiveTarget(root, nested), missing.size(), missing));
        }
        return out;
    }

    /** The same read taken before anything moved, which is what a module's loss is measured against. */
    static Map<String, Set<String>> baselinePerModule(Path ws) throws IOException {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        for (ModuleState s : perModule(ws, Map.of())) {
            out.put(s.module().path(), s.passing());
        }
        return out;
    }

    /** Constant-pool tag to the bytes that follow it. Long and Double take two slots. */
    private static final Map<Integer, Integer> CONSTANT_SIZE = Map.ofEntries(
            Map.entry(3, 4), Map.entry(4, 4), Map.entry(5, 8), Map.entry(6, 8),
            Map.entry(7, 2), Map.entry(8, 2), Map.entry(9, 4), Map.entry(10, 4),
            Map.entry(11, 4), Map.entry(12, 4), Map.entry(15, 3), Map.entry(16, 2),
            Map.entry(17, 4), Map.entry(18, 4), Map.entry(19, 2), Map.entry(20, 2));
}
