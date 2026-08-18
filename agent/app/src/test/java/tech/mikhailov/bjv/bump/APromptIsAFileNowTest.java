package tech.mikhailov.bjv.bump;

import com.deepagents.langchain4j.subagents.SubAgentDefinition;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import tech.mikhailov.bjv.engine.Shape;

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
            assertTrue(defined.size() >= Shape.agentNames(Bump.stages()).size(),
                    "every agent the tree reaches is built, on " + hop);
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
                Path.of("src/main/java/tech/mikhailov/bjv/bump/Agents.java"),
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
    void everyPlatformFragmentIsReachedByName() throws IOException {
        // THE MIRROR OF THE TEST ABOVE, FOR THE COMPOSED HALF. The loader names its key rather than
        // listing the directory, so a fragment nobody loads is a prompt somebody is editing for
        // nothing and only this would notice. The other direction, a fragment that is named and
        // missing, throws at class initialisation and needs no test.
        Path dir = Path.of("src/main/resources/prompts/platform");
        if (!Files.isDirectory(dir)) {
            return;
        }
        String source = Files.readString(
                Path.of("src/main/java/tech/mikhailov/bjv/bump/Agents.java"),
                StandardCharsets.UTF_8);
        Set<String> keys = new TreeSet<>();
        Set<String> platforms = new TreeSet<>();
        try (var dirs = Files.list(dir)) {
            for (Path p : dirs.toList()) {
                String platform = p.getFileName().toString();
                assertTrue(Managed.PLATFORMS.contains(platform),
                        platform + " is not a regime the detector can answer with");
                platforms.add(platform);
                Set<String> here = new TreeSet<>();
                try (var files = Files.list(p)) {
                    for (Path f : files.filter(x -> x.toString().endsWith(".md")).toList()) {
                        String key = f.getFileName().toString().replace(".md", "");
                        here.add(key);
                        assertFalse(Files.readString(f, StandardCharsets.UTF_8).isBlank(),
                                f + " is empty, and an empty fragment is a save that went wrong");
                        // AND SOMETHING ASKS FOR IT BY NAME. The loader names its key rather
                        // than listing this directory, so a fragment nobody names is a file
                        // somebody edits for nothing and this is what notices.
                        //
                        // A FRAGMENT KEY IS NOT A BODY FILENAME, though it was one until
                        // after-pins stopped borrowing before-pins' fragment. Both pin phases
                        // still share pins.md, differing by {WHEN} and {PINS}, because the
                        // mechanics of raising a version do not change between them; what changes
                        // is the platform half, so after-pins composes that same body with a
                        // fragment of its own. There is no after-pins.md and there should not be.
                        // This assertion used to open that file, which was asserting a
                        // coincidence: it held only while every fragment happened to be spliced
                        // into a body of the same name.
                        //
                        // The slot needs no assertion here. withPlatform throws when a body has
                        // no {PLATFORM} line, at class initialisation, which is before any bump
                        // starts and long before anything a test could miss.
                        // MATCHED STRUCTURALLY, NOT BY THE LAMBDA'S VARIABLE NAME. The first
                        // version of this looked for the literal followed by ", p)" and went red
                        // on bumper, whose call site happens to spell the same argument
                        // "platform". What is being asserted is that a withPlatform call names
                        // this key, and the name of its other argument is nobody's business.
                        assertTrue(java.util.regex.Pattern
                                        .compile("withPlatform\\([^,]+,\\s*\""
                                                + java.util.regex.Pattern.quote(key) + "\"")
                                        .matcher(source).find(),
                                key + " is a fragment that no withPlatform call names");
                    }
                }
                if (keys.isEmpty()) {
                    keys.addAll(here);
                }
                assertEquals(keys, here, platform + " does not carry the same texts as the others");
            }
        }
        assertEquals(new TreeSet<>(Managed.PLATFORMS), platforms, "one directory per regime");
        // FOURTEEN AGENTS, FOURTEEN FRAGMENTS, ELEVEN BODIES. The count was eleven while the
        // two pin phases shared a fragment as well as a body. They still share the body, because
        // raising a version works the same way whichever phase asks for it; they no longer share
        // the platform half, because what a Boot module needs to hear about enabling a hop and
        // what it needs to hear about a CVE in a member of its managed set are different things,
        // and one of them has a wrong move that silences a scanner.
        assertEquals(14, keys.size(),
                "one fragment per module-scoped agent, since after-pins stopped borrowing "
                        + "the platform half that belongs to before-pins");
    }

    @Test
    void theTextThatSurvivedTheMoveIsTheTextThatWasThere() {
        // Spot checks against versions and phrases that were in the compiled constants before the
        // move, one per substitution helper, so a silently mangled text block would show up here
        // rather than in a sweep. The values come from the hop's own floors.
        // ON EVERY PLATFORM, because substitution runs on the composed text: the platform half is
        // spliced in first and the hop's numbers are resolved over the whole of it, so a fragment
        // is free to name the target and a phase that lost its floors on one regime would be a
        // silent hole in a third of the walk.
        for (String platform : Managed.PLATFORMS) {
            String pins8 = prompt(new Hop(8, 11), Agents.named("before-pins-doer", platform));
            assertTrue(pins8.contains("1.18.30"), "the lombok floor for 8 to 11 reached the doer");
            assertTrue(pins8.contains("JDK 8") && pins8.contains("JDK 11"), "and its hop did");

            String pins25 = prompt(new Hop(21, 25), Agents.named("after-pins-doer", platform));
            assertTrue(pins25.contains("3.5.16"), "the boot floor for 21 to 25 reached the doer");

            assertTrue(prompt(new Hop(17, 21),
                            Agents.named("module-repair-step-doer", platform))
                            .contains("ONE MODULE"),
                    "and the repair doer still knows its scope");
        }
    }

    private static String prompt(Hop hop, String agent) {
        return Agents.forHop(hop, Path.of("/tmp")).stream()
                .filter(d -> d.name().equals(agent))
                .findFirst().orElseThrow()
                .systemPrompt();
    }
}
