package tech.mikhailov.bjv.bump;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import tech.mikhailov.ratchet.flow.Agent;
import tech.mikhailov.ratchet.flow.Flow;
import tech.mikhailov.ratchet.record.Trace;
import tech.mikhailov.bjv.jvm.Modules;

/**
 * A PICTURE THAT IS THE PROGRAM CANNOT BE WRONG ABOUT IT.
 *
 * <p>The one this replaces drew the chain as boxes with a loop arc between array positions three
 * and four. Those numbers were written when the array was typed by hand. Later the array became
 * derived from a declaration, the positions shifted, and the arc went on being drawn between two
 * stages that had never been connected in any version of the system. It rendered for months.
 * Nothing crashed and no test failed; every reader was simply told something untrue.
 *
 * <p>That is possible because the picture was drawn BESIDE the program. These combinators make the
 * composition itself the shape: {@link Flow#shape} walks the same object the runtime executes, so
 * there is nothing to keep in step and no coordinates to get wrong.
 */
class TheShapeIsTheProgramTest {

    /**
     * A BUMP, FOR ITS TREE ALONE. The tree is assembled in the constructor and every node holds a
     * body it has not run, so this needs no workspace, no agents and no trace: a shape is what the
     * program can do, not what one run of it did.
     */
    private static Object aBump() throws Exception {
        var ctor = Bump.class.getDeclaredConstructor(java.nio.file.Path.class, String.class,
                Trace.class);
        ctor.setAccessible(true);
        return ctor.newInstance(java.nio.file.Path.of("."), "owner/repo|abc123|17|21", null);
    }

    private static Agent node(Object bump, String field) throws Exception {
        var f = Bump.class.getDeclaredField(field);
        f.setAccessible(true);
        return (Agent) f.get(bump);
    }

    /** A leaf that records that it ran, so the order can be checked rather than assumed. */
    private static Agent says(String name, List<String> log) {
        return Flow.code(name, task -> {
            log.add(name);
            return name;
        });
    }

    @Test
    void theShapeIsWalkedOffTheThingThatRuns() {
        List<String> log = new ArrayList<>();
        Agent program = Flow.seq("bump",
                says("survey", log),
                Flow.each("modules", () -> List.of("core", "web"), m -> String.valueOf(m),
                        m -> Flow.seq("module", says("before-pins", log), says("bump", log))),
                says("gate", log));

        assertEquals("""
                bump
                    survey
                    modules
                        module
                            before-pins
                            bump
                    gate
                """, Flow.shape(program));
    }

    @Test
    void aWalkRunsItsBodyOncePerItemAndTheShapeStillShowsItOnce() throws Exception {
        // THE SHAPE IS WHAT THE PROGRAM CAN DO, NOT WHAT ONE RUN DID. Drawing the body twice
        // because this repository has two modules would make the picture a property of the input,
        // and the next repository would get a different diagram of the same system.
        List<String> log = new ArrayList<>();
        Agent program = Flow.each("modules", () -> List.of("core", "web"),
                m -> String.valueOf(m), m -> says("bump", log));

        program.run("go");

        assertEquals(List.of("bump", "bump"), log, "once per module");
        assertEquals("""
                modules
                    bump
                """, Flow.shape(program), "and once in the picture");
    }

    @Test
    void theItemTravelsIntoTheBrief() throws Exception {
        List<String> seen = new ArrayList<>();
        Agent program = Flow.each("modules", () -> List.of("core", "web"),
                m -> String.valueOf(m),
                m -> Flow.code("bump", task -> {
                    seen.add(task);
                    return "";
                }));

        program.run("Migration: 17 to 21");

        assertTrue(seen.get(0).contains("This pass is for: core"), seen.get(0));
        assertTrue(seen.get(1).contains("This pass is for: web"), seen.get(1));
        assertTrue(seen.get(0).startsWith("Migration: 17 to 21"), "and the brief survives it");
    }

    @Test
    void aLoopAsksBeforeItRunsAtAll() throws Exception {
        // A loop that always ran once before checking is how a repository needing no repair still
        // paid for a repair planner. The condition is asked first, so a block whose work is already
        // unnecessary costs nothing at all.
        AtomicInteger ran = new AtomicInteger();
        Agent never = Flow.loop("module-gate", 3, () -> false,
                Flow.code("repair", task -> {
                    ran.incrementAndGet();
                    return "";
                }));

        never.run("go");

        assertEquals(0, ran.get(), "the body never ran");
    }

