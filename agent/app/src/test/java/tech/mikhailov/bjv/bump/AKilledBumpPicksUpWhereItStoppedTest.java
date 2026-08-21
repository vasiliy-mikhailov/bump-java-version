package tech.mikhailov.bjv.bump;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import tech.mikhailov.ratchet.flow.Agent;
import tech.mikhailov.ratchet.record.Journal;
import tech.mikhailov.ratchet.record.JsonlTrace;
import tech.mikhailov.ratchet.record.Trace;
import tech.mikhailov.bjv.jvm.Modules;

/**
 * A SWEEP RUNS FOR A FORTNIGHT AND THE HARNESS CHANGES DAILY, so lanes get killed and every killed
 * bump used to start over. One of them had twenty hours in it.
 *
 * <p>What makes that fixable here rather than hard is two properties this system already has. The
 * agents are stateless, so there is no conversation to rebuild: a call is a system prompt and one
 * task string, and nothing carries between calls except what the harness splices in. The workspace
 * is durable, so what was edited is committed as each stage lands and the readers re-read it for
 * free. So almost everything is re-derived, and the journal holds only the three things that cannot
 * be: the baseline measured before anything moved, the module list an agent chose, and which
 * model-driven stages have already been paid for.
 *
 * <p>These tests are about the wiring rather than about the journal, which has its own. What they
 * assert is which stages a resumed bump skips and which it insists on running again. Whether a
 * resume happens at all is {@code Resume}'s rule and is tested in the library; what is left here is
 * the part that is about this domain, which is what a bump puts its name to and what this project
 * calls the two words the library has to be told.
 */
class AKilledBumpPicksUpWhereItStoppedTest {

    private static final String BUMP = "owner/repo|abc123|17|21";

    /**
     * THE STAGES THAT LANDED ARE NOT WALKED AGAIN, and the ones that did not are.
     *
     * <p>Keyed on the module, because each of these completes once per module: a journal keyed on
     * the stage alone would watch the first module finish and skip the other nineteen. The bump
     * here has no agents and no runner, which is what makes the assertion sharp rather than
     * incidental. A stage that was replayed cannot have called a model, and a stage that was not
     * reaches for one that is not there.
     */
    @Test
    void theModuleStagesThatLandedAreNotPaidForAgain(@TempDir Path dir) throws Exception {
        Journal journal = new Journal(dir.resolve("journal.jsonl"), () -> "sha1");
        journal.done("before-pins", "core", "core: pinned", "sha1");
        journal.done("bump", "core", "core: bumped", "sha1");
        Recording trace = new Recording();
        Object bump = resumedBump(dir, trace, journal);

        Agent module = moduleWalk(bump, new Modules.Module("core", "core"));

        assertEquals("core: pinned", named(module, "before-pins").run(""));
        assertEquals("core: bumped", named(module, "bump").run(""));
        assertTrue(trace.notes.isEmpty(),
                "a replayed stage does no work, so it reports none: " + trace.notes);

        // AND THE ONE THAT NEVER FINISHED STILL RUNS. It says what it is doing and then reaches for
        // an agent this bump has never built, which is exactly as far as a test without a model can
        // follow it.
        assertThrows(Exception.class, () -> named(module, "after-pins").run(""));
        assertTrue(trace.notes.stream().anyMatch(n -> n.contains("hardening")),
                "the unfinished stage was entered: " + trace.notes);
    }

    /**
     * THE GATES ARE NOT WRAPPED, and this is the assertion that says so.
     *
     * <p>Both are deterministic builds and both are the arbiter, so re-running one on a resume is
     * not waste: it is the answer to the only question a resume has, which is what is true of the
     * tree as it now is. The journal here carries rows for both, which nothing should ever write,
     * and both stages ignore them and go to build.
     */
    @Test
    void theGatesRunAgainRatherThanBeingReplayed(@TempDir Path dir) throws Exception {
        Journal journal = new Journal(dir.resolve("journal.jsonl"), () -> "sha1");
        journal.done("gate", "repo", "PASS", "sha1");
        journal.done("module-gate", "core", "core: compiles under JDK 21", "sha1");
        Recording trace = new Recording();
        Object bump = resumedBump(dir, trace, journal);

        assertThrows(Exception.class, () -> node(bump, "gate").run(""),
                "it went to the build rather than answering out of the journal");
        assertTrue(trace.notes.stream().anyMatch(n -> n.startsWith("gate: building and testing")),
                trace.notes.toString());

        Agent module = moduleWalk(bump, new Modules.Module("core", "core"));
        assertThrows(Exception.class, () -> named(module, "module-gate").run(""),
                "the module gate compiles the module again, which is what makes it the arbiter");
    }

