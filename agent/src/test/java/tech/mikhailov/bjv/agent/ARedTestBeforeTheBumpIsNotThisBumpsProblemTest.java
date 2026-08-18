package tech.mikhailov.bjv.agent;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WHAT AN ALREADY-BROKEN TEST IS ALLOWED TO COST.
 *
 * <p>The baseline used to be refused unless every test was green, which measured nothing and called
 * it rigour: conservation asks whether the tests that PASSED still pass, and a suite with three
 * broken tests answers that as well as a green one, because the three were red before anything was
 * touched and never enter the set.
 *
 * <p>It also judged one corpus by two standards. jvmjob runs Maven with
 * {@code -Dmaven.test.failure.ignore=true}, so a Maven project with red tests exits 0 and conserves
 * its green set, while the Gradle invocation carries no such flag and an identical project was
 * refused. 28 of 30 no-baseline verdicts were Gradle against 2 Maven, with both passing at an
 * identical rate otherwise, 39 apiece.
 */
class ARedTestBeforeTheBumpIsNotThisBumpsProblemTest {

    @Test
    void theGreenSetIsWhatConservationIsMeasuredOver() {
        // Eight tests, one of them red before the bump: exactly 5jyo/message-queue's shape.
        Set<String> greenBefore = Set.of("A#one", "A#two", "B#one", "B#two",
                "C#one", "C#two", "D#one");

        // The bump keeps all seven and the pre-existing red one is still red. That is a PASS: the
        // red test was never in the set, so it cannot be lost.
        Gate.Verdict kept = Gate.decide(greenBefore, greenBefore, true, 21, 21);
        assertTrue(kept.pass(), "conserving every test that passed is the whole requirement");

        // Losing one of the seven is still a failure, and it is named.
        Set<String> afterLoss = Set.of("A#one", "A#two", "B#one", "B#two", "C#one", "C#two");
        Gate.Verdict lost = Gate.decide(greenBefore, afterLoss, true, 21, 21);
        assertEquals("FAIL_test_conservation", lost.state());
        assertEquals(1, lost.lost());
        assertEquals("D#one", lost.missing().get(0));
    }

    @Test
    void aTestThatWasAlreadyRedCannotBeLostAndCannotRescueTheBump() {
        Set<String> greenBefore = Set.of("A#one", "A#two");

        // The previously-red test going green afterwards is not a conservation event either way.
        Set<String> afterFixed = Set.of("A#one", "A#two", "Broken#wasRed");
        assertTrue(Gate.decide(greenBefore, afterFixed, true, 21, 21).pass(),
                "gaining a test is allowed; conservation is about what was there");

        // And it cannot paper over a real loss.
        Set<String> afterSwap = Set.of("A#one", "Broken#wasRed");
        Gate.Verdict swapped = Gate.decide(greenBefore, afterSwap, true, 21, 21);
        assertFalse(swapped.pass(), "one lost and one gained must not net out to zero");
        assertEquals("A#two", swapped.missing().get(0));
    }

    @Test
    void anEmptyGreenSetIsStillNoBaseline() {
        // Nothing green before means nothing to conserve, and a bump there is unverifiable. That
        // check lives after this change and is what still refuses the genuinely unmeasurable.
        Gate.Verdict none = Gate.decide(Set.of(), Set.of("A#one"), true, 21, 21);
        assertEquals("NO_BASELINE_NOTESTS", none.state());
    }

    @Test
    void aGradleSuiteThatRanIsNotAnInfrastructureFailure() {
        // Verbatim from 5jyo/message-queue's baseline: eight tests ran, one assertion failed, and
        // Gradle exited non-zero. The harness read "Tests run:" -- surefire's wording, absent here
        // -- concluded no test had executed, and reported "the tests could not be RUN".
        String gradle = String.join("\n",
                "> Task :test",
                "com.example.messagequeue.test.KotestUseTest > Then: topic should be empty FAILED",
                "    io.kotest.assertions.AssertionFailedError at KotestUseTest.kt:84",
                "> Task :test FAILED",
                "8 tests completed, 1 failed",
                "BUILD FAILED in 39s");
        assertEquals(8, Runner.testsIn(gradle), "the suite plainly ran, in Gradle's own words");

        String maven = "[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0";
        assertEquals(12, Runner.testsIn(maven), "and surefire still counts");

        String died = "[ERROR] COMPILATION ERROR : cannot find symbol";
        assertEquals(0, Runner.testsIn(died), "a build that died before testing counts nothing");
    }
}
