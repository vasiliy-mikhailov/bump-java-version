package tech.mikhailov.bjv.agent;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WHICH BUILD SYSTEM, ASKED RATHER THAN INFERRED FROM A FAILURE.
 *
 * <p>apply_recipe runs the OpenRewrite MAVEN plugin. On a module with no pom it answers "no POM in
 * this directory" and no recipe can run. Before this tool the only route to that knowledge was to
 * call the tool, read the error and infer the project type from it, and in the corpus that usually
 * ended in calling it again: 24 of 75 bumps, and the ten of those scanned on both sides held 143
 * CVEs that never moved.
 *
 * <p>The fix is not cleverer prompting. It is a tool that answers the question, so the prompt can
 * say "ask" instead of teaching an agent to recognise an error string.
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
    void aMavenProjectSaysRecipesCanRun(@TempDir Path ws) throws IOException {
        module(ws, "", "pom.xml");

        String said = ask(ws);

        assertTrue(said.contains("apply_recipe can run"), said);
        assertTrue(said.contains("maven"), said);
    }

    @Test
    void aGradleProjectSaysTheyCannot(@TempDir Path ws) throws IOException {
        module(ws, "", "build.gradle");

        String said = ask(ws);

        // The sentence has to be unambiguous: this is the one an agent acts on.
        assertTrue(said.contains("cannot execute a recipe"), said);
        assertTrue(said.contains("gradle"), said);
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
        assertTrue(said.contains("on none of the Gradle ones"), said);
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

        public void built(String phase, Runner.Result r) {
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
}
