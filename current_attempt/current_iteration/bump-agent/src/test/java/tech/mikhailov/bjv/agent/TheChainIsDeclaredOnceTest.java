package tech.mikhailov.bjv.agent;

import com.deepagents.langchain4j.subagents.SubAgentDefinition;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE CHAIN EXISTED TWICE AND THE COPIES DRIFTED.
 *
 * <p>{@link Agents} built the agents and {@link Dashboard} carried a hand-typed array of the same
 * chain. When a stage was deleted, only one copy heard about it: the page went on advertising a
 * {@code prepare} stage with a {@code preparer} and a {@code prepare-critic} for hours after all
 * three were gone, and showed none of the agents added in their place. Nothing failed and no test
 * went red. A reader was simply told something untrue by the page whose whole job is to say what a
 * bump is doing.
 *
 * <p>This is the test that makes that impossible. It is not about behaviour; it is about two
 * declarations being one declaration.
 */
class TheChainIsDeclaredOnceTest {

    private static final List<SubAgentDefinition> DEFINED =
            Agents.forHop(new Hop(17, 21), Path.of("/tmp"));

    @Test
    void everyAgentTheChainNamesIsDefined() {
        Set<String> defined = new TreeSet<>(DEFINED.stream().map(SubAgentDefinition::name).toList());
        Set<String> missing = new TreeSet<>(Chain.agentNames());
        missing.removeAll(defined);

        assertTrue(missing.isEmpty(), "the chain names agents nobody built: " + missing);
    }

    @Test
    void everyAgentDefinedAppearsInTheChain() {
        // The direction that caught the stale page: an agent that exists but no stage runs is
        // invisible to a reader, and an agent a stage runs but nobody defined is a crash.
        Set<String> named = new TreeSet<>(Chain.agentNames());
        Set<String> orphans = new TreeSet<>(DEFINED.stream().map(SubAgentDefinition::name).toList());
        orphans.removeAll(named);

        assertTrue(orphans.isEmpty(), "defined but no stage runs them: " + orphans);
    }

    @Test
    void everyStageIsAtLeastPlanDoVerifyOrHonestlyDeterministic() {
        for (Chain.Stage s : Chain.stages()) {
            List<String> roles = s.steps().stream().map(Chain.Step::role).toList();
            if (roles.size() == 1) {
                assertEquals(List.of("doer"), roles, s.title() + " is a lone step, so it is a doer");
                assertFalse(s.steps().get(0).agent(),
                        s.title() + " is a lone AGENT: a doer with nobody planning or checking it");
                continue;
            }
            assertEquals(List.of("planner", "doer", "verifier"), roles,
                    s.title() + " is not plan, do, verify");
        }
    }

    @Test
    void everyAgentNameCarriesItsRole() {
        // The names are the vocabulary. A "critic" or a "-er" tells a reader nothing about where it
        // sits in its stage, and this chain had surveyor, preparer, bumper, troubleshooter and four
        // different words for the same job.
        for (String name : Chain.agentNames()) {
            assertTrue(name.endsWith("-planner") || name.endsWith("-doer")
                            || name.endsWith("-verifier"),
                    name + " does not say which of the three it is");
        }
    }

    @Test
    void aNestedStageNamesAStageThatExists() {
        Set<String> titles = new TreeSet<>(Chain.stages().stream()
                .map(Chain.Stage::title).toList());
        for (Chain.Stage s : Chain.stages()) {
            if (s.nested()) {
                assertTrue(titles.contains(s.within()),
                        s.title() + " is nested inside a stage that does not exist: " + s.within());
            }
        }
    }

    @Test
    void theOrderIsTheOrderTheBumpRunsThem() {
        List<String> titles = Chain.stages().stream().map(Chain.Stage::title).toList();

        assertEquals("survey", titles.get(0), "the survey is first");
        assertEquals("estimator", titles.get(titles.size() - 1), "and pricing is last");
        assertTrue(titles.indexOf("baseline") < titles.indexOf("modules"),
                "no baseline, no bump");
        assertTrue(titles.indexOf("security-before") < titles.indexOf("modules"),
                "the scan has to precede the work, or it is not the project's own state");
        assertTrue(titles.indexOf("before-pins") < titles.indexOf("bump"),
                "lombok moves before the JDK, or javac dies before anything else runs");
        assertTrue(titles.indexOf("bump") < titles.indexOf("after-pins"),
                "spring boot cannot resolve until the JDK has moved");
        assertTrue(titles.indexOf("modules") < titles.indexOf("gate"),
                "the gate judges finished work");
    }
}
