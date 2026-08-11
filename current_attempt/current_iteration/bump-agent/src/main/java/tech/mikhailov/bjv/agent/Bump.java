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
        if (parts.length < 2) {
            throw new IllegalArgumentException("bump must be repo|sha[|from|to], got: " + bump);
        }
        this.ws = ws;
        this.bump = bump;
        this.from = parts.length > 2 ? parts[2] : "";
        this.to = parts.length > 3 ? parts[3] : "";
        this.trace = trace;
    }

    /** The whole bump. Read it top to bottom; that is the order, and nothing can reorder it. */
    private String run() throws IOException {
        // ---- SURVEY: which hop, actually. The caller's from|to is the detector's guess, not law.
        trace.progress(bump, "survey: which hop is this");
        // The surveyor has tools; the brief is a starting point, not the whole tree.
        Agents surveying = new Agents(Model.forProducer(trace), ws, null, to.isBlank() ? "17" : to, trace);
        String evidence = buildFiles() + "\nThe deterministic detector's guess: "
                + (from.isBlank() ? "none" : from + "->" + to);
        String claim = surveying.surveyor().run(evidence);
        String[] hop = parseHop(claim);
        String check = surveying.surveyCritic().run(evidence + "\n\nThe claim:\n" + claim);
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
        security = new Security(ws, hoptools, trace);
        // The producers' try_build must target the hop the survey settled, so they are built now.
        agents = new Agents(Model.forProducer(trace), ws, runner, to, trace);

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
        baselineGreen = !preTest.infra() && preTest.passed();
        if (!baselineGreen) {
            return "no-baseline\nthe tests are not green under the project's own JDK " + from
                    + ", so conservation cannot be judged:\n" + preTest.summary();
        }
        // THE SET, NOT THE COUNT. Conservation is which tests passed, so a bump that loses one and
        // generates another cannot net out to zero.
        pre = Gate.passing(ws);
        trace.applied("baseline", "tests passing under JDK " + from + ": " + pre.size());
        if (pre.isEmpty()) {
            return "no-baseline\nno test reports under JDK " + from + ", so there is nothing to "
                    + "conserve and a bump here would be unverifiable";
        }

        // ---- SECURITY BEFORE: the project's own state, and the last moment it still is.
        // Migrate applies recipes, floors and a target sweep next, every one of which moves a
        // resolved version, so a scan taken after it is not this project's prior state. It also
        // has to follow a build, because the collect is offline and copies only what the build
        // already pulled down.
        trace.progress(bump, "security: scanning before any migration, under JDK " + from);
        before = security.scan(from, "before");
        securityBeforePhase();

        // ---- PREPARE: deterministic pre-pass first, then the proactive steps, judged.
        String prePass = new Migrate(ws, hoptools, trace).run(from, to);
        preparePhase(prePass);

        // ---- BUMP: land the target, judged against the pin grep re-run after the edits.
        bumpPhase();

        // ---- TROUBLESHOOT: the bounded loop. Free things first, every turn.
        String lastLog = "";
        for (int turn = 1; turn <= TURNS; turn++) {
            trace.progress(bump, "gate: turn " + turn + " of " + TURNS + " under JDK " + to);
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
                        + " lost=" + v.lost() + " effective-target=" + v.effectiveTarget() + ")");
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
            if (!troubleshootTurn(lastLog)) {
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
        judged("prepare", agents.preparer(), agents.prepareCritic(), brief,
                diff -> brief + "\n\nWhat the preparer changed:\n" + diff,
                "sound", "missed", "overreach");
    }

    /** The bumper and its critic: the pins the recipe under-applied. */
    private void bumpPhase() throws IOException {
        String pins = pinGrep();
        String brief = "Migration: JDK " + from + " -> " + to
                + "\n\nPins found still below " + to + ":\n" + (pins.isBlank() ? "(none)" : pins);
        judged("bump", agents.bumper(), agents.bumpCritic(), brief, diff -> {
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
            String diff = diff();
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

    /** The brief a critic gets, built once the producer's diff is known. */
    @FunctionalInterface
    private interface Audit {
        String brief(String diff);
    }

    /** One troubleshooter round. True to keep looping, false to stop. */
    private boolean troubleshootTurn(String log) throws IOException {
        String before = diff();
        String brief = brief(log);
        String reply = agents.troubleshooter().run(brief);
        for (int again = 0; again <= REASK; again++) {
            if (reply.stripLeading().startsWith("BLOCKED:")) {
                trace.progress(bump, "troubleshooter declined: "
                        + reply.lines().findFirst().orElse(""));
                return false;
            }
            String now = diff();
            if (now.equals(before)) {
                trace.progress(bump, "troubleshooter changed nothing this turn");
                return false;
            }
            trace.applied("troubleshooter", now);
            String judgement = agents.troubleCritic().run("The failing build said:\n" + log
                    + "\n\nThe edits now in the workspace:\n" + now + "\n\nWhat they said:\n" + reply);
            String verdict = word(judgement, "sound", "gaming", "off-target");
            if ("gaming".equals(verdict)) {
                revert();
                trace.progress(bump, "trouble-critic: gaming; workspace reverted");
                return false;
            }
            if ("off-target".equals(verdict) && again < REASK) {
                revert();
                reply = agents.troubleshooter().run(brief
                        + "\nA reviewer judged your edit aims at the wrong wall:\n" + judgement
                        + "\nYour edit was reverted. Try again.");
                continue;
            }
            return true;
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

    /**
     * What the workspace has become, from git. The record of a phase, not its claim about itself.
     *
     * <p>A FAILED GIT IS AN EMPTY DIFF, NOT A DIFF OF THE ERROR. git writes its usage text to
     * stdout and exits non-zero when it will not read a repository, and that text is not blank —
     * so returning it made every phase look as though it had edited something, handed the critic
     * git's usage message as the evidence to audit, and made a producer answering NOTHING-TO-DO
     * indistinguishable from one that worked. The honest reading of "git could not tell me" is
     * "nothing is recorded", loudly.
     */
    private String diff() {
        try {
            Shell.Output stat = git("diff", "--stat=200", "--", ".");
            Shell.Output full = git("diff", "-U2", "--", ".");
            if (!stat.ok() || !full.ok()) {
                trace.progress(bump, "git diff failed: " + Runner.tail(stat.text()));
                return "";
            }
            String body = full.text();
            return stat.text() + (body.length() > 20000
                    ? body.substring(0, 20000) + "\n[diff truncated]" : body);
        } catch (IOException | InterruptedException e) {
            trace.progress(bump, "git diff failed: " + e.getMessage());
            return "";
        }
    }

    /**
     * git, told this workspace is safe to read.
     *
     * <p>The chain runs as root in its container while the checkout is owned by the user who
     * cloned it, and git's dubious-ownership guard then refuses the repository. The guard is
     * protecting against reading a repo someone else controls; this one we cloned ourselves a few
     * seconds earlier, so the exception is stated per call rather than disabled image-wide.
     */
    private Shell.Output git(String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of("git", "-c", "safe.directory=" + ws));
        cmd.addAll(List.of(args));
        return Shell.run(ws, Map.of(), Duration.ofMinutes(3), cmd.toArray(new String[0]));
    }

    private void revert() {
        try {
            Shell.Output out = git("checkout", "--", ".");
            if (!out.ok()) {
                trace.progress(bump, "revert failed: " + Runner.tail(out.text()));
            }
        } catch (IOException | InterruptedException e) {
            trace.progress(bump, "revert failed: " + e.getMessage());
        }
    }

    /**
     * What to put in front of the next agent: the scorer's finding, in the terms it can act on.
     *
     * <p>A conservation failure and an unraised target are not build errors, and handing either one
     * the build log invites a fix for a problem the build does not have.
     */
    private String failureFor(Gate.Verdict v, Runner.Result test) {
        return switch (v.state()) {
            case "FAIL_test_conservation" -> "The build is green under JDK " + to + " but " + v.lost()
                    + " of " + v.preTests() + " tests that passed under JDK " + from
                    + " no longer pass. Find what the migration dropped.\n" + test.summary();
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
                + ". What the workspace became:\n" + diff());
        Matcher m = Pattern.compile("minutes:\\s*(\\d+)").matcher(estimate);
        trace.priced(bump, m.find() ? m.group(1) : "", estimate);
    }

    private static String[] parseHop(String claim) {
        Matcher m = Pattern.compile("hop:\\s*(\\d+)\\s*->\\s*(\\d+)")
                .matcher(claim == null ? "" : claim);
        return m.find() ? new String[]{m.group(1), m.group(2)} : null;
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
