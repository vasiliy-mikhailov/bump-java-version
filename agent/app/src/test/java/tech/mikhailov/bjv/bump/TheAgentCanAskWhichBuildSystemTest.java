package tech.mikhailov.bjv.bump;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import tech.mikhailov.bjv.engine.Trace;

/**
 * WHICH BUILD SYSTEM, ASKED RATHER THAN INFERRED FROM A FAILURE.
 *
 * <p>This tool was added when apply_recipe ran only the OpenRewrite MAVEN plugin, so that an
 * agent could learn its module had no pom by asking rather than by reading an error: in the corpus
 * the usual route to that knowledge was to call the tool, misread the failure and call it again.
 *
 * <p>THE ANSWER IT GIVES HAS CHANGED, BECAUSE THE THING IT DESCRIBES HAS. There is a Gradle
 * actuator now, so "every module here is Gradle" no longer means a recipe cannot run; it means the
 * run goes through the Gradle plugin from the root and reaches every module at once. A test that
 * still asserted the old sentence would be pinning a statement to the agent that is no longer
 * true, which is exactly the failure this whole area is about: 622 calls made on the strength of a
 * description that did not match the runner behind it.
 *
 * <p>What the tool is FOR has changed with it. It is no longer how you find out whether you can
 * act. It is how a reader, and a verifier, tells a pin that was never attempted from one that was
 * attempted and did not land.
 */
class TheAgentCanAskWhichBuildSystemTest {

    private static String ask(Path ws) {
        Map<dev.langchain4j.agent.tool.ToolSpecification,
                dev.langchain4j.service.tool.ToolExecutor> tools =
                Tools.pinning(ws, null, null, "21", new NoTrace(), "after-pins-doer");
        var entry = tools.entrySet().stream()
                .filter(e -> e.getKey().name().equals("build_system"))
                .findFirst().orElseThrow();
        return entry.getValue().execute(
                ToolExecutionRequest.builder().name("build_system").arguments("{}").build(), null);
    }

    private static void module(Path ws, String dir, String file) throws IOException {
        Path d = dir.isEmpty() ? ws : ws.resolve(dir);
        Files.createDirectories(d);
        Files.writeString(d.resolve(file), "// build file\n");
    }

    /**
     * A ROOT THAT DECLARES ITS CHILDREN, because {@link Modules} only knows a module the build
     * mentions. An undeclared directory with a build file in it is not a module and reporting it
     * as one would be this tool inventing structure the rest of the harness does not see.
     */
    private static void mavenRoot(Path ws, String... children) throws IOException {
        StringBuilder pom = new StringBuilder("<project><modules>");
        for (String c : children) {
            pom.append("<module>").append(c).append("</module>");
        }
        Files.writeString(ws.resolve("pom.xml"), pom.append("</modules></project>").toString());
    }

    @Test
    void aMavenProjectSaysSo(@TempDir Path ws) throws IOException {
        module(ws, "", "pom.xml");

        String said = ask(ws);

        assertTrue(said.contains("Every module here is Maven"), said);
        assertTrue(said.contains("maven"), said);
    }

    @Test
    void aGradleProjectIsNotToldItCannotAct(@TempDir Path ws) throws IOException {
        module(ws, "", "build.gradle");

        String said = ask(ws);

        assertTrue(said.contains("Every module here is Gradle"), said);
        assertTrue(said.contains("gradle"), said);
        // The sentence an agent acts on. It used to read "apply_recipe runs the OpenRewrite MAVEN
        // plugin and cannot execute a recipe on any of them", and for four hundred bumps it was
        // true. Leaving it in place after building the actuator would cost every Gradle pin twice
        // over: once to the runner, once to a description telling agents not to try.
        assertFalse(said.contains("cannot"), said);
    }

    @Test
    void andIsToldWhereTheRunStartsInstead(@TempDir Path ws) throws IOException {
        module(ws, "", "build.gradle");

        // A recipe run is per repository on Gradle, not per module: one invocation at the root
        // reaches every subproject, measured on a multi-project build whose root held no sources.
        // An agent that does not know that walks the modules calling it once each.
        assertTrue(ask(ws).contains("from the root"), ask(ws));
    }

    @Test
    void theKotlinDslCountsAsGradle(@TempDir Path ws) throws IOException {
        module(ws, "", "build.gradle.kts");

        assertTrue(ask(ws).contains("gradle"), "the Kotlin DSL is not a different build system");
    }

    @Test
    void aMixedRepositoryIsReportedAsMixedRatherThanAsOneWord(@TempDir Path ws) throws IOException {
        // THE CASE A ROOT-LEVEL FILE CHECK GETS WRONG. A pom at the root and a Gradle module under
        // it: `Files.exists(ws.resolve("pom.xml"))` calls the whole repository Maven and is wrong
        // about half of it, silently, which is worse than the bug this tool replaces.
        mavenRoot(ws, "tooling");
        module(ws, "tooling", "build.gradle");

        String said = ask(ws);

        assertTrue(said.startsWith("Mixed."), said);
        // Both actuators run in turn on a repository that is both, and the arms of the document
        // that do not match the build system in front of them are no-ops rather than errors.
        assertTrue(said.contains("reaches both"), said);
    }

