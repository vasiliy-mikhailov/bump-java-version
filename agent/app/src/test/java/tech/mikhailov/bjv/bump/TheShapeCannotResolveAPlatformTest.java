package tech.mikhailov.bjv.bump;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import tech.mikhailov.ratchet.flow.Agent;
import tech.mikhailov.ratchet.flow.Shape;
import tech.mikhailov.ratchet.record.Trace;
import tech.mikhailov.bjv.jvm.Modules;

/**
 * A SHAPE IS DRAWN BEFORE ANY MODULE EXISTS, SO NOTHING IN IT MAY ASK WHAT MANAGES ONE.
 *
 * <p>Every stage inside the module walk is now keyed by platform, and the obvious way to write
 * that is to resolve the platform where the module arrives, at the top of {@code moduleWalk}, and
 * build the nodes from it. That would work on every bump and take the dashboard down.
 *
 * <p>THE PATH IS EXACT AND IT IS NOT HYPOTHETICAL. {@code Flow.each().inside()} reports the body
 * built for a NULL item, because a shape is what the program can do rather than what one
 * repository made it do; {@link Bump#stages} builds a bump with no workspace, no runner, no trace
 * and no agents, purely to walk it, and Settings reads that walk on every request.
 * A resolve at construction time is therefore an {@code ExceptionInInitializerError} in a class
 * initialiser, which does not fail one request. It fails every request that class ever serves, for
 * the life of the process, including the pages that have nothing to do with prompts.
 *
 * <p>So the resolve lives in a node BODY, where a module exists and a run is happening, and the
 * walk holds a per-module array the body writes into. This is the test that says so. It fails if
 * someone later moves the resolve up, and the failure names the reason rather than a null pointer.
 */
class TheShapeCannotResolveAPlatformTest {

    @Test
    void theWalkCanBeBuiltForAModuleThatDoesNotExist() throws Exception {
        // THE CALL Flow.each MAKES TO DRAW THE PICTURE, made here directly. Nothing in the tree
        // this returns has run, so nothing may have needed a module, a workspace or an agent: the
        // bump it is built on has none of the three.
        Object bump = aBump();
        Method walk = Bump.class.getDeclaredMethod("moduleWalk", Modules.Module.class);
        walk.setAccessible(true);

        Agent module = assertDoesNotThrow(() -> (Agent) walk.invoke(bump, (Object) null),
                "moduleWalk(null) is how the shape is drawn, so it may not touch the module");

        assertEquals("module", module.name(), "and it is the walk's own body");
        assertEquals(List.of("platform", "before-pins", "bump", "module-gate", "after-pins"),
                module.inside().stream().map(Agent::name).toList(),
                "with the platform settled first, because every stage after it is keyed by it");
    }

    @Test
    void theStagesAndTheirAgentsAreReadableWithNoBumpRunning() {
        // WHAT THE PAGES ACTUALLY CALL. If a platform were resolved while these nodes were built,
        // this is the line that would throw, and it is reached from three different pages.
        List<Shape.Stage> stages = assertDoesNotThrow(Bump::stages,
                "the settings page and the bump strip read this before any bump exists");

        List<String> agents = Shape.agentNames(stages);
        for (String role : List.of("platform-planner", "platform-doer", "platform-verifier")) {
            assertTrue(agents.contains(role), role + " is not in the shape the pages read");
        }

        // AND THE TREE NAMES NONE OF THE PLATFORMS, which is the same fact from the other side. A
        // node that could name one would be a node that had resolved one.
        for (String agent : agents) {
            assertEquals(agent, Agents.stem(agent),
                    agent + " carries a platform, so something resolved one to draw the shape");
        }
    }

    // THE STATIC-INITIALISER CASE IS GONE RATHER THAN FIXED. It pinned the worst consequence of
    // resolving a platform during a shape walk: the legacy page read the walk into a static field,
    // so a null dereference here was not one failed request, it was an ExceptionInInitializerError
    // that took the class down. That page is deleted and Settings reads the walk per request, so
    // blast radius is a 500. The test above still holds the rule, which was never about the page.

    @Test
    void aModuleStartsAtTheFallbackRatherThanAtNothing() throws Exception {
        // THE HOLDER'S INITIAL VALUE IS AN ANSWER, NOT A GAP. A blank there would reach
        // Agents.named as before-pins-doer@ and throw "no agent called" on any path that reads it
        // before the detector has written, and the fallback is a real regime rather than a marker:
        // it owns its own conflicts, which is the safe thing to be wrong towards.
        var fallback = Bump.class.getDeclaredField("UNRESOLVED_PLATFORM");
        fallback.setAccessible(true);
        String word = (String) fallback.get(null);

        assertTrue(Managed.PLATFORMS.contains(word),
                "the fallback is one of the words the walk has prompts for: " + word);
        assertEquals(word, Managed.platformIn("an answer that names nothing at all"),
                "and it is derived from the reader rather than typed a second time");
    }

    private static Object aBump() throws Exception {
        var ctor = Bump.class.getDeclaredConstructor(Path.class, String.class, Trace.class);
        ctor.setAccessible(true);
        return ctor.newInstance(Path.of("."), "owner/repo|abc123|17|21", null);
    }
}
