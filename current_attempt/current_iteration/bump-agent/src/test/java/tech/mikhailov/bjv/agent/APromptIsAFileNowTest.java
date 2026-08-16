package tech.mikhailov.bjv.agent;

import com.deepagents.langchain4j.subagents.SubAgentDefinition;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * THE PROMPTS ARE FILES, AND SUBSTITUTION MOVED WITH THEM.
 *
 * <p>Six hundred lines of Agents.java were prompt text, half the file, and a comma in one of them
 * cost a Maven build, a jar copy, a docker build and a deploy before anyone could read it back. They
 * are resources now, on the same route {@link Bom} already loads its lists by.
 *
 * <p>WHAT THAT MOVES IS WHEN A MISTAKE IS FOUND. A missing file used to be impossible and is now a
 * class-initialisation failure; an unresolved placeholder used to be a compile-time constant nobody
 * checked and is now a token that reaches a model. So this walks every agent of every hop and
 * demands a prompt that exists, says something, and has nothing left in braces.
 */
class APromptIsAFileNowTest {

    private static final List<Hop> HOPS =
            List.of(new Hop(8, 11), new Hop(11, 17), new Hop(17, 21), new Hop(21, 25));

    /** {FLOORS}, {TARGET}, {PINS} and the rest: anything the substitution should have eaten. */
    private static final Pattern LEFTOVER = Pattern.compile("\\{[A-Z_]{2,}}");

    @Test
    void everyAgentOfEveryHopGetsAPromptThatSaysSomething() {
        for (Hop hop : HOPS) {
            List<SubAgentDefinition> defined = Agents.forHop(hop, Path.of("/tmp"));
            for (SubAgentDefinition d : defined) {
                String prompt = d.systemPrompt();
                assertFalse(prompt.isBlank(), d.name() + " has no prompt on " + hop);
                // AN AGENT GIVEN NOTHING TO DO DOES SOMETHING ARBITRARY, and the trace records it
                // as a decision. Twenty characters is not a threshold, it is a floor under
                // "somebody saved an empty box".
                assertTrue(prompt.length() > 20,
                        d.name() + " has a prompt of " + prompt.length() + " characters on " + hop);

                Matcher left = LEFTOVER.matcher(prompt);
                if (left.find()) {
                    fail(d.name() + " on " + hop + " still carries " + left.group()
                            + ", so substitution moved but this one did not follow it");
                }
            }
            assertTrue(defined.size() >= Chain.agentNames().size(),
                    "every agent the chain names is built, on " + hop);
        }
    }

    @Test
    void everyPromptFileIsReachedByName() throws IOException {
        // A FILE NOBODY LOADS IS A PROMPT SOMEBODY IS EDITING FOR NOTHING. The move renamed the
        // constants into keys, and a key that no longer matches a file throws at class
        // initialisation; the reverse, a file no key names, is silent and this is the only thing
        // that would notice.
        Path dir = Path.of("src/main/resources/prompts");
        if (!Files.isDirectory(dir)) {
            return;
        }
        String source = Files.readString(
                Path.of("src/main/java/tech/mikhailov/bjv/agent/Agents.java"),
                StandardCharsets.UTF_8);
        try (var files = Files.list(dir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".md")).toList()) {
                String key = f.getFileName().toString().replace(".md", "");
                assertTrue(source.contains("text(\"" + key + "\")"),
                        key + ".md is not loaded by any constant in Agents");
                assertFalse(Files.readString(f, StandardCharsets.UTF_8).isBlank(),
                        key + ".md is empty, and an empty prompt is a save that went wrong");
            }
        }
    }

    @Test
    void theTextThatSurvivedTheMoveIsTheTextThatWasThere() {
        // Spot checks against versions and phrases that were in the compiled constants before the
        // move, one per substitution helper, so a silently mangled text block would show up here
        // rather than in a sweep. The values come from the hop's own floors.
        String pins8 = prompt(new Hop(8, 11), "before-pins-doer");
        assertTrue(pins8.contains("1.18.30"), "the lombok floor for 8 to 11 reached the doer");
        assertTrue(pins8.contains("JDK 8") && pins8.contains("JDK 11"), "and its hop did");

        String pins25 = prompt(new Hop(21, 25), "after-pins-doer");
        assertTrue(pins25.contains("3.5.16"), "the boot floor for 21 to 25 reached the doer");

        assertTrue(prompt(new Hop(17, 21), "module-repair-step-doer").contains("ONE MODULE"),
                "and the repair doer still knows its scope");
    }

    private static String prompt(Hop hop, String agent) {
        return Agents.forHop(hop, Path.of("/tmp")).stream()
                .filter(d -> d.name().equals(agent))
                .findFirst().orElseThrow()
                .systemPrompt();
    }
}