    /**
     * THE BASELINE SURVIVES A RESUME, WHICH IS THE WHOLE REASON THE JOURNAL KEEPS FACTS.
     *
     * <p>It is measured under the project's own JDK before anything moved, and the tree has moved.
     * Re-running it would not be expensive-but-correct, it would be wrong: it would build a
     * migrated tree at the old level and file the result as the set every later judgement is
     * measured against. Read back, no build runs at all.
     */
    @Test
    void theBaselineIsReadBackRatherThanMeasuredOnAMovedTree(@TempDir Path dir) throws Exception {
        Journal journal = new Journal(dir.resolve("journal.jsonl"), () -> "sha1");
        journal.fact("baseline-pre", "A#one\nA#two\nB#one");
        journal.fact("baseline-by-module", "core\tA#one\ncore\tA#two\n\tB#one");
        journal.fact("baseline-green", "false");
        Recording trace = new Recording();
        Object bump = resumedBump(dir, trace, journal);

        String said = (String) call(bump, "baselinePhase");

        assertEquals(Set.of("A#one", "A#two", "B#one"), field(bump, "pre"));
        assertEquals(Map.of("core", Set.of("A#one", "A#two"), "", Set.of("B#one")),
                field(bump, "baselineByModule"),
                "and split by module, so a lost test is still attributed where it happened");
        assertEquals(Boolean.FALSE, field(bump, "baselineGreen"),
                "whether the suite was all green is a fact about the project, not about this run");
        assertTrue(said.contains("3"), said);
        assertTrue(trace.builds.isEmpty(), "no build ran: " + trace.builds);
    }

    /**
     * AND A BUMP THAT WAS NOT RESUMED DOES NOT READ THEM, however tempting the file beside it is.
     *
     * <p>Starting fresh is decided in {@code main} against three conditions, and a stage that read
     * a fact back anyway would quietly undo that decision. This one goes to the build, which is
     * what a fresh bump should do and is as far as a test with no runner can watch it go.
     */
    @Test
    void aFreshBumpIgnoresTheFactsOfTheAttemptBeforeIt(@TempDir Path dir) throws Exception {
        Journal journal = new Journal(dir.resolve("journal.jsonl"), () -> "sha1");
        journal.fact("baseline-pre", "A#one");
        Recording trace = new Recording();
        Object fresh = bump(dir, trace, journal, false);

        assertThrows(Exception.class, () -> call(fresh, "baselinePhase"));
        assertTrue(trace.notes.stream().anyMatch(n -> n.startsWith("baseline: building and testing")),
                trace.notes.toString());
    }

    /**
     * THE REPAIR BUDGET IS NOT REFILLED BY RESUMING.
     *
     * <p>It is 192 steps for the whole bump, drawn down by a walk that shares it across the
     * modules. A resume that started that count again would let every kill buy another 192, which
     * over a fortnight is unbounded repair, so it is derived from the rows rather than kept: a
     * spent-so-far counter would be a second copy of a fact the rows already carry.
     *
     * <p>A COMPLETED CAMPAIGN IS CHARGED WHAT IT WAS ENTITLED TO ORDER, because the row says the
     * campaign finished and not how many of its steps it used. The two readings err in opposite
     * directions and only one of them can refill a budget that was spent.
     */
    @Test
    void theRepairBudgetIsCountedRatherThanRefilled(@TempDir Path dir) throws Exception {
        Journal journal = new Journal(dir.resolve("journal.jsonl"), () -> "sha1");
        journal.done("module-repair-step", "core#0", "a step landed", "sha1");
        journal.done("module-repair-step", "core#1", "nothing landed", "sha1");
        journal.done("module-repair-step", "web#0", "a step landed", "sha1");
        int budget = constant("REPAIR_BUDGET");
        int steps = constant("STEPS");

        assertEquals(budget, repairLeft(bump(new TempWorkspace(dir).path, new Recording(),
                journal, false)), "a fresh bump starts with all of it, whatever the file says");
        assertEquals(budget - 3 * steps, repairLeft(resumedBump(dir, new Recording(), journal)),
                "three campaigns finished, and a resume may not order their steps again");

        Journal spent = new Journal(dir.resolve("spent.jsonl"), () -> "sha1");
        for (int i = 0; i < 100; i++) {
            spent.done("module-repair-step", "core#" + i, "a step landed", "sha1");
        }
        assertEquals(0, repairLeft(resumedBump(dir, new Recording(), spent)),
                "and it floors at nothing left rather than going negative");
    }

