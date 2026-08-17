package tech.mikhailov.bjv.agent;

import com.deepagents.langchain4j.subagents.SubAgentDefinition;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A CAPABILITY THE PROMPTS DENIED FOR FOUR HUNDRED BUMPS AFTER IT WAS BUILT.
 *
 * <p>{@code apply_recipe} could not run on a Gradle module, once. {@link Migrate#rewriteGradle}
 * fixed that; three prompts and a tool description went on saying otherwise. {@code pins.md} told
 * the doer that a Gradle module could not be pinned and to report it unapplied. {@code
 * pins-critic.md} told the verifier to accept that answer as {@code done}. {@code bumper.md} told
 * the bump doer not to retry. So the two agents that could have disagreed agreed, and the phase
 * closed green having written nothing: 84 pin summaries came back BLOCKED and 74 of them named
 * Gradle.
 *
 * <p>WHY NO TEST CAUGHT IT. There was one, and it asserted the sentence. A test that pins wording
 * cannot notice that the wording became false, because the thing it compares against is the
 * wording. This compares against the capability instead: {@link Migrate#actuatorFor} returns
 * gradle, therefore no agent may be told a recipe cannot reach a Gradle module.
 *
 * <p>It reads every prompt an agent is actually handed, on every hop, plus the descriptions of the
 * tools handed with them, because a tool description is a prompt the model reads too and the same
 * claim lived in one of those.
 */
class NoPromptSaysRecipesCannotReachGradleTest {

    /**
     * The claims, each of which was in the text and each of which is now false.
     *
     * <p>These are exact phrases rather than a pattern on purpose. A regex broad enough to catch
     * the idea would also catch the true sentence that replaced it, which says that apply_recipe
     * drives the Maven plugin on a pom project and the Gradle plugin on a Gradle one. The point is
     * not to ban the words Maven and Gradle near each other; it is to ban six specific assertions
     * that the corpus proved cost it 74 unapplied pins.
     */
    private static final List<String> FALSE_NOW = List.of(
            "no recipe can execute",
            "cannot execute any recipe",
            "no pin here can be applied",
            "no pin can be applied",
            "is the whole toolkit",
            "has no other way to write");

    private static final List<Hop> EVERY_HOP =
            List.of(new Hop(8, 11), new Hop(11, 17), new Hop(17, 21), new Hop(21, 25));

    @Test
    void theGradleActuatorReallyExists(@TempDir Path ws) throws Exception {
        // THE PREMISE OF EVERY OTHER ASSERTION HERE, checked rather than assumed. If this ever
        // goes red the rest of the file is wrong to be red, and the fix is to restore the actuator
        // rather than to put the paragraphs back.
        java.nio.file.Files.writeString(ws.resolve("build.gradle"), "plugins { id 'java' }\n");
        assertTrue("gradle".equals(Migrate.actuatorFor(ws)),
                "a workspace with a build.gradle and no pom resolves to the gradle actuator");
    }

    @Test
    void noAgentOnAnyHopIsToldARecipeCannotReachGradle(@TempDir Path ws) {
        List<String> offences = new ArrayList<>();
        for (Hop hop : EVERY_HOP) {
            for (SubAgentDefinition d : Agents.forHop(hop, ws)) {
                for (String claim : FALSE_NOW) {
                    if (d.systemPrompt().contains(claim)) {
                        offences.add(hop.from() + "->" + hop.to() + " " + d.name()
                                + " says \"" + claim + "\"");
                    }
                }
            }
        }
        assertTrue(offences.isEmpty(),
                "prompts still deny a capability the harness has: " + offences);
    }

    @Test
    void noToolDescriptionSaysItEither(@TempDir Path ws) {
        // A TOOL DESCRIPTION IS A PROMPT. It travels with the request and the model reads it, and
        // the build_system description carried this claim in its javadoc and its own text while
        // the prompts beside it were being argued about.
        List<String> offences = new ArrayList<>();
        for (String phase : List.of("bump-doer", "before-pins-doer", "after-pins-doer")) {
            var tools = Tools.raising(ws, null, null, null, "17", "21", null, phase);
            for (ToolSpecification spec : tools.keySet()) {
                String text = spec.description() == null ? "" : spec.description();
                for (String claim : FALSE_NOW) {
                    if (text.contains(claim)) {
                        offences.add(phase + " / " + spec.name() + " says \"" + claim + "\"");
                    }
                }
            }
        }
        assertTrue(offences.isEmpty(), "tool descriptions still deny it: " + offences);
    }

    @Test
    void thePinPhasesSayPlainlyThatAGradleModuleIsPinnable(@TempDir Path ws) {
        // THE NEGATIVE IS NOT ENOUGH. Deleting the escape leaves silence, and silence in front of
        // an agent that spent four hundred bumps being told the opposite is not a correction. The
        // doer is told it can reach the module; the verifier is told that being Gradle is not a
        // reason a pin went unapplied, because the verifier is the half that accepted the excuse.
        List<SubAgentDefinition> defs = Agents.forHop(new Hop(17, 21), ws);
        String doer = of(defs, "before-pins-doer");
        String critic = of(defs, "before-pins-verifier");

        assertTrue(doer.contains("apply_recipe reaches both"),
                "the pin doer is told the tool reaches either build system: " + doer);
        assertTrue(critic.contains("BEING GRADLE IS NOT A REASON A PIN COULD NOT BE APPLIED"),
                "and the verifier is told not to accept it as one: " + critic);
    }

    private static String of(List<SubAgentDefinition> defs, String name) {
        return defs.stream().filter(d -> d.name().equals(name)).findFirst().orElseThrow()
                .systemPrompt();
    }
}
