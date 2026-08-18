package tech.mikhailov.bjv.agent;

import com.deepagents.langchain4j.subagents.SubAgentDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;

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
        // ON EVERY PLATFORM, because the floors belong to the hop and not to the regime. The pin
        // doer exists once per platform now, and a floor that reached only one of the three would
        // be a rule most of this corpus never hears: nothing manages 43 per cent of its modules.
        for (String platform : Managed.PLATFORMS) {
        String low = pinsFor(new Hop(8, 11), ws, platform);
        String high = pinsFor(new Hop(21, 25), ws, platform);

        assertNotEquals(low, high, "the same agent, told different things");

        // 8 to 11 cannot reach any of these, so it is not told about them.
        assertFalse(low.contains("2.3.20"), "kotlin 2.3.20 is a target-25 rule: " + low);
        assertFalse(low.contains("3.5.16"), "spring-boot is the after-JDK pair's business");
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
    }

    @Test
    void everyHopBuildsTheWholeChainAndTheOrderIsTheChains() {
        for (Hop hop : List.of(new Hop(8, 11), new Hop(11, 17), new Hop(17, 21), new Hop(21, 25))) {
            List<SubAgentDefinition> all = Agents.forHop(hop, Path.of("/tmp"));
            // THE WHOLE CHAIN, AND INSIDE THE MODULE WALK ONE OF EACH PER PLATFORM. The catalogue
            // is bigger than the tree by construction: the shape is drawn before any module has
            // been looked at, so it can only ever name before-pins-doer, and the catalogue holds
            // three of it. What must hold either way is the stems.
            assertEquals(new TreeSet<>(Shape.agentNames(Bump.stages())),
                    new TreeSet<>(all.stream().map(d -> Agents.stem(d.name())).toList()),
                    "every hop gets the whole chain, and the chain is the tree: " + hop);
            assertEquals("survey-planner", all.get(0).name(), "which starts where the chain starts");
            assertEquals("estimator-verifier", all.get(all.size() - 1).name(),
                    "and ends where it ends: every stage plans, does and verifies, including the last");
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
        // COUNTED AS LINES, because lines are all these are now. Floors.at() parsed each one into a
        // record, and that parse was also what decided whether an agent ever saw the list — it read
        // an artifact nothing declares, called every floor met, and skipped the phase for the whole
        // corpus. Nothing parses the floors any more; a planner reads them.
        int previous = 0;
        for (int target : new int[] {11, 17, 21, 25}) {
            int rules = pins(target);
            assertTrue(rules >= previous, "a higher target cannot reach fewer rules");
            previous = rules;
        }
        // A BARE COUNT, AND DELIBERATELY SO. What it catches is a row quietly leaving the list,
        // which is the failure this whole area keeps producing: a shorter list is a healthier
        // looking percentage and nothing else complains about one. So when these numbers move,
        // they should move because somebody meant them to, and the reason belongs here.
        //
        // Both rose by one when tomcat-embed-core 10.1.55 was added to every block. It had been in
        // all four hardens tsv files and in no Floors line since the bill of materials was written,
        // so compliance scored every project against a floor no agent was ever asked to reach.
        assertEquals(17, pins(11), "8 to 11 pins seventeen");
        assertEquals(20, pins(25), "21 to 25 pins twenty");
    }

    private static int pins(int target) {
        return (int) Floors.forTarget(target).lines().filter(l -> !l.isBlank()).count();
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

    /**
     * What the pre-JDK pin pair is told on one platform, which is where the hop's versions live.
     *
     * <p>Keyed by platform as well as by hop: the pin doer runs inside the module walk and is
     * defined once for each regime, so the bare name finds nothing at all.
     */
    private static String pinsFor(Hop hop, Path ws, String platform) {
        return prompt(Agents.forHop(hop, ws), Agents.named("before-pins-doer", platform));
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
            assertEquals(pins(target),
                    before.lines().filter(l -> !l.isBlank()).count()
                            + after.lines().filter(l -> !l.isBlank()).count(),
                    "every pin lands in exactly one phase at " + target);
        }
    }

    @Test
    void eachPinPairIsToldOnlyItsOwnPhase(@TempDir Path ws) {
        var defs = Agents.forHop(new Hop(21, 25), ws);
        // THE SPLIT IS THE HOP'S, NOT THE REGIME'S, so it has to hold on all three. Which pins can
        // be applied before the JDK moves and which cannot is a fact about the JDK, and a phase
        // that leaked the other half on one platform would be a pin applied where it cannot resolve.
        for (String platform : Managed.PLATFORMS) {
            String beforePins = prompt(defs, Agents.named("before-pins-doer", platform));
            String afterPins = prompt(defs, Agents.named("after-pins-doer", platform));

            assertTrue(beforePins.contains("1.18.46"), "the pre-JDK pair gets lombok");
            assertFalse(beforePins.contains("3.5.16"),
                    "and is not shown a pin it cannot apply yet");
            assertTrue(beforePins.contains("has NOT been raised"), "it knows where it stands");

            // 3.5, not 4.1: UpgradeSpringBoot_3_5 chains the whole migration and is free, while
            // the only recipe that reaches 4.1 is under the Moderne Proprietary License.
            assertTrue(afterPins.contains("3.5.16"), "the post-JDK pair gets spring-boot");
            assertFalse(afterPins.contains("1.18.46"), "and is not asked to redo the first phase");
            assertTrue(afterPins.contains("has already been raised"), "it knows where it stands");
        }
    }

    private static String prompt(List<SubAgentDefinition> defs, String name) {
        return defs.stream().filter(d -> d.name().equals(name)).findFirst().orElseThrow()
                .systemPrompt();
    }

    @Test
    void theFactoryOrderIsNotTheChainOrderAndNothingMayDependOnIt() {
        // MEASURED ON THE LIVE PAGE, not imagined. forHop hands agents back in the order its
        // methods happen to be listed, and that order interleaves: after-pins arrived after
        // troubleshoot and step, with modules-verifier after that. The settings page drew the
        // module block missing its third pass and closed the loop in the wrong place, while its
        // own comment claimed the order was the chain's.
        //
        // This test does not require the factory to be sorted. It requires the two orders to be
        // KNOWN to differ, so that anything showing the chain sorts by Chain and no future reader
        // assumes the factory's order means something.
        List<String> factory = Agents.forHop(new Hop(17, 21), Path.of("/tmp")).stream()
                .map(SubAgentDefinition::name)
                .toList();
        List<String> chain = Shape.agentNames(Bump.stages());

        // ON THE STEMS, because fourteen of the factory's entries exist three times over and the
        // tree names each of them once. That is also what the page sorts on, for the same reason.
        List<String> stems = factory.stream().map(Agents::stem).distinct().toList();

        assertEquals(chain.size(), stems.size(), "the same agents, either way round");
        assertTrue(stems.containsAll(chain), "and the same names");

        assertNotEquals(chain, stems,
                "the two orders differ; if they ever stop differing the sort in Api.settings is "
                        + "still correct and this test is the record of why it exists");

        // AND THE CHAIN'S ORDER IS THE RUN'S. Repair happens inside the module walk now, so the
        // hardening pins come after it: a module is pinned, bumped, compiled, repaired, and only
        // then hardened, because hardening polishes a module that already compiles.
        assertTrue(chain.indexOf("module-repair-step-planner") < chain.indexOf("after-pins-planner"),
                "repair runs before the hardening pins, inside the module walk");
        assertTrue(chain.indexOf("after-pins-verifier") < chain.indexOf("security-after-planner"),
                "and the whole module walk finishes before the repository is scored");
    }
}