    /**
     * THE MODULE LIST IS AN AGENT'S DECISION, so it is kept rather than asked again.
     *
     * <p>Not because it is expensive. It is a judgement about which trees are vendored or
     * generated, so a second pass answers it differently, and a resumed bump would then walk a
     * different set of modules from the one it had already half finished.
     */
    @Test
    void theModuleListIsTheOneTheFilterChose(@TempDir Path dir) throws Exception {
        TempWorkspace ws = new TempWorkspace(dir);
        ws.mavenAggregator("core", "web", "vendor");
        Journal journal = new Journal(dir.resolve("journal.jsonl"), () -> "sha1");
        journal.fact("modules", "root\ncore\nweb");
        Recording trace = new Recording();
        Object bump = resumedBump(ws.path, trace, journal);

        @SuppressWarnings("unchecked")
        List<Modules.Module> kept = (List<Modules.Module>) call(bump, "filteredModules");

        assertEquals(List.of("", "core", "web"), kept.stream().map(Modules.Module::path).toList(),
                "the vendored tree the filter set aside stays set aside");
        assertTrue(trace.applied.stream().anyMatch(a -> a.contains("read back from the journal")),
                trace.applied.toString());
    }

    /**
     * THE ROUND REACHES THE ROW, LAST, AND IT IS NOT PART OF THE FINGERPRINT.
     *
     * <p>Last because {@code run.sh} greps this file and bash cannot be corrected while it runs, so
     * a field may be appended and never moved. Outside the fingerprint because that string is what
     * the library's fourth resume condition compares: a round number inside it would make every
     * round read as a new pipeline, and nothing would ever resume.
     */
    @Test
    void theRoundIsAppendedToTheRowWithoutJoiningTheFingerprint(@TempDir Path dir)
            throws Exception {
        Path settlements = dir.resolve("settlements.jsonl");
        int[] round = {3};
        JsonlTrace trace = new JsonlTrace(dir.resolve("trace.jsonl"), settlements, BUMP,
                Fingerprint.provenanceOf(BUMP, settlements, () -> round[0]), List.of(), List.of());
        trace.settled(BUMP, "paused", "the lane ended between stages", false, false, true);

        String row = Files.readAllLines(settlements).get(0);
        assertTrue(row.contains(",\"round\":\"3\"}"),
                "the round is the last field on the row: " + row);
        assertFalse(Bump.pipelineOf(BUMP, dir).contains("round"),
                "and it is not in the string the resume condition compares");

        // ZERO MEANS NOBODY IS COUNTING, which is most of this corpus and is not round one.
        Path other = dir.resolve("other.jsonl");
        new JsonlTrace(dir.resolve("t2.jsonl"), other, BUMP,
                Fingerprint.provenanceOf(BUMP, other), List.of(), List.of())
                .settled(BUMP, "PASS", "148 conserved", true, true, false);
        assertFalse(Files.readAllLines(other).get(0).contains("round"),
                "an absent round is absent, never nought");
    }

