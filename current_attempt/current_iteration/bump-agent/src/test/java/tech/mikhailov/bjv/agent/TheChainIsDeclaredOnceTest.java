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

    @Test
    void howOftenAStageRunsIsDeclaredWhereItRuns() {
        // NESTING SAYS WHERE, NOT HOW MANY TIMES, and a reader will answer the second question from
        // the first unless told. Under modules only bumpPhase walks the module list (Bump.java:673);
        // pinPhase runs once for the repository and hands the module list to its agents as text.
        // Three peers inside a stage whose deterministic step is called "the three passes" read as
        // three per-module passes, and two of them are not.
        // THE COUNT IS ON THE BLOCK, NOT REPEATED ON EVERY LINE IN IT. modules says "per module"
        // and everything nested under it inherits that; saying it again on each of before-pins,
        // bump and after-pins is three chances to disagree with the one place it is decided.
        assertEquals("per module", repeatsOf("modules"));
        assertEquals("", repeatsOf("bump"));
        assertEquals("", repeatsOf("before-pins"));
        assertEquals("", repeatsOf("after-pins"));

        // Repair lives inside the module walk. The module-gate compiles one module and the repair
        // runs only when that is red, which is the same shape the repository gate used to have and
        // the reason it no longer needs one: the turns were repair's, and repair has moved.
        assertTrue(repeatsOf("module-gate").contains("until green"), repeatsOf("module-gate"));
        assertTrue(repeatsOf("module-repair").contains("module-gate is red"),
                repeatsOf("module-repair"));
        // SIX PER CAMPAIGN AND TWO CAMPAIGNS, so twelve, and the bump's own allowance caps the
        // lot. The label read "up to 6" and meant half of one module's worst case, which is the
        // kind of number a reader multiplies by the module count and gets a quarter of the answer.
        assertTrue(repeatsOf("module-repair-step").contains("12 per module"),
                repeatsOf("module-repair-step"));
        assertTrue(repeatsOf("module-repair-step").contains("192 per bump"),
                "and the ceiling that actually binds is the bump's, not the module's");
        assertTrue(repeatsOf("security-after").contains("green gate"), repeatsOf("security-after"));

        // THE MIRROR OF security-after. A green gate returns PASS from inside the turn loop and
        // never reaches the closers, so the arguer only ever argues bumps that failed: it is the
        // stage that says what went wrong, not the stage that says what happened.
        assertTrue(repeatsOf("verdict").contains("never went green"), repeatsOf("verdict"));

        // A stage that runs once and always says nothing, because a note on every row is a note
        // nobody reads.
        assertEquals("", repeatsOf("survey"));
        assertEquals("", repeatsOf("estimator"),
                "the estimator prices every bump, green or not");
    }

    private static String repeatsOf(String title) {
        return Chain.stages().stream()
                .filter(s -> s.title().equals(title))
                .findFirst().orElseThrow()
                .repeats();
    }

    @Test
    void eachPinPhaseNamesTheHalfOfTheListItWorksTo() {
        // THE SAME SPLIT THE LISTS ARE KEPT IN, said in the chain as well. What enables the bump is
        // a precondition and below one of those the bump does not happen; what hardens the result
        // is polish on a project that already builds and tests green. Naming it here is what
        // connects the two pages: a reader looking at before-pins should not have to know that the
        // phase runs before the JDK moves in order to work out which list it is acting on.
        assertEquals("enables", readsOf("before-pins"));
        assertEquals("hardens", readsOf("after-pins"));

        // The JDK move itself reads no list. It is the thing the two lists are either side of, and
        // giving it one would make the split about timing again.
        assertEquals("", readsOf("bump"));

        // AND THE NAMES ARE THE FILES'. A chain saying "hardens" while the resource is called
        // something else is the two-readers-of-one-string failure this codebase keeps paying for.
        for (String part : Bom.parts()) {
            assertFalse(Bom.of(new Hop(17, 21), part).isEmpty(), part + " names a real list");
        }
        for (Chain.Stage s : Chain.stages()) {
            assertTrue(s.reads().isEmpty() || Bom.parts().contains(s.reads()),
                    s.title() + " reads a list that does not exist: " + s.reads());
        }
    }

    private static String readsOf(String title) {
        return Chain.stages().stream()
                .filter(s -> s.title().equals(title))
                .findFirst().orElseThrow()
                .reads();
    }

    @Test
    void theCatalogueAndTheFactoryCannotDisagreeAboutTools() {
        // THEY DID, ON SIX AGENTS, AND THE PAGE WAS THE ONE THAT WAS WRONG. definitions() gave
        // before-pins-planner, before-pins-verifier, after-pins-planner, after-pins-verifier,
        // modules-planner and modules-verifier the reading tool set, while the factory that
        // actually runs them handed out Tools.judging. The settings page reads the catalogue, so
        // it described a tool surface those agents did not have, and their own prompt tells them
        // to call declared_versions and build_system, which only judging carries.
        //
        // The factories derive from the catalogue now. This is the thing that stops it happening
        // again: every agent the chain names must resolve through the same lookup, and a factory
        // that restates a tool set instead of asking for one will show up as a name the catalogue
        // does not carry.
        List<String> catalogued = Agents.forHop(new Hop(17, 21), Path.of("/tmp")).stream()
                .map(SubAgentDefinition::name)
                .toList();

        for (String name : Chain.agentNames()) {
            assertTrue(catalogued.contains(name),
                    name + " runs but is not in the catalogue, so nothing can describe it");
        }
        for (String name : catalogued) {
            assertTrue(Chain.agentNames().contains(name),
                    name + " is catalogued but the chain never reaches it");
        }
    }
}
