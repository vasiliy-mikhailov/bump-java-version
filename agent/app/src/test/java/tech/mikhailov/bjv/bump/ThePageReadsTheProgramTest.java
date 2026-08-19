package tech.mikhailov.bjv.bump;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import tech.mikhailov.ratchet.flow.Shape;

/**
 * THE PAGES READ THE PROGRAM NOW, AND THIS IS WHAT THAT HAD TO KEEP.
 *
 * <p>The settings page and the bump strip took five things from a declaration that sat beside
 * {@link Bump}: the order of the agents, the nesting of the stages, how often each stage runs,
 * which bill of materials each pin phase works to, and the name of the step that IS a block. The
 * declaration is deleted. Every one of those is still a fact a reader needs, so every one of them
 * moved onto the node it is true of, and this is the test that says the page still gets them.
 *
 * <p>IT ASSERTS THE ENDPOINT BY RUNNING IT rather than by reading the code that builds it. The
 * failure this whole line of work is about is a page that says something untrue while every test
 * passes, and a test that checks the same field the page-builder reads is a test that would agree
 * with a page nobody can load.
 *
 * <p>THE NESTING RULE IS A REAL CONSTRAINT AND NOT A PREFERENCE. The settings page groups agents by
 * consecutive stage and then recurses by title through the groups that HAVE agents. A stage whose
 * {@code within} names something with no agent in it is a stage nothing draws: it and everything
 * under it vanish from the page, silently, with the endpoint still returning every agent it has.
 */
class ThePageReadsTheProgramTest {

    private static final List<Shape.Stage> STAGES = Bump.stages();

    private static final List<String> NAMED = Shape.agentNames(STAGES);

    private static String settings() throws Exception {
        Path results = Files.createTempDirectory("bjv-settings");
        // Settings is package-private in tech.mikhailov.bjv.web, where every type is, so
        // it is named here rather than imported.
        Class<?> page = Class.forName("tech.mikhailov.bjv.web.Settings");
        var made = page.getDeclaredConstructor(Path.class);
        made.setAccessible(true);
        Method agents = page.getDeclaredMethod("agents", String.class);
        agents.setAccessible(true);
        return (String) agents.invoke(made.newInstance(results), "17-21");
    }

    @Test
    void theSettingsPageAnswersEveryAgentInTheOrderTheBumpRunsThem() throws Exception {
        // Measured on the live page once, before any of this: unsorted, after-pins arrived after
        // the repair agents and modules-verifier after that, so the module block drew missing its
        // third pass and the loop closed in the wrong place. The order is the tree's walk now, so
        // the page cannot be sorted by anything the bump does not do.
        String json = settings();

        List<String> named = new ArrayList<>();
        // Only at the head of an object or after a field, so a prompt quoting the word does not
        // count: escaped text carries a backslash before the quote and cannot match.
        Matcher m = Pattern.compile("[{,]\"name\":\"([^\"]+)\"").matcher(json);
        while (m.find()) {
            named.add(m.group(1));
        }

        // SIXTY-FIVE OBJECTS FOR THIRTY-SEVEN NAMES, and the difference is the module walk: the
        // fourteen agents inside it exist once per platform, because a pin doer told to raise an
        // artifact Spring Boot manages and one told to raise an artifact nothing manages are given
        // opposite instructions.
        assertEquals(Agents.forHop(new Hop(17, 21), Path.of("/tmp")).size(), named.size(),
                "every agent the catalogue holds, one object each");

        // AND THE ORDER IS STILL THE TREE'S. The page sorts on the stem, because a shape drawn
        // before any module is looked at cannot name a platform, so what has to match the walk is
        // the sequence of stems. A platform-keyed agent whose three copies were split across the
        // page would put a stage's third pass under the next stage's heading, which is the exact
        // failure this test was written for.
        List<String> stems = named.stream().map(Agents::stem).distinct().toList();
        assertEquals(NAMED, stems, "in the order the bump reaches them");

        // AND THE STALE EDGE IS GONE. The page used to list the arguer's three agents before the
        // estimator's, because the declaration it sorted by still ended with the estimator, while
        // every bump had priced before arguing for months.
        assertTrue(named.indexOf("estimator-planner") < named.indexOf("verdict-planner"),
                "the page prices before it argues, because the bump does");
    }