    @Test
    void aLoopStopsWhenTheConditionClearsAndOtherwiseAtItsBound() throws Exception {
        AtomicInteger ran = new AtomicInteger();
        AtomicBoolean red = new AtomicBoolean(true);
        Flow.loop("gate", 5, red::get, Flow.code("fix", task -> {
            if (ran.incrementAndGet() == 2) {
                red.set(false);
            }
            return "";
        })).run("go");
        assertEquals(2, ran.get(), "it stopped when the condition cleared");

        AtomicInteger forever = new AtomicInteger();
        Flow.loop("gate", 3, () -> true, Flow.code("fix", task -> {
            forever.incrementAndGet();
            return "";
        })).run("go");
        assertEquals(3, forever.get(), "and otherwise at the bound, which is the number on the page");
    }

    @Test
    void selectionKeepsItsBodyInThePictureEvenWhenItDoesNotRun() throws Exception {
        // "only after a green gate" is a fact about the program. A picture that hid the stage on a
        // run where the gate was red would be a picture of one outcome rather than of the system,
        // and a reader could not find the prompt of a stage that had not fired.
        AtomicInteger ran = new AtomicInteger();
        Agent program = Flow.when("security-after", () -> false,
                Flow.code("scan", task -> {
                    ran.incrementAndGet();
                    return "";
                }));

        program.run("go");

        assertEquals(0, ran.get());
        assertEquals("""
                security-after
                    scan
                """, Flow.shape(program));
    }

    @Test
    void aSequenceAnswersWithItsLastWord() throws Exception {
        // Concatenating every step would hand the next reader a transcript rather than a result,
        // and every caller would then have to decide which part of it mattered.
        assertEquals("third", Flow.seq("s",
                Flow.code("a", t -> "first"),
                Flow.code("b", t -> "second"),
                Flow.code("c", t -> "third")).run("go"));
    }

    @Test
    void everyNodeIsNameableSoNothingIsUnpointableInATrace() {
        Agent program = Flow.seq("bump",
                Flow.code("survey", t -> ""),
                Flow.loop("gate", 2, () -> true, Flow.code("repair", t -> "")));

        assertEquals(List.of("bump", "survey", "gate", "repair"), Flow.names(program));
    }

    @Test
    void aTurnIsItsStepsInOrderRatherThanANamelessSequence() throws Exception {
        // The module gate is a build and then, only when the build came back red, a repair. A loop
        // that took one body would need an unnamed sequence between it and the pair, which is a
        // node in the picture nobody can point at, saying something the loop already says.
        List<String> log = new ArrayList<>();
        AtomicInteger turns = new AtomicInteger();
        Agent gate = Flow.loop("module-gate", 2, () -> turns.incrementAndGet() <= 2,
                says("compile", log), says("repair", log));

        gate.run("go");

        assertEquals(List.of("compile", "repair", "compile", "repair"), log);
        assertEquals("""
                module-gate
                    compile
                    repair
                """, Flow.shape(gate));
    }

    @Test
    void theModuleWalkIsTheProgramItRuns() throws Exception {
        // THE FIRST STAGE OF THE BUMP WHOSE WHOLE STRUCTURE IS A TREE, and so the first that can be
        // asserted against a picture at all. Everything under it is built before the bump starts,
        // which is what makes this possible: no agent exists yet, no module has been chosen, and
        // the shape is still complete, because a shape is what the program can do rather than what
        // one run of it did.
        assertEquals("""
                modules
                    module
                        platform
                        before-pins
                        bump
                        module-gate
                            module-repair
                                module-repair-step
                        after-pins
                """, Flow.shape(node(aBump(), "modulesStage")));
    }

    @Test
    void theWholeBumpIsOneTreeAndTheClosersAreInIt() throws Exception {
        // WHAT THIS ASSERTION IS WORTH. A green gate used to return PASS from the middle of the
        // run, so whether a closing stage happened at all was a property of a return four stages
        // above it, and no picture could show that without being written by hand and kept in step
        // by hand. Both were: the page advertised an estimator that priced every bump while the
        // code priced one path of two. Selection says it in the program instead, and this is the
        // assertion that the saying and the doing are the same object.
        assertEquals("""
                survey
                baseline
                security-before
                module-filter
                modules
                    module
                        platform
                        before-pins
                        bump
                        module-gate
                            module-repair
                                module-repair-step
                        after-pins
                gate
                security-after
                estimator
                verdict
                """, Flow.shape(node(aBump(), "everything")));
    }

    @Test
    void theClosersAreSelectionOnTheGateAndTheirPolarityIsTheWholeContent() throws Exception {
        // A when() that reads correctly and runs inverted is an ordinary mistake and an expensive
        // one, so it is worth an assertion of its own. A stage that is skipped answers with nothing
        // and touches none of the fields it would need; an inverted one reaches for a runner, an
        // agent and a scanner that a bump built for its shape alone has never made.
        Object red = aBump();
        assertEquals("", node(red, "securityAfter").run(""),
                "no after-scan until the gate has gone green: on any other exit the offline collect"
                        + " copies whatever resolved before the build died, and the count falls"
                        + " because modules are missing rather than because anything was fixed");

        Object green = aBump();
        var flag = Bump.class.getDeclaredField("gateGreen");
        flag.setAccessible(true);
        flag.setBoolean(green, true);
        assertEquals("", node(green, "verdict").run(""),
                "and nothing to argue about a bump the build settled");
    }

