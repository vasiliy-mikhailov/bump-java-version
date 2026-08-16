package tech.mikhailov.bjv.agent;

import com.deepagents.langchain4j.subagents.SubAgentDefinition;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE CHAIN EXISTED TWICE AND THE COPIES DRIFTED. NOW IT EXISTS ONCE, AS THE PROGRAM.
 *
 * <p>{@link Agents} built the agents and {@link Dashboard} carried a hand-typed array of the same
 * chain. When a stage was deleted, only one copy heard about it: the page went on advertising a
 * {@code prepare} stage with a {@code preparer} and a {@code prepare-critic} for hours after all
 * three were gone, and showed none of the agents added in their place. Nothing failed and no test
 * went red. A reader was simply told something untrue by the page whose whole job is to say what a
 * bump is doing.
 *
 * <p>A file called {@code Chain} was the first answer to that: one declaration, with this test
 * binding it to the agents that existed. It worked, and it was still a second copy of the program,
 * so it drifted in its turn. It said pricing came last months after the estimator moved ahead of
 * the arguer, and it said a module may order twelve repair steps when the program allows
 * thirty-six.
 *
 * <p>So the declaration is gone. {@link Bump#stages} walks the tree the harness runs and this test
 * binds THAT to the agents: every agent a node names must be defined, every definition must be
 * named by a node, and the facts a page needs must be on the node they are true of. It is still not
 * about behaviour; it is about there being one declaration, and about that one being the program.
 */
class TheChainIsDeclaredOnceTest {

    private static final List<SubAgentDefinition> DEFINED =
            Agents.forHop(new Hop(17, 21), Path.of("/tmp"));

    /** The stages, walked off the tree a bump runs. There is no other list of them. */
    private static final List<Shape.Stage> STAGES = Bump.stages();

    private static final List<String> NAMED = Shape.agentNames(STAGES);

    @Test
    void everyAgentTheTreeReachesIsDefined() {
        Set<String> defined = new TreeSet<>(DEFINED.stream().map(SubAgentDefinition::name).toList());
        Set<String> missing = new TreeSet<>(NAMED);
        missing.removeAll(defined);

        assertTrue(missing.isEmpty(), "the tree names agents nobody built: " + missing);
    }

    @Test
    void everyAgentDefinedIsReachedByAStage() {
        // The direction that caught the stale page: an agent that exists but no stage runs is
        // invisible to a reader, and an agent a stage runs but nobody defined is a crash.
        //
        // IT ALSO CAUGHT THE LAST GAP THE DECLARATION LEFT. The three step agents were reached by
        // ordinary code inside module-repair, so the picture drew that stage as a leaf and only the
        // declaration knew they existed; deleting it would have dropped their stage, their nesting
        // and the only written record of the budget they spend. The campaign is a node now, which
        // is what makes this assertion hold with nothing beside the program to hold it up.
        Set<String> named = new TreeSet<>(NAMED);
        Set<String> orphans = new TreeSet<>(DEFINED.stream().map(SubAgentDefinition::name).toList());
        orphans.removeAll(named);

        assertTrue(orphans.isEmpty(), "defined but no stage runs them: " + orphans);
        assertEquals(34, NAMED.size(), "and the whole chain is thirty-four");
    }

    @Test
    void everyStageIsAtLeastPlanDoVerifyOrHonestlyDeterministic() {
        for (Shape.Stage s : STAGES) {
            List<String> roles = s.steps().stream().map(Shape.Step::role).toList();
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
        for (String name : NAMED) {
            assertTrue(name.endsWith("-planner") || name.endsWith("-doer")
                            || name.endsWith("-verifier"),
                    name + " does not say which of the three it is");
        }
    }

    @Test
    void aNestedStageNamesAStageThatExists() {
        Set<String> titles = new TreeSet<>(STAGES.stream().map(Shape.Stage::title).toList());
        for (Shape.Stage s : STAGES) {
            if (s.nested()) {
                assertTrue(titles.contains(s.within()),
                        s.title() + " is nested inside a stage that does not exist: " + s.within());
            }
        }
    }

    @Test
    void theOrderIsTheOrderTheBumpRunsThem() {
        // IT CANNOT BE ANYTHING ELSE NOW, which is the point of the deletion: this list is a walk
        // of the tree that executes. What is still worth asserting is the constraints, because
        // those are facts about migration rather than about the data structure, and a reordering
        // that broke one of them would compile and run.
        List<String> titles = STAGES.stream().map(Shape.Stage::title).toList();

        assertEquals("survey", titles.get(0), "the survey is first");
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

        // PRICED BEFORE THE ARGUMENT, and this is where the declaration was stale for months. The
        // estimator prices the work that LANDED and an argument lands nothing, so it runs first;
        // the arguer only ever speaks about a bump the gate never settled. The old declaration
        // ended with the estimator, which is where it ran before the closers became selection, and
        // the settings page was sorted by that declaration, so it showed six agents in an order no
        // bump had used for months.
        assertTrue(titles.indexOf("estimator") < titles.indexOf("verdict"),
                "the estimator prices what landed, and the arguer argues about what did not");
        assertEquals("verdict", titles.get(titles.size() - 1), "and the argument is the last word");
    }

    @Test
    void howOftenAStageRunsIsDeclaredWhereItRuns() {
        // NESTING SAYS WHERE, NOT HOW MANY TIMES, and a reader will answer the second question from
        // the first unless told. Under modules only the walk goes module by module; both pin phases
        // are inside that walk, and the modules planner and verifier are not: they plan and judge
        // one pass over the whole repository.
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
        assertTrue(repeatsOf("security-after").contains("green gate"), repeatsOf("security-after"));

        // THE MIRROR OF security-after, and both are selection in the tree rather than prose about
        // it: the scan runs on a green gate, the arguer on one that never went green, and a reader
        // can see which from the shape alone.
        assertTrue(repeatsOf("verdict").contains("never went green"), repeatsOf("verdict"));

        // A stage that runs once and always says nothing, because a note on every row is a note
        // nobody reads.
        assertEquals("", repeatsOf("survey"));
        assertEquals("", repeatsOf("estimator"),
                "the estimator prices every bump, green or not");
    }

    @Test
    void theStepCeilingIsTheArithmeticRatherThanANumberSomebodyTyped() throws Exception {
        // THE NUMBER THAT WAS TYPED WAS A THIRD OF THE TRUTH. Six steps per campaign and two
        // campaigns is twelve, which is what ONE module-repair may order, and twelve is what the
        // declaration said a module may order. But module-repair is the second half of a
        // module-gate TURN, and a turn where a repair landed opens the next one, so a module that
        // keeps almost compiling reaches it up to MODULE_TURNS times: thirty-six, which is the
        // number REPAIR_BUDGET's own javadoc works from.
        //
        // Nothing was ever over-spent, because the bump's 192 caps the lot and that half of the
        // sentence was right. What a reader took from it was a module ceiling three times smaller
        // than the program allows, in the one place the ceiling was written down at all. So it is
        // not written down any more: the label is built from the three constants that bound it, and
        // this is the arithmetic, held against them from outside.
        int steps = constant("STEPS");
        int turns = constant("MODULE_TURNS");
        int campaigns = constant("REASK") + 1;

        assertEquals("up to " + turns * campaigns * steps + " per module, "
                        + constant("REPAIR_BUDGET") + " per bump",
                repeatsOf("module-repair-step"),
                "the ceiling a page prints is the ceiling the constants set");

        if (System.getenv("BJV_STEPS") == null && System.getenv("BJV_MODULE_TURNS") == null
                && System.getenv("BJV_REPAIR_BUDGET") == null) {
            assertEquals(12, campaigns * steps, "twelve is what ONE module-repair may order");
            assertEquals("up to 36 per module, 192 per bump", repeatsOf("module-repair-step"),
                    "and a module may reach module-repair once per gate turn, which is what the"
                            + " declaration's number left out");
        }
    }

    private static int constant(String name) throws Exception {
        Field f = Bump.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.getInt(null);
    }

    private static String repeatsOf(String title) {
        return stage(title).repeats();
    }

    private static String readsOf(String title) {
        return stage(title).reads();
    }

    private static Shape.Stage stage(String title) {
        return STAGES.stream().filter(s -> s.title().equals(title)).findFirst().orElseThrow();
    }

    @Test
    void eachPinPhaseNamesTheHalfOfTheListItWorksTo() {
        // THE SAME SPLIT THE LISTS ARE KEPT IN, said on the node as well. What enables the bump is
        // a precondition and below one of those the bump does not happen; what hardens the result
        // is polish on a project that already builds and tests green. Naming it here is what
        // connects the two pages: a reader looking at before-pins should not have to know that the
        // phase runs before the JDK moves in order to work out which list it is acting on.
        assertEquals("enables", readsOf("before-pins"));
        assertEquals("hardens", readsOf("after-pins"));

        // The JDK move itself reads no list. It is the thing the two lists are either side of, and
        // giving it one would make the split about timing again.
        assertEquals("", readsOf("bump"));

        // AND THE NAMES ARE THE FILES'. A node saying "hardens" while the resource is called
        // something else is the two-readers-of-one-string failure this codebase keeps paying for.
        for (String part : Bom.parts()) {
            assertFalse(Bom.of(new Hop(17, 21), part).isEmpty(), part + " names a real list");
        }
        for (Shape.Stage s : STAGES) {
            assertTrue(s.reads().isEmpty() || Bom.parts().contains(s.reads()),
                    s.title() + " reads a list that does not exist: " + s.reads());
        }
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
        // again: every agent a node names must resolve through the same lookup, and a factory
        // that restates a tool set instead of asking for one will show up as a name the catalogue
        // does not carry.
        List<String> catalogued = Agents.forHop(new Hop(17, 21), Path.of("/tmp")).stream()
                .map(SubAgentDefinition::name)
                .toList();

        for (String name : NAMED) {
            assertTrue(catalogued.contains(name),
                    name + " runs but is not in the catalogue, so nothing can describe it");
        }
        for (String name : catalogued) {
            assertTrue(NAMED.contains(name),
                    name + " is catalogued but no stage of the tree reaches it");
        }
    }
}