    @Test
    void everyFactTheDeclarationCarriedStillReachesThePage() throws Exception {
        // WHAT THE PAGE ACTUALLY SHOWS, quoted from the endpoint rather than described. Each of
        // these is a sentence a reader sees beside a stage heading: how often it runs, which half
        // of the bill of materials it works to, and what the deterministic step inside it is
        // called. None of the three can be walked off a shape, so each moved onto a node, and this
        // is where a move that compiled but landed nowhere would show up.
        String json = settings();

        assertTrue(json.contains("\"repeats\":\"per module\""),
                "the modules block still says how often the walk below it runs");
        assertTrue(json.contains("\"repeats\":\"only when the module-gate is red\""),
                "and repair still says it does not always happen");
        assertTrue(json.contains("\"repeats\":\"only after a green gate\""),
                "and the after-scan still says what it waits for");
        String ceiling = stage("module-repair-step").repeats();
        assertFalse(ceiling.isBlank(), "the step campaign has a ceiling to carry");
        assertTrue(json.contains("\"repeats\":\"" + ceiling + "\""),
                "and the page still prints it: " + ceiling);

        assertTrue(json.contains("\"reads\":\"enables\""), "before-pins works to the enabling list");
        assertTrue(json.contains("\"reads\":\"hardens\""), "after-pins to the hardening one");

        assertTrue(json.contains("\"loop\":\"the module walk\""),
                "the modules block still names the step that is its doing");
        assertTrue(json.contains("\"loop\":\"a campaign of steps\""),
                "and so does the repair block");
    }

    @Test
    void everyNestedStageHangsUnderAStageThePageCanDraw() {
        // THE RULE THE PAGE IMPOSES, asserted where it can fail loudly. Two nodes sit between a
        // module-scoped stage and the modules stage: the walk's own body, which names no step at
        // all, and the module gate, which names a step and prompts nobody. Neither can be a parent
        // on this page: it recurses by title through the groups that have agents, so a stage hung
        // under either would be dropped along with everything below it.
        Set<String> speaking = new TreeSet<>(STAGES.stream()
                .filter(Shape.Stage::speaks).map(Shape.Stage::title).toList());

        int nested = 0;
        for (Shape.Stage s : STAGES) {
            if (!s.nested()) {
                continue;
            }
            nested++;
            assertTrue(speaking.contains(s.within()),
                    s.title() + " hangs under " + s.within() + ", which prompts nobody, so the"
                            + " settings page draws neither it nor anything under it");
        }
        // COUNTED, so that a walk which quietly stopped finding stages reads as a failure rather
        // than as agreement. A skip list and an empty list look identical from the outside.
        assertEquals(7, nested,
                "six stages inside the module walk, and the step campaign");
    }

    @Test
    void theStagesOfOneModulesTurnHangUnderModulesAndTheStepsUnderTheirCampaign() {
        for (String title : List.of("platform", "before-pins", "bump", "module-gate",
                "module-repair", "after-pins")) {
            assertEquals("modules", stage(title).within(),
                    title + " is part of what the modules stage does");
        }
        assertEquals("module-repair", stage("module-repair-step").within(),
                "and the steps belong to the campaign that orders them");

        // THE WALK'S OWN BODY IS NOT A STAGE. It is a scope: the node the walk enters once per
        // module, which is what "per module" on the stage above it already says. Drawing it would
        // put a heading on the page with nothing in it.
        assertTrue(STAGES.stream().noneMatch(s -> s.title().equals("module")),
                "the walk's body names no step, so it is scope rather than stage");
        assertEquals("per module", stage("modules").repeats(),
                "and what it means is said on the block, once");
    }

    @Test
    void everyStageIsOneUnbrokenRunOfAgents() {
        // THE PAGE GROUPS BY CONSECUTIVE STAGE. It walks the agents in the order the endpoint hands
        // them back and starts a new group whenever the stage name changes, so a stage whose agents
        // are interleaved with another's is drawn twice, under the same heading, with its verifier
        // in the wrong block. That is what an unsorted factory order did to the module block.
        Set<String> seen = new LinkedHashSet<>();
        String last = "";
        for (String agent : NAMED) {
            String stage = stageOf(agent);
            if (stage.equals(last)) {
                continue;
            }
            assertTrue(seen.add(stage), stage + " comes back after " + last
                    + ", so the page draws it as two stages of the same name");
            last = stage;
        }
    }

    // THE SPINE TEST WENT WITH THE PAGE IT GUARDED. It read a static String[][] CHAIN out of the
    // legacy dashboard and checked the arc it drew matched the top of the tree. That page is
    // deleted; the client draws the same spine from Bump.stages() over the API, which the tests
    // above already hold to the program.

    private static String stageOf(String agent) {
        return STAGES.stream()
                .filter(s -> s.steps().stream().anyMatch(step -> step.name().equals(agent)))
                .findFirst().orElseThrow()
                .title();
    }

    private static Shape.Stage stage(String title) {
        return STAGES.stream().filter(s -> s.title().equals(title)).findFirst().orElseThrow();
    }
}