    @Test
    void aPassIsWrittenWhereTheLastFactInItArrives() throws Exception {
        // THE ACCOUNT IS A WIRE FORMAT, not prose: the sweep splits it at the first newline and
        // files the word, and the page reads "N tests conserved" back out of the second line with
        // a pattern. It also quotes a CVE count, which is why the gate that decided the bump passed
        // cannot be the stage that writes it. At that point nothing has scanned the tree it has
        // just proved green, and the scan means nothing until it has.
        Object bump = aBump();
        var verdict = Bump.class.getDeclaredField("lastVerdict");
        verdict.setAccessible(true);
        verdict.set(bump, new Gate.Verdict("PASS", 148, 0, 21));
        var passed = Bump.class.getDeclaredMethod("passed");
        passed.setAccessible(true);

        assertEquals("PASS\n148 tests conserved, effective target 21; CRITICAL+HIGH not measured",
                passed.invoke(bump),
                "nothing measured the scan here, and the line says so rather than saying zero");
    }

    @Test
    void theRepairBudgetIsTheBumpsAndTheWalkNeverResetsIt() throws Exception {
        // THE ARITHMETIC THAT WENT WRONG ONCE. Sixteen turns times two campaigns times six steps
        // was 192 spent on the repository; per module the same sum is 36 each, which is 216 at the
        // corpus median and 720 at twenty modules, so the change that was meant to bound repair
        // quadrupled it. The budget is a field of the bump and the walk draws it down.
        //
        // The walk is run here for real, twice, which needs no agents and no runner: no module has
        // been chosen, so the body executes zero times and the doer still does everything it does
        // around it. If a later edit moved the allowance into moduleWalk or reset it per pass, this
        // is where it shows up.
        Object bump = aBump();
        var left = Bump.class.getDeclaredField("repairLeft");
        left.setAccessible(true);
        assertFalse(java.lang.reflect.Modifier.isStatic(left.getModifiers()),
                "the allowance is one bump's");

        var budget = Bump.class.getDeclaredField("REPAIR_BUDGET");
        budget.setAccessible(true);
        assertEquals(budget.getInt(null), left.getInt(bump), "a fresh bump starts with all of it");

        var doer = Bump.class.getDeclaredField("walkDoer");
        doer.setAccessible(true);
        Flow.Doer walk = (Flow.Doer) doer.get(bump);

        left.setInt(bump, 5);
        walk.run("", "");
        walk.run("", "a reviewer read the whole walk and sent it back");

        assertEquals(5, left.getInt(bump),
                "two passes over the modules and the allowance is still where the first left it");

        var repaired = Bump.class.getDeclaredField("modulesRepaired");
        repaired.setAccessible(true);
        assertEquals(0, repaired.getInt(bump),
                "what the walk does reset is its own count, which is a fact about one pass");

        // AND THE TURN STATE IS PER MODULE, which is the half that must NOT be shared: each turn
        // through the walk builds its own nodes, so no module reads the gate state of the one
        // before it.
        var moduleWalk = Bump.class.getDeclaredMethod("moduleWalk", Modules.Module.class);
        moduleWalk.setAccessible(true);
        assertNotSame(moduleWalk.invoke(bump, (Object) null), moduleWalk.invoke(bump, (Object) null),
                "one module's turn is not the next one's");
    }

    @Test
    void aSettledRunStopsTheSequenceAndKeepsItsAccount() {
        // Three things end a bump before anything can be gated: the tooling is not staged, the
        // project does not build under its own JDK, no test passed that a bump could conserve.
        // None is a stage deciding something, so none is selection, and guarding every later stage
        // with "and we are still going" would put error handling into the picture and stop it
        // being the shape of the program. What must survive the jump is the account: its first
        // line is the state the sweep files, so a settlement that lost it reads as a crash.
        List<String> log = new ArrayList<>();
        Agent program = Flow.seq("bump",
                says("survey", log),
                Flow.code("baseline", task -> {
                    throw new Flow.Settled("no-baseline\nno test passed under JDK 11");
                }),
                says("gate", log));

        Flow.Settled settled = assertThrows(Flow.Settled.class, () -> program.run("go"));

        assertEquals(List.of("survey"), log, "nothing after the settlement ran");
        assertEquals("no-baseline\nno test passed under JDK 11", settled.account());
    }
}
