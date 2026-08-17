package tech.mikhailov.bjv.agent;

import com.deepagents.langchain4j.subagents.SubAgentDefinition;
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

    private static String bumper(@TempDir Path ws) {
        return prompt(Agents.forHop(new Hop(17, 21), ws), "bump-doer");
    }

    private static String prompt(List<SubAgentDefinition> defs, String name) {
        return defs.stream().filter(d -> d.name().equals(name)).findFirst().orElseThrow()
                .systemPrompt();
    }

    @Test
    void itAsksRatherThanWaitingToBeToldByAFailure(@TempDir Path ws) {
        // THIS USED TO ASSERT THE ERROR STRING. The first version taught the doer to recognise
        // "no POM in this directory" and infer the project type from it: three inference steps
        // standing in for a question. build_system answers the question, so the instruction is to
        // ask. What the answer is FOR has changed since; that it is asked first has not.
        assertTrue(bumper(ws).contains("CALL build_system FIRST"),
                "the bump doer is told to establish the fact before acting");
    }

    @Test
    void itSaysTheAnswerDecidesPlacementRatherThanReach(@TempDir Path ws) {
        // The replacement for the deleted paragraph, asserted so the escape cannot come back in
        // the same position wearing different words. build_system used to be asked in order to
        // find out whether the phase could act at all; it is asked now to find out where.
        String p = bumper(ws);
        assertTrue(p.contains("where a version lives, not whether a recipe can reach it"),
                "the question build_system answers is placement: " + p);
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
        String p = bumper(ws);
        // Five dialects, because "edit the build file" is not an instruction on a project that
        // could be keeping the version in any of them.
        assertTrue(p.contains("spring-boot-gradle-plugin"), "the buildscript classpath form");
        assertTrue(p.contains("libs.versions.toml"), "the version catalog");
        assertTrue(p.contains("extra[") || p.contains("ext["), "the property form");
        assertTrue(p.contains("gradle-wrapper.properties"), "and the wrapper, which has a floor");
    }

    @Test
    void thePinPhasesAreStillNotPromisedAnEditor(@TempDir Path ws) {
        // BECAUSE THEY DO NOT HOLD ONE. pinning() hands out apply_recipe and no writer, so a pin
        // phase told to edit a file is a prompt that lies about its own tool set. This is the one
        // assertion in the original file that was about a capability rather than a wording, and it
        // is the only one that stayed true across the change that invalidated the rest.
        String pins = prompt(Agents.forHop(new Hop(17, 21), ws), "after-pins-doer");
        assertFalse(pins.contains("edit_file"),
                "after-pins is not promised a tool it does not hold: " + pins);
    }
}
