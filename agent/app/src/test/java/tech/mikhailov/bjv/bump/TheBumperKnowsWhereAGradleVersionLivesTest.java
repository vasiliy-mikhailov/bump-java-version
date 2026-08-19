package tech.mikhailov.bjv.bump;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WHAT {@code build_system} IS FOR, NOW THAT BOTH HALVES OF {@code apply_recipe} EXIST.
 *
 * <p>This file used to be called TheBumperKnowsGradleHasNoPomTest and it asserted, in three places,
 * that a recipe cannot run on a Gradle module. That was true when it was written: {@code
 * Migrate.rewrite} built {@code mvn ... rewrite-maven-plugin:run} with no branch on the build
 * system, and 622 calls landed on "Goal requires a project to execute but there is no POM" without
 * a single success. {@link Migrate#rewriteGradle} was built afterwards and the claim stopped being
 * true; the prompts and this test went on making it for four hundred bumps, and 74 of the 84 pins
 * reported BLOCKED in that window named Gradle.
 *
 * <p>THE LESSON IS ABOUT THE TEST, NOT THE PROMPT. A test that pins a sentence pins whatever the
 * sentence says, including that it is still true. Nothing here could have gone red when the Gradle
 * actuator landed, because every assertion was about the text and none was about the capability.
 * {@code NoPromptSaysRecipesCannotReachGradleTest} is the guard that would have caught it, and it
 * checks the claim rather than the wording.
 *
 * <p>What survives is the part that was always about placement rather than reach: a version in a
 * Gradle project can live in a plugins block, a buildscript classpath, a property, a dependency
 * string, a version catalog or the wrapper, and "edit the build file" is not an instruction to
 * someone who does not know which. That is still worth asserting and is still what the phase needs.
 */
class TheBumperKnowsWhereAGradleVersionLivesTest {

    /**
     * What the bump doer is told, on one platform.
     *
     * <p>The doer runs inside the module walk and is defined once for each regime, so the bare
     * name finds nothing. Every claim in this file is about where a Gradle version lives rather
     * than about what manages it, so each of them is asserted on all three.
     */
    private static String bumper(Path ws, String platform) {
        return prompt(Agents.forHop(new Hop(17, 21), ws), Agents.named("bump-doer", platform));
    }

    private static String prompt(List<Definition> defs, String name) {
        return defs.stream().filter(d -> d.name().equals(name)).findFirst().orElseThrow()
                .systemPrompt();
    }

    @Test
    void itAsksRatherThanWaitingToBeToldByAFailure(@TempDir Path ws) {
        // THIS USED TO ASSERT THE ERROR STRING. The first version taught the doer to recognise
        // "no POM in this directory" and infer the project type from it: three inference steps
        // standing in for a question. build_system answers the question, so the instruction is to
        // ask. What the answer is FOR has changed since; that it is asked first has not.
        for (String platform : Managed.PLATFORMS) {
            assertTrue(bumper(ws, platform).contains("CALL build_system FIRST"),
                    "the bump doer is told to establish the fact before acting, on " + platform);
        }
    }

    @Test
    void itSaysTheAnswerDecidesPlacementRatherThanReach(@TempDir Path ws) {
        // The replacement for the deleted paragraph, asserted so the escape cannot come back in
        // the same position wearing different words. build_system used to be asked in order to
        // find out whether the phase could act at all; it is asked now to find out where.
        for (String platform : Managed.PLATFORMS) {
            String p = bumper(ws, platform);
            assertTrue(p.contains("where a version lives, not whether a recipe can reach it"),
                    "the question build_system answers is placement: " + p);
        }
    }

    @Test
    void thePhaseReallyDoesHoldTheEditor(@TempDir Path ws) {
        // THE PROMPT AND THE TOOL SET HAVE TO AGREE. Telling a doer to use edit_file in a phase
        // that was not given it is the same class of bug as telling it a recipe cannot run where
        // it can: an instruction with no action behind it.
        var tools = Tools.raising(ws, null, null, null, "21", "21", null, "bump-doer");
        assertTrue(tools.keySet().stream().anyMatch(s -> s.name().equals("edit_file")),
                "bump holds edit_file: " + tools.keySet().stream().map(s -> s.name()).toList());
    }

    @Test
    void itSaysWhereAGradleVersionActuallyLives(@TempDir Path ws) {
        for (String platform : Managed.PLATFORMS) {
            String p = bumper(ws, platform);
            // Five dialects, because "edit the build file" is not an instruction on a project that
            // could be keeping the version in any of them.
            assertTrue(p.contains("spring-boot-gradle-plugin"), "the buildscript classpath form");
            assertTrue(p.contains("libs.versions.toml"), "the version catalog");
            assertTrue(p.contains("extra[") || p.contains("ext["), "the property form");
            assertTrue(p.contains("gradle-wrapper.properties"), "and the wrapper, with its floor");
        }
    }

    @Test
    void thePinPhasesAreStillNotPromisedAnEditor(@TempDir Path ws) {
        // BECAUSE THEY DO NOT HOLD ONE. pinning() hands out apply_recipe and no writer, so a pin
        // phase told to edit a file is a prompt that lies about its own tool set. This is the one
        // assertion in the original file that was about a capability rather than a wording, and it
        // is the only one that stayed true across the change that invalidated the rest.
        for (String platform : Managed.PLATFORMS) {
            String pins = prompt(Agents.forHop(new Hop(17, 21), ws),
                    Agents.named("after-pins-doer", platform));
            assertFalse(pins.contains("edit_file"),
                    "after-pins is not promised a tool it does not hold: " + pins);
        }
    }
}