    /**
     * THE SURVEY IS THE ONE STAGE WHERE SKIPPING LOSES MORE THAN AN ANSWER.
     *
     * <p>It builds the runner, the scanner and the agents that every stage after it runs on. So the
     * construction sits outside the journaled part and happens on a resume too, and only the asking
     * is replayed. Here the tools cannot be built, because nothing has told this test where hoptools
     * lives, and that is exactly what proves the stage went for them: it fails there rather than
     * answering out of the journal and carrying on with no runner.
     */
    @Test
    void aResumedSurveyStillBuildsTheToolsTheRestOfTheBumpRunsOn(@TempDir Path dir)
            throws Exception {
        Journal journal = new Journal(dir.resolve("journal.jsonl"), () -> "sha1");
        journal.done("survey", "repo", "hop: 17 -> 21", "sha1");
        Recording trace = new Recording();
        Object bump = resumedBump(dir, trace, journal);

        assertThrows(Exception.class, () -> node(bump, "survey").run(""));
        assertTrue(trace.notes.stream().noneMatch(n -> n.startsWith("survey: does the project")),
                "the asking was replayed; it is the tooling that it went back for: " + trace.notes);
    }

    /**
     * A RESUMED BUMP SAYS SO IN THE ROW THE SWEEP COMPARES ON.
     *
     * <p>It is not the same trial as a fresh one: it carries a different budget history and
     * possibly a different module order. This corpus already refuses to let an agent pick the hop
     * for that reason, and the same reasoning applies here: a comparison has to be able to leave
     * these rows out.
     */
    @Test
    void theSettlementRowSaysWhetherTheBumpResumed(@TempDir Path dir) throws Exception {
        Path settlements = dir.resolve("settlements.jsonl");
        JsonlTrace trace = new JsonlTrace(dir.resolve("trace.jsonl"), settlements, BUMP);

        trace.settled(BUMP, "PASS", "148 tests conserved", true, true, true);
        trace.settled(BUMP, "PASS", "148 tests conserved", true, true, false);
        trace.progress(BUMP, "still going");

        List<String> rows = Files.readAllLines(settlements);
        assertTrue(rows.get(0).contains("\"resumed\":true"), rows.get(0));
        assertTrue(rows.get(1).contains("\"resumed\":false"), rows.get(1));
        assertFalse(rows.get(2).contains("resumed"),
                "a progress note is not a trial and has nothing to be excluded from: " + rows.get(2));
    }

    /**
     * THE ROUND IS COUNTED THE WAY {@code run.sh} COUNTS IT, off the same rows and with the same
     * word starting it again.
     *
     * <p>WHAT THIS ASSERTS IS THE WIRING RATHER THAN THE COUNTING. The library counts, and it is
     * tested where it lives; what belongs here is the pair of conventions this program hands it and
     * told the shell about: the record is one file beside the results, and a lane is asked to hand
     * over by a file under {@code expiring/} named with this bump's slug. The shell resets its count
     * at {@code requeued} because a bump asked for again from the start has spent nothing, and the
     * one way the two sides can disagree about a number neither of them stores is for this call to
     * name a different word.
     */
    @Test
    void theRoundIsCountedTheWayRunShCountsIt(@TempDir Path dir) throws Exception {
        Path settlements = dir.resolve("settlements.jsonl");
        assertEquals(1, Bump.roundOf(dir, BUMP).number(), "a bump nobody has paused is in its first");

        settle(settlements, BUMP, "paused");
        settle(settlements, "other/repo|def456|17|21", "paused");
        assertEquals(2, Bump.roundOf(dir, BUMP).number(),
                "somebody else's boundary is not this bump's round");

        settle(settlements, BUMP, Bump.REQUEUED);
        settle(settlements, BUMP, "paused");
        assertEquals(2, Bump.roundOf(dir, BUMP).number(),
                "a bump asked for again from the start has spent nothing, which is what run.sh says");

        // THE MARKER IS THE ONLY THING THIS PROCESS KNOWS ABOUT TIME, and it is the file run.sh
        // creates, under the name run.sh creates it with.
        assertFalse(Bump.roundOf(dir, BUMP).reached(), "nobody has asked this lane to hand over");
        Files.createDirectories(dir.resolve("expiring"));
        Files.writeString(dir.resolve("expiring").resolve(Bump.slugOf(BUMP)), "");
        assertTrue(Bump.roundOf(dir, BUMP).reached());
    }

    // ---- fixtures ----

    /** One pipeline, as the row records it and as the resume condition compares it. */
    private static String fingerprint(String commit, String image, String prompts, String boms) {
        return "\"commit\":\"" + commit + "\",\"image\":\"" + image + "\",\"prompts\":\"" + prompts
                + "\",\"boms\":\"" + boms + "\"";
    }

