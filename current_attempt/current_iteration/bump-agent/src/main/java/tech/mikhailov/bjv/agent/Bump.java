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
 * THE ORDER, run in a sequence nothing can rewrite, with the gate between every phase that changes
 * the workspace.
 *
 * <pre>
 * surveyor ──→ survey-critic          which hop is this, actually   (wrong-hop → adopt correction)
 *    ↓
 * baseline @ from-JDK                 a FACT; no baseline, no bump; captured per module
 *    ↓
 * security-before                     the last moment this is still the project's own state
 *    ↓
 * module-filter ──→ its critic        which modules this bump leaves alone (a skip is dangerous)
 *    ↓
 * modules triad: planner → [ three passes ] → verifier
 *   before-pins   per module          what the new JDK needs, before it moves
 *   bump          per module          the step that moves the JDK
 *   after-pins    per module          what only resolves once it has moved
 *    ↓
 * reflect loop, bounded:
 *   gate @ to-JDK                     green → settled by the build, no model involved
 *   troubleshooter ──→ trouble-critic the residue                    (gaming → revert and stop)
 *    ↓
 * verdict                             argues ONLY what execution could not settle
 * estimator                           prices the attempt from the record
 * </pre>
 *
 * <p>EVERY STAGE IS PLANNER, DOER, VERIFIER, and the verifier holds the loop. See {@link Triad}.
 * The three passes are ordered globally rather than per module because one reactor compiles the
 * whole project with one javac: Lombok has to be in place everywhere before the JDK moves anywhere,
 * and Spring Boot cannot resolve until after it has.
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

    /** One re-ask per objection, quoting whoever objected. Every pair shares it, stated once. */
    private static final int REASK = 1;
    /** Steps one campaign may order before the loop critic gets to read it. */
    private static final int STEPS = Integer.parseInt(
            System.getenv().getOrDefault("BJV_STEPS", "6"));

    /**
     * HOW MANY TIMES ONE MODULE MAY BE COMPILED AND REPAIRED.
     *
     * <p>Much smaller than the sixteen the repository loop used, and deliberately: that budget was
     * spent on the whole tree once, and this one is spent per module. Sixteen here would be
     * sixteen times the modules, and a twenty-module repository could order nineteen hundred
     * repair steps before the gate had run once.
     */
    private static final int MODULE_TURNS = Integer.parseInt(
            System.getenv().getOrDefault("BJV_MODULE_TURNS", "3"));

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("usage: Bump <checkout> <repo|sha[|from|to]> [results-dir]");
            System.exit(2);
        }
        Path checkout = Path.of(args[0]);
        String bump = args[1];
        Path results = Path.of(args.length > 2 ? args[2] : "results");
        // Where an edited prompt would be, if anybody made one. Set before any agent is built.
        Prompts.beside(results);
        // The same store, for the same reason: an edited list replaces the built-in entirely, and
        // a bump reads whichever was on disk when it started.
        Bom.beside(results);

        String slug = bump.replaceAll("[^A-Za-z0-9]+", "_");
        JsonlTrace trace = new JsonlTrace(results.resolve(slug).resolve("trace.jsonl"),
                results.resolve("settlements.jsonl"), bump);
        try {
            Bump b = new Bump(checkout, bump, trace);
            String account = b.run();
            String state = account.split("\n", 2)[0];
            trace.settled(bump, state, account, b.baselineGreen, b.gateGreen);
            // A GREEN GATE IS NOT COMPLIANCE. The verdict says the project builds under the target
            // and lost no test; it says nothing about whether the versions the target needs were
            // reached. Measured here because this is the last moment the working tree exists and
            // the hop is known, and filed beside the settlement rather than inside it.
            String[] part = bump.split("\\|");
            if (part.length >= 4) {
                try {
                    Path written = results.resolve(slug).resolve("trace.jsonl");
                    Hop measured = new Hop(Integer.parseInt(part[2]), Integer.parseInt(part[3]));
                    Bom.record(results, bump, Bom.measure(checkout, written, measured),
                            Bom.measureBefore(checkout, written, part[1], measured));
                } catch (NumberFormatException notAHop) {
                    // A manifest row with no target has nothing to be compliant with.
                }
            }
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
    // What the builds actually did, carried to the settlement. An implication is not a record.
    private boolean baselineGreen;
    private boolean gateGreen;
    private Set<String> pre = Set.of();
    private Gate.Verdict lastVerdict;

    /** Every module the build files declare, which is what scopes a per-module read. */
    private List<Modules.Module> allModules = List.of();
    /** The ones this bump works on: everything above, minus what the filter pair set aside. */
    private List<Modules.Module> modules = List.of();
    /** Each module's own passing set before anything moved, so a loss is attributed where it happened. */
    private Map<String, Set<String>> baselineByModule = Map.of();
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
        // The planner decides which evidence settles the question; the doer reads it; the verifier
        // checks the claim against the same files. A pair here had the reader choosing what counted
        // as proof and then producing it.
        String look = surveying.surveyPlanner().run(evidence);
        String claim = surveying.surveyDoer().run(evidence
                + "\n\nWhat would settle it, and where to look:\n" + look);
        String check = surveying.surveyVerifier().run(evidence + "\n\nThe claim:\n" + claim);
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
        security = new Security(ws, hoptools, trace);
        // The producers' try_build must target the hop the survey settled, so they are built now.
        agents = new Agents(Model.forProducer(trace), ws, runner, tree, Hop.of(from, to), trace);

        // ---- BASELINE: a fact. No baseline, no bump.
        //
        // FIRST, THOUGH: IS THE TOOLING EVEN THERE. Builds here are sealed, so a Gradle wrapper
        // resolves its distribution out of a staged cache and cannot download. Staging can stop
        // half way and leaves a directory that looks exactly like a distribution; Gradle finds it,
        // uses it, and dies reaching for a jar that was never unpacked. The verdict that produced
        // was "the project does not build under its own JDK", about a project not one line of which
        // had run. Four of this corpus's thirty-one no-baseline verdicts are that.
        String tooling = Staged.problem(ws, Env.get("BJV_GRADLE_DISTS"));
        if (!tooling.isEmpty()) {
            trace.progress(bump, "infra: " + tooling);
            return "infra\n" + tooling;
        }
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
        // The same read, split by module, off the reactor build that just ran. A module's lost tests
        // have to be measured against its own baseline or every loss lands on the repository and the
        // record cannot say where it happened.
        baselineByModule = Gate.baselinePerModule(ws);
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
        // THE ORDER IS THE POINT. Lombok has to move before the JDK, because a Lombok that cannot
        // read the new class file kills javac before anything else runs. Spring Boot has to move
        // after, because Boot 4.1 declares java.version 17 and cannot be resolved by a project
        // still on 11. Both used to happen in one pass with nothing sequencing them.
        agents.withRecipes(new Migrate(ws, hoptools, trace));

        // ---- MODULES: what this repository is actually made of, before anything is asked of it.
        modules = moduleFilterPhase();

        modulesPhase();

        // ---- GATE: the scorer, and no longer a loop.
        //
        // It ran sixteen times, with repair between the turns, and those turns were repair's: the
        // gate had to keep re-running to find out whether the last repair had worked. Repair lives
        // inside the module walk now, so what is left here is the one thing only this can decide.
        //
        // WHAT ONLY THIS CAN DECIDE. The passing set against the baseline, which is a whole-suite
        // fact: a per-module run cannot tell a test that was lost from one that moved. And the
        // lowest bytecode level any module actually emits, which is a property of the tree.
        //
        // WHAT THIS GIVES UP, knowingly: a module that compiles alone and breaks the reactor
        // because a sibling moved under it now has no repair path. Every module gate was green,
        // this one is red, and nothing tries again. The argument for taking that is that a
        // cross-module break is usually a bad edit, and a bad edit is better failed loudly than
        // papered over sixteen times.
        String lastLog;
        trace.progress(bump, "gate: building and testing the whole repository under JDK " + to);
        // The gate measures bytecode, so it must compile bytecode rather than inherit the
        // baseline's. Without this Maven finds the old classes newer than the sources and skips
        // the compile, and the target is read off the level the project started at.
        runner.clearClasses();
        Runner.Result build = runner.build(to);
        trace.built("gate-build", build);
        if (!build.infra()) {
            runner.clearReports();
            Runner.Result test = runner.test(to);
            trace.built("gate-test", test);
            // THE SCORER DECIDES, NOT THE EXIT CODE. A green build with an unraised module or a
            // quietly dropped test is exactly the false pass this measures.
            Gate.Verdict v = Gate.decide(pre, Gate.passing(ws), !test.infra(),
                    Gate.effectiveTarget(ws), Integer.parseInt(to));
            trace.applied("gate", v.state() + " (pre=" + v.preTests()
                    + " lost=" + v.lost() + " effective-target=" + v.effectiveTarget() + ")"
                    + names(v.missing())
                    + perModule(Integer.parseInt(to)));
            if (v.pass()) {
                gateGreen = true;
                // THE ONLY PLACE THE AFTER SCAN MEANS ANYTHING. The workspace has just built and
                // tested green at the target, so the offline collect is complete. On any other
                // exit the collect copies whatever resolved before the build died, and the count
                // falls because modules are missing rather than because anything was fixed: the
                // corpus's largest apparent wins are dead builds.
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
                        + v.effectiveTarget() + "; CRITICAL+HIGH " + securitySummary();
            }
            lastVerdict = v;
            lastLog = failureFor(v, test);
        } else {
            lastVerdict = null;
            lastLog = build.summary();
        }

        // ---- CLOSERS: argue only the unsettled, price everything.
        String context = brief(lastLog)
                + (lastVerdict == null ? "" : "\nThe scorer's last verdict: " + lastVerdict.state())
                + "\nThe reflect loop ended without a green gate.";
        // The planner names the ONE question execution could not settle. Without it the arguer was
        // choosing the question and answering it, and a verdict in this corpus once called a
        // dependency incompatible with JDK 21 on the strength of a compile error the troubleshooter
        // had caused itself.
        String question = agents.verdictPlanner().run(context);
        context = context + "\n\nWhat is actually unsettled here:\n" + question;
        String argued = agents.verdictDoer().run(context);

        // THE WORD IS WHAT THE CORPUS RECORDS, and nothing after this re-reads the log to check it.
        // One verdict here called a dependency incompatible with JDK 21 on the strength of a
        // compile error the troubleshooter had caused itself, and it stood because no one asked.
        for (int again = 0; again < REASK; again++) {
            String judgement = agents.verdictVerifier().run(context
                    + "\n\nYour colleague argues:\n" + argued);
            if (word(judgement, "sound", "wrong").equals("sound")) {
                break;
            }
            trace.progress(bump, "verdict-critic: " + judgement.lines().findFirst().orElse(""));
            argued = agents.verdictDoer().run(context
                    + "\n\nYou argued:\n" + argued
                    + "\n\nA reviewer checked it against the record and disagrees:\n" + judgement
                    + "\nArgue it again, or keep your word and answer the objection.");
        }
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
        advisory("security-before", agents.securityBeforePlanner(), agents.securityBeforeDoer(),
                agents.securityBeforeVerifier(), brief);
    }

    /**
     * A STAGE THAT PRODUCES A RECORD RATHER THAN AN EDIT, still as plan, do, verify.
     *
     * <p>These were pairs, and a pair collapses deciding into doing: the same agent chose what the
     * reading was about and then wrote it, so a reviewer objecting to the framing had nowhere to
     * send it back to. The planner here decides RELEVANCE, which is the whole difficulty in a
     * security reading: a bump that clears a CVE it never came near is the failure being guarded
     * against, and that is a question about scope, not about prose.
     *
     * <p>The facts are the brief itself. Nothing here touches the workspace, so there is nothing to
     * read back from it, and the verifier judges the answer against the same scan the doer saw.
     */
    private void advisory(String stage, Agents.Agent planner, Agents.Agent doer,
                          Agents.Agent verifier, String brief) {
        try {
            new Triad(stage, planner,
                    (plan, feedback) -> doer.run(brief
                            + "\n\nWhat this reading should cover:\n" + plan + feedback),
                    verifier, () -> "Nothing in the workspace changed; this stage only reads.",
                    trace, bump, REASK + 1)
                    .run(brief);
        } catch (IOException impossible) {
            // No tool here touches the filesystem, so this cannot fire; a record beats a crash.
            trace.progress(bump, stage + ": " + impossible.getMessage());
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
        advisory("security-after", agents.securityAfterPlanner(), agents.securityAfterDoer(),
                agents.securityAfterVerifier(), brief);
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
    /**
     * One pin phase: raise, check, and go again while anything is outstanding.
     *
     * <p>The loop terminates on the build files rather than on anyone's account of them. A producer
     * that says it raised Lombok and a critic that agrees are two opinions; check_pins reads the
     * project, and both agents hold it, so the disagreement that ends the loop is with the pom.
     */
    /**
     * THE MODULE WORK, AS ONE TRIAD OVER THREE ORDERED PASSES.
     *
     * <p>The doer is the three phases; the verifier reads every module and decides whether the
     * repository is actually done. That verifier is the piece the repo-level gate cannot supply in
     * time: the gate runs four stages later and reports a single minimum across the tree, so a
     * module left behind arrives as an unraised repository pointing nowhere.
     *
     * <p>THREE PASSES OVER THE MODULES, NOT ONE PASS PER MODULE. The phases are globally ordered and
     * the modules inside them are not. Lombok has to be in place everywhere before the JDK moves
     * anywhere, because one reactor compiles the whole project with one javac; Spring Boot has to
     * wait until after, because Boot 4.1 declares java.version 17 and cannot resolve against a
     * project still below it. Walking module-major would put module two's "the JDK has not moved
     * yet" phase after module one had already moved it, and the phase prompts state that as fact.
     */
    private void modulesPhase() throws IOException {
        new Triad("modules", agents.modulesPlanner(),
                (plan, feedback) -> {
                    // ONE MODULE AT A TIME, ALL THE WAY THROUGH. Pinned, bumped, compiled,
                    // repaired and hardened before the walk moves on.
                    //
                    // It used to be three passes over the whole repository with repair sixteen
                    // turns later, which meant a break in the first module surfaced as a reactor
                    // error after the last one, with no obvious owner and two hundred lines of
                    // log. The context a repair needs is the diff that caused it, and that diff
                    // exists for about one module's worth of time.
                    int repaired = 0;
                    for (Modules.Module m : modules) {
                        String where = label(m);
                        trace.progress(bump, "module " + where + ": pinning what the hop needs");
                        pinPhase("before-pins-doer", false, m);

                        trace.progress(bump, "module " + where + ": moving the JDK");
                        bumpModule(m);

                        // COMPILE IT NOW, while its diff is the only thing that changed.
                        if (moduleGate(m)) {
                            repaired++;
                        }

                        // AFTER THE REPAIR, NOT BEFORE IT. Hardening polishes a module that
                        // already compiles; asking it of one that does not is the wrong question.
                        trace.progress(bump, "module " + where + ": hardening what the bump left");
                        pinPhase("after-pins-doer", true, m);
                    }
                    return "The walk covered " + modules.size()
                            + (modules.size() == 1 ? " module" : " modules")
                            + (repaired == 0 ? ", none needing repair."
                                    : ", " + repaired + " needing repair.");
                },
                agents.modulesVerifier(),
                // The same facts the planners read: what every module declares, and the target
                // levels still below the hop. Nothing here judges either.
                () -> Declared.report(ws, modules)
                        + "\nDeclarations still below JDK " + to + ":\n"
                        + String.join("\n", Pins.belowTarget(ws, Integer.parseInt(to))),
                trace, bump, REASK + 1)
                .run("Migration: JDK " + from + " -> " + to + " (" + bump + ")"
                        + "\n\nThe modules this bump works on:\n"
                        + modules.stream().map(m -> "  " + label(m))
                                .collect(java.util.stream.Collectors.joining("\n")));
    }

    /**
     * ONE PIN PHASE: PLAN, DO, VERIFY, AND NOTHING IN FRONT OF IT.
     *
     * <p>This used to loop the modules and skip any whose pins a regex called satisfied. The skip
     * was an optimisation and it was also a veto: the regex decided whether an agent was shown the
     * instruction at all, and when it was wrong the phase did nothing and reported success. It was
     * wrong about Spring Boot for the whole corpus.
     *
     * <p>So the module loop is gone from here and lives in the PLAN instead, which is where it
     * belongs: the planner reads {@code declared_versions}, which reports every module without
     * judging any of it, and says which pins are below their floor in which modules. Working out
     * what is outstanding is what planning IS, and it is a comparison a model does well and a
     * positional split does badly.
     *
     * <p>The verifier reads the same tool and holds the loop. Nothing between the floors and the
     * agents parses anything.
     */
    private void pinPhase(String stage, boolean after, Modules.Module only) throws IOException {
        Agents.Agent planner = after ? agents.afterPinsPlanner() : agents.beforePinsPlanner();
        Agents.Agent doer = after ? agents.afterPinsDoer() : agents.beforePinsDoer();
        Agents.Agent verifier = after ? agents.afterPinsVerifier() : agents.beforePinsVerifier();

        // SCOPED TO ONE MODULE, and said in the first line so it cannot be skimmed past. An
        // agent handed the whole module list and asked about one of them will drift into the
        // others, and the diff it leaves is then somebody else's turn's problem.
        String brief = "Migration: JDK " + from + " -> " + to + " (" + bump + ")\n\n"
                + "YOU ARE WORKING ON ONE MODULE: " + label(only)
                + "\nEvery other module in this repository is somebody else's turn. Do not edit "
                + "them.\n\nThe modules of this project, for context only:\n" + moduleList()
                + "\n\nThe JDK has " + (after ? "already been raised to " + to
                        + ", so versions that require it can now be resolved."
                        : "NOT been raised yet; it is still " + from + ".");

        new Triad(stage, planner,
                (plan, feedback) -> {
                    if (plan.stripLeading().startsWith("NOTHING-OUTSTANDING")) {
                        return "NOTHING-OUTSTANDING: the plan found no pin below its floor.";
                    }
                    String said = doer.run(brief + "\n\nThe plan you are carrying out:\n"
                            + plan + feedback);
                    trace.applied(stage, said + "\n" + tree.diff());
                    return said;
                },
                verifier, () -> Declared.report(ws, List.of(only)), trace, bump, REASK + 1)
                .run(brief);
        tree.land(stage + " " + label(only));
    }

    /** The modules, as a list the planner can name back. */
    private String moduleList() {
        StringBuilder b = new StringBuilder();
        for (Modules.Module m : modules) {
            b.append("  ").append(m.isRoot() ? "root" : m.path()).append('\n');
        }
        return b.toString();
    }

    /** The brief every module-scoped agent starts from. */
    private String moduleBrief(Modules.Module m) {
        return "Migration: JDK " + from + " -> " + to + " (" + bump + ")"
                + "\n\nYou are working on ONE module of this project: " + label(m)
                + "\nThe project has " + allModules.size()
                + (allModules.size() == 1 ? " module." : " modules; the others are not yours.");
    }

    private static String label(Modules.Module m) {
        return m.isRoot() ? "root" : m.path();
    }

    /**
     * WHICH MODULES THIS BUMP SHOULD TOUCH, enumerated deterministically and then filtered by
     * judgement.
     *
     * <p>The enumeration is a read of the build files and is not up for discussion. What needs a
     * judgement is narrower and genuinely hard to write down: a vendored third-party tree or a
     * generated module is one this bump should leave alone, and neither is distinguishable from a
     * real module by its path.
     *
     * <p>The asymmetry runs one way, so the critic is told it. Keeping a module that should have
     * been skipped wastes a diff; skipping one that should have been kept leaves it below the target
     * and the gate takes the lowest module, which fails the entire bump.
     */
    private List<Modules.Module> moduleFilterPhase() throws IOException {
        allModules = Modules.of(ws);
        if (allModules.size() == 1) {
            trace.applied("modules", "one module; no filtering to do");
            return allModules;
        }
        String listing = allModules.stream().map(m -> "  " + label(m))
                .collect(java.util.stream.Collectors.joining("\n"));
        String brief = "Migration: JDK " + from + " -> " + to + " (" + bump + ")"
                + "\n\nThe modules, read from the build files:\n" + listing;
        String[] answer = {""};
        new Triad("module-filter", agents.moduleFilterPlanner(),
                (plan, feedback) -> {
                    answer[0] = agents.moduleFilterDoer().run(brief
                            + "\n\nWhere to look, and what would count as evidence:\n"
                            + plan + feedback);
                    return answer[0];
                },
                agents.moduleFilterVerifier(),
                () -> "The modules, unchanged by this stage:\n" + listing,
                trace, bump, REASK + 1)
                .run(brief);
        String said = answer[0];
        List<Modules.Module> keep = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (Modules.Module m : allModules) {
            if (!m.isRoot() && skips(said, m)) {
                skipped.add(label(m));
            } else {
                keep.add(m);
            }
        }
        trace.applied("modules", allModules.size() + " modules, working on " + keep.size()
                + (skipped.isEmpty() ? "" : "; skipping " + String.join(", ", skipped))
                + "\n" + listing);
        return keep;
    }

    /**
     * A skip counts only when a SKIP line names EXACTLY this module.
     *
     * <p>This matched the path as a bare substring of the whole line, and both collisions that
     * follow are the common case rather than the exotic one. Module paths nest, so "SKIP
     * server/protobuf" contains "server" and dropped the aggregator that holds the compiler
     * settings for the entire subtree. Siblings share prefixes, so skipping "app-generated" dropped
     * "app". The evidence text after the colon could name a third module and drop that too.
     *
     * <p>The asymmetry makes it worse than an ordinary parsing bug: a module wrongly KEPT costs a
     * wasted diff, and a module wrongly SKIPPED keeps its old target, which the gate reads as the
     * repository minimum and fails the whole bump. So the match is exact, and the evidence half of
     * the line is not searched at all.
     */
    static boolean skipsForTest(String reply, Modules.Module m) {
        return skips(reply, m);
    }

    private static boolean skips(String reply, Modules.Module m) {
        if (reply == null) {
            return false;
        }
        String want = m.path().toLowerCase();
        for (String line : reply.lines().toList()) {
            String l = line.strip().toLowerCase().replaceFirst("^[-*>#`\\s]+", "");
            if (!l.startsWith("skip ")) {
                continue;
            }
            // Only the token after SKIP is a path; everything from the first colon is prose.
            String named = l.substring(5).split(":", 2)[0].strip();
            named = named.replaceAll("^[\"'`]|[\"'`,.]$", "").replaceAll("/+$", "");
            if (named.equals(want)) {
                return true;
            }
        }
        return false;
    }

    /** The bumper and its critic: the pins the recipe under-applied. */
    /**
     * Raise every declared pin to the target, and keep going while any is still below it.
     *
     * <p>This was one attempt and a single re-ask, with the list of remaining pins pasted into the
     * brief as text -- readable once, and stale the moment an edit landed. Both agents now hold
     * check_target and can ask the files after each attempt, which is the same shape the two pin
     * phases use and for the same reason: the loop should end on what the project says, not on what
     * anyone reports having done.
     *
     * <p>It is the pin the GATE measures, so a bumper that stops early does not fail here, it fails
     * four stages later as FAIL_target_not_bumped with nothing left to try.
     */
    private void bumpModule(Modules.Module m) throws IOException {
        int target = Integer.parseInt(to);
        // A module that declares no target of its own inherits the parent's, and inventing one
        // here is the most expensive edit available: a module-local property shadows a correctly
        // raised parent, and the gate reads the old target off the module that shadowed it.
        //
        // THE GUARD IS "DECLARES NOTHING", NOT "NOTHING IS BELOW". Those differ exactly where a
        // module spells its level in a dialect the pattern does not parse, and skipping THAT is a
        // module silently left behind for the gate to find as the repository minimum.
        if (Pins.belowTarget(ws, m, allModules, target).isEmpty()
                && !Pins.mentionsTarget(ws, m, allModules)) {
            trace.progress(bump, "module " + label(m) + ": declares no target of its own; it "
                    + "inherits, and inventing one here is how a module shadows a raised parent");
            return;
        }
        new Triad("bump:" + label(m), agents.bumpPlanner(),
                (plan, feedback) -> {
                    String said = agents.bumpDoer().run(moduleBrief(m)
                            + "\n\nThe plan you are carrying out:\n" + plan + feedback);
                    trace.applied("bump", label(m) + "\n" + said + "\n" + tree.diff());
                    return said;
                },
                agents.bumpVerifier(), () -> bumpFacts(m, target), trace, bump, REASK + 1)
                .run(moduleBrief(m));
        tree.land("bump " + label(m));
    }

    /**
     * COMPILE ONE MODULE, AND REPAIR IT UNTIL IT COMPILES OR THE TURNS RUN OUT.
     *
     * <p>The same shape the repository gate used to have, one module wide, and the reason the
     * repository no longer needs it: the turns were repair's, and repair has moved here. A failure
     * found now is a failure with that module's diff in front of it, rather than a reactor error
     * after every module has moved.
     *
     * <p>Compile only. Test conservation is a whole-suite fact measured against the baseline, so a
     * per-module test run cannot decide it, and the repository gate has to run the suite anyway.
     *
     * @return whether this module needed repairing at all
     */
    private boolean moduleGate(Modules.Module m) throws IOException {
        boolean everRed = false;
        for (int turn = 1; turn <= MODULE_TURNS; turn++) {
            Runner.Result compiled = runner.buildModule(to, m.isRoot() ? "" : m.path());
            trace.built("module-gate-" + label(m) + "-" + turn, compiled);
            if (!compiled.infra()) {
                if (turn > 1) {
                    trace.progress(bump, "module " + label(m) + ": compiles after repair");
                }
                return everRed;
            }
            everRed = true;
            trace.progress(bump, "module " + label(m) + ": will not compile under JDK " + to
                    + " (turn " + turn + " of " + MODULE_TURNS + ")");
            if (!moduleRepair(m, compiled.summary())) {
                // Nothing landed, so going round again would ask the same question of the same
                // tree. The repository gate is the arbiter either way.
                break;
            }
        }
        return everRed;
    }

    /**
     * WHICH MODULE IS BEHIND, appended to the gate's own line.
     *
     * <p>The verdict itself stays a repository verdict and stays all-or-nothing: the gate takes the
     * lowest module, so a project passes only when every module does. What changes is that the
     * record can now say which one failed. A single minimum across the whole tree told us a bump had
     * not been raised and pointed nowhere, and that is most of what makes a
     * FAIL_target_not_bumped untriageable a week later.
     *
     * <p>Advisory and cheap: it re-reads the output the reactor build already wrote, and a failure
     * to read it is not allowed to change the verdict.
     */
    private String perModule(int target) {
        try {
            List<Gate.ModuleState> states = Gate.perModule(ws, baselineByModule);
            if (states.size() <= 1) {
                return "";
            }
            List<Gate.ModuleState> behind = states.stream().filter(s -> !s.ok(target)).toList();
            return "\nBy module (" + (states.size() - behind.size()) + " of " + states.size()
                    + " clear):\n" + states.stream().map(s -> "  " + s.describe(target))
                    .collect(java.util.stream.Collectors.joining("\n"));
        } catch (IOException e) {
            return "\nBy module: unreadable (" + e.getMessage() + ")";
        }
    }

    /** What one module says about its own target declarations. */
    private String bumpFacts(Modules.Module m, int target) throws IOException {
        List<String> left = Pins.belowTarget(ws, m, allModules, target);
        return "Module " + label(m) + ", target declarations still below " + target + ":\n"
                + (left.isEmpty() ? "(none in this module)" : String.join("\n", left))
                + "\n\nAcross the whole repository, still below " + target + ":\n"
                + (Pins.belowTarget(ws, target).isEmpty() ? "(none)"
                : String.join("\n", Pins.belowTarget(ws, target)))
                + "\n\nThe edits currently in the workspace:\n" + tree.diff();
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
    private boolean moduleRepair(Modules.Module m, String log) throws IOException {
        // SCOPED, AND SAID FIRST. An agent handed a reactor log and asked about one module drifts
        // into the others, and the diff it leaves is then the next module's turn's problem.
        String scoped = "YOU ARE REPAIRING ONE MODULE: " + label(m)
                + "\nIt does not compile under JDK " + to + ". Every other module in this "
                + "repository is somebody else's turn; do not edit them.\n\n" + log;
        return repairCampaign(scoped);
    }

    private boolean repairCampaign(String log) throws IOException {
        String floor = tree.head();
        String feedback = "";
        // WHAT THE CAMPAIGN IS FOR, decided before anyone edits. A campaign with no stated end runs
        // until its budget is spent, and this planner is also the one place a failure that is not
        // this bump's doing can be named as such: a test red before anything moved is not a wall,
        // and treating it as one has cost this corpus whole runs.
        String aim = agents.moduleRepairPlanner().run(brief(log)
                + "\n\nWhat has landed so far:\n" + tree.history(floor));
        if (aim.stripLeading().startsWith("NOT-OURS")) {
            trace.progress(bump, "troubleshoot: " + aim.lines().findFirst().orElse(""));
            return false;
        }
        for (int campaign = 0; campaign <= REASK; campaign++) {
            boolean landed = campaignOfSteps(log, floor,
                    "\n\nWhat this campaign is for:\n" + aim + feedback);
            String judgement = agents.moduleRepairVerifier(floor)
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
            String order = agents.moduleRepairStepPlanner(floor)
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
        String reply = agents.moduleRepairStepDoer().run(brief);
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
            trace.applied("step-doer", now);
            String judgement = agents.moduleRepairStepVerifier().run("The failing build said:\n" + log
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
            reply = agents.moduleRepairStepDoer().run(brief
                    + "\n\nYour earlier attempts at this step, and why they were rejected:\n"
                    + String.join("\n\n", rejected)
                    + "\nEach was reverted. Do not repeat one.");
        }
        return false;
    }

    // ---- what the agents are handed to start from; they have tools for the rest ----

    private String brief(String log) throws IOException {
        return "Migration: JDK " + from + " -> " + to + " (" + bump + ")"
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
        String context = "The bump " + bump + " (JDK " + from + " -> " + to
                + ")"
                + ". What the workspace became:\n" + tree.diff();
        // The planner lists the distinct pieces of work that landed, which is where the expensive
        // error lives: a fix attempted three times and landed once is one piece of work, and the
        // record shows all three.
        String pieces = agents.estimatorPlanner().run(context);
        context = context + "\n\nThe distinct pieces of work that landed:\n" + pieces;
        String estimate = agents.estimatorDoer().run(context);

        // NOTHING DOWNSTREAM DEPENDS ON THIS NUMBER, which is exactly why it drifts: an estimate
        // nobody checks is read later as though it had been measured.
        String judged = agents.estimatorVerifier().run(context + "\n\nThe estimate:\n" + estimate);
        if (!word(judged, "sound", "off").equals("sound")) {
            trace.progress(bump, "estimator-critic: " + judged.lines().findFirst().orElse(""));
            estimate = agents.estimatorDoer().run(context + "\n\nYou estimated:\n" + estimate
                    + "\n\nA reviewer checked it against the log:\n" + judged
                    + "\nPrice it again.");
        }
        Matcher m = Pattern.compile("minutes:\\s*(\\d+)").matcher(estimate);
        trace.priced(bump, m.find() ? m.group(1) : "", estimate);
    }

    private static String[] parseHop(String claim) {
        Matcher m = Pattern.compile("hop:\\s*(\\d+)\\s*->\\s*(\\d+)")
                .matcher(claim == null ? "" : claim);
        return m.find() ? new String[]{m.group(1), m.group(2)} : null;
    }

    /**
     * WHICH VERDICT AN AGENT ACTUALLY GAVE, which is not the first place its letters appear.
     *
     * <p>This was {@code indexOf} over the whole lowercased reply, taking the earliest hit. Three
     * collisions follow from that and all three are ordinary English rather than adversarial input:
     *
     * <ul>
     *   <li>{@code done} is inside "not done", "nothing done", "abandoned". A verifier that opens by
     *       denying completion scored {@code done} before reaching its real verdict.
     *   <li>{@code again} is inside "against".
     *   <li>{@code sound} is inside "unsound", so the security critic's rejection read as approval.
     * </ul>
     *
     * <p>The first is the expensive one. {@code done} is both the earliest-colliding word and the
     * default when nothing matches, so the approving answer was the easiest to trigger by accident,
     * which is precisely backwards for a construction whose whole purpose is that a reviewer can
     * stop the work.
     *
     * <p>Three rules, in order. A line that STARTS with one of the words is the verdict, because
     * that is what every prompt asks for. Failing that, a whole-word match wins, which kills
     * "unsound" and "against" outright. And a match immediately preceded by a negation is not a
     * match, which kills "not done".
     */
    static String word(String reply, String... allowed) {
        if (reply == null || reply.isBlank()) {
            return allowed[0];
        }
        for (String line : reply.lines().toList()) {
            String l = line.strip().toLowerCase().replaceFirst("^[-*>#`\\s]+", "");
            for (String w : allowed) {
                if (l.equals(w) || l.startsWith(w + ":") || l.startsWith(w + " ")
                        || l.startsWith(w + ".") || l.startsWith(w + ",")
                        || l.startsWith(w + ";") || l.startsWith(w + "!")) {
                    return w;
                }
            }
        }
        String lower = reply.toLowerCase();
        int best = Integer.MAX_VALUE;
        String chosen = allowed[0];
        for (String w : allowed) {
            Matcher m = Pattern.compile("\\b" + Pattern.quote(w) + "\\b").matcher(lower);
            while (m.find()) {
                if (negated(lower, m.start())) {
                    continue;
                }
                if (m.start() < best) {
                    best = m.start();
                    chosen = w;
                }
                break;
            }
        }
        return chosen;
    }

    /** Whether the words just before a match turn it into its opposite. */
    private static boolean negated(String text, int at) {
        String before = text.substring(Math.max(0, at - 24), at);
        return NEGATION.matcher(before).find();
    }

    private static final Pattern NEGATION = Pattern.compile(
            "\\b(not|isn't|isnt|is not|no|never|nothing|cannot|can't|cant|wasn't|wasnt|"
                    + "aren't|arent|hasn't|hasnt|far from|less than)\\b[\\s\\p{Punct}]*$");
}
