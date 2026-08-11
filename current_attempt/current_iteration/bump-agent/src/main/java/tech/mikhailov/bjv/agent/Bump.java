package tech.mikhailov.bjv.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * THE ORDER: survey, prepare, bump, troubleshoot — four producer/critic pairs, run in a sequence
 * nothing can rewrite, with the gate between every phase that changes the workspace.
 *
 * <pre>
 * surveyor ──→ survey-critic          which hop is this, actually   (wrong-hop → adopt correction)
 *    ↓
 * baseline @ from-JDK                 a FACT; no baseline, no bump
 *    ↓
 * migrate.sh (deterministic)          recipes by the project's own line; floors; target sweep
 * preparer ──→ prepare-critic         the skill's PROACTIVE section  (missed|overreach → once more)
 *    ↓
 * bumper ──→ bump-critic              the skill's TARGET GATE        (not-landed → once more)
 *    ↓
 * reflect loop, bounded:
 *   gate @ to-JDK                     green → settled by the build, no model involved
 *   walls table                       the enumerable rows, free, before any model call
 *   troubleshooter ──→ trouble-critic the residue                    (gaming → revert and stop)
 *    ↓
 * verdict                             argues ONLY what execution could not settle
 * estimator                           prices the attempt from the record
 * </pre>
 *
 * <p>THE GATE IS NOT A TOOL. No agent may invoke the runner; whether the gate ran after an edit is
 * not a model's choice. Wherever the builds established the outcome, {@code settle} computes it and
 * no model is called, because routing a deterministic outcome through a model makes it a sampled one.
 */
public final class Bump {

