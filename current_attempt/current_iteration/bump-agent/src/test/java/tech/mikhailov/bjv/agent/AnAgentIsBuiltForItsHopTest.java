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
    void thePreparerCarriesOnlyTheFloorsItsHopCanReach(@TempDir Path ws) {
        String low = preparer(new Hop(8, 11), ws);
        String high = preparer(new Hop(21, 25), ws);

        assertNotEquals(low, high, "the same agent, told different things");

        // 8 to 11 cannot reach any of these, so it is not told about them.
        assertFalse(low.contains("2.3.20"), "kotlin 2.3.20 is a target-25 rule: " + low);
        assertTrue(low.contains("2.7.18"), "below 17 the Boot ceiling is the last of the 2.x line");
        assertFalse(low.contains("4.1.0"), "Boot 4 declares java.version 17 and cannot run here");
        assertFalse(low.contains("9.0.105"), "the tomcat floor is a target-21 rule");
        assertFalse(low.contains("1.18.46"), "the JDK 25 lombok is unreachable at 11");

        // What it CAN reach, it is told, with the version that applies rather than a conditional.
        assertTrue(low.contains("1.18.30"), "the lombok floor for this target: " + low);
        assertTrue(low.contains("JDK 8 to 11"), "and it knows which hop it is on");

        // 21 to 25 gets the ones 8 to 11 was spared.
        assertTrue(high.contains("1.18.46"), "the JDK 25 lombok");
        assertTrue(high.contains("2.3.20"), "and the kotlin pin");
        assertTrue(high.contains("4.1.0"), "and Boot 4, which 17 and up can run");
        assertFalse(high.contains("2.7.18"), "the 2.x ceiling is not mentioned where it cannot apply");
    }

    @Test
    void everyHopBuildsAllSixteenAgentsAndTheOrderIsTheChains() {
        for (Hop hop : List.of(new Hop(8, 11), new Hop(11, 17), new Hop(17, 21), new Hop(21, 25))) {
            List<SubAgentDefinition> all = Agents.forHop(hop, Path.of("/tmp"));
            assertEquals(16, all.size(), "every hop gets the whole chain: " + hop);
            assertEquals("surveyor", all.get(0).name(), "which starts where the chain starts");
            assertEquals("estimator", all.get(15).name(), "and ends where it ends");
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
        assertEquals(8, Floors.at(11).size(), "8 to 11 reaches eight of the seventeen");
        assertEquals(11, Floors.at(25).size(), "21 to 25 reaches eleven");
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

    private static String preparer(Hop hop, Path ws) {
        return Agents.forHop(hop, ws).stream()
                .filter(d -> d.name().equals("preparer"))
                .findFirst().orElseThrow()
                .systemPrompt();
    }
}
