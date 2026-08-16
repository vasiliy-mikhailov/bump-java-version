package tech.mikhailov.bjv.agent;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /** A leaf that records that it ran, so the order can be checked rather than assumed. */
    private static Agents.Agent says(String name, List<String> log) {
        return Flow.code(name, task -> {
            log.add(name);
            return name;
        });
    }

    @Test
    void theShapeIsWalkedOffTheThingThatRuns() {
        List<String> log = new ArrayList<>();
        Agents.Agent program = Flow.seq("bump",
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
        Agents.Agent program = Flow.each("modules", () -> List.of("core", "web"),
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
        Agents.Agent program = Flow.each("modules", () -> List.of("core", "web"),
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
        Agents.Agent never = Flow.loop("module-gate", 3, () -> false,
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
        Agents.Agent program = Flow.when("security-after", () -> false,
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
        Agents.Agent program = Flow.seq("bump",
                Flow.code("survey", t -> ""),
                Flow.loop("gate", 2, () -> true, Flow.code("repair", t -> "")));

        assertEquals(List.of("bump", "survey", "gate", "repair"), Flow.names(program));
    }
}