    /** Turns of the reflect loop. Rung-1's recovered repos took 4-9 iterations; the mean was 6. */
    private static final int TURNS = 8;
    /** One re-ask per objection, quoting whoever objected. Every pair shares it, stated once. */
    private static final int REASK = 1;

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("usage: Bump <checkout> <repo|sha[|from|to]> [results-dir] [migrate-script]");
            System.exit(2);
        }
        Path checkout = Path.of(args[0]);
        String bump = args[1];
        Path results = Path.of(args.length > 2 ? args[2] : "results");
        String migrate = args.length > 3 ? args[3] : null;

        String slug = bump.replaceAll("[^A-Za-z0-9]+", "_");
        JsonlTrace trace = new JsonlTrace(results.resolve(slug).resolve("trace.jsonl"),
                results.resolve("settlements.jsonl"), bump);
        try {
            Bump b = new Bump(checkout, bump, new Agents(Llm.fromEnv(), trace), trace, migrate);
            String account = b.run();
            String state = account.split("\n", 2)[0];
            trace.settled(bump, state, account, b.baselineGreen, b.gateGreen);
        } catch (RuntimeException e) {
            trace.failed(bump, e);
            e.printStackTrace();
            System.exit(1);
        }
    }

    private final Path ws;
    private final String bump;
    private final Agents agents;
    private final Trace trace;
    private final String migrate;
    private String from;
    private String to;
    private Runner runner;
    private Walls walls;
    // What the builds actually did, carried to the settlement. An implication is not a record.
    private boolean baselineGreen;
    private boolean gateGreen;

    private Bump(Path ws, String bump, Agents agents, Trace trace, String migrate) {
        String[] parts = bump.split("\\|");
        if (parts.length < 2) {
            throw new IllegalArgumentException("bump must be repo|sha[|from|to], got: " + bump);
        }
        this.ws = ws;
        this.bump = bump;
        this.from = parts.length > 2 ? parts[2] : "";
        this.to = parts.length > 3 ? parts[3] : "";
        this.agents = agents;
        this.trace = trace;
        this.migrate = migrate;
    }

    /** The whole bump. Read it top to bottom; that is the order, and nothing can reorder it. */
    private String run() throws IOException {
        // ---- SURVEY: which hop, actually. The caller's from|to is the detector's guess, not law.
        trace.progress(bump, "survey: which hop is this");
        String evidence = buildFiles() + "\n\nEvery version pin found:\n" + pinGrep()
                + "\nThe deterministic detector's guess: "
                + (from.isBlank() ? "none" : from + "->" + to);
        String claim = agents.surveyor().run(evidence);
        String[] hop = parseHop(claim);
        String check = agents.surveyCritic().run(evidence + "\n\nThe claim:\n" + claim);
        Matcher corrected = Pattern.compile("wrong-hop:\\s*(\\d+)\\s*->\\s*(\\d+)").matcher(check);
        if (corrected.find()) {
            hop = new String[]{corrected.group(1), corrected.group(2)};
            trace.progress(bump, "survey-critic corrected the hop to " + hop[0] + "->" + hop[1]);
        }
        if (hop == null) {
            return "not-a-bump\n" + claim;
        }
        from = hop[0];
        to = hop[1];
        String hoptools = System.getenv().getOrDefault("BJV_HOPTOOLS",
                "/home/vmihaylov/bump-java-version/current_attempt/current_iteration/hoptools");
        runner = new Runner(ws, hoptools);
        walls = new Walls(ws);

        // ---- BASELINE: a fact. No baseline, no bump.
        trace.progress(bump, "baseline: building and testing under JDK " + from);
        Runner.Result preBuild = runner.build(from);
        trace.built("baseline-build", preBuild);
        if (preBuild.infra()) {
            return "no-baseline\nthe project does not build under its own JDK " + from + ":\n"
                    + preBuild.summary();
        }
        Runner.Result preTest = runner.test(from);
        trace.built("baseline-test", preTest);
        baselineGreen = !preTest.infra() && preTest.passed();
        if (!baselineGreen) {
            return "no-baseline\nthe tests are not green under the project's own JDK " + from
                    + ", so conservation cannot be judged:\n" + preTest.summary();
        }

        // ---- PREPARE: deterministic pre-pass first, then the skill's proactive section, judged.
        String prePass = "";
        if (migrate != null) {
            trace.progress(bump, "migrate: recipes, floors, target sweep");
            prePass = migrateScript();
        }
        preparePhase(prePass);

        // ---- BUMP: land the target, judged against the skill's own gate.
        bumpPhase();

        // ---- TROUBLESHOOT: the bounded loop. Free things first, every turn.
        String lastLog = "";
        for (int turn = 1; turn <= TURNS; turn++) {
            trace.progress(bump, "gate: turn " + turn + " of " + TURNS + " under JDK " + to);
            Runner.Result build = runner.build(to);
            trace.built("gate-build-" + turn, build);
            if (!build.infra()) {
                Runner.Result test = runner.test(to);
                trace.built("gate-test-" + turn, test);
                if (!test.infra() && test.passed()) {
                    gateGreen = true;
                    return "green\nthe gate is green after " + turn + " turn(s); walls cleared: "
                            + walls.appliedSoFar();
                }
                lastLog = test.summary();
            } else {
                lastLog = build.summary();
            }
            Walls.Turn t = walls.match(lastLog, Integer.parseInt(to));
            if (t.fixed()) {
                trace.applied("walls", t.what());
                continue;
            }
            if (!troubleshootTurn(lastLog)) {
                break;
            }
        }

        // ---- CLOSERS: argue only the unsettled, price everything.
        String argued = agents.verdict().run(brief(lastLog)
                + "\nThe reflect loop ended without a green gate. Walls cleared: "
                + walls.appliedSoFar());
        price();
        return word(argued, "blocked-dependency", "behavior-change", "infra") + "\n" + argued;
    }

    /** The preparer and its critic: the skill's proactive section, executed and then audited. */
    private void preparePhase(String prePass) throws IOException {
        String brief = "Migration: JDK " + from + " -> " + to
                + "\n\nThe deterministic pre-pass already did:\n" + prePass
                + "\n" + buildFiles();
        String reply = agents.preparer().run(brief);
        applyJudged(reply, brief, agents.preparer(), () -> {
            String audit = agents.prepareCritic().run(brief + "\n\nThe edits:\n" + reply);
            return word(audit, "sound", "missed", "overreach").equals("sound") ? null : audit;
        }, "prepare");
    }

    /** The bumper and its critic: the pins the recipe under-applied, judged against the gate. */
    private void bumpPhase() throws IOException {
        String pins = pinGrep();
        String brief = "Migration: JDK " + from + " -> " + to
                + "\nEvery pin still below " + to + ":\n" + (pins.isBlank() ? "(none found)" : pins);
        String reply = agents.bumper().run(brief);
        applyJudged(reply, brief, agents.bumper(), () -> {
            String stillBelow = pinGrep();
            if (stillBelow.isBlank()) {
                return null;
            }
            String audit = agents.bumpCritic().run(brief + "\n\nThe edits:\n" + reply
                    + "\n\nPins STILL below target after them:\n" + stillBelow);
            return word(audit, "sound", "not-landed", "overreach").equals("sound") ? null : audit;
        }, "bump");
    }

    /** A critic's objection, or null when the work stands. Reads files, so it may throw. */
    @FunctionalInterface
    private interface Audit {
        String objection() throws IOException;
    }

    /** One producer round: apply its edits, ask its critic, allow one corrected resubmission. */
    private void applyJudged(String reply, String brief, Agents.Agent producer,
                             Audit audit, String stage) throws IOException {
        for (int again = 0; again <= REASK; again++) {
            if (reply.stripLeading().startsWith("NOTHING-TO-DO")) {
                trace.progress(bump, stage + ": nothing to do");
                return;
            }
            Edits.Applied applied = Edits.apply(ws, reply);
            if (applied.count() == 0) {
                if (again == REASK) {
                    trace.progress(bump, stage + " edit unusable: " + applied.report());
                    return;
                }
                reply = producer.run(brief + "\nYour previous answer could not be applied: "
                        + applied.report() + "\nAnswer again with corrected EDIT blocks.");
                continue;
            }
            trace.applied(stage, applied.report());
            String objection = audit.objection();
            if (objection == null || again == REASK) {
                return;
            }
            reply = producer.run(brief + "\nA reviewer objected:\n" + objection
                    + "\nAnswer again; address the objection, do not resubmit the same edits.");
        }
    }

    /** One troubleshooter round. True to keep looping, false to stop. */
    private boolean troubleshootTurn(String log) throws IOException {
        String brief = brief(log);
        String reply = agents.troubleshooter().run(brief);
        for (int again = 0; again <= REASK; again++) {
            if (Edits.declined(reply)) {
                trace.progress(bump, "troubleshooter declined: "
                        + reply.lines().findFirst().orElse(""));
                return false;
            }
            Edits.Applied applied = Edits.apply(ws, reply);
            if (applied.count() == 0) {
                if (again == REASK) {
                    trace.progress(bump, "troubleshooter edit unusable: " + applied.report());
                    return false;
                }
                reply = agents.troubleshooter().run(brief
                        + "\nYour previous answer could not be applied: " + applied.report()
                        + "\nAnswer again with corrected EDIT blocks.");
                continue;
            }
            trace.applied("troubleshooter", applied.report());
            String judgement = agents.troubleCritic().run("The failing build said:\n" + log
                    + "\n\nThe proposed edit, already applied:\n" + reply);
            String word = word(judgement, "sound", "gaming", "off-target");
            if ("gaming".equals(word)) {
                revert();
                trace.progress(bump, "trouble-critic: gaming; edit reverted");
                return false;
            }
            if ("off-target".equals(word) && again < REASK) {
                revert();
                reply = agents.troubleshooter().run(brief
                        + "\nA reviewer judged your edit aims at the wrong wall:\n" + judgement
                        + "\nAnswer again.");
                continue;
            }
            return true;
        }
        return false;
    }

    // ---- briefs: THE CONTEXT IS HANDED OVER, NOT FETCHED. No agent here has tools. ----

    private String brief(String log) throws IOException {
        StringBuilder b = new StringBuilder("Migration: JDK " + from + " -> " + to + " (" + bump
                + ")\nWalls already cleared mechanically: " + walls.appliedSoFar()
                + "\n\nThe failing build:\n" + log + "\n");
        Set<String> named = new LinkedHashSet<>();
        Matcher m = Pattern.compile("/work/([\\w./$-]+\\.(?:java|xml|kts|gradle))[:\\[]").matcher(log);
        while (m.find() && named.size() < 3) {
            named.add(m.group(1));
        }
        if (named.isEmpty()) {
            named.add("pom.xml");
        }
        for (String rel : named) {
            Path f = ws.resolve(rel);
            if (Files.isRegularFile(f)) {
                String content = Files.readString(f);
                if (content.length() > 8000) {
                    content = cutAround(content, log, rel);
                }
                b.append("\nThe file ").append(rel).append(":\n").append(content).append('\n');
            }
        }
        return b.toString();
    }

    /** The build files a surveyor or preparer reasons over: root build file plus the module list. */
    private String buildFiles() throws IOException {
        StringBuilder b = new StringBuilder("The build files:\n");
        for (String name : List.of("pom.xml", "build.gradle", "build.gradle.kts",
                "settings.gradle", "settings.gradle.kts",
                "gradle/wrapper/gradle-wrapper.properties")) {
            Path f = ws.resolve(name);
            if (Files.isRegularFile(f)) {
                String content = Files.readString(f);
                b.append("\n--- ").append(name).append(" ---\n")
                        .append(content.length() > 6000 ? content.substring(0, 6000) + "\n[cut]"
                                : content).append('\n');
            }
        }
        List<Path> poms = Walls.poms(ws);
        if (poms.size() > 1) {
            b.append("\nModules with their own pom: ");
            for (Path p : poms) {
                b.append(ws.relativize(p).toString()).append("  ");
            }
        }
        return b.toString();
    }

    /** Every pin below the target, with its file: the bumper's work list and the critic's proof. */
    private String pinGrep() throws IOException {
        int target = to.isBlank() ? 99 : Integer.parseInt(to);
        Pattern pin = Pattern.compile(
                "<(?:maven\\.compiler\\.(?:source|target|release)|java\\.version|jdk\\.version"
                        + "|source|target|release|jvmTarget)>\\s*(?:1\\.)?(\\d+)\\s*<"
                        + "|JavaLanguageVersion\\.of\\((\\d+)\\)"
                        + "|(?:sourceCompatibility|targetCompatibility|jvmTarget)\\s*[=:]?\\s*['\"]?(?:1\\.)?(\\d+)");
        StringBuilder out = new StringBuilder();
        List<Path> files = new java.util.ArrayList<>(Walls.poms(ws));
        try (var s = Files.walk(ws)) {
            s.filter(p -> {
                String n = p.toString();
                return (n.endsWith("build.gradle") || n.endsWith("build.gradle.kts"))
                        && !n.contains("/target/") && !n.contains("/node_modules/");
            }).forEach(files::add);
        }
        for (Path f : files) {
            List<String> lines = Files.readAllLines(f);
            for (int i = 0; i < lines.size(); i++) {
                Matcher m = pin.matcher(lines.get(i));
                while (m.find()) {
                    String v = m.group(1) != null ? m.group(1)
                            : m.group(2) != null ? m.group(2) : m.group(3);
                    if (v != null && Integer.parseInt(v) < target) {
                        out.append(ws.relativize(f)).append(':').append(i + 1).append("  ")
                                .append(lines.get(i).strip()).append('\n');
                    }
                }
            }
        }
        return out.toString();
    }

    private static String cutAround(String content, String log, String rel) {
        Matcher line = Pattern.compile(Pattern.quote(rel) + ":\\[?(\\d+)").matcher(log);
        int at = line.find() ? Integer.parseInt(line.group(1)) : 1;
        List<String> lines = content.lines().toList();
        int lo = Math.max(0, at - 40);
        int hi = Math.min(lines.size(), at + 40);
        return "[lines " + (lo + 1) + "-" + hi + " of " + lines.size() + "]\n"
                + String.join("\n", lines.subList(lo, hi));
    }

    private String migrateScript() {
        try {
            Shell.Output out = Shell.run(ws, Map.of("BJV_FROM", from, "BJV_TO", to),
                    Duration.ofSeconds(2700), migrate, ws.toString(), from, to);
            String tail = Runner.tail(out.text());
            trace.applied("migrate", tail);
            return tail;
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("migrate: " + e.getMessage(), e);
        }
    }

    private void revert() {
        try {
            Shell.run(ws, Map.of(), Duration.ofMinutes(2), "git", "checkout", "--", ".");
        } catch (IOException | InterruptedException e) {
            trace.progress(bump, "revert failed: " + e.getMessage());
        }
    }

    private void price() {
        String estimate = agents.estimator().run("The bump " + bump + "; walls cleared: "
                + walls.appliedSoFar() + ". Estimate from this record.");
        Matcher m = Pattern.compile("minutes:\\s*(\\d+)").matcher(estimate);
        trace.priced(bump, m.find() ? m.group(1) : "", estimate);
    }

    private static String[] parseHop(String claim) {
        Matcher m = Pattern.compile("hop:\\s*(\\d+)\\s*->\\s*(\\d+)").matcher(claim == null ? "" : claim);
        if (m.find()) {
            return new String[]{m.group(1), m.group(2)};
        }
        return null;
    }

    /** The first of the allowed words found in the answer; the first option is the default. */
    private static String word(String reply, String... allowed) {
        String lower = reply == null ? "" : reply.toLowerCase();
        int best = Integer.MAX_VALUE;
        String chosen = allowed[0];
        for (String w : allowed) {
            int at = lower.indexOf(w);
            if (at >= 0 && at < best) {
                best = at;
                chosen = w;
            }
        }
        return chosen;
    }
}
