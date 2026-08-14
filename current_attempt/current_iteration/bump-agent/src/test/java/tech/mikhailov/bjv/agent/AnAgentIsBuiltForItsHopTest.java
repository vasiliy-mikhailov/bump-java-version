package tech.mikhailov.bjv.agent;

import com.deepagents.langchain4j.subagents.SubAgentDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE SAME AGENT IS A DIFFERENT AGENT ON A DIFFERENT HOP.
 *
 * <p>Every agent used to be told every rule for every target. An 8-to-11 preparer was handed the
 * Kotlin pin for JDK 25 and the jakarta move at 21, neither of which it can reach from where it
 * stands, and a rule that cannot fire is not merely wasted context: it is an invitation to apply it
 * anyway. This corpus has a troubleshooter that raised a Gradle wrapper to a version that was never
 * published, for want of being told which versions were real.
 */
class AnAgentIsBuiltForItsHopTest {

    @Test
    void thePinPairCarriesOnlyTheFloorsItsHopCanReach(@TempDir Path ws) {
        String low = pinsFor(new Hop(8, 11), ws);
        String high = pinsFor(new Hop(21, 25), ws);

        assertNotEquals(low, high, "the same agent, told different things");

        // 8 to 11 cannot reach any of these, so it is not told about them.
        assertFalse(low.contains("2.3.20"), "kotlin 2.3.20 is a target-25 rule: " + low);
        assertFalse(low.contains("4.1.0"), "spring-boot is the after-JDK pair's business");
        assertFalse(low.contains("9.0.105"), "the tomcat floor is a target-21 rule");
        assertFalse(low.contains("1.18.46"), "the JDK 25 lombok is unreachable at 11");

        // What it CAN reach, it is told, with the version that applies rather than a conditional.
        assertTrue(low.contains("1.18.30"), "the lombok floor for this target: " + low);
        assertTrue(low.contains("JDK 8") && low.contains("JDK 11"),
                "and it knows which hop it is on");

        // 21 to 25 gets the ones 8 to 11 was spared.
        assertTrue(high.contains("1.18.46"), "the JDK 25 lombok");
        assertTrue(high.contains("2.3.20"), "and the kotlin pin");

    }

    @Test
    void everyHopBuildsTheWholeChainAndTheOrderIsTheChains() {
        for (Hop hop : List.of(new Hop(8, 11), new Hop(11, 17), new Hop(17, 21), new Hop(21, 25))) {
            List<SubAgentDefinition> all = Agents.forHop(hop, Path.of("/tmp"));
            assertEquals(20, all.size(), "every hop gets the whole chain: " + hop);
            assertEquals("surveyor", all.get(0).name(), "which starts where the chain starts");
            assertEquals("estimator-critic", all.get(all.size() - 1).name(),
                    "and ends where it ends: every producer has a critic, including the last");
            for (SubAgentDefinition d : all) {
                assertFalse(d.systemPrompt().isBlank(), d.name() + " has no prompt on " + hop);
                assertFalse(d.systemPrompt().contains("{FLOORS}"),
                        d.name() + " has an unresolved token on " + hop);
                assertFalse(d.systemPrompt().contains("{TARGET}"),
                        d.name() + " has an unresolved target on " + hop);
                assertFalse(d.description().isBlank(), d.name() + " has no description");
            }
        }
    }

    @Test
    void theRuleCountRisesWithTheTargetAndNeverExceedsTheTable() {
        int previous = 0;
        for (int target : new int[] {11, 17, 21, 25}) {
            int rules = Floors.at(target).size();
            assertTrue(rules >= previous, "a higher target cannot reach fewer rules");
            assertTrue(rules <= Floors.all().size(), "and never more than the table holds");
            previous = rules;
        }
        assertEquals(14, Floors.at(11).size(), "8 to 11 pins fourteen");
        assertEquals(17, Floors.at(25).size(), "21 to 25 pins seventeen");
    }

    @Test
    void aHopKnowsTheRungsItCrossesNotJustWhereItLands() {
        // A prescribed hop need not be one step, and the walls at a rung it passes through do not
        // go away because nothing stopped there.
        assertEquals(List.of(11), new Hop(8, 11).rungs());
        assertEquals(List.of(11, 17, 21, 25), new Hop(8, 25).rungs(), "a four-rung hop crosses all");
        assertTrue(new Hop(8, 17).crosses(11), "8 to 17 still needs what 11 needed");
        assertFalse(new Hop(17, 21).crosses(11), "and 17 to 21 does not");
        assertTrue(new Hop(17, 21).crossesJakarta());
        assertFalse(new Hop(8, 11).crossesJakarta());
    }

    /** What the pre-JDK pin pair is told, which is where the hop's versions now live. */
    private static String pinsFor(Hop hop, Path ws) {
        return Agents.forHop(hop, ws).stream()
                .filter(d -> d.name().equals("before-pins"))
                .findFirst().orElseThrow()
                .systemPrompt();
    }

    @Test
    void thePinPhasesSplitOnWhatTheJdkChangeAllows() {
        // Lombok must move BEFORE the JDK: a Lombok that cannot read the new class file kills javac
        // before anything else runs. Spring Boot must move AFTER: Boot 4.1 declares java.version 17
        // and cannot be resolved by a project still on 11.
        for (int target : new int[] {11, 17, 21, 25}) {
            String before = Floors.before(target);
            String after = Floors.after(target);
            assertTrue(before.contains("lombok"), "lombok goes first at " + target);
            assertFalse(before.contains("spring-boot"), "boot does not, at " + target);
            assertTrue(after.contains("spring-boot"), "boot goes second at " + target);
            assertFalse(after.contains("lombok"), "and lombok is not repeated there");
            assertEquals(Floors.at(target).size(),
                    before.lines().filter(l -> !l.isBlank()).count()
                            + after.lines().filter(l -> !l.isBlank()).count(),
                    "every pin lands in exactly one phase at " + target);
        }
    }

    @Test
    void eachPinPairIsToldOnlyItsOwnPhase(@TempDir Path ws) {
        var defs = Agents.forHop(new Hop(21, 25), ws);
        String beforePins = prompt(defs, "before-pins");
        String afterPins = prompt(defs, "after-pins");

        assertTrue(beforePins.contains("1.18.46"), "the pre-JDK pair gets lombok");
        assertFalse(beforePins.contains("4.1.0"), "and is not shown a pin it cannot apply yet");
        assertTrue(beforePins.contains("has NOT been raised"), "it knows where it stands");

        assertTrue(afterPins.contains("4.1.0"), "the post-JDK pair gets spring-boot");
        assertFalse(afterPins.contains("1.18.46"), "and is not asked to redo the first phase");
        assertTrue(afterPins.contains("has already been raised"), "it knows where it stands");
    }

    private static String prompt(List<SubAgentDefinition> defs, String name) {
        return defs.stream().filter(d -> d.name().equals(name)).findFirst().orElseThrow()
                .systemPrompt();
    }
}
