package tech.mikhailov.bjv.bump;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE TWO PIN PHASES SHARE A BODY ON PURPOSE, AND THEY MUST NOT SHARE THE PLATFORM HALF.
 *
 * <p>before-pins and after-pins are handed pins.md, pins-planner.md and pins-critic.md between
 * them, differing by {@code {WHEN}} and {@code {PINS}}, and that is deliberate: the mechanics of
 * raising a version do not change between raising what ENABLES a hop and raising what HARDENS the
 * result. The platform half does change. Enabling a hop on a Boot module is a question about what
 * the managed set already gives you; hardening one is a question about a CVE in a member of that
 * set, where the obvious edit quietens the scanner and takes the artifact out of the set in the
 * same stroke.
 *
 * <p>WHAT THIS EXISTS TO CATCH. Until the fragments were split, both phases composed their body
 * with the fragment keyed {@code pins}, so after-pins read its own list of hardening floors through
 * before-pins' argument: the paragraphs it was given were written about the enabling list, named
 * rows it had not been handed, and never mentioned the one move that hardening on a managed module
 * comes down to. Nothing failed. The composed prompt read perfectly well, every other assertion in
 * this package stayed green, and the only symptom was a phase reasoning about the wrong bill.
 *
 * <p>SO THE ASSERTION IS DIFFERENCE, NOT WORDING. Pinning a phrase would only be a test of the
 * phrase. What is checked is that the three after-pins fragments are their own texts on every
 * platform, and that the prompt each after-pins agent is actually handed carries them rather than
 * the before-pins ones. A regression here is a copy-paste or a key pointed back at {@code pins},
 * and both land as a fragment that is once again indistinguishable from its neighbour.
 */
class AfterPinsReadsItsOwnListTest {

    /** before-pins fragment key, after-pins fragment key, and the agent that composes the pair. */
    private record Pair(String before, String after, String agent) {}

    private static final List<Pair> PAIRS = List.of(
            new Pair("pins-planner", "after-pins-planner", "after-pins-planner"),
            new Pair("pins", "after-pins", "after-pins-doer"),
            new Pair("pins-critic", "after-pins-critic", "after-pins-verifier"));

    private static final Path DIR = Path.of("src/main/resources/prompts/platform");

    @Test
    void noAfterPinsFragmentIsACopyOfItsBeforePinsNeighbour() throws IOException {
        List<String> offences = new ArrayList<>();
        for (String platform : Managed.PLATFORMS) {
            for (Pair pair : PAIRS) {
                String before = normalised(fragment(platform, pair.before()));
                String after = normalised(fragment(platform, pair.after()));
                // WHITESPACE IS NOT A DIFFERENCE. The composer strips and re-indents a fragment to
                // its slot, so a copy that was only reflowed reaches the model as the same words.
                if (before.equals(after)) {
                    offences.add(platform + "/" + pair.after()
                            + " is " + pair.before() + " again, so this phase is reading its own "
                            + "list through the other phase's argument");
                }
            }
        }
        assertTrue(offences.isEmpty(), "the split of the platform half is cosmetic: " + offences);
    }

    @Test
    void everyAfterPinsFragmentSaysSomethingOfItsOwn() throws IOException {
        // A FRAGMENT SAVED AS A NOTE TO ITSELF is different from its neighbour and still says
        // nothing, which the assertion above cannot tell apart from a real text. These were nine
        // five-line placeholders an hour before they were written, and a placeholder that survived
        // would compose, differ, and leave the phase with less to go on than the copy it replaced.
        for (String platform : Managed.PLATFORMS) {
            for (Pair pair : PAIRS) {
                Path file = DIR.resolve(platform).resolve(pair.after() + ".md");
                String text = Files.readString(file, StandardCharsets.UTF_8);
                assertTrue(text.strip().lines().count() >= 10,
                        file + " is shorter than the placeholder it replaced");
                for (String unwritten : List.of("has not been written yet", "Placeholder",
                        "placeholder", "TODO", "TBD")) {
                    assertFalse(text.contains(unwritten),
                            file + " still says \"" + unwritten + "\"");
                }
            }
        }
    }

    @Test
    void theAfterPinsAgentIsHandedTheAfterPinsHalf() throws IOException {
        // THE FILES ARE ONLY HALF THE CLAIM. Two distinct fragments on disk still compose wrongly
        // if the call site names the other key, which is precisely the bug: one argument, spelled
        // once, sent both phases to the same text. So this reads the prompt the agent is actually
        // built with and looks for a sentence only the other phase's fragment has.
        List<Definition> defs = Agents.forHop(new Hop(17, 21), Path.of("/tmp"));
        for (String platform : Managed.PLATFORMS) {
            for (Pair pair : PAIRS) {
                String before = fragment(platform, pair.before());
                String after = fragment(platform, pair.after());
                String body = Files.readString(
                        Path.of("src/main/resources/prompts").resolve(pair.before() + ".md"),
                        StandardCharsets.UTF_8);
                String composed = normalised(prompt(defs, Agents.named(pair.agent(), platform)));

                String onlyBefore = distinguishing(before, after, body);
                assertFalse(composed.contains(onlyBefore),
                        Agents.named(pair.agent(), platform)
                                + " carries a line that belongs to " + pair.before() + ": "
                                + onlyBefore);

                String onlyAfter = distinguishing(after, before, body);
                assertTrue(composed.contains(onlyAfter),
                        Agents.named(pair.agent(), platform)
                                + " does not carry " + pair.after() + " at all, looking for: "
                                + onlyAfter);
            }
        }
    }

    /**
     * A sentence this fragment has and the other one does not, derived rather than pinned.
     *
     * <p>Quoting a phrase here would make the test a test of the phrase, and the next edit to a
     * prompt would go red for having been improved. The longest sentence the two do not share is
     * whatever the author happened to write, and it stops being a distinguishing sentence only
     * when the two texts have converged, which is the thing being watched for.
     *
     * <p>Two things are excluded from the candidates. A sentence the shared body already says
     * would be in the composed prompt whichever fragment was spliced into it, and a sentence
     * carrying a {@code {PLACEHOLDER}} is a different string before and after substitution.
     */
    private static String distinguishing(String mine, String theirs, String body) {
        String other = normalised(theirs);
        String shared = normalised(body);
        return normalised(mine).lines()
                .flatMap(l -> java.util.Arrays.stream(l.split("(?<=\\. )")))
                .map(String::strip)
                .filter(l -> l.length() > 40 && !l.contains("{"))
                .filter(l -> !other.contains(l) && !shared.contains(l))
                .max(java.util.Comparator.comparingInt(String::length))
                .orElseThrow(() -> new AssertionError(
                        "these two fragments share every sentence they have, so the phase that "
                                + "reads one of them is reading the other phase's list"));
    }

    private static String fragment(String platform, String key) throws IOException {
        return Files.readString(DIR.resolve(platform).resolve(key + ".md"), StandardCharsets.UTF_8);
    }

    /** One space between words, so re-indentation and rewrapping are not read as a difference. */
    private static String normalised(String text) {
        return text.replaceAll("[ \\t]+", " ").strip();
    }

    private static String prompt(List<Definition> defs, String agent) {
        return defs.stream().filter(d -> d.name().equals(agent)).findFirst().orElseThrow()
                .systemPrompt();
    }
}