    private static final String PIPELINE =
            fingerprint("ff7a4ab3", "sha256:6f2c1b0a9d3", "54906737", "bb42094f");

    /** A settlement row, in the shape the file already holds them, from THIS pipeline. */
    private static void settle(Path settlements, String bump, String state) {
        tech.mikhailov.ratchet.record.Settlement.note(settlements, bump, state, "because", false,
                false, PIPELINE);
    }

    /** A workspace on disk, for the two tests that read build files rather than fields. */
    private static final class TempWorkspace {
        private final Path path;

        TempWorkspace(Path dir) throws Exception {
            this.path = Files.createDirectories(dir.resolve("ws"));
        }

        void mavenAggregator(String... modules) throws Exception {
            StringBuilder pom = new StringBuilder("<project><modules>");
            for (String module : modules) {
                Files.createDirectories(path.resolve(module));
                pom.append("<module>").append(module).append("</module>");
            }
            Files.writeString(path.resolve("pom.xml"), pom.append("</modules></project>"));
        }
    }

    private static Object resumedBump(Path ws, Trace trace, Journal journal) throws Exception {
        return bump(ws, trace, journal, true);
    }

    private static Object bump(Path ws, Trace trace, Journal journal, boolean resumed)
            throws Exception {
        var ctor = Bump.class.getDeclaredConstructor(Path.class, String.class, Trace.class,
                Journal.class, boolean.class);
        ctor.setAccessible(true);
        return ctor.newInstance(ws, BUMP, trace, journal, resumed);
    }

    private static Agent moduleWalk(Object bump, Modules.Module m) throws Exception {
        var walk = Bump.class.getDeclaredMethod("moduleWalk", Modules.Module.class);
        walk.setAccessible(true);
        return (Agent) walk.invoke(bump, m);
    }

    /** One stage of a module's turn, by the name the trace and the picture call it. */
    private static Agent named(Agent module, String stage) {
        return module.inside().stream().filter(a -> a.name().equals(stage)).findFirst()
                .orElseThrow(() -> new AssertionError("no stage called " + stage + " in "
                        + module.inside().stream().map(Agent::name).toList()));
    }

    private static Agent node(Object bump, String field) throws Exception {
        var f = Bump.class.getDeclaredField(field);
        f.setAccessible(true);
        return (Agent) f.get(bump);
    }

    private static Object field(Object bump, String name) throws Exception {
        var f = Bump.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(bump);
    }

    private static int repairLeft(Object bump) throws Exception {
        var f = Bump.class.getDeclaredField("repairLeft");
        f.setAccessible(true);
        return f.getInt(bump);
    }

    private static int constant(String name) throws Exception {
        var f = Bump.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.getInt(null);
    }

    private static Object call(Object bump, String method) throws Exception {
        var m = Bump.class.getDeclaredMethod(method, method.equals("baselinePhase")
                ? new Class<?>[]{String.class} : new Class<?>[0]);
        m.setAccessible(true);
        try {
            return method.equals("baselinePhase") ? m.invoke(bump, "") : m.invoke(bump);
        } catch (java.lang.reflect.InvocationTargetException wrapped) {
            Throwable cause = wrapped.getCause();
            throw cause instanceof Exception e ? e : new IllegalStateException(cause);
        }
    }

    /** What the bump said it was doing, which is how a skipped stage is told from a run one. */
    private static final class Recording implements Trace {
        private final List<String> notes = new ArrayList<>();
        private final List<String> applied = new ArrayList<>();
        private final List<String> builds = new ArrayList<>();

        public void asked(String a, String p, String r) {
        }

        public void applied(String stage, String what) {
            applied.add(stage + ": " + what);
        }

        public void tool(String a, String t, String args, String result) {
        }

        public void thought(String f, String t, String c) {
        }

        public void built(String phase, Trace.Outcome result) {
            builds.add(phase);
        }

        public void settled(String b, String s, String w, boolean x, boolean y) {
        }

        public void failed(String b, Throwable c) {
        }

        public void progress(String b, String note) {
            notes.add(note);
        }

        public void priced(String b, String m, String i) {
        }
    }
}
