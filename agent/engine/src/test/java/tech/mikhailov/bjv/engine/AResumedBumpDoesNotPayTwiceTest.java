package tech.mikhailov.bjv.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The point of the journal is the stages it does not run again, and the point of the decorator is
 * that nothing else in {@link Flow} has to know it is there.
 */
class AResumedBumpDoesNotPayTwiceTest {

    @Test
    void aJournaledAnswerIsReplayedRatherThanPaidForAgain(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("journal.jsonl");
        AtomicInteger asked = new AtomicInteger();
        Agent survey = Flow.code("survey", task -> "the project is on 8 (" + asked.incrementAndGet() + ")");

        String said = Flow.resumable(survey, new Journal(file, () -> "sha1"), () -> "repo").run("bump it");
        assertEquals(1, asked.get());

        String again = Flow.resumable(survey, new Journal(file, () -> "sha1"), () -> "repo").run("bump it");

        assertEquals(said, again, "the journaled answer, not a fresh one");
        assertEquals(1, asked.get(), "a killed bump does not pay for the stage it already finished");
    }

    @Test
    void theWalkResumesPerModuleRatherThanPerStage(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("journal.jsonl");
        Journal journal = new Journal(file, () -> "sha1");
        journal.done("before-pins", "core", "raised two pins", "sha1");
        List<String> ran = new ArrayList<>();

        Agent walk = Flow.each("modules", () -> List.of("core", "web"), m -> m,
                m -> Flow.resumable(Flow.code("before-pins", task -> {
                    ran.add(m);
                    return "raised the pins of " + m;
                }), journal, () -> m));

        walk.run("bump it");

        assertEquals(List.of("web"), ran, "core finished before the kill; web never started");
        assertEquals("raised the pins of web", journal.answered("before-pins", "web").orElse(""));
    }

    @Test
    void whatTheNodeReturnedIsWhatTheJournalKeeps(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("journal.jsonl");
        Journal journal = new Journal(file, () -> "sha9");

        Flow.resumable(Flow.code("migrate", task -> "applied two recipes"), journal, () -> "core")
                .run("bump it");

        assertEquals("applied two recipes", journal.answered("migrate", "core").orElse(""));
        assertTrue(journal.standsOn("sha9"), "and the tree it landed on, so a resume can check it");
        assertFalse(journal.standsOn("sha8"));
    }

    @Test
    void aRunThatEndedInsteadOfFinishingIsNotJournaled(@TempDir Path dir) {
        Journal journal = new Journal(dir.resolve("journal.jsonl"));
        Agent baseline = Flow.code("baseline", task -> {
            throw new Flow.Settled("no-baseline\nno test passed that a bump could conserve");
        });

        assertThrows(Flow.Settled.class,
                () -> Flow.resumable(baseline, journal, () -> "repo").run("bump it"));

        assertTrue(journal.answered("baseline", "repo").isEmpty(),
                "the row is written after the node returns, and this one did not return");
    }

    @Test
    void theShapeStillWalksThroughAWrappedNode(@TempDir Path dir) {
        Journal journal = new Journal(dir.resolve("journal.jsonl"));
        Flow.Node gate = Flow.code("module-gate", task -> "green").deterministic();
        Flow.Node modules = (Flow.Node) Flow.code("modules", gate, task -> gate.run(task))
                .around("walk").repeats("per module").reads("the runtime half");

        Agent bump = Flow.seq("bump", Flow.resumable(modules, journal, () -> "core"));

        List<Shape.Stage> stages = Shape.of(bump);
        assertEquals(List.of("modules", "module-gate"), stages.stream().map(Shape.Stage::title).toList(),
                "a wrapper that does not delegate loses every stage under it: " + Flow.shape(bump));
        assertEquals("modules", stages.get(1).within(), "and the nesting the picture is drawn from");
        assertEquals("per module", stages.get(0).repeats());
        assertEquals("the runtime half", stages.get(0).reads());
        assertTrue(Shape.agentNames(stages).contains("modules-planner"),
                "the agents a wrapped stage speaks with are still declared");
        assertTrue(Flow.names(bump).contains("modules"), Flow.shape(bump));
    }
}
