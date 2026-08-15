package tech.mikhailov.bjv.agent;

import com.deepagents.langchain4j.subagents.SubAgentDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A GRADLE PROJECT HAS NO POM, AND apply_recipe RUNS THROUGH MAVEN.
 *
 * <p>{@code Migrate.rewrite} builds {@code mvn ... rewrite-maven-plugin:run} with no branch on the
 * build system, while {@code hoptools/jvmjob} has picked between {@code mvn} and {@code gradlew}
 * for build, test and scan since it was written. So on a project with no pom the tool answers "no
 * POM in this directory" and no recipe can execute: not that one, not any, not on a retry.
 *
 * <p>Measured on the live corpus: 24 of 75 bumps hit it, and the ten of those that were scanned
 * both sides held 143 CVEs that never moved. The agents were not at fault. The tool's own
 * description says the recipes "know where a version belongs in Maven AND in Gradle" and lists
 * three {@code org.openrewrite.gradle.*} recipes, so they emitted them, correctly, into a Maven
 * invocation that could not start.
 *
 * <p>The bump doer already holds {@code edit_file} -- it is told to use it for what the recipes
 * miss. What it did not know is that on Gradle the recipes miss everything. That is what this
 * asserts, because it is the difference between a phase with no options and a phase that had one
 * all along and was never told.
 */
class TheBumperKnowsGradleHasNoPomTest {

    private static String bumper(@TempDir Path ws) {
        return prompt(Agents.forHop(new Hop(17, 21), ws), "bump-doer");
    }

    private static String prompt(List<SubAgentDefinition> defs, String name) {
        return defs.stream().filter(d -> d.name().equals(name)).findFirst().orElseThrow()
                .systemPrompt();
    }

    @Test
    void itRecognisesTheAnswerTheToolActuallyGives(@TempDir Path ws) {
        // The exact string, because a doer matching on anything else matches nothing.
        assertTrue(bumper(ws).contains("no POM in this directory"),
                "the bump doer is told the sentence that means Gradle");
    }

    @Test
    void itSaysRetryingIsPointlessRatherThanLeavingItOpen(@TempDir Path ws) {
        // The observed failure was not one call, it was the same call repeated. A doer told only
        // "this failed" will try again; it has to be told the failure is structural.
        String p = bumper(ws);
        assertTrue(p.contains("not on a retry"), "retrying is ruled out explicitly: " + p);
        assertTrue(p.contains("STOP CALLING IT"), "and the instruction is an imperative");
    }

    @Test
    void itNamesTheToolThatDoesWorkThere(@TempDir Path ws) {
        assertTrue(bumper(ws).contains("edit_file is the whole toolkit"),
                "the fallback is named, and it is one the phase actually holds");
    }

    @Test
    void thePhaseReallyDoesHoldThatTool(@TempDir Path ws) {
        // THE PROMPT AND THE TOOL SET HAVE TO AGREE. Telling a doer to use edit_file in a phase
        // that was not given it is the same class of bug as telling it to run a Maven recipe on a
        // Gradle project: an instruction with no action behind it.
        var tools = Tools.raising(ws, null, null, null, "21", "21", null, "bump-doer");
        assertTrue(tools.keySet().stream().anyMatch(s -> s.name().equals("edit_file")),
                "bump holds edit_file: " + tools.keySet().stream().map(s -> s.name()).toList());
    }

    @Test
    void itSaysWhereAGradleVersionActuallyLIVES(@TempDir Path ws) {
        String p = bumper(ws);
        // Four dialects, because "edit the build file" is not an instruction on a project that
        // could be keeping the version in any of them.
        assertTrue(p.contains("spring-boot-gradle-plugin"), "the buildscript classpath form");
        assertTrue(p.contains("libs.versions.toml"), "the version catalog");
        assertTrue(p.contains("extra[") || p.contains("ext["), "the property form");
        assertTrue(p.contains("gradle-wrapper.properties"), "and the wrapper, which has a floor");
    }

    @Test
    void thePinPhasesAreNotToldThis(@TempDir Path ws) {
        // BECAUSE THEY CANNOT ACT ON IT. pinning() hands out apply_recipe and no writer at all, so
        // on a Gradle project after-pins has an empty action space and no instruction changes
        // that. Telling it to edit a file it cannot open would be a prompt that lies.
        String pins = prompt(Agents.forHop(new Hop(17, 21), ws), "after-pins-doer");
        assertFalse(pins.contains("edit_file is the whole toolkit"),
                "after-pins is not promised a tool it does not hold");
    }
}
