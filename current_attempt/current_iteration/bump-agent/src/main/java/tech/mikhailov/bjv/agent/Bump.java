package tech.mikhailov.bjv.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
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
 * Migrate (deterministic)            recipes by the project's own line; floors; target sweep
 * preparer ──→ prepare-critic         the proactive steps            (missed|overreach → once more)
 *    ↓
 * bumper ──→ bump-critic              land the effective target      (not-landed → once more)
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
 * <p>THE GATE IS NOT A TOOL. Producers can try their own build, and what they learn from it is
 * feedback; the build that DECIDES runs here, between the stages, because whether the gate ran after
 * an edit is not a model's choice. Wherever the builds established the outcome this class computes
 * it and no model is called, since routing a deterministic outcome through a model makes it sampled.
 *
 * <p>PRODUCERS EDIT THE WORKSPACE THROUGH THEIR TOOLS, so what a phase did is read back from git,
 * not from what the agent said it did. A producer that describes an edit it never made is then
 * indistinguishable from one that made none, which is the honest reading.
 */
public final class Bump {

    /**
     * Turns of the reflect loop.
     *
     * <p>Rung-1's recovered repos took four to nine iterations with a mean of six, so a cap of
     * eight cut the tail off the very distribution it was drawn from. Walls are serial: each turn
     * clears one and reveals the next, so the repos needing the most turns are the ones with the
     * most walls, not the ones making the least progress. The loop already ends on its own when a
     * turn changes nothing, when the troubleshooter declines, or when its critic calls an edit
     * gaming; this number is only a backstop against a loop that never converges.
     */
    private static final int TURNS = Integer.parseInt(
            System.getenv().getOrDefault("BJV_TURNS", "16"));
    /** One re-ask per objection, quoting whoever objected. Every pair shares it, stated once. */
    private static final int REASK = 1;
    /** Steps one campaign may order before the loop critic gets to read it. */
    private static final int STEPS = Integer.parseInt(
            System.getenv().getOrDefault("BJV_STEPS", "6"));

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("usage: Bump <checkout> <repo|sha[|from|to]> [results-dir]");
            System.exit(2);
        }
        Path checkout = Path.of(args[0]);
        String bump = args[1];
        Path results = Path.of(args.length > 2 ? args[2] : "results");

        String slug = bump.replaceAll("[^A-Za-z0-9]+", "_");
        JsonlTrace trace = new JsonlTrace(results.resolve(slug).resolve("trace.jsonl"),
                results.resolve("settlements.jsonl"), bump);
        try {
            Bump b = new Bump(checkout, bump, trace);
            String account = b.run();
            String state = account.split("\n", 2)[0];
            trace.settled(bump, state, account, b.baselineGreen, b.gateGreen);
        } catch (Exception e) {
            // EVERY failure leaves a row, not only the unchecked ones. A checked IOException
            // escaping main killed the process with the last settlement still reading "bumping",
            // so the sweep could not tell a crash from a bump still in flight.
            trace.failed(bump, e);
            e.printStackTrace();
            System.exit(1);
        }
    }

    private final Path ws;
    private final String bump;
    private final Trace trace;
    private final Tree tree;
    private String from;
    private String to;
    private Agents agents;
    private Runner runner;
    private Walls walls;
    // What the builds actually did, carried to the settlement. An implication is not a record.
    private boolean baselineGreen;
    private boolean gateGreen;
    private Set<String> pre = Set.of();
    private Gate.Verdict lastVerdict;
    private Security security;
    private Security.Scan before = Security.Scan.notMeasured("not run");
    private Security.Scan after = Security.Scan.notMeasured("the gate never went green");
    private Security.Delta delta = Security.Delta.unknown("not computed", -1, -1);

    private Bump(Path ws, String bump, Trace trace) {
        String[] parts = bump.split("\\|");
        // from and to are REQUIRED now: the hop is the experiment's independent variable and there
        // is no sensible default for it. A row without one is a manifest bug, not a thing to guess.
        if (parts.length < 4) {
            throw new IllegalArgumentException("bump must be repo|sha|from|to, got: " + bump);
        }
        this.ws = ws;
        this.bump = bump;
        this.from = parts[2];
        this.to = parts[3];
        this.trace = trace;
        this.tree = new Tree(ws, note -> trace.progress(bump, note));
    }

    /** The whole bump. Read it top to bottom; that is the order, and nothing can reorder it. */
    private String run() throws IOException {
        // ---- SURVEY: does the project agree it is where the manifest says?
        //
        // THE HOP IS PRESCRIBED, NOT DISCOVERED. It arrives in the manifest row and nothing here
        // may change it: the target is the experiment's independent variable, and an agent that
        // picks it makes every run a different experiment. It also went wrong in exactly the way
        // that predicts — the surveyor demoted three repos from 11->17 to 8->11 off a `release 8`
        // flag, the chain then baselined at a JDK those projects cannot build on, and all three
        // were recorded as the project's failure.
        //
        // The surveyor still runs, because "this project is not at 11" is worth knowing about a
        // manifest row. It is recorded as a disagreement and changes nothing.
        trace.progress(bump, "survey: does the project agree it is at JDK " + from);
        // The surveyor has tools; the brief is a starting point, not the whole tree.
        Agents surveying = new Agents(Model.forProducer(trace), ws, null, tree,
                Hop.of(from.isBlank() ? "17" : from, to.isBlank() ? "17" : to), trace);
        String evidence = buildFiles() + "\nThe deterministic detector's guess: "
                + (from.isBlank() ? "none" : from + "->" + to);
        String claim = surveying.surveyor().run(evidence);
        String check = surveying.surveyCritic().run(evidence + "\n\nThe claim:\n" + claim);
        String[] read = parseHop(claim);
        if (read != null && !read[0].equals(from)) {
            // Recorded, not obeyed. A row whose `from` the project disputes is a row worth looking
            // at; it is not a licence to run a different experiment than the one that was queued.
            trace.progress(bump, "survey disagrees: it reads the project as JDK " + read[0]
                    + " while the manifest prescribes " + from + "->" + to
                    + "; proceeding with the prescribed hop");
        }
        String hoptools = Env.get("BJV_HOPTOOLS");
        if (hoptools == null) {
            throw new IllegalStateException(
                    "BJV_HOPTOOLS must be the host path of hoptools/ (jvm-run is invoked from it)");
        }
        runner = new Runner(ws, hoptools);
        walls = new Walls(ws);
        security = new Security(ws, hoptools, trace);
        // The producers' try_build must target the hop the survey settled, so they are built now.
        agents = new Agents(Model.forProducer(trace), ws, runner, tree, Hop.of(from, to), trace);

        // ---- BASELINE: a fact. No baseline, no bump.
        trace.progress(bump, "baseline: building and testing under JDK " + from);
        Runner.Result preBuild = runner.build(from);
        trace.built("baseline-build", preBuild);
        if (preBuild.infra()) {
            return "no-baseline\nthe project does not build under its own JDK " + from + ":\n"
                    + preBuild.summary();
        }
        runner.clearReports();
        Runner.Result preTest = runner.test(from);
        trace.built("baseline-test", preTest);
        // A RED TEST BEFORE THE BUMP IS NOT THIS BUMP'S PROBLEM. What conservation asks is whether
        // the tests that passed still pass, and a suite with three broken tests answers that just as
        // well as a green one: the three were red before anything was touched and stay outside the
        // set. Refusing here measured nothing and called it rigour.
        //
        // IT ALSO JUDGED THE SAME CORPUS BY TWO STANDARDS. jvmjob runs Maven with
        // -Dmaven.test.failure.ignore=true, so a Maven project with red tests exits 0, passes this
        // check and conserves its green set; the Gradle invocation has no such flag, so an identical
        // project is refused. Measured over the corpus: 28 of 30 no-baseline verdicts are Gradle
        // against 2 Maven, while the two pass at an identical rate otherwise, 39 apiece. That gap
        // was this line, not anything about the projects.
        // THE REPORTS DECIDE, NOT THE EXIT CODE. Whether tests ran is a fact about the workspace,
        // and it is written down: the JUnit XML each runner leaves behind. Asking the log instead
        // meant asking whether one build system's phrasing appeared in another's output, and a
        // Gradle suite that ran all eight of its tests and failed one was filed as an
        // infrastructure failure because the exit code was non-zero and "Tests run:" is Maven's
        // wording. The set below is read from those reports and is the honest answer to both
        // questions at once: what ran, and what passed.
        // THE SET, NOT THE COUNT. Conservation is which tests passed, so a bump that loses one and
        // generates another cannot net out to zero.
        pre = Gate.passing(ws);
        baselineGreen = preTest.passed();
        if (pre.isEmpty()) {
            return "no-baseline\n" + (preTest.infra()
                    ? "the tests could not be RUN under the project's own JDK " + from
                    + ", so there is nothing to conserve:\n" + preTest.summary()
                    : "no test passed under the project's own JDK " + from + ", so there is nothing"
                    + " to conserve and a bump here would be unverifiable:\n" + preTest.summary());
        }
        trace.applied("baseline", "tests passing under JDK " + from + ": " + pre.size()
                + (baselineGreen ? "" : " (the suite is not all green; the red ones were red before"
                + " this bump and are not in the set)"));

        // ---- SECURITY BEFORE: the project's own state, and the last moment it still is.
        // Migrate applies recipes, floors and a target sweep next, every one of which moves a
        // resolved version, so a scan taken after it is not this project's prior state. It also
        // has to follow a build, because the collect is offline and copies only what the build
        // already pulled down.
        trace.progress(bump, "security: scanning before any migration, under JDK " + from);
        before = security.scan(from, "before");
        securityBeforePhase();

        // ---- PREPARE: deterministic pre-pass first, then the proactive steps, judged.
        // The before-scan travels into the migration: the Tomcat floor has to know which line the
        // project actually resolved, and no build file says that.
        tree.excludeBuildOutput();
        String prePass = new Migrate(ws, hoptools, trace).run(from, to, before);
        // The deterministic pass is not up for review, so it lands before anyone can object.
        tree.land("migrate");
        preparePhase(prePass);

        // ---- BUMP: land the target, judged against the pin grep re-run after the edits.
        bumpPhase();

        // ---- TROUBLESHOOT: the bounded loop. Free things first, every turn.
        String lastLog = "";
        for (int turn = 1; turn <= TURNS; turn++) {
            trace.progress(bump, "gate: turn " + turn + " of " + TURNS + " under JDK " + to);
            // The gate measures bytecode, so it must compile bytecode rather than inherit the
            // baseline's. Without this Maven finds the old classes newer than the sources and
            // skips the compile, and the target is read off the level the project started at.
            runner.clearClasses();
            Runner.Result build = runner.build(to);
            trace.built("gate-build-" + turn, build);
            if (!build.infra()) {
                runner.clearReports();
                Runner.Result test = runner.test(to);
                trace.built("gate-test-" + turn, test);
                // THE SCORER DECIDES, NOT THE EXIT CODE. A green build with an unraised module or a
                // quietly dropped test is exactly the false pass this measures.
                Gate.Verdict v = Gate.decide(pre, Gate.passing(ws), !test.infra(),
                        Gate.effectiveTarget(ws), Integer.parseInt(to));
                trace.applied("gate", "turn " + turn + ": " + v.state() + " (pre=" + v.preTests()
                        + " lost=" + v.lost() + " effective-target=" + v.effectiveTarget() + ")"
                        + names(v.missing()));
                if (v.pass()) {
                    gateGreen = true;
                    // THE ONLY PLACE THE AFTER SCAN MEANS ANYTHING. The workspace has just built
                    // and tested green at the target, so the offline collect is complete. On any
                    // other exit the collect copies whatever resolved before the build died, and
                    // the count falls because modules are missing rather than because anything was
                    // fixed: the corpus's largest apparent wins are dead builds.
                    trace.progress(bump, "security: scanning after a green gate, under JDK " + to);
                    after = security.scan(to, "after");
                    delta = Security.compare(before, after);
                    trace.applied("security-delta", delta.valid()
                            ? delta.before() + " -> " + delta.after() + " CRITICAL+HIGH; cleared "
                            + delta.cleared() + ", introduced " + delta.introduced()
                            : "UNKNOWN: " + delta.why());
                    securityAfterPhase();
                    price();
                    return "PASS\n" + v.preTests() + " tests conserved, effective target "
                            + v.effectiveTarget() + "; CRITICAL+HIGH " + securitySummary()
                            + "; walls cleared: " + walls.appliedSoFar();
                }
                lastVerdict = v;
                lastLog = failureFor(v, test);
            } else {
                lastVerdict = null;
                lastLog = build.summary();
            }
            Walls.Turn t = walls.match(lastLog, Integer.parseInt(to));
            if (t.fixed()) {
                trace.applied("walls", t.what());
                continue;
            }
            if (!troubleshoot(lastLog)) {
                break;
            }
        }

        // ---- CLOSERS: argue only the unsettled, price everything.
        String argued = agents.verdict().run(brief(lastLog)
                + (lastVerdict == null ? "" : "\nThe scorer's last verdict: " + lastVerdict.state())
                + "\nThe reflect loop ended without a green gate. Walls cleared: "
                + walls.appliedSoFar());
        price();
        return word(argued, "blocked-dependency", "behavior-change", "infra") + "\n" + argued;
    }

    /**
     * The security reading, and its critic. ADVISORY: neither has a tool that writes.
     *
     * <p>Nothing downstream lifts a dependency for security, and an edit made here would be
     * charged by the reward and flagged by the prepare-critic as answering no trigger. What this
     * produces is a record: which findings this hop could plausibly reach, for whoever decides how
     * a CVE count and a PASS trade against each other.
     */
    private void securityBeforePhase() {
        if (!before.measured()) {
            trace.progress(bump, "security-before: nothing to read (" + before.why() + ")");
            return;
        }
        String brief = "Migration: JDK " + from + " -> " + to + " (" + bump + ")\n\n"
                + "The scan, taken before any migration work:\n" + Security.digest(before, 12);
        String reading = agents.securityBefore().run(brief);
        String audit = agents.securityBeforeCritic().run(brief + "\n\nThe reading:\n" + reading);
        if (!word(audit, "sound", "overclaimed", "missed-family").equals("sound")) {
            // One re-ask, as everywhere else. The answer is a record either way; there is no edit
            // to revert and nothing downstream blocks on it.
            agents.securityBefore().run(brief + "\n\nA reviewer objected to your reading:\n"
                    + audit + "\nAnswer again.");
        }
    }

    /** The accounting is computed; this judges whether it describes a real change. */
    private void securityAfterPhase() {
        if (!after.measured()) {
            trace.progress(bump, "security-after: not measured (" + after.why() + ")");
            return;
        }
        String brief = "Migration: JDK " + from + " -> " + to + " (" + bump + ")\n\n"
                + "BEFORE:\n" + Security.digest(before, 8)
                + "\nAFTER:\n" + Security.digest(after, 8)
                + "\nThe harness computed: " + (delta.valid()
                ? "cleared " + delta.cleared() + ", remaining " + delta.remaining()
                + ", introduced " + delta.introduced()
                + (delta.clearedBy().isEmpty() ? "" : "\nCleared: "
                + String.join(", ", delta.clearedBy()))
                : "UNKNOWN, and it flagged why: " + delta.why());
        String judgement = agents.securityAfter().run(brief);
        String audit = agents.securityAfterCritic().run(brief + "\n\nThe judgement:\n" + judgement);
        if (!word(audit, "sound", "wrong-call").equals("sound")) {
            agents.securityAfter().run(brief + "\n\nA reviewer objected:\n" + audit
                    + "\nAnswer again.");
        }
    }

    /** What to put in the settlement line, in the form the dashboard parses. */
    private String securitySummary() {
        if (!before.measured()) {
            return "not measured";
        }
        if (!after.measured() || !delta.valid()) {
            return before.total() + " -> unknown";
        }
        return before.total() + " -> " + after.total();
    }

    /** The preparer and its critic: the proactive steps, executed and then audited. */
    private void preparePhase(String prePass) throws IOException {
        String brief = "Migration: JDK " + from + " -> " + to + " (" + bump + ")"
                + "\n\nThe deterministic pre-pass already did:\n" + prePass + "\n" + buildFiles();
        judgedThenLanded("prepare", agents.preparer(), agents.prepareCritic(), brief,
                diff -> brief + "\n\nWhat the preparer changed:\n" + diff,
                "sound", "missed", "overreach");
    }

    /** The bumper and its critic: the pins the recipe under-applied. */
    private void bumpPhase() throws IOException {
        String pins = pinGrep();
        String brief = "Migration: JDK " + from + " -> " + to
                + "\n\nPins found still below " + to + ":\n" + (pins.isBlank() ? "(none)" : pins);
        judgedThenLanded("bump", agents.bumper(), agents.bumpCritic(), brief, diff -> {
            String left = "";
            try {
                left = pinGrep();
            } catch (IOException e) {
                left = "(grep failed: " + e.getMessage() + ")";
            }
            return brief + "\n\nWhat the bumper changed:\n" + diff
                    + "\n\nPins STILL below target after those edits:\n"
                    + (left.isBlank() ? "(none)" : left);
        }, "sound", "not-landed", "overreach");
    }

    /**
     * One producer round with its critic, and one re-ask when the critic objects.
     *
     * <p>What the producer DID is read from git, not from its answer: the critic judges the
     * workspace, so a phase that narrates an edit it never made is judged as having made none.
     */
    private void judged(String stage, Agents.Agent producer, Agents.Agent critic, String brief,
                        Audit audit, String... words) throws IOException {
        String reply = producer.run(brief);
        for (int again = 0; again <= REASK; again++) {
            String diff = tree.diff();
            if (diff.isBlank()) {
                trace.progress(bump, stage + ": no edit reached the workspace ("
                        + reply.lines().findFirst().orElse("") + ")");
                return;
            }
            trace.applied(stage, diff);
            String judgement = critic.run(audit.brief(diff));
            String verdict = word(judgement, words);
            if (words[0].equals(verdict) || again == REASK) {
                return;
            }
            reply = producer.run(brief + "\n\nA reviewer objected to what you did:\n" + judgement
                    + "\nAddress the objection. Do not repeat an edit you already made.");
        }
    }

    /** A judged stage whose work then lands, so no later objection can reach back past it. */
    private void judgedThenLanded(String stage, Agents.Agent producer, Agents.Agent critic,
                                  String brief, Audit audit, String... words) throws IOException {
        judged(stage, producer, critic, brief, audit, words);
        tree.land(stage);
    }

    /** The brief a critic gets, built once the producer's diff is known. */
    @FunctionalInterface
    private interface Audit {
        String brief(String diff);
    }

    /**
     * THE TROUBLESHOOT CONSTRUCTION: a campaign of steps, and a critic of the campaign.
     *
     * <p>Two producer/critic pairs, one inside the other. The inner pair fixes one thing and is
     * judged on that one thing. The outer pair decides what the sequence should be and whether it
     * added up to anything, and it is the only thing here that may put the workspace back.
     *
     * <p>The levels exist because the two questions are different and were being answered by the
     * same verdict. A step critic saying "this edit fakes the behaviour" is a correction, not a
     * conclusion; it used to end the entire bump, discarding the remedy the critic had just written
     * out and leaving fifteen of sixteen gate turns unspent.
     *
     * @return true when a step landed and the gate should be re-run, false when the campaign is over
     */
    private boolean troubleshoot(String log) throws IOException {
        String floor = tree.head();
        String feedback = "";
        for (int campaign = 0; campaign <= REASK; campaign++) {
            boolean landed = campaignOfSteps(log, floor, feedback);
            String judgement = agents.troubleshootLoopCritic(floor)
                    .run("The failing build:\n" + log
                            + "\n\nThe whole campaign, since it began:\n" + tree.diffSince(floor)
                            + "\n\nThe steps that landed:\n" + tree.history(floor));
            if (word(judgement, "done", "again").equals("done") || campaign == REASK) {
                return landed;
            }
            trace.progress(bump, "troubleshoot-loop-critic sent the campaign back: "
                    + judgement.lines().findFirst().orElse(""));
            // The critic may already have rewound; if it did not, its objection stands on top of
            // whatever is there, which is its choice to make and not this loop's.
            feedback = "\n\nA reviewer read your whole campaign and sent it back:\n" + judgement;
        }
        return false;
    }

    /** One campaign: the loop proposer orders steps until it stops or the budget is spent. */
    private boolean campaignOfSteps(String log, String floor, String feedback) throws IOException {
        boolean landed = false;
        for (int step = 0; step < STEPS; step++) {
            String order = agents.troubleshootLoopProposer(floor)
                    .run(brief(log) + feedback
                            + "\n\nSteps landed so far in this campaign:\n" + tree.history(floor)
                            + "\n\nWhat the campaign has changed:\n" + tree.diffSince(floor));
            String head = order.stripLeading();
            if (head.startsWith("DONE:") || head.startsWith("BLOCKED:")) {
                trace.progress(bump, "troubleshoot-loop: " + head.lines().findFirst().orElse(""));
                return landed;
            }
            if (step(log, order)) {
                landed = true;
                tree.land("step: " + head.lines().findFirst().orElse("").strip());
            } else {
                // A step nobody could land is a signal about the order, not about the workspace,
                // and the proposer sees it next time round in the history that did not grow.
                trace.progress(bump, "troubleshoot-loop: the ordered step did not land");
            }
        }
        trace.progress(bump, "troubleshoot-loop: step budget spent");
        return landed;
    }

    /**
     * One step: an edit, and a reviewer of that edit.
     *
     * <p>An objection is a correction and stays inside this method. It reverts the step and re-asks
     * with the reviewer's own words, which is the whole reason the reviewer wrote them; what it may
     * never do is end the campaign, because whether the campaign should end is a judgement one
     * level up.
     */
    private boolean step(String log, String order) throws IOException {
        String brief = brief(log) + "\n\nThe step you have been asked to make:\n" + order;
        String reply = agents.troubleshooter().run(brief);
        List<String> rejected = new ArrayList<>();
        for (int attempt = 0; attempt <= REASK; attempt++) {
            if (reply.stripLeading().startsWith("BLOCKED:")) {
                trace.progress(bump, "step declined: " + reply.lines().findFirst().orElse(""));
                return false;
            }
            String now = tree.diff();
            if (now.isBlank()) {
                trace.progress(bump, "step reached the workspace as nothing");
                return false;
            }
            trace.applied("troubleshooter", now);
            String judgement = agents.troubleCritic().run("The failing build said:\n" + log
                    + "\n\nThe edits now in the workspace:\n" + now + "\n\nWhat they said:\n" + reply);
            if ("sound".equals(word(judgement, "sound", "gaming", "off-target"))) {
                return true;
            }
            tree.revert();
            rejected.add("You tried:\n" + reply + "\nA reviewer rejected it:\n" + judgement);
            if (attempt == REASK) {
                trace.progress(bump, "step rejected twice; handing back to the loop");
                return false;
            }
            reply = agents.troubleshooter().run(brief
                    + "\n\nYour earlier attempts at this step, and why they were rejected:\n"
                    + String.join("\n\n", rejected)
                    + "\nEach was reverted. Do not repeat one.");
        }
        return false;
    }

    // ---- what the agents are handed to start from; they have tools for the rest ----

    private String brief(String log) throws IOException {
        return "Migration: JDK " + from + " -> " + to + " (" + bump + ")"
                + "\nWalls already cleared mechanically: " + walls.appliedSoFar()
                + "\n\nThe failing build:\n" + log;
    }

    private String buildFiles() throws IOException {
        StringBuilder b = new StringBuilder("The root build files:\n");
        for (String name : List.of("pom.xml", "build.gradle", "build.gradle.kts",
                "settings.gradle", "settings.gradle.kts",
                "gradle/wrapper/gradle-wrapper.properties")) {
            Path f = ws.resolve(name);
            if (Files.isRegularFile(f)) {
                String content = Files.readString(f);
                b.append("\n--- ").append(name).append(" ---\n")
                        .append(content.length() > 6000 ? content.substring(0, 6000) + "\n[cut; read"
                                + " the file if you need the rest]" : content).append('\n');
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
                        + "|(?:sourceCompatibility|targetCompatibility|jvmTarget)\\s*[=:]?\\s*"
                        + "['\"]?(?:1\\.)?(\\d+)");
        StringBuilder out = new StringBuilder();
        List<Path> files = new ArrayList<>(Walls.poms(ws));
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

    private Shell.Output git(String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of("git", "-c", "safe.directory=" + ws));
        cmd.addAll(List.of(args));
        return Shell.run(ws, Map.of(), Duration.ofMinutes(3), cmd.toArray(new String[0]));
    }


    /**
     * What to put in front of the next agent: the scorer's finding, in the terms it can act on.
     *
     * <p>A conservation failure and an unraised target are not build errors, and handing either one
     * the build log invites a fix for a problem the build does not have.
     */
    /**
     * The lost tests, as a readable tail.
     *
     * <p>Capped, because a jakarta migration can drop four figures of them and a brief that is
     * mostly test names is a brief the model skims. The cap is generous enough to show a pattern,
     * which is the thing worth reading: one package gone is a different problem from a scatter.
     */
    private static String names(java.util.List<String> missing) {
        if (missing.isEmpty()) {
            return "";
        }
        int show = Math.min(missing.size(), 40);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < show; i++) {
            b.append("\n  ").append(missing.get(i));
        }
        if (missing.size() > show) {
            b.append("\n  ... and ").append(missing.size() - show).append(" more");
        }
        return b.toString();
    }

    private String failureFor(Gate.Verdict v, Runner.Result test) {
        return switch (v.state()) {
            case "FAIL_test_conservation" -> "The build is green under JDK " + to + " but " + v.lost()
                    + " of " + v.preTests() + " tests that passed under JDK " + from
                    + " no longer pass. Find what the migration dropped.\nThe tests that stopped "
                    + "passing are:" + names(v.missing()) + "\n" + test.summary();
            case "FAIL_target_not_bumped" -> "The build is green and the tests are conserved, but "
                    + "the effective bytecode target is " + v.effectiveTarget() + ", not " + to
                    + ". At least one compiled module is still below the target.";
            case "FAIL_no_main_bytecode" -> "The build reported success but produced no inspectable "
                    + "main classes, so the bump cannot be verified.";
            default -> test.summary();
        };
    }

    private void price() {
        String estimate = agents.estimator().run("The bump " + bump + " (JDK " + from + " -> " + to
                + "); walls cleared mechanically: " + walls.appliedSoFar()
                + ". What the workspace became:\n" + tree.diff());
        Matcher m = Pattern.compile("minutes:\\s*(\\d+)").matcher(estimate);
        trace.priced(bump, m.find() ? m.group(1) : "", estimate);
    }

    private static String[] parseHop(String claim) {
        Matcher m = Pattern.compile("hop:\\s*(\\d+)\\s*->\\s*(\\d+)")
                .matcher(claim == null ? "" : claim);
        return m.find() ? new String[]{m.group(1), m.group(2)} : null;
    }

    /** The first of the allowed words found in the answer; the first option is the default. */
    static String word(String reply, String... allowed) {
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