    @Test
    void everyModuleIsNamedSoAReaderKnowsWhichOne(@TempDir Path ws) throws IOException {
        mavenRoot(ws, "api", "web");
        module(ws, "api", "pom.xml");
        module(ws, "web", "build.gradle");

        String said = ask(ws);

        assertTrue(said.contains("api"), said);
        assertTrue(said.contains("web"), said);
    }

    @Test
    void thePhaseThatCannotActStillHoldsIt(@TempDir Path ws) throws IOException {
        // after-pins has apply_recipe and no writer, so on Gradle its action space is empty. It
        // still needs to ASK, because "every pin met" from a phase whose only tool could not start
        // is the worst answer available, and it is the one the corpus was giving.
        module(ws, "", "build.gradle");
        var tools = Tools.pinning(ws, null, null, "21", new NoTrace(), "after-pins-doer");

        assertTrue(tools.keySet().stream().anyMatch(s -> s.name().equals("build_system")));
        assertFalse(tools.keySet().stream().anyMatch(s -> s.name().equals("edit_file")),
                "and it is still not given a writer it does not have a use for");
    }

    private record NoTrace() implements Trace {
        public void asked(String a, String p, String r) {
        }

        public void applied(String s, String w) {
        }

        public void tool(String a, String t, String args, String result) {
        }

        public void thought(String f, String t, String c) {
        }

        public void built(String phase, Trace.Outcome r) {
        }

        public void settled(String b, String s, String w, boolean x, boolean y) {
        }

        public void failed(String b, Throwable c) {
        }

        public void progress(String b, String n) {
        }

        public void priced(String b, String m, String i) {
        }
    }

    @Test
    void theJudgeCanCheckTheClaimRatherThanTrustOrIgnoreIt(@TempDir Path ws) throws IOException {
        // OBSERVED, TWICE IN ONE PHASE. after-pins-doer reported "BLOCKED: root module is
        // Gradle-only, so apply_recipe cannot execute", which was true and was the honest answer.
        // The verifier could see only that the version was below its floor, so it answered
        // "again: try UpgradeSpringBoot_3_5" to a colleague holding no tool that could try it, and
        // would have gone on doing that until the phase budget ran out.
        //
        // The prompt already allowed `done` for something genuinely unreachable. What was missing
        // was any way to establish that it was, which is the same reason inspect_jar is given to
        // judges rather than only to producers.
        module(ws, "", "build.gradle");
        var judge = Tools.judging(ws, null, new NoTrace(), "after-pins-verifier");

        assertTrue(judge.keySet().stream().anyMatch(s -> s.name().equals("build_system")),
                "a judge can establish the build system for itself: "
                        + judge.keySet().stream().map(s -> s.name()).toList());
        assertFalse(judge.keySet().stream().anyMatch(s -> s.name().equals("apply_recipe")),
                "and still cannot act, which is what makes it a judge");
    }

    @Test
    void theVerifierIsToldThatBeingGradleIsNotAnExcuse(@TempDir Path ws) {
        for (String platform : Managed.PLATFORMS) {
        String critic = Agents.forHop(new Hop(17, 21), ws).stream()
                .filter(d -> d.name().equals(Agents.named("after-pins-verifier", platform)))
                .findFirst().orElseThrow().systemPrompt();

        // THIS ASSERTED THE OPPOSITE, AND STAYED GREEN THE WHOLE TIME THE OPPOSITE WAS FALSE. The
        // paragraph it pinned told the verifier that a Gradle module could not be pinned by
        // anyone, so accepting a colleague's claim to that effect was `done`. The class doc above
        // was corrected when the Gradle actuator landed and this method was not, which is the
        // disagreement to notice: a doc says what is true, an assertion says what the file must
        // keep saying, and only the second one holds the prompt.
        //
        // THE VERIFIER IS THE HALF THAT MATTERS HERE. A doer that reports a pin unapplied costs
        // one pin; a verifier that accepts the report makes the excuse free, and 74 of the 84 pins
        // reported BLOCKED in that window named Gradle.
        assertTrue(critic.contains("BEING GRADLE IS NOT A REASON A PIN COULD NOT BE APPLIED"),
                critic);
        assertTrue(critic.contains("that is `again` with the recipe named"),
                "and it is told what to answer instead, not merely what not to answer");
        assertFalse(critic.contains("UNREACHABLE IS A REAL ANSWER"),
                "the escape it replaces is gone rather than sitting beside its correction");
        }
    }
}
