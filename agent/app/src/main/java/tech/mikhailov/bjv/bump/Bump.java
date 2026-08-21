package tech.mikhailov.bjv.bump;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tech.mikhailov.ratchet.flow.Agent;
import tech.mikhailov.ratchet.config.Env;
import tech.mikhailov.ratchet.flow.Flow;
import tech.mikhailov.ratchet.record.Journal;
import tech.mikhailov.ratchet.record.Json;
import tech.mikhailov.ratchet.record.JsonlTrace;
import tech.mikhailov.ratchet.llm.Model;
import tech.mikhailov.ratchet.config.Prompts;
import tech.mikhailov.ratchet.flow.Reply;
import tech.mikhailov.ratchet.flow.Shape;
import tech.mikhailov.ratchet.record.Trace;
import tech.mikhailov.bjv.jvm.Declared;
import tech.mikhailov.bjv.jvm.Migrate;
import tech.mikhailov.bjv.jvm.Modules;
import tech.mikhailov.bjv.jvm.Runner;
import tech.mikhailov.bjv.jvm.Staged;
import tech.mikhailov.bjv.jvm.Tree;

/**
 * THE ORDER, AS ONE TREE THAT RUNS, and no second copy of it anywhere to keep in step.
 *
 * <p>{@link #everything} is that sequence, and {@code Flow.shape} prints it, so the picture is
 * walked off the thing that executes. What stood here instead was a picture drawn beside the
 * program: it drew a bounded reflect loop around the gate with a troubleshooter beside it, long
 * after repair had moved inside the module walk and that loop had been deleted. Nothing failed and
 * no test went red. Every reader was simply told something untrue.
 *
 * <p>The shape is asserted in {@code TheShapeIsTheProgramTest}, against the tree itself: survey,
 * baseline, security-before, module-filter; the module walk; then the gate and the three stages
 * that close on it. Two of those three are selection on the gate's own word, so a reader can see
 * from the shape alone that the scan follows a green gate and the arguer follows a red one.
 *
 * <p>EVERY STAGE IS PLANNER, DOER, VERIFIER, and the verifier holds the loop. See
 * {@link Flow#triad}. The three passes are ordered inside a module rather than across the
 * repository: one module is pinned, bumped, compiled, repaired and hardened before the walk moves
 * on, because the context a repair needs is the diff that caused it and that diff exists for about
 * one module's worth of time. Their order within the module is what the two pin phases are for:
 * Lombok has to be in place before the JDK moves, and Spring Boot cannot resolve until after it
 * has.
 *
 * <p>THE GATE IS NOT A TOOL. Producers can try their own build, and what they learn from it is
 * feedback; the build that DECIDES runs here, between the stages, because whether the gate ran after
 * an edit is not a model's choice. Wherever the builds established the outcome this class computes
 * it and no model is called, since routing a deterministic outcome through a model makes it sampled.
 *
 * <p>PRODUCERS EDIT THE WORKSPACE THROUGH THEIR TOOLS, so what a phase did is read back from git,
 * not from what the agent said it did. A producer that describes an edit it never made is then
 * indistinguishable from one that made none, which is the honest reading.
 *
 * <p>A KILLED BUMP CONTINUES RATHER THAN BEGINNING AGAIN. The sweep runs for a fortnight and the
 * harness changes daily, so lanes are killed; one of them had twenty hours in it. Almost nothing
 * here has to be checkpointed to survive that, because the agents are stateless and the workspace
 * is durable: what was edited is committed as each stage lands, and the readers re-read it for
 * free. What a {@link Journal} holds is the little that cannot be derived again -- the baseline
 * measured before anything moved, the module list an agent chose, and which model-driven stages
 * have already been paid for -- and {@link #journaled} is the only thing that touches it.
 *
 * <p>THE GATES ARE NOT AMONG THEM, deliberately. module-gate and gate are deterministic builds and
 * they are the arbiter; running one again after a kill is not waste but the answer to the only
 * question a resume actually has, which is what is true of the tree as it now is. The decision to
 * resume at all is {@link #resuming}, and it takes three conditions to say yes and any one to say
 * no, because a wrong resume is worse than a slow one.
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

    /**
     * ORDERED REPAIR STEPS FOR THE WHOLE BUMP, not for each module.
     *
     * <p>The old ceiling was sixteen turns times two campaigns times six steps: 192, spent on the
     * repository once. Per module the same arithmetic is 36 per module, which is 216 at the corpus
     * median of six modules and 720 at twenty, so the change that was supposed to bound repair
     * quadrupled it. The commit that made it compared 16N against 3N and never against 16.
     *
     * <p>So the budget stays where it was, per bump, and the walk draws down a shared allowance. A
     * module that needs thirty steps is welcome to them; what it may not do is leave nothing for
     * the nineteen modules behind it.
     */
    private static final int REPAIR_BUDGET = Integer.parseInt(
            System.getenv().getOrDefault("BJV_REPAIR_BUDGET", "192"));

    /** What is left of it. Read and decremented by the step loop, never reset mid-bump. */
    private int repairLeft = REPAIR_BUDGET;

    /**
     * WHAT A MODULE IS TREATED AS BEFORE THE DETECTOR HAS SPOKEN, AND AFTER IT HAS FAILED TO.
     *
     * <p>Derived rather than typed. {@link Managed#platformIn} answers this for a reply that names
     * nothing, and writing the word here as well would be one fact in two files, which is what the
     * deletion of Chain.java was about. It is the regime that owns its own conflicts, which is also
     * the safe thing to tell an agent when nobody knows: it asks for evidence before every pin
     * rather than trusting a managed set that may not be there.
     */
    private static final String UNRESOLVED_PLATFORM = Managed.platformIn("");

    /**
     * WHICH LINES SURVIVE THE BUDGET when an agent is shown what happened before it.
     *
     * <p>The record writer keeps the highest-ranked lines and drops the rest, and it ranks by kind
     * alone unless a pipeline says what its own decisive words are. Kind alone is true of any
     * pipeline; these two lists are not, which is why they arrive from here.
     *
     * <p>THE MEASUREMENT BEHIND THEM: shown an unranked tail, 13 of 349 agent turns were given the
     * verdict that had just been reached, and the rest were given file reads. A run wired with the
     * ranking and no lists compiles, runs, and looks entirely healthy while making slowly worse
     * decisions, so the constructor that takes a provenance supplier is the one that takes these
     * as well and there is no form that takes one without the other.
     */
    private static final List<String> DECISIVE = List.of("fail_", "pass (");

    /** Words that mean somebody objected. Rank below a verdict, above an ordinary note. */
    private static final List<String> DISPUTED = List.of(
            "gaming", "off-target", "blocked:", "rejected", "declined", "reverted");

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

        String slug = slugOf(bump);
        Path settlements = results.resolve("settlements.jsonl");
        // WHICH ROUND OF ITS LANE BUDGET THIS IS, and where the launcher says the round is over.
        // Counted off the record before anything else, because the row every write from here on
        // carries it. See {@link Round}: this process has no clock and no budget of its own.
        Round round = Round.of(results, bump);
        JsonlTrace trace = new JsonlTrace(results.resolve(slug).resolve("trace.jsonl"),
                settlements, bump, Fingerprint.provenanceOf(bump, settlements, round::number),
                DECISIVE, DISPUTED);
        // THE JOURNAL SITS BESIDE THE TRACE IT BELONGS TO, and its rows carry the tree they landed
        // on: that is what a resume is checked against. The supplier is asked at write time rather
        // than now, because every stage that lands moves the tree it is asked about.
        Path journalFile = results.resolve(slug).resolve("journal.jsonl");
        Tree checkoutTree = new Tree(checkout, note -> trace.progress(bump, note));
        String sha = checkoutTree.head();
        Journal journal = new Journal(journalFile, checkoutTree::head);
        // WHAT THIS PROCESS WOULD PUT ITS NAME TO, as the row's own four fields, so the comparison
        // and the record cannot drift apart: the compared string IS the recorded string.
        String pipeline = pipelineOf(bump, results);
        boolean resumed = resuming(journal, settlements, bump, sha, pipeline);
        if (!resumed) {
            // A FRESH START MUST NOT REPLAY A STALE JOURNAL, which is the whole failure mode of
            // getting a resume wrong: the skipped stages would be skipped against edits that are
            // not in this checkout. The old file is moved aside rather than deleted, because it is
            // the evidence of what the killed attempt did and this record never rewrites history.
            // AND A MIGRATED TREE HAS NO BEFORE LEFT TO MEASURE. Starting fresh runs the
            // baseline, which measures what passed under the project's OWN JDK; on a checkout that
            // has already been pinned, bumped and repaired that number is not a baseline, it is the
            // migration's own result wearing the label of the thing it is judged against. The
            // journal exists because that measurement cannot be taken twice.
            //
            // THE LAUNCHER THAT PRESERVES THE CHECKOUT IS HERE NOW, so this is reachable, and the
            // case that reaches it is a round boundary whose pipeline moved underneath it. Filing
            // that repository as unmeasurable would be wrong: there is nothing wrong with it, only
            // with this tree. So the tree is put back first.
            //
            // IT DOES NOT NEED THE NETWORK AND MUST NOT ASK FOR IT. The workspace is a full clone,
            // so resetting to the sha the manifest names and cleaning everything untracked away is
            // bit for bit what a fresh clone and checkout would produce, and the origin and its
            // credential are the launcher's, deliberately. Afterwards migrated() is false, because
            // no bjv: commit is reachable from the new HEAD, and the run is a genuine fresh start
            // with a genuine baseline.
            //
            // THE SETTLEMENT BELOW STAYS EXACTLY WHERE IT IS, as the backstop behind the reset
            // rather than instead of it. It is the last thing standing between a preserving
            // launcher and a corpus of bumps that look healthy and measure nothing.
            if (checkoutTree.migrated()) {
                String manifestSha = bump.split("\\|").length > 1 ? bump.split("\\|")[1] : "";
                if (!manifestSha.isBlank() && !checkoutTree.resolve(manifestSha).isBlank()) {
                    trace.progress(bump, "this checkout carries commits from an earlier round of"
                            + " this bump and the pipeline that made them is not the one running"
                            + " now, so it goes back to " + manifestSha + " and starts again");
                    checkoutTree.restartAt(manifestSha);
                    sha = checkoutTree.head();
                }
            }
            if (checkoutTree.migrated()) {
                String why = "no-baseline\nthis checkout already carries bjv commits and its"
                        + " journal does not stand on it, so there is no before-state left to"
                        + " measure; re-clone at " + bump.split("\\|")[1] + " and run it again";
                trace.settled(bump, why.split("\n", 2)[0], why, false, false);
                System.out.println(why);
                return;
            }
            journal = restarted(journalFile, checkoutTree);
        }
        try {
            Bump b = new Bump(checkout, bump, trace, journal, resumed, round);
            if (resumed) {
                trace.progress(bump, "resuming: a journal for this bump stands on the checkout at "
                        + sha + ", so the stages it records are not paid for twice; the gates run"
                        + " again regardless, because they are the arbiter of the tree as it is now");
                // BACK TO THE LAST THING THAT LANDED. Uncommitted work is, by this workspace's own
                // convention, the step being judged right now: the stage that made it was killed
                // before it committed and before any critic read it, so nobody ever accepted it.
                // Left in place it would turn up in the next stage's diff and be judged as that
                // stage's work. HEAD does not move, so the sha this resume was checked against is
                // still the sha it stands on.
                checkoutTree.revert();
            }
            String account = b.run();
            String state = account.split("\n", 2)[0];
            // WHETHER THIS WAS ONE ATTEMPT OR TWO, in the row the sweep compares on. A resumed bump
            // carries a different budget history and possibly a different module order, so it is
            // not the same trial as a fresh one and a comparison has to be able to leave it out.
            trace.settled(bump, state, account, b.baselineGreen, b.gateGreen, resumed);
            // A GREEN GATE IS NOT COMPLIANCE. The verdict says the project builds under the target
            // and lost no test; it says nothing about whether the versions the target needs were
            // reached. Measured here because this is the last moment the working tree exists and
            // the hop is known, and filed beside the settlement rather than inside it.
            // AND NOT ON A BUMP THAT IS ONLY PAUSED. The measurement reads the working tree, and a
            // paused tree is half migrated: a compliance figure taken there is a partial migration
            // filed as a finished one, which is exactly the shape of failure this project keeps
            // producing. It runs on a state that means the bump is over.
            String[] part = bump.split("\\|");
            if (part.length >= 4 && !Round.PAUSED.equals(state)) {
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

    /**
     * THE ONE SETTLEMENT STATE THAT MEANS A RUN WAS INTERRUPTED RATHER THAN CONCLUDED.
     *
     * <p>Every other word in that column is a bump that finished having something to say, including
     * {@code requeued}, which is somebody asking for the work to be done again from the start.
     */
    private static final String IN_FLIGHT = "bumping";

    /**
     * DOES THIS ATTEMPT PICK UP AN UNFINISHED ONE, and the answer is no unless all four agree.
     *
     * <p>A journal that recorded a completed stage, a last settlement row saying the work was
     * interrupted rather than concluded, a checkout standing where the journal left it, and the
     * same pipeline that made it. Any one of them missing and the bump starts fresh, because a
     * wrong resume is worse than a slow one: the stages it skips are skipped against edits that
     * are not in this tree, and the bump is then judged on a workspace nobody built.
     *
     * <p>TWO WORDS MEAN INTERRUPTED AND ONE OF THEM IS NOT {@code requeued}. {@link #IN_FLIGHT} is
     * a lane that died; {@link Round#PAUSED} is a lane that reached the end of its round, which is
     * the same tree with the same journal and one more round behind it. {@code requeued} is
     * somebody on a page asking for the work to be done again FROM THE START, and resuming one
     * would hand that person back the state they were trying to discard.
     *
     * <p>THE FOURTH CLAUSE IS WHAT A ROUND BOUNDARY MADE NECESSARY. A killed lane was picked up by
     * whatever image happened to run next, and it did not matter much because the checkout was
     * re-cloned anyway and nothing ever actually resumed. Now something does, and this sweep
     * deploys about once every ten hours against a six-hour budget, so a paused bump meeting a
     * different pipeline is the ordinary case rather than the exotic one. Skipping stages a
     * different pipeline paid for would file one pipeline's work under another's name.
     *
     * <p>Package-visible and static because it is the rule rather than a step of the run, and a
     * rule with four clauses is worth being able to test one clause at a time.
     */
    static boolean resuming(Journal journal, Path settlements, String bump, String sha,
                            String pipeline) {
        if (journal.tree().isEmpty()) {
            return false;
        }
        Map<String, String> last = lastSettledRow(settlements, bump);
        String said = last.getOrDefault("state", "");
        if (!IN_FLIGHT.equals(said) && !Round.PAUSED.equals(said)) {
            return false;
        }
        if (!samePipeline(last, pipeline)) {
            return false;
        }
        return journal.standsOn(sha);
    }

    /**
     * WHETHER THE ROW WAS WRITTEN BY THIS PIPELINE, field by field.
     *
     * <p>EMPTY AGAINST NON-EMPTY IS A DIFFERENCE, not a missing answer to be forgiven. Every row on
     * disk before the launcher started forwarding the image identity carries an empty one, and
     * reading that as agreement would resume across exactly the change that introduced the check.
     * Empty on both sides IS equality, which is what stops a host where {@code docker image
     * inspect} answers nothing from calling every run a different pipeline from itself.
     *
     * <p>All four fields, and none of them is redundant. {@code commit} and {@code image} answer
     * where the code came from, and the image is not the commit here: this project iterates by
     * deploying dirty trees, so two builds share a stamp exactly while somebody is iterating.
     * {@code prompts} and {@code boms} answer what the agents were handed, which lives in a store
     * beside the results and outside the image altogether. See {@link Version}.
     */
    private static boolean samePipeline(Map<String, String> row, String pipeline) {
        for (String field : List.of("commit", "image", "prompts", "boms")) {
            String recorded = row.getOrDefault(field, "");
            String now = fieldOf(pipeline, field);
            if (!recorded.equals(now)) {
                return false;
            }
        }
        return true;
    }

    /** One field out of the composed fingerprint string, which is the form the row stores. */
    private static String fieldOf(String fields, String name) {
        Matcher at = Pattern.compile("\"" + name + "\":\"([^\"]*)\"").matcher(fields);
        return at.find() ? at.group(1) : "";
    }

    /**
     * WHAT THIS PROCESS WOULD PUT ITS NAME TO, as the row's own four fields.
     *
     * <p>The same call {@link Fingerprint#provenanceOf} makes, so the string compared IS the string
     * recorded, character for character. The round is appended by the supplier OUTSIDE this, or
     * every round would read as a new pipeline and nothing would ever resume.
     */
    static String pipelineOf(String bump, Path results) {
        String[] p = bump.split("\\|");
        return p.length < 4 ? "" : Version.fields(p[2] + "-" + p[3], results, Fingerprint.OF_A_BUMP);
    }

    /** The directory name a bump's record lives under: the key with everything unsafe flattened. */
    static String slugOf(String bump) {
        return bump.replaceAll("[^A-Za-z0-9]+", "_");
    }

    /**
     * The last thing the record said about this bump, whole, or an empty row when it never has.
     *
     * <p>THE WHOLE ROW RATHER THAN ITS STATE, because two of the four resume conditions are read
     * off it and they must be read off the SAME row: the state of one row beside the fingerprint
     * of another would answer a question nobody asked.
     *
     * <p>Read leniently, for the reason the journal is: this file is appended to by a process that
     * gets killed, so a torn last line is the normal case rather than a fault. Rows for other bumps
     * share the file, so the key is checked rather than assumed.
     */
    private static Map<String, String> lastSettledRow(Path settlements, String bump) {
        if (!Files.isReadable(settlements)) {
            return Map.of();
        }
        String text;
        try {
            text = new String(Files.readAllBytes(settlements), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            return Map.of();
        }
        Map<String, String> last = Map.of();
        for (String line : text.split("\n")) {
            if (!line.contains(bump)) {
                continue;
            }
            Map<String, String> row;
            try {
                row = Json.row(line);
            } catch (RuntimeException torn) {
                continue;
            }
            if (!bump.equals(row.getOrDefault("bump", ""))) {
                continue;
            }
            if (!row.getOrDefault("state", "").isBlank()) {
                last = row;
            }
        }
        return last;
    }

    /**
     * A JOURNAL WITH NOTHING IN IT, and the old one kept where it fell.
     *
     * <p>Moved aside rather than truncated. The rows are what the killed attempt actually did, and
     * a file that is emptied to make room cannot be read afterwards by anyone asking why a bump
     * that had twenty hours in it started again from nothing.
     */
    private static Journal restarted(Path journalFile, Tree checkout) {
        Path aside = journalFile.resolveSibling("journal." + System.currentTimeMillis() + ".jsonl");
        try {
            if (Files.isRegularFile(journalFile)) {
                Files.move(journalFile, aside, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException couldNotMove) {
            // THE FRESH RUN GOES SOMEWHERE ELSE RATHER THAN ON TOP. A journal that could not be
            // moved still holds the killed attempt's rows, and handing it back would replay every
            // one of them into a run that was decided to start over, which is the exact failure
            // this whole path exists to prevent.
            System.err.println("journal: could not set aside " + journalFile + ": "
                    + couldNotMove.getMessage());
            return new Journal(aside, checkout::head);
        }
        return new Journal(journalFile, checkout::head);
    }

    /**
     * WHAT THIS BUMP HAS ALREADY FINISHED, so a killed one does not begin again from nothing.
     *
     * <p>Null for a bump built to be read rather than run: {@link #stages()} constructs one with no
     * workspace, no trace and no agents, and a journal is a file. See {@link #journaled}, which is
     * the only thing that touches it and answers with the bare node when there is none.
     *
     * <p>WHAT GOES IN IT IS WHAT CANNOT BE DERIVED AGAIN. Almost everything a bump knows is cheap
     * to re-derive: the agents are stateless, so there is no conversation to rebuild, and the
     * workspace is durable, so what was edited is in git and {@link Declared} re-reads it for free.
     * Three things are not: the baseline measured before anything moved, the module list an agent
     * chose, and which model-driven stages have already been paid for.
     */
    private final Journal journal;

    /**
     * WHETHER THIS PROCESS PICKED UP WHERE A KILLED ONE STOPPED.
     *
     * <p>It is in the settlement row because a resumed bump is not the same trial as a fresh one:
     * it carries a different budget history and possibly a different module order. This corpus
     * refuses to let an agent choose the hop for exactly that reason, and a comparison that mixed
     * resumed rows with fresh ones would be measuring two experiments as one.
     */
    private final boolean resumed;

    /** The passing set, under the project's own JDK, before anything moved. */
    private static final String BASELINE_PRE = "baseline-pre";
    /** The same read split by module, so a loss is attributed where it happened. */
    private static final String BASELINE_BY_MODULE = "baseline-by-module";
    /** Whether that suite was all green, which is a fact about the project and not about the bump. */
    private static final String BASELINE_GREEN = "baseline-green";
    /** What the gate decided, recorded because the closing stages select on it. */
    private static final String GATE_GREEN = "gate-green";
    /** The modules the filter kept: an agent's decision, which a second run would make differently. */
    private static final String MODULE_LIST = "modules";

    /** The key every repository-wide stage journals under: there is one of each per bump. */
    private static final Supplier<String> REPO = () -> "repo";

    private final Path ws;
    private final String bump;
    private final Trace trace;
    private final Tree tree;
    private String from;
    private String to;
    private Agents agents;
    private Runner runner;
    /** Where jvm-run is invoked from. The survey reads it; the recipes the filter installs need it. */
    private String hoptools;
    // What the builds actually did, carried to the settlement. An implication is not a record.
    private boolean baselineGreen;
    private boolean gateGreen;
    private Set<String> pre = Set.of();
    /** What the scorer last said, which is what the arguer is answering. Null until the gate runs. */
    private Gate.Verdict lastVerdict;
    /** What went wrong, in the terms the arguer can act on: written by the gate when it is red. */
    private String lastLog = "";

    /**
     * WHAT THE BUMP SETTLED ON, WRITTEN BY THE STAGE THAT SETTLED IT.
     *
     * <p>A sequence answers with its last word, and the last word of this one belongs to a stage
     * that only runs when the gate went red. So the answer cannot be the tree's: on the run that
     * passed, the stage that would have supplied it is the one that was skipped. It is a field, and
     * the two stages that can end a bump write it, the after-scan on a green gate and the arguer on
     * a red one. The third way a bump ends is {@link Flow.Settled}, which carries its own.
     *
     * <p>WHY THE AFTER-SCAN AND NOT THE GATE, which is the stage that actually decided. The account
     * of a passing bump quotes the CRITICAL+HIGH line, and that number does not exist until the
     * scan has run, so the gate cannot yet say the sentence. What the gate settles is
     * {@link #gateGreen}, and that is what both closers select on.
     */
    private String account = "";

    /** Every module the build files declare, which is what scopes a per-module read. */
    private List<Modules.Module> allModules = List.of();
    /** The ones this bump works on: everything above, minus what the filter pair set aside. */
    private List<Modules.Module> modules = List.of();

    /**
     * WHAT AN OUTER OBJECTION MEANS TO A NESTED BLOCK.
     *
     * <p>The modules verifier judges the repository: it reads what every module declares and says
     * whether the walk is done. When it says again, the doer it is sending back is the whole walk,
     * and a walk that re-runs having been told nothing asks every one of its agents the question it
     * asked five minutes ago and gets the answer it got then. That is what this did: the doer took
     * {@code (plan, feedback)} and read neither, so a second pass over twenty modules was forty
     * stages spent to differ from the first only by sampling.
     *
     * <p>So the objection travels DOWN, into the brief of every module-scoped stage that shapes a
     * declaration: before-pins, bump, after-pins. It is carried in a field rather than through the
     * task because those stages build their own briefs and read no task, which is deliberate: what
     * a module agent needs first is its module, not the walk's transcript.
     *
     * <p>WHAT IT MAY NOT DO IS PICK WHICH MODULES RUN AGAIN. The objection names modules in prose,
     * and parsing agent prose for module paths is how the filter once dropped an aggregator whose
     * path was a prefix of the module it had been told to skip. Every module is visited again; what
     * changed is that each is told why.
     *
     * <p>It does not reach module-repair, which is answering a compiler. A reviewer of declarations
     * has no opinion about a javac error, and pasting one in front of a repair step is how a
     * campaign starts working on somebody else's problem.
     */
    private String walkObjection = "";

    /** How many modules of the current walk needed repairing at all. Reset when the walk starts. */
    private int modulesRepaired;

    /** Each module's own passing set before anything moved, so a loss is attributed where it happened. */
    private Map<String, Set<String>> baselineByModule = Map.of();
    private Security security;
    private Security.Scan before = Security.Scan.notMeasured("not run");
    private Security.Scan after = Security.Scan.notMeasured("the gate never went green");
    private Security.Delta delta = Security.Delta.unknown("not computed", -1, -1);

    /**
     * WHETHER THE LANE HAS BEEN ASKED TO STOP, which is all this process knows about time.
     *
     * <p>There is no clock here and no budget here. The launcher owns both and says so by creating
     * one file this container can see; see {@link Round}. Nothing an agent is handed mentions it,
     * no tool reports it, and no prompt is built from it.
     */
    private final Round round;

    /** A bump with nothing to resume from: what {@link #stages()} builds, and what a test reads. */
    private Bump(Path ws, String bump, Trace trace) {
        this(ws, bump, trace, null, false, Round.none());
    }

    /** The five-argument form the tests use: a bump nobody is going to ask to stop. */
    private Bump(Path ws, String bump, Trace trace, Journal journal, boolean resumed) {
        this(ws, bump, trace, journal, resumed, Round.none());
    }

    private Bump(Path ws, String bump, Trace trace, Journal journal, boolean resumed, Round round) {
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
        this.journal = journal;
        this.resumed = resumed;
        this.round = round;
        if (resumed) {
            // THE BUDGET IS DERIVED RATHER THAN KEPT, which is why no row records it. A second copy
            // of a number these rows already carry is a number that drifts, and this one drifts in
            // the expensive direction: a resume that refilled the allowance would let a bump order
            // the whole 192 steps again on every kill, which over a fortnight is unbounded repair.
            //
            // A COMPLETED CAMPAIGN IS CHARGED WHAT IT WAS ENTITLED TO ORDER. The row says the
            // campaign finished, not how many of its steps it used, and the two readings differ in
            // opposite directions: under-charging refills a budget that was spent. So the maximum
            // is charged, and a resumed bump repairs less rather than more.
            repairLeft = Math.max(0, REPAIR_BUDGET - STEPS * journal.count("module-repair-step"));
            // A SCAN TAKEN NOW IS NOT A SCAN OF THIS PROJECT'S PRIOR STATE. The tree has moved, so
            // the before-scan cannot be retaken and is not: the resumed run says so instead of
            // quietly comparing the migrated tree against itself.
            before = Security.Scan.notMeasured("the pre-migration scan belongs to the attempt that"
                    + " was killed; the tree has moved and it cannot be taken again");
        }
        this.tree = new Tree(ws, note -> trace.progress(bump, note));
        // THE MODULES STAGE, ASSEMBLED HERE AND NOT AS A FIELD INITIALISER: a triad keeps the trace
        // and the bump it is running, and field initialisers run before the constructor body has
        // set either of them, so one built up there would hold a null trace for the whole run.
        //
        // Its planner and verifier are looked up when they are called rather than now, because the
        // survey is what builds Agents and the survey has not run yet.
        //
        // THE STAGE BUILDS ITS OWN BRIEF, which is why the two agent roles splice it in rather than
        // read it off the task. A sequence hands every step the same task, and this brief names the
        // modules the bump works on: those are chosen by a stage four nodes earlier, so a task
        // fixed before the run cannot carry them. The concatenation is exactly what a triad does
        // with a brief handed in, in the same order, so the prompts are the ones these agents have
        // always been given.
        this.modulesStage = Flow.triad("modules",
                brief -> agents.modulesPlanner().run(modulesBrief() + brief),
                walkDoer,
                brief -> agents.modulesVerifier().run(modulesBrief() + brief),
                // The same facts the planners read: what every module declares, and the target
                // levels still below the hop. Nothing here judges either.
                () -> Declared.report(ws, modules)
                        + "\nDeclarations still below JDK " + to + ":\n"
                        + String.join("\n", Pins.belowTarget(ws, Integer.parseInt(to))),
                trace, bump, REASK + 1)
                // THE DOING IS THE WALK, AND THE WALK IS PER MODULE. Both are said here because
                // neither can be walked off the shape: the walk is a nameless each() below, and no
                // picture can count what a supplier is going to hand it. The count belongs on the
                // block rather than on every line inside it, or it is three chances to disagree
                // with the one place it is decided.
                .around("the module walk")
                .repeats("per module");
        // THE ORDER, AND THE ONLY PLACE IT EXISTS. Here rather than in a field initialiser for the
        // same reason as the stage above: it holds that stage, and that stage does not exist until
        // the line before this one.
        //
        // WHICH STAGES A RESUME SKIPS IS DECIDED ON THIS LINE, and it is the model-driven ones. The
        // journal is a constructor argument, so the wrapping happens here rather than on the field:
        // a field initialiser runs before the constructor body and would read a journal that does
        // not exist yet, which is the same trap the modules stage above is assembled here to avoid.
        //
        // THE GATES ARE NOT WRAPPED, and that is the part to keep right. module-gate and gate are
        // deterministic builds, they are the arbiter, and they are cheap beside an agent. Running
        // one again on a resume is not waste, it is the answer to the only question that matters
        // after a kill: what is true of the tree as it now is. The baseline is not wrapped either,
        // for the opposite reason: it cannot be re-measured at all once the tree has moved, so it
        // is carried as facts rather than as an answer. See {@link #baselinePhase}.
        //
        // survey and module-filter are not wrapped here even though both are model-driven, because
        // both build something the rest of the bump runs on: the runner, the agents and the scanner
        // in one, the recipes in the other. Their model halves are wrapped inside their own bodies,
        // where the construction can happen either way.
        this.everything = Flow.seq("", survey, baseline, journaled(securityBefore, REPO),
                moduleFilter, modulesStage, gate, journaled(securityAfter, REPO),
                journaled(estimator, REPO), journaled(verdict, REPO));
        // The campaign, wrapped for the same reason and keyed by the field the walk sets before it
        // hands over. It is reached through this rather than past it; see {@link #repairSteps}.
        this.repairSteps = journaled(stepCampaign, () -> campaignKey);
    }

    /**
     * THE SAME NODE, MINUS WHAT IT HAS ALREADY DONE, or the same node when there is no journal.
     *
     * <p>{@link Flow#resumable} needs a journal, and a bump built for its shape alone has none: it
     * has no workspace and no trace either, because every node holds a body it has not run. This
     * answers with the bare node in that case, so the picture and the tests can walk a tree that
     * was never going to journal anything.
     *
     * <p>THE KEY IS A SUPPLIER BECAUSE THE WALK IS PER MODULE. before-pins completes once for every
     * module, and a key decided when the tree was built would be the same key for all of them.
     */
    private Agent journaled(Agent node, Supplier<String> key) {
        return journal == null ? node : Flow.resumable(node, journal, key);
    }

    /**
     * THE ROUND ENDS HERE, OR IT DOES NOT END IN THIS STAGE AT ALL.
     *
     * <p>Called at the top of a stage's body, which is the only place a boundary is free: what a
     * stage landed is committed as it lands, so stopping in front of one loses nothing at all, and
     * the journal beside the checkout says which commits belong to which stage. Stopping INSIDE a
     * stage would lose the work the resume reverts anyway, and it would need a check in the agent
     * loop, which is one refactor away from being something the agent can read.
     *
     * <p>IT IS INSIDE THE BODY AND {@link #journaled} IS OUTSIDE IT, which is what makes a replayed
     * stage unable to trigger a boundary. That is correct: a replay costs nothing, and a resumed
     * round that paused again on the first stage it replayed would never make progress.
     *
     * <p>{@link Flow.Settled} is how the three existing stop-the-run settlements travel, and this
     * is the fourth. Nothing between here and {@link #run} catches it.
     */
    private void betweenStages(String stage) {
        if (round.reached()) {
            throw new Flow.Settled(round.account(stage));
        }
    }

    /**
     * SOMETHING A KILLED ATTEMPT MEASURED THAT CANNOT BE MEASURED AGAIN, if this run is its
     * continuation.
     *
     * <p>Empty on a fresh run even when a file happens to be there. Starting fresh was a decision
     * taken in {@link #main} against three conditions, and a stage that read a fact back anyway
     * would quietly undo it.
     */
    private Optional<String> recalled(String name) {
        return journal == null || !resumed ? Optional.empty() : journal.fact(name);
    }

    /** The same, for the writing half. A bump with no journal records nothing and says nothing. */
    private void record(String name, String value) {
        if (journal != null) {
            journal.fact(name, value);
        }
    }

    /**
     * EVERY STAGE IS A NODE, WHICH IS WHAT MAKES THE ORDER SAYABLE IN ONE PLACE.
     *
     * <p>Each is the body it always was with a name on it. The order is not here: it is the
     * sequence assembled in the constructor, and that is now the only place it exists. The pages
     * read it too: {@link Shape} walks this tree for the stage list that the settings page and the
     * bump strip used to take from a declaration of their own, which drifted from it twice.
     *
     * <p>WHAT A WALK CANNOT DERIVE IS DECLARED ON THE NODE. Who speaks inside a stage, how often it
     * runs, and which half of the bill of materials it works to: an agent is called from inside a
     * body, and a condition is a {@code BooleanSupplier} with no English in it. Each is written on
     * the node it is true of, one line above the body that makes it true.
     *
     * <p>They are built on the fields because every stage reads and writes those fields and the
     * bodies close over {@code this}, exactly as the lambdas inside them already do. Nothing is
     * constructed early by building them here: a node holds a body and runs it later, and
     * {@code agents} does not exist until the survey has made it.
     */
    private final Agent survey = Flow.code("survey", this::surveyPhase).triplet();
    private final Agent baseline =
            Flow.code("baseline", this::baselinePhase).deterministic();
    private final Agent securityBefore =
            Flow.code("security-before", this::securityBeforePhase).triplet();
    private final Agent moduleFilter =
            Flow.code("module-filter", this::moduleFilterPhase).triplet();
    private final Agent gate = Flow.code("gate", this::gatePhase).deterministic();

    /**
     * THE CLOSERS, WHICH ARE SELECTION ON THE GATE RATHER THAN A JUMP OUT OF THE RUN.
     *
     * <p>A green gate used to return PASS from the middle of the bump and reach none of these, so
     * whether a stage ran was a property of a {@code return} four stages above it. That is how the
     * estimator came to be described everywhere as pricing every bump while the code ran it on one
     * path of two. Written as selection, the picture says what happens: the scan only after a green
     * gate, the arguer only when the gate never went green, the estimator always, because a bump
     * that reached the gate is work whether it landed or not.
     *
     * <p>PRICED BEFORE THE ARGUMENT, WHICH IS A CHANGE ON THE RED PATH. It used to run after the
     * arguer had finished. The estimator prices the work that LANDED, and an argument lands
     * nothing, so the order now says that: what it reads is the workspace, which the arguer cannot
     * touch because every tool it holds only reads. The one visible difference is that the priced
     * row reaches the trace before the verdict's own rows.
     *
     * <p>The bodies carry no name of their own. The selection IS the stage; printing the word twice
     * would say there are two.
     */
    private final Agent securityAfter = Flow.when("security-after", () -> gateGreen,
            Flow.code("", this::securityAfterPhase))
            .triplet().repeats("only after a green gate");
    private final Agent estimator = Flow.code("estimator", this::price).triplet();
    private final Agent verdict = Flow.when("verdict", () -> !gateGreen,
            Flow.code("", this::verdictPhase))
            .triplet().repeats("only when the gate never went green");

    /**
     * THE STAGES OF A BUMP, FOR A READER RATHER THAN FOR A RUN.
     *
     * <p>A bump built for its shape alone: no workspace, no agents and no trace, because every node
     * holds a body it has not run. This is what the settings page and the bump strip read, and it
     * is the same object a live bump executes, so no stage can go on being advertised after it is
     * deleted. That happened for hours, twice, with a declaration in the middle.
     *
     * <p>THE HOP IN THE ROW IS NOMINAL and nothing walked here reads it. The titles, the nesting and
     * the agents' names are the same on every hop; what differs by hop is the prompts, and
     * {@link Agents#forHop} is what answers for those.
     */
    public static List<Shape.Stage> stages() {
        return Shape.of(new Bump(Path.of("."), "shape|shape|17|21", null).everything);
    }

    /**
     * THE WHOLE BUMP, AS ONE TREE. This is the thing the rest of it was for.
     *
     * <p>It carries no name of its own because it IS the bump, and the class is called that;
     * printing a root line would put a stage above the survey where there is none. Assembled in the
     * constructor, because it holds the modules stage and a triad cannot be built until the trace
     * and the bump id have been set.
     */
    private final Agent everything;

    /**
     * THE STEP CAMPAIGN, WHICH IS A STAGE AND WAS NOT A NODE.
     *
     * <p>Three agents spend the whole repair budget here, one such three for each platform, and
     * for as long as the campaign was ordinary code inside {@code module-repair} the picture drew
     * that stage as a leaf
     * and said nothing about them. The one written record of what bounds them lived in a
     * declaration beside the program, and it was wrong: it said twelve per module, which is what
     * one campaign may order, and a module reaches the campaign once per gate turn.
     *
     * <p>So the campaign is a node and {@link #repairCampaign} reaches it through this field rather
     * than past it. What it may not claim is a count, which is why the ceiling is computed from the
     * three constants that bound it instead of typed.
     *
     * <p>The brief is carried in fields because a node runs on a task string and this one needs
     * three: what failed, where the campaign began, and what the campaign is for. One campaign runs
     * at a time, in one thread, so they are set by the caller on the line before it hands over.
     */
    private String campaignLog = "";
    private String campaignFloor = "";
    private String campaignAim = "";
    /** And a fourth: which regime the module is in, so the steps are asked for the right agents. */
    private String campaignPlatform = UNRESOLVED_PLATFORM;

    /**
     * And a fifth, which is the journal's rather than the agents': which campaign this is.
     *
     * <p>{@code <module>#<round>}, because the same node completes once per campaign and a module
     * reaches a campaign once per gate turn. Keyed on the module alone, the second module's first
     * campaign would replay the first module's answer and order no steps at all.
     */
    private String campaignKey = "";
    /**
     * WHICH CAMPAIGN THIS IS FOR THIS MODULE, COUNTED ACROSS THE GATE TURNS AND NOT WITHIN ONE.
     *
     * <p>The key was module + "#" + the inner loop index, and that index is reset on every call to
     * {@link #repairCampaign}, which the module gate calls once per TURN. So a module had at most
     * two distinct keys while it could run six campaigns, and turns two and three replayed turn
     * one. That is not a resume bug: it degraded runs that never resumed at all. The replayed
     * answer said a step had landed, which kept the gate open, so the remaining turns recompiled an
     * untouched tree while still paying a repair planner and a repair verifier each time.
     *
     * <p>RESET PER MODULE RATHER THAN PER BUMP, because a resume has to produce the same sequence
     * of keys or replay lines up with the wrong campaign. The walk is sequential and replays the
     * same modules in the same order, so a per-module count reproduces exactly; a process-wide
     * count would not, the moment an earlier module ran a different number of campaigns.
     */
    private String campaignModule = "";
    private int campaignsForModule;

    /** What a campaign answers with when it ordered a step that stuck. The journal replays it. */
    private static final String LANDED = "a step landed";

    private final Agent stepCampaign = Flow.code("module-repair-step", task -> {
        // A CAMPAIGN IS THE LONGEST THING A MODULE DOES, so the boundary is offered in front of one
        // as well as in front of the module. A campaign that has started runs to its own end.
        betweenStages("a repair campaign for " + campaignModule);
        return campaignOfSteps(campaignLog, campaignFloor, campaignAim, campaignPlatform)
                ? LANDED : "nothing landed"; })
            .triplet().repeats("up to " + MODULE_TURNS * (REASK + 1) * STEPS + " per module, "
            + REPAIR_BUDGET + " per bump");

    /**
     * THE SAME CAMPAIGN, JOURNALED, and it is what {@link #repairCampaign} actually runs.
     *
     * <p>Assigned in the constructor, because it wraps the field above and the journal is a
     * constructor argument. The picture is drawn off the unwrapped node inside the module walk and
     * is the same either way: a wrapper delegates its name and everything under it.
     */
    private final Agent repairSteps;

    /**
     * THE MODULE WALK: ONE MODULE AT A TIME, ALL THE WAY THROUGH.
     *
     * <p>Pinned, bumped, compiled, repaired and hardened before the walk moves on. It used to be
     * three passes over the whole repository with repair sixteen turns later, which meant a break
     * in the first module surfaced as a reactor error after the last one, with no obvious owner and
     * two hundred lines of log. The context a repair needs is the diff that caused it, and that
     * diff exists for about one module's worth of time.
     *
     * <p>THE WALK CARRIES NO NAME OF ITS OWN, because it IS the modules stage and the triad above
     * it is already called that; printing the word twice would say there are two stages where there
     * is one. The same rule covers the two bodies inside it that have no name: the build IS the
     * module gate, and the campaign IS module-repair.
     */
    private final Agent walk = Flow.each("", () -> modules, Bump::label, this::moduleWalk);

    /**
     * ONE MODULE'S TURN, WHICH IS WHAT THE WALK REPEATS.
     *
     * <p>A method rather than a lambda in the field above, and not for taste: a field initialiser
     * may not read a blank final, and every stage in here reads the trace and the bump it is
     * running. The tree is the same either way.
     *
     * <p>THE TURN STATE IS PER MODULE BECAUSE THE TURNS ARE. It is allocated here, and this runs
     * once per module, so no module can read the state of the one before it. The one thing
     * deliberately NOT per module is the repair budget: that one is the bump's, it is a field, and
     * the walk draws it down across the modules it visits.
     *
     * <p>Called once with a null module to draw the picture, so nothing may read the module until
     * the nodes run. A shape is what the program can do, not what one repository made it do.
     */
    private Agent moduleWalk(Modules.Module m) {
        boolean[] red = {false};      // this module's last build came back red
        boolean[] open = {true};      // there is still a turn worth taking
        boolean[] counted = {false};  // it is already counted as having needed repair
        int[] turn = {0};
        String[] log = {""};
        // WHAT MANAGES THIS MODULE'S VERSIONS: written by the first stage below, read by the rest.
        //
        // Per module for the same reason the turn state is: it is allocated here, this method runs
        // once per module, and so no module can read the regime of the one before it.
        //
        // IT STARTS AT THE FALLBACK AND IS RESOLVED INSIDE A NODE BODY, WHICH IS NOT A PREFERENCE.
        // This method is also called once with a NULL module, to draw the picture, on a bump that
        // has no agents, no trace and no workspace. This used to be sharper: the legacy page
        // read stages() from a STATIC INITIALISER, so a null dereference here was not a 500 on
        // one page, it was an ExceptionInInitializerError that took the whole class with it.
        // That page is deleted and only Api reads this now, per request. The rule stands anyway,
        // because the reason it existed is that a shape is what the program can do rather than
        // what one repository made it do.
        String[] platform = {UNRESOLVED_PLATFORM};
        return Flow.seq("module",
                // FIRST, BECAUSE EVERY STAGE AFTER IT IS KEYED BY WHAT IT SETTLES. A pin doer told
                // to raise an artifact Spring Boot manages and a pin doer told to raise one nothing
                // manages are given opposite instructions, and until this stage existed they were
                // one agent handed whichever of the two the text happened to say.
                // NOT JOURNALED, ALONE AMONG THE MODULE STAGES, and it is the one whose answer is
                // not the point. What this settles is the regime the four stages under it are
                // keyed by, and it settles it in a variable: a replayed answer returns the sentence
                // and sets nothing, so a resumed module would pin and bump under the fallback
                // regime while its journal row said Spring Boot. One triad per resumed module is
                // the price of that, and it is the cheapest of the five.
                Flow.code("platform", task -> {
                    // THE WALK IS WHERE A ROUND MOST OFTEN ENDS, because it is where the hours go.
                    // Asked once per module, at the top of the module's turn, so a boundary lands
                    // between two modules and the ones behind it are committed and journaled.
                    betweenStages("the module walk, before " + label(m));
                    platform[0] = platformOf(m);
                    return label(m) + ": " + platform[0];
                }).triplet(),
                // KEYED ON THE MODULE, WHICH IS WHAT MAKES THE WALK RESUMABLE AT ALL. Each of these
                // completes once per module, so a journal keyed on the stage alone would watch the
                // first module finish and skip the other nineteen. What they edited is committed
                // when they land, so a module the journal names is a module whose work is in git.
                //
                // The key is read when the node runs rather than when it is built, because this
                // method is also called once with a null module to draw the picture.
                journaled(Flow.code("before-pins", task -> {
                    trace.progress(bump, "module " + label(m) + ": pinning what the hop needs");
                    pinPhase("before-pins-doer", false, m, platform[0]);
                    return label(m) + ": pinned";
                }).triplet().reads("enables"), () -> label(m)),
                journaled(Flow.code("bump", task -> {
                    trace.progress(bump, "module " + label(m) + ": moving the JDK");
                    bumpModule(m, platform[0]);
                    return label(m) + ": bumped";
                }).triplet(), () -> label(m)),
                // COMPILE IT NOW, while its diff is the only thing that changed. This is the shape
                // the repository gate used to have, one module wide, and the reason the repository
                // no longer needs one: the turns were repair's, and repair has moved here.
                //
                // COMPILE ONLY. Test conservation is a whole-suite fact measured against the
                // baseline, so a per-module test run cannot decide it, and the repository gate has
                // to run the suite anyway.
                Flow.loop("module-gate", MODULE_TURNS, () -> open[0],
                        Flow.code("", task -> {
                            // ROOT IS THE WHOLE REACTOR, NOT A MODULE. jvmjob turns an empty path
                            // into the unscoped build, so gating root on a multi-module repository
                            // compiles everything and then hands a full reactor log to an agent
                            // told it is repairing one module named "root". That is the failure
                            // this walk was built to remove, moved from the last module to the
                            // first. The repository gate compiles everything already; this one has
                            // nothing to add.
                            //
                            // It is a turn that declines rather than a guard in front of the loop,
                            // so the one thing that answers for the module gate is the module gate.
                            if (m.isRoot() && modules.size() > 1) {
                                trace.progress(bump, "module root: skipping its gate, because "
                                        + "compiling root is compiling the whole reactor and the "
                                        + "repository gate does that already");
                                open[0] = false;
                                return "root: no gate of its own";
                            }
                            String path = m.isRoot() ? "" : m.path();
                            turn[0]++;
                            // THE GATE MEASURES BYTECODE, SO IT HAS TO COMPILE BYTECODE. A bump is
                            // a pom-only edit, so nothing is newer than its class and Maven answers
                            // "Nothing to compile". Measured on the first three Maven bumps to run
                            // this walk: every module gate compiled zero files while the repository
                            // gate seconds later compiled five, seven and five. The gate was
                            // reading whether the baseline's classes were stale, not whether the
                            // module compiles under the target, and the repository repair loop that
                            // used to absorb the consequence was deleted in the same commit that
                            // added this.
                            runner.clearClasses(path);
                            Runner.Result compiled = runner.buildModule(to, path);
                            trace.built("module-gate-" + label(m) + "-" + turn[0],
                                    compiled.outcome());
                            red[0] = compiled.infra();
                            if (!red[0]) {
                                open[0] = false;
                                if (turn[0] > 1) {
                                    trace.progress(bump, "module " + label(m)
                                            + ": compiles after repair");
                                }
                                return label(m) + ": compiles under JDK " + to;
                            }
                            if (!counted[0]) {
                                counted[0] = true;
                                modulesRepaired++;
                            }
                            log[0] = compiled.summary();
                            trace.progress(bump, "module " + label(m) + ": will not compile under "
                                    + "JDK " + to + " (turn " + turn[0] + " of " + MODULE_TURNS
                                    + ")");
                            return label(m) + ": will not compile";
                        }),
                        // ONLY WHEN THE BUILD CAME BACK RED, which is the whole reason the build is
                        // the first step of the turn: a module that compiles never pays for a
                        // repair planner.
                        Flow.when("module-repair", () -> red[0],
                                Flow.code("", stepCampaign, task -> {
                                    // NOTHING LANDED CLOSES THE GATE. Going round again would ask
                                    // the same question of the same tree, and the repository gate
                                    // is the arbiter either way.
                                    open[0] = moduleRepair(m, log[0], platform[0]);
                                    return open[0] ? label(m) + ": a repair landed"
                                            : label(m) + ": nothing landed";
                                }))
                                .around("a campaign of steps")
                                .repeats("only when the module-gate is red"))
                        .deterministic()
                        .repeats("until green, or the turns run out"),
                // AFTER THE REPAIR, NOT BEFORE IT. Hardening polishes a module that already
                // compiles; asking it of one that does not is the wrong question.
                journaled(Flow.code("after-pins", task -> {
                    trace.progress(bump, "module " + label(m) + ": hardening what the bump left");
                    pinPhase("after-pins-doer", true, m, platform[0]);
                    return label(m) + ": hardened";
                }).triplet().reads("hardens"), () -> label(m)));
    }

    /**
     * THE WALK, STANDING AS THE MODULES STAGE'S DOER.
     *
     * <p>A block rather than a lambda because a triad's {@link Agent#inside()} can only show a
     * doer that is an agent, and a stage whose work is a whole walk drew as a leaf: the stage was
     * in the picture and everything it did was not.
     *
     * <p>THE OBJECTION IS PLACED HERE, which is the only place that knows what it means. See
     * {@link #walkObjection}: it goes into the brief of every module-scoped stage that shapes a
     * declaration, because a declaration is what the verifier read to form it.
     *
     * <p>THE PLAN IS STILL NOT ROUTED, and that is unchanged rather than decided. No module stage
     * has ever been handed the modules planner's plan, and handing it one now would rewrite every
     * brief in the walk on the pass that works. The objection is the half that was costing a whole
     * second walk to say nothing at all.
     */
    private final Flow.Doer walkDoer = new Flow.Block(walk) {
        @Override
        public String run(String plan, String feedback) throws IOException {
            walkObjection = objection(feedback);
            modulesRepaired = 0;
            // THE TASK IS EMPTY BECAUSE NO LEAF READS ONE, exactly as at the top of the bump: every
            // module stage builds its own brief, and that brief names its module far more plainly
            // than a line spliced onto a task could.
            body.run("");
            // THE WALK'S OWN WORD, not the last module's. What the stage did is what it covered.
            return "The walk covered " + modules.size()
                    + (modules.size() == 1 ? " module" : " modules")
                    + (modulesRepaired == 0 ? ", none needing repair."
                            : ", " + modulesRepaired + " needing repair.");
        }
    };

    /** The modules stage: the walk above, with a planner and the verifier that holds its loop. */
    private final Agent modulesStage;

    /** An objection to the walk, framed for an agent that is working on one module of it. */
    private static String objection(String feedback) {
        if (feedback == null || feedback.isBlank()) {
            return "";
        }
        return "\n\nThis is a second pass over the modules. A reviewer read the whole walk and sent"
                + " it back, and this is what it said. The objection is about the repository, so"
                + " most of it will be about modules that are not yours: answer the part that names"
                + " this one, and change nothing it does not name.\n" + feedback.strip();
    }

    /**
     * THE TREE, RUN, AND THE ACCOUNT IT SETTLED ON.
     *
     * <p>The order used to be written out here as statements. It is {@link #everything} now, which
     * is a sequence of nodes and the only statement of the order there is. What is left in this
     * method is the two ways a bump ends: a settlement thrown from inside a stage, which is the
     * jump {@link Flow.Settled} exists to name, and the ordinary one, where the tree runs to the
     * end and whichever stage settled the bump has written {@link #account}.
     *
     * <p>THE TASK IS EMPTY BECAUSE NO STAGE READS ONE. Every stage builds its own brief from the
     * hop and from the module it is working on, and a repository-wide brief invented here would be
     * a second source for something each of them already decides.
     */
    private String run() throws IOException {
        String last;
        try {
            last = everything.run("");
        } catch (Flow.Settled settled) {
            // NO TOOLING, NO BUILD, NOTHING TO CONSERVE. Every stage after the one that threw is
            // skipped, the estimator included: an attempt that never reached the gate has no work
            // to price. That is what the returns this replaces did, from inside those same places.
            return settled.account();
        }
        if (account.isBlank()) {
            // A REPLAYED CLOSER WRITES NO FIELD, so the account is settled here instead.
            //
            // Both closers set {@link #account} from inside their bodies, and a resumed bump can
            // reach either of them with the answer already in the journal, in which case the body
            // does not run. Neither fact is lost: the sequence answers with the verdict's own last
            // word, which IS the account of a red bump, and a green one's account is assembled from
            // the gate that has just re-run. Without this a resumed bump settles as the empty
            // string, which the sweep files as a state nobody has a name for.
            account = gateGreen && lastVerdict != null ? passed() : last;
        }
        if (account.isBlank()) {
            account = "infra\nthe bump ran to the end and no stage settled it";
        }
        return account;
    }

    /**
     * SURVEY: does the project agree it is where the manifest says?
     *
     * <p>It is also where the tools the rest of the bump runs on are built, because they have to
     * target the hop, and this is the stage that asks about the hop.
     *
     * <p>THE BUILDING COMES FIRST AND THE ASKING SECOND, which is a resume talking. The asking is
     * journaled, so a continued bump gets the answer back without paying for it; the tools are not,
     * because they are objects in a process that has just started and a bump that skipped making
     * them would resume into a stage with no runner to call. Nothing is lost by the order: the hop
     * is prescribed by the manifest, so the tools do not depend on what the surveyor concludes.
     */
    private String surveyPhase(String task) throws IOException {
        betweenStages("survey");
        // THE TOOLS FIRST, BECAUSE A RESUME SKIPS THE ANSWER AND NOT THE TOOLING. This stage is
        // where the runner, the scanner and the agents are made, and every stage after it runs on
        // them; a survey that came back out of the journal without building them would resume into
        // a bump whose next stage has no runner to call. They do not depend on what the surveyor
        // says either, because the hop is prescribed, so nothing is lost by making them first.
        hoptools = Env.get("BJV_HOPTOOLS");
        if (hoptools == null) {
            throw new IllegalStateException(
                    "BJV_HOPTOOLS must be the host path of hoptools/ (jvm-run is invoked from it)");
        }
        runner = new Runner(ws, hoptools);
        security = new Security(ws, hoptools, trace);
        // The producers' try_build must target the hop the survey settled, so they are built now.
        agents = new Agents(Model.forProducer(trace), ws, runner, tree, Hop.of(from, to), trace);
        // AND THE ASKING IS WHAT THE JOURNAL HOLDS. Wrapped here rather than around the stage,
        // because the wrapper has to sit inside the construction above and outside the three model
        // calls below. It is the same node name the stage carries, so the journal's row says
        // "survey" and the picture is untouched: nothing walks a node built inside a body.
        return journaled(Flow.code("survey", this::surveyAsk), REPO).run(task);
    }

    /**
     * What the survey is actually for: whether the project agrees it is where the manifest says.
     *
     * <p>Separated from the tooling above it because this half is the half a resume replays, and
     * the tooling is the half it must not. Nothing here is read by anything else in the bump; the
     * answer is a record, which is exactly why it is safe to hand back out of the journal.
     */
    private String surveyAsk(String task) throws IOException {
        // THE HOP IS PRESCRIBED, NOT DISCOVERED. It arrives in the manifest row and nothing here
        // may change it: the target is the experiment's independent variable, and an agent that
        // picks it makes every run a different experiment. It also went wrong in exactly the way
        // that predicts: the surveyor demoted three repos from 11->17 to 8->11 off a `release 8`
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
        return claim;
    }

    /** BASELINE: a fact, and the one everything later is measured against. No baseline, no bump. */
    private String baselinePhase(String task) throws IOException {
        betweenStages("baseline");
        // FIRST, THOUGH: IS THE TOOLING EVEN THERE. Builds here are sealed, so a Gradle wrapper
        // resolves its distribution out of a staged cache and cannot download. Staging can stop
        // half way and leaves a directory that looks exactly like a distribution; Gradle finds it,
        // uses it, and dies reaching for a jar that was never unpacked. The verdict that produced
        // was "the project does not build under its own JDK", about a project not one line of which
        // had run. Four of this corpus's thirty-one no-baseline verdicts are that.
        String tooling = Staged.problem(ws, Env.get("BJV_GRADLE_DISTS"));
        if (!tooling.isEmpty()) {
            trace.progress(bump, "infra: " + tooling);
            throw new Flow.Settled("infra\n" + tooling);
        }
        // AND THEN: HAS ANYONE ALREADY MEASURED IT. This is the one stage a resume must not run
        // again, and not because it is expensive. It is measured under the project's OWN JDK before
        // anything moved, and the tree has moved: a second run of it would build a migrated tree at
        // the old level and call the result the baseline, so every conservation judgement after it
        // would be against a set that never existed. That is why the journal keeps facts at all.
        Optional<String> measured = recalled(BASELINE_PRE);
        if (measured.isPresent()) {
            pre = tests(measured.get());
            baselineByModule = perModuleTests(recalled(BASELINE_BY_MODULE).orElse(""));
            baselineGreen = Boolean.parseBoolean(recalled(BASELINE_GREEN).orElse("false"));
            String counted = "tests passing under JDK " + from + ": " + pre.size()
                    + " (read back from the journal: this was measured before anything moved, and"
                    + " once the tree is edited it cannot be measured again)";
            trace.progress(bump, "baseline: " + counted);
            trace.applied("baseline", counted);
            return counted;
        }
        trace.progress(bump, "baseline: building and testing under JDK " + from);
        Runner.Result preBuild = runner.build(from);
        trace.built("baseline-build", preBuild.outcome());
        if (preBuild.infra()) {
            throw new Flow.Settled("no-baseline\nthe project does not build under its own JDK "
                    + from + ":\n" + preBuild.summary());
        }
        runner.clearReports();
        Runner.Result preTest = runner.test(from);
        trace.built("baseline-test", preTest.outcome());
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
            throw new Flow.Settled("no-baseline\n" + (preTest.infra()
                    ? "the tests could not be RUN under the project's own JDK " + from
                    + ", so there is nothing to conserve:\n" + preTest.summary()
                    : "no test passed under the project's own JDK " + from + ", so there is nothing"
                    + " to conserve and a bump here would be unverifiable:\n" + preTest.summary()));
        }
        // WRITTEN THE MOMENT IT EXISTS, and after the check above rather than before it: a bump
        // that settles here has no baseline to carry, and a fact recorded for it would be a set
        // nobody measured. Three rows, because the three answer different questions and the last
        // of them decides which closing stages a resumed bump runs.
        record(BASELINE_PRE, String.join("\n", pre));
        record(BASELINE_BY_MODULE, joinedPerModule(baselineByModule));
        record(BASELINE_GREEN, String.valueOf(baselineGreen));
        String counted = "tests passing under JDK " + from + ": " + pre.size()
                + (baselineGreen ? "" : " (the suite is not all green; the red ones were red before"
                + " this bump and are not in the set)");
        trace.applied("baseline", counted);
        return counted;
    }

    /**
     * THE PASSING SET, AS ONE LINE PER TEST.
     *
     * <p>Safe because {@code Gate.passing} escapes control characters injectively before a name
     * reaches a set: JUnit 5 display names carry literal newlines, and a set written one per line
     * would otherwise come back as more tests than were ever run. The same fold is what lets the
     * per-module map below use a tab.
     */
    private static Set<String> tests(String recorded) {
        Set<String> out = new LinkedHashSet<>();
        for (String line : recorded.split("\n")) {
            if (!line.isBlank()) {
                out.add(line);
            }
        }
        return out;
    }

    /** Each module's own set, as {@code <module>\t<test>} rows. Root's path is empty and stays so. */
    private static String joinedPerModule(Map<String, Set<String>> byModule) {
        StringBuilder b = new StringBuilder();
        byModule.forEach((module, passing) -> passing.forEach(
                test -> b.append(module).append('\t').append(test).append('\n')));
        return b.toString();
    }

    private static Map<String, Set<String>> perModuleTests(String recorded) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        for (String line : recorded.split("\n")) {
            int tab = line.indexOf('\t');
            if (tab < 0) {
                continue;
            }
            out.computeIfAbsent(line.substring(0, tab), m -> new LinkedHashSet<>())
                    .add(line.substring(tab + 1));
        }
        return out;
    }

    /** SECURITY BEFORE: the project's own state, and the last moment it still is. */
    private String securityBeforePhase(String task) throws IOException {
        betweenStages("security-before");
        // Migrate applies recipes, floors and a target sweep next, every one of which moves a
        // resolved version, so a scan taken after it is not this project's prior state. It also
        // has to follow a build, because the collect is offline and copies only what the build
        // already pulled down.
        trace.progress(bump, "security: scanning before any migration, under JDK " + from);
        before = security.scan(from, "before");
        securityBeforeAdvisory();
        return before.measured()
                ? "CRITICAL+HIGH before any migration: " + before.total()
                : "nothing to read (" + before.why() + ")";
    }

    /**
     * MODULE FILTER: what this repository is made of, minus what this bump should leave alone.
     *
     * <p>It installs the deterministic pre-pass as well, and that is why it sits behind the scan
     * rather than in front of it: the recipes move resolved versions, and a scan taken after they
     * have run is not this project's prior state.
     */
    private String moduleFilterPhase(String task) throws IOException {
        betweenStages("module-filter");
        // The before-scan travels into the migration: the Tomcat floor has to know which line the
        // project actually resolved, and no build file says that.
        tree.excludeBuildOutput();
        // THE ORDER IS THE POINT. Lombok has to move before the JDK, because a Lombok that cannot
        // read the new class file kills javac before anything else runs. Spring Boot has to move
        // after, because Boot 4.1 declares java.version 17 and cannot be resolved by a project
        // still on 11. Both used to happen in one pass with nothing sequencing them.
        agents.withRecipes(new Migrate(ws, hoptools, trace));

        modules = filteredModules();
        return "this bump works on " + modules.size()
                + (modules.size() == 1 ? " module." : " modules.");
    }

    /**
     * The security reading, and its critic. ADVISORY: neither has a tool that writes.
     *
     * <p>Nothing downstream lifts a dependency for security, and an edit made here would be
     * charged by the reward and flagged by the prepare-critic as answering no trigger. What this
     * produces is a record: which findings this hop could plausibly reach, for whoever decides how
     * a CVE count and a PASS trade against each other.
     */
    private void securityBeforeAdvisory() {
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
    private void advisory(String stage, Agent planner, Agent doer, Agent verifier,
                          String brief) {
        try {
            Flow.triad(stage, planner,
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
    private void securityAfterAdvisory() {
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

    /**
     * THE MODULE WORK, AS ONE TRIAD OVER THE WALK BELOW IT.
     *
     * <p>The doer is the walk; the verifier reads every module and decides whether the repository
     * is actually done. That verifier is the piece the repo-level gate cannot supply in time: the
     * gate runs four stages later and reports a single minimum across the tree, so a module left
     * behind arrives as an unraised repository pointing nowhere.
     *
     * <p>The stage is assembled in the constructor, so the tree exists before anything runs and the
     * picture can be walked off it. What is left here is the brief, which is made of fields that
     * have no value until the filter has chosen the modules, and so cannot be handed down by a
     * sequence that was built before either of them existed.
     */
    private String modulesBrief() {
        return "Migration: JDK " + from + " -> " + to + " (" + bump + ")"
                + "\n\nThe modules this bump works on:\n"
                + modules.stream().map(m -> "  " + label(m))
                        .collect(java.util.stream.Collectors.joining("\n"));
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
    private void pinPhase(String stage, boolean after, Modules.Module only, String platform)
            throws IOException {
        Agent planner = after ? agents.afterPinsPlanner(platform)
                : agents.beforePinsPlanner(platform);
        Agent doer = after ? agents.afterPinsDoer(platform)
                : agents.beforePinsDoer(platform);
        Agent verifier = after ? agents.afterPinsVerifier(platform)
                : agents.beforePinsVerifier(platform);

        // SCOPED TO ONE MODULE, and said in the first line so it cannot be skimmed past. An
        // agent handed the whole module list and asked about one of them will drift into the
        // others, and the diff it leaves is then somebody else's turn's problem.
        String brief = "Migration: JDK " + from + " -> " + to + " (" + bump + ")\n\n"
                + "YOU ARE WORKING ON ONE MODULE: " + label(only)
                + "\nEvery other module in this repository is somebody else's turn. Do not edit "
                + "them.\n\nThe modules of this project, for context only:\n" + moduleList()
                // TRUE OF THIS MODULE, NOT OF THE REPOSITORY. The walk is module-major, so by
                // the time module two is pinned, module one has already been raised; saying "the
                // JDK has NOT been raised yet" is then a false statement of fact to a producing
                // agent, once per module rather than once per bump.
                + "\n\nTHIS MODULE has " + (after ? "already been raised to " + to
                        + ", so versions that require it can now be resolved. Earlier modules in "
                        + "the walk have been raised too; later ones have not yet."
                        : "NOT been raised yet; it is still " + from + ". Modules earlier in the "
                        + "walk may already have been raised, which is expected and is not your "
                        + "business.")
                // WHY THIS PASS IS HAPPENING, when it is not the first one. Empty on every first
                // walk, which is every walk that is not answering a reviewer.
                + walkObjection;

        Flow.triad(stage, planner,
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

    /**
     * WHICH REGIME MANAGES THIS MODULE'S VERSIONS, settled by a pair rather than by a predicate.
     *
     * <p>Every stage after it in the walk is keyed by the word it settles on, because the regimes
     * ask for opposite work: on a Spring Boot module the managed set moves as a set and pinning one
     * of its members overrides it, while on a module nothing manages every artifact is raised on
     * its own and the conflicts are the raiser's.
     *
     * <p>WHY AN AGENT AND NOT A STRING MATCH. Of the 277 Boot-managed modules in this corpus, 41
     * name a Boot parent themselves and a match would find them; 185 inherit an in-repo parent pom
     * that is itself Boot, which is visible only after following that chain to its outermost pom,
     * and two of the corpus's import-scope BOMs are written as a property rather than a literal, so
     * nothing matching text will ever find them. {@link Managed#report} states all of it without
     * judging any of it, and this pair does the judging.
     *
     * <p>THE ANSWER IS CLOSED, AND THE FALLBACK IS COUNTABLE. A fourth word names a prompt
     * directory that does not exist, so it becomes the unmanaged regime, which is also the safe one
     * to be wrong towards. What a fallback must not be is invisible: a detector that quietly missed
     * would key five later stages to the wrong instructions with nothing in the record to say so.
     */
    private String platformOf(Modules.Module m) throws IOException {
        String brief = "Migration: JDK " + from + " -> " + to + " (" + bump + ")\n\n"
                + "YOU ARE LOOKING AT ONE MODULE: " + label(m)
                + "\nDecide what manages its dependency versions. Nothing is edited in this stage."
                + "\n\nThe modules of this project, for context only:\n" + moduleList();
        // WHAT THE VERIFIER ACTUALLY SAID, which a triad keeps to itself: it answers with the
        // doer's last word either way, so a pair that agreed and a pair that ran out of rounds come
        // back as the same value. That difference is most of what "detection failed" means.
        String[] judged = {""};
        String said = Flow.triad("platform", agents.platformPlanner(),
                (plan, feedback) -> agents.platformDoer().run(brief
                        + "\n\nWhat to look at, and what would settle it:\n" + plan + feedback),
                task -> {
                    judged[0] = agents.platformVerifier().run(task);
                    return judged[0];
                },
                () -> Managed.report(ws, m, allModules) + "\n" + Declared.report(ws, List.of(m)),
                trace, bump, REASK + 1)
                .run(brief);

        String platform = Managed.platformIn(said);
        // A blank verdict is `again`, exactly as a triad reads one: silence is not agreement.
        boolean settled = !judged[0].isBlank()
                && Reply.word(judged[0], "done", "again", "replan").equals("done");
        // WHY TWO TESTS AND NOT ONE. A word outside the three is what the verifier is there to
        // reject, so most out-of-set answers arrive here as a loop that never closed. The second
        // catches the one that gets past it, a verifier approving a word this walk has no prompts
        // for: platformIn answers with the fallback for "micronaut" exactly as it does for a
        // considered unmanaged, and the two are told apart by whether the word is in the answer at
        // all. That is a containment test rather than a second parser -- the labelled line is read
        // in one place, and this does not read it.
        boolean named = !platform.equals(UNRESOLVED_PLATFORM)
                || said.toLowerCase().contains(UNRESOLVED_PLATFORM);
        if (!settled || !named) {
            platform = UNRESOLVED_PLATFORM;
            trace.progress(bump, "platform: " + label(m) + " fell back to " + platform + " ("
                    + (settled ? "the answer named no platform this walk has prompts for"
                            : "the pair never settled") + "): "
                    + said.lines().findFirst().orElse(""));
        }
        // PER MODULE, IN THE RECORD, so a corpus can be grouped by regime afterwards. Said only in
        // prose, the one fact that chose every prompt below it would be unrecoverable from a trace.
        trace.applied("platform", label(m) + ": " + platform);
        return platform;
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
                + (allModules.size() == 1 ? " module." : " modules; the others are not yours.")
                + walkObjection;
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
    private List<Modules.Module> filteredModules() throws IOException {
        allModules = Modules.of(ws);
        if (allModules.size() == 1) {
            trace.applied("modules", "one module; no filtering to do");
            return allModules;
        }
        // WHAT THE FILTER CHOSE IS A JOURNALED FACT, and it is a fact of a different kind from the
        // baseline. The baseline cannot be measured again; this could be asked again, and that is
        // the problem: it is an agent's judgement about which trees are vendored or generated, so a
        // second pass would answer it differently and a resumed bump would walk a different set of
        // modules from the one it had already half finished.
        Optional<String> chosen = recalled(MODULE_LIST);
        if (chosen.isPresent()) {
            List<String> labels = List.of(chosen.get().split("\n"));
            List<Modules.Module> kept = allModules.stream()
                    .filter(m -> labels.contains(label(m))).toList();
            if (!kept.isEmpty()) {
                trace.applied("modules", "read back from the journal: working on " + kept.size()
                        + " of " + allModules.size() + "\n" + String.join("\n", labels));
                return kept;
            }
            // A LIST THAT NAMES NOTHING IN THIS TREE IS NOT THIS TREE'S LIST. Nothing should be
            // able to produce that, since the resume was checked against the workspace sha, and
            // filtering to nothing would walk no modules at all and gate an untouched repository.
            trace.progress(bump, "modules: the journal names no module this checkout has; "
                    + "choosing again rather than walking nothing");
        }
        String listing = allModules.stream().map(m -> "  " + label(m))
                .collect(java.util.stream.Collectors.joining("\n"));
        String brief = "Migration: JDK " + from + " -> " + to + " (" + bump + ")"
                + "\n\nThe modules, read from the build files:\n" + listing;
        String[] answer = {""};
        // THE ASKING IS JOURNALED, THE PARSE IS NOT. What the reply means is decided by skips()
        // below, which is deterministic and reads the same reply the same way every time, so there
        // is one record of the decision rather than two that can disagree.
        String said = journaled(Flow.code("module-filter", task -> {
            Flow.triad("module-filter", agents.moduleFilterPlanner(),
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
            return answer[0];
        }), REPO).run(brief);
        List<Modules.Module> keep = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (Modules.Module m : allModules) {
            if (!m.isRoot() && skips(said, m)) {
                skipped.add(label(m));
            } else {
                keep.add(m);
            }
        }
        record(MODULE_LIST, keep.stream().map(Bump::label)
                .collect(java.util.stream.Collectors.joining("\n")));
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
    private void bumpModule(Modules.Module m, String platform) throws IOException {
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
        Flow.triad("bump:" + label(m), agents.bumpPlanner(platform),
                (plan, feedback) -> {
                    String said = agents.bumpDoer(platform).run(moduleBrief(m)
                            + "\n\nThe plan you are carrying out:\n" + plan + feedback);
                    trace.applied("bump", label(m) + "\n" + said + "\n" + tree.diff());
                    return said;
                },
                agents.bumpVerifier(platform), () -> bumpFacts(m, target), trace, bump, REASK + 1)
                .run(moduleBrief(m));
        tree.land("bump " + label(m));
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
    private boolean moduleRepair(Modules.Module m, String log, String platform)
            throws IOException {
        // SCOPED, AND SAID FIRST. An agent handed a reactor log and asked about one module drifts
        // into the others, and the diff it leaves is then the next module's turn's problem.
        String scoped = "YOU ARE REPAIRING ONE MODULE: " + label(m)
                + "\nIt does not compile under JDK " + to + ". Every other module in this "
                + "repository is somebody else's turn; do not edit them.\n\n" + log;
        return repairCampaign(label(m), scoped, platform);
    }

    /**
     * THE NEXT CAMPAIGN FOR THIS MODULE, COUNTED ACROSS ITS GATE TURNS AND NOT WITHIN ONE.
     *
     * <p>A method rather than two lines inside the loop because the invariant it holds is worth a
     * test, and the version this replaces passed a suite of 338 while being wrong. It keyed on the
     * inner loop index, which resets on every call, while the module gate calls that method once
     * per TURN: two distinct keys for up to six campaigns, so turns two and three replayed turn
     * one. A replayed answer saying a step landed keeps the gate open, so those turns recompiled an
     * untouched tree and still paid a planner and a verifier each time. It degraded runs that never
     * resumed.
     *
     * <p>RESET PER MODULE, NOT PER BUMP, because a resume must produce the same key sequence or
     * replay lines up with the wrong campaign. The walk is sequential and replays the same modules
     * in the same order, so counting per module reproduces exactly.
     *
     * <p>KNOWN LIMIT: an outer verifier answering `again` re-runs the whole walk, and this counter
     * resets with it, so a second pass replays the first pass's campaigns rather than running new
     * ones. That is the honest reading, since those campaigns did happen, and the gate is re-run
     * either way because it is never journaled. It is written down because it is the one case where
     * this key means something other than what it appears to.
     */
    private String nextCampaignKey(String module) {
        if (!module.equals(campaignModule)) {
            campaignModule = module;
            campaignsForModule = 0;
        }
        return module + "#" + campaignsForModule++;
    }

    private boolean repairCampaign(String module, String log, String platform) throws IOException {
        String floor = tree.head();
        String feedback = "";
        // WHAT THE CAMPAIGN IS FOR, decided before anyone edits. A campaign with no stated end runs
        // until its budget is spent, and this planner is also the one place a failure that is not
        // this bump's doing can be named as such: a test red before anything moved is not a wall,
        // and treating it as one has cost this corpus whole runs.
        String aim = agents.moduleRepairPlanner(platform).run(brief(log)
                + "\n\nWhat has landed so far:\n" + tree.history(floor));
        if (aim.stripLeading().startsWith("NOT-OURS")) {
            trace.progress(bump, "module-repair: " + aim.lines().findFirst().orElse(""));
            return false;
        }
        for (int campaign = 0; campaign <= REASK; campaign++) {
            // THROUGH THE NODE RATHER THAN PAST IT. The steps are a stage with three agents of
            // their own and the whole repair budget to spend, and while the campaign was reached
            // by an ordinary call the picture could not see any of that. What runs is this
            // method's own campaign either way; the difference is that the page can now name it.
            campaignLog = log;
            campaignFloor = floor;
            campaignAim = "\n\nWhat this campaign is for:\n" + aim + feedback;
            campaignPlatform = platform;
            campaignKey = nextCampaignKey(module);
            // WHAT LANDED IS READ OFF THE ANSWER, NOT OFF A FIELD, and that is what makes this
            // resumable rather than merely journaled. A replayed node returns the sentence it
            // returned before and runs no body, so a field the body used to set would still hold
            // the previous campaign's outcome and this loop would act on it.
            boolean landed = LANDED.equals(repairSteps.run(""));
            String judgement = agents.moduleRepairVerifier(platform, floor)
                    .run("The failing build:\n" + log
                            + "\n\nThe whole campaign, since it began:\n" + tree.diffSince(floor)
                            + "\n\nThe steps that landed:\n" + tree.history(floor));
            if (Reply.word(judgement, "done", "again").equals("done") || campaign == REASK) {
                return landed;
            }
            trace.progress(bump, "module-repair-verifier sent the campaign back: "
                    + judgement.lines().findFirst().orElse(""));
            // The critic may already have rewound; if it did not, its objection stands on top of
            // whatever is there, which is its choice to make and not this loop's.
            feedback = "\n\nA reviewer read your whole campaign and sent it back:\n" + judgement;
        }
        return false;
    }

    /** One campaign: the loop proposer orders steps until it stops or the budget is spent. */
    private boolean campaignOfSteps(String log, String floor, String feedback, String platform)
            throws IOException {
        boolean landed = false;
        for (int step = 0; step < STEPS; step++) {
            // THE ALLOWANCE IS THE BUMP'S, NOT THIS MODULE'S. A module that needs thirty steps may
            // have them; what it may not do is leave nothing for the modules behind it.
            if (repairLeft <= 0) {
                trace.progress(bump, "repair: the bump's step budget of " + REPAIR_BUDGET
                        + " is spent; nothing further is ordered for any module");
                return landed;
            }
            repairLeft--;
            String order = agents.moduleRepairStepPlanner(platform, floor)
                    .run(brief(log) + feedback
                            + "\n\nSteps landed so far in this campaign:\n" + tree.history(floor)
                            + "\n\nWhat the campaign has changed:\n" + tree.diffSince(floor));
            String head = order.stripLeading();
            if (head.startsWith("DONE:") || head.startsWith("BLOCKED:")) {
                trace.progress(bump, "module-repair-step: " + head.lines().findFirst().orElse(""));
                return landed;
            }
            if (step(log, order, platform)) {
                landed = true;
                tree.land("step: " + head.lines().findFirst().orElse("").strip());
            } else {
                // A step nobody could land is a signal about the order, not about the workspace,
                // and the proposer sees it next time round in the history that did not grow.
                trace.progress(bump, "module-repair-step: the ordered step did not land");
            }
        }
        trace.progress(bump, "module-repair-step: step budget spent");
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
    private boolean step(String log, String order, String platform) throws IOException {
        String brief = brief(log) + "\n\nThe step you have been asked to make:\n" + order;
        String reply = agents.moduleRepairStepDoer(platform).run(brief);
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
            String judgement = agents.moduleRepairStepVerifier(platform)
                    .run("The failing build said:\n" + log
                    + "\n\nThe edits now in the workspace:\n" + now + "\n\nWhat they said:\n" + reply);
            if ("sound".equals(Reply.word(judgement, "sound", "gaming", "off-target"))) {
                return true;
            }
            tree.revert();
            rejected.add("You tried:\n" + reply + "\nA reviewer rejected it:\n" + judgement);
            if (attempt == REASK) {
                trace.progress(bump, "step rejected twice; handing back to the loop");
                return false;
            }
            reply = agents.moduleRepairStepDoer(platform).run(brief
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

    /**
     * THE GATE: THE SCORER, AND NO LONGER A LOOP.
     *
     * <p>It ran sixteen times, with repair between the turns, and those turns were repair's: the
     * gate had to keep re-running to find out whether the last repair had worked. Repair lives
     * inside the module walk now, so what is left here is the one thing only this can decide.
     *
     * <p>WHAT ONLY THIS CAN DECIDE. The passing set against the baseline, which is a whole-suite
     * fact: a per-module run cannot tell a test that was lost from one that moved. And the lowest
     * bytecode level any module actually emits, which is a property of the tree.
     *
     * <p>WHAT THIS GIVES UP, knowingly: a module that compiles alone and breaks the reactor because
     * a sibling moved under it now has no repair path. Every module gate was green, this one is
     * red, and nothing tries again. The argument for taking that is that a cross-module break is
     * usually a bad edit, and a bad edit is better failed loudly than papered over sixteen times.
     *
     * <p>IT DECIDES AND IT DOES NOT SETTLE. What it leaves behind is {@link #gateGreen}, which both
     * closers select on, and the log the arguer reads when it is red. The sentence a passing bump
     * settles on quotes a scan that has not been taken yet, so this cannot be where it is written.
     */
    private String gatePhase(String task) throws IOException {
        betweenStages("gate");
        trace.progress(bump, "gate: building and testing the whole repository under JDK " + to);
        // The gate measures bytecode, so it must compile bytecode rather than inherit the
        // baseline's. Without this Maven finds the old classes newer than the sources and skips
        // the compile, and the target is read off the level the project started at.
        runner.clearClasses();
        Runner.Result build = runner.build(to);
        trace.built("gate-build", build.outcome());
        if (build.infra()) {
            lastVerdict = null;
            lastLog = build.summary();
            return "the repository does not build under JDK " + to;
        }
        runner.clearReports();
        Runner.Result test = runner.test(to);
        trace.built("gate-test", test.outcome());
        // THE SCORER DECIDES, NOT THE EXIT CODE. A green build with an unraised module or a
        // quietly dropped test is exactly the false pass this measures.
        Gate.Verdict v = Gate.decide(pre, Gate.passing(ws), !test.infra(),
                Gate.effectiveTarget(ws), Integer.parseInt(to));
        trace.applied("gate", v.state() + " (pre=" + v.preTests()
                + " lost=" + v.lost() + " effective-target=" + v.effectiveTarget() + ")"
                + names(v.missing())
                + perModule(Integer.parseInt(to)));
        gateGreen = v.pass();
        // RECORDED, THOUGH NOTHING READS IT BACK, and the difference is worth stating. Both closing
        // stages select on this word, so the journal would be an incomplete account of the run
        // without it. What a resume does NOT do is trust it: this stage is a build, it is the
        // arbiter, and it has just run again, so the value above is the tree's own answer about the
        // tree as it now is rather than a memory of what it was before the kill.
        record(GATE_GREEN, String.valueOf(gateGreen));
        // KEPT WHETHER IT PASSED OR NOT, which is what the field's name says. Only the arguer reads
        // it, and the arguer does not run on a green gate, so it is the same verdict it always saw.
        lastVerdict = v;
        if (!gateGreen) {
            lastLog = failureFor(v, test);
        }
        return v.state();
    }

    /**
     * SECURITY AFTER: THE ONLY MOMENT THE AFTER SCAN MEANS ANYTHING.
     *
     * <p>The workspace has just built and tested green at the target, so the offline collect is
     * complete. On any other exit the collect copies whatever resolved before the build died, and
     * the count falls because modules are missing rather than because anything was fixed: the
     * corpus's largest apparent wins are dead builds. That is why this is selection on the gate and
     * not a stage that always runs and decides for itself whether to bother.
     *
     * <p>IT WRITES THE ACCOUNT OF A PASSING BUMP, because the last fact in that sentence is the
     * number this stage has just measured. See {@link #account}.
     */
    private String securityAfterPhase(String task) throws IOException {
        betweenStages("security-after");
        trace.progress(bump, "security: scanning after a green gate, under JDK " + to);
        after = security.scan(to, "after");
        delta = Security.compare(before, after);
        trace.applied("security-delta", delta.valid()
                ? delta.before() + " -> " + delta.after() + " CRITICAL+HIGH; cleared "
                + delta.cleared() + ", introduced " + delta.introduced()
                : "UNKNOWN: " + delta.why());
        securityAfterAdvisory();
        account = passed();
        return "CRITICAL+HIGH " + securitySummary();
    }

    /**
     * WHAT A GREEN BUMP SETTLED ON: the state on the first line, then what it conserved, at what
     * level, against what CVE count.
     *
     * <p>A wire format rather than prose. The sweep splits it at the first newline and files the
     * word; the page reads "N tests conserved" back out of the second line with a pattern. So it is
     * assembled in one place, and this is it.
     */
    private String passed() {
        return "PASS\n" + lastVerdict.preTests() + " tests conserved, effective target "
                + lastVerdict.effectiveTarget() + "; CRITICAL+HIGH " + securitySummary();
    }

    /**
     * THE VERDICT: ARGUES ONLY WHAT EXECUTION COULD NOT SETTLE.
     *
     * <p>Only when the gate never went green, which is the mirror of the after-scan and the reason
     * both are selection. A bump that passed has nothing left unsettled to argue, and an arguer
     * handed one would find something to say regardless, because saying something is what it is
     * for.
     */
    private String verdictPhase(String task) throws IOException {
        betweenStages("verdict");
        String context = brief(lastLog)
                + (lastVerdict == null ? "" : "\nThe scorer's last verdict: " + lastVerdict.state())
                // THE GATE RUNS ONCE. Telling the arguers a budget was exhausted biases them
                // toward blocked-dependency over "nobody tried", and the word they choose is what
                // the corpus keeps.
                + "\nThe repository gate ran once and was not green. Repair happened per module,"
                + " during the walk, and there is no repository-level retry.";
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
            if (Reply.word(judgement, "sound", "wrong").equals("sound")) {
                break;
            }
            trace.progress(bump, "verdict-critic: " + judgement.lines().findFirst().orElse(""));
            argued = agents.verdictDoer().run(context
                    + "\n\nYou argued:\n" + argued
                    + "\n\nA reviewer checked it against the record and disagrees:\n" + judgement
                    + "\nArgue it again, or keep your word and answer the objection.");
        }
        account = Reply.word(argued, "blocked-dependency", "behavior-change", "infra") + "\n" + argued;
        return account;
    }

    /**
     * THE ESTIMATOR: WHAT THE SAME WORK WOULD HAVE COST A PERSON, priced from the record.
     *
     * <p>It runs on every bump that reached the gate, green or red, which is what it always claimed
     * to do and what it now does. It throws because an Agent may be plain code and plain code
     * touches the workspace; the signature was the only thing pretending otherwise.
     */
    private String price(String task) throws IOException {
        betweenStages("estimator");
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
        if (!Reply.word(judged, "sound", "off").equals("sound")) {
            trace.progress(bump, "estimator-critic: " + judged.lines().findFirst().orElse(""));
            estimate = agents.estimatorDoer().run(context + "\n\nYou estimated:\n" + estimate
                    + "\n\nA reviewer checked it against the log:\n" + judged
                    + "\nPrice it again.");
        }
        Matcher m = Pattern.compile("minutes:\\s*(\\d+)").matcher(estimate);
        trace.priced(bump, m.find() ? m.group(1) : "", estimate);
        return estimate;
    }

    private static String[] parseHop(String claim) {
        Matcher m = Pattern.compile("hop:\\s*(\\d+)\\s*->\\s*(\\d+)")
                .matcher(claim == null ? "" : claim);
        return m.find() ? new String[]{m.group(1), m.group(2)} : null;
    }

}
