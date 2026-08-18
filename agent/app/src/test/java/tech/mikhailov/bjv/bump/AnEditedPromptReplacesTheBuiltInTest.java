package tech.mikhailov.bjv.bump;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import tech.mikhailov.bjv.engine.Prompts;

/**
 * AN EDIT REPLACES THE BUILT-IN ENTIRELY, AND REVERT PUTS IT BACK BY DELETING THE EDIT.
 *
 * <p>There is no merge. A prompt half from the code and half from a box is a prompt nobody can read
 * in one place, and reading it in one place is how anybody works out why an agent did what it did.
 *
 * <p>PER AGENT AND PER HOP, because the same agent is a different agent on a different hop: an
 * 8-to-11 pin planner is not shown the Kotlin rule for JDK 25, and the text on the settings page is
 * the text for the hop being looked at, floors and all. One override across every hop would take an
 * edit made against 17-to-21's floors and hand it to a bump that cannot reach them.
 */
class AnEditedPromptReplacesTheBuiltInTest {

    private static final String TO_21 = new Hop(17, 21).key();
    private static final String TO_11 = new Hop(8, 11).key();

    @Test
    void anEditIsWhatTheAgentGets(@TempDir Path root) throws IOException {
        Prompts.save(root, "before-pins-planner", TO_21, "only this");

        assertEquals("only this", Prompts.override(root, "before-pins-planner", TO_21));
        assertTrue(Prompts.edited(root, "before-pins-planner", TO_21));
    }

    @Test
    void revertDeletesTheEditRatherThanRestoringAnything(@TempDir Path root) throws IOException {
        // The built-in was never gone: it lives in the code and the store only ever holds an edit.
        Prompts.save(root, "bump-doer", TO_21, "edited");

        Prompts.revert(root, "bump-doer", TO_21);

        assertFalse(Prompts.edited(root, "bump-doer", TO_21));
        assertEquals("", Prompts.override(root, "bump-doer", TO_21));
    }

    @Test
    void revertingSomethingNeverEditedIsNotAnError(@TempDir Path root) throws IOException {
        Prompts.revert(root, "bump-doer", TO_21);

        assertFalse(Prompts.edited(root, "bump-doer", TO_21));
    }

    @Test
    void anEditOnOneHopDoesNotReachAnother(@TempDir Path root) throws IOException {
        // An edit made against 17-to-21's floors would be nonsense to a bump that cannot reach them.
        Prompts.save(root, "after-pins-doer", TO_21, "for twenty-one");

        assertEquals("for twenty-one", Prompts.override(root, "after-pins-doer", TO_21));
        assertEquals("", Prompts.override(root, "after-pins-doer", TO_11));
    }

    @Test
    void anEmptyFileIsNotAnEmptyPrompt(@TempDir Path root) throws IOException {
        // A save that went wrong or a revert that half happened. An agent handed nothing to do does
        // something arbitrary, which is worse than doing what the code says.
        Prompts.save(root, "verdict-doer", TO_21, "x");
        Files.writeString(root.resolve("17-21").resolve("verdict-doer.txt"), "   \n");

        assertEquals("", Prompts.override(root, "verdict-doer", TO_21));
    }

    @Test
    void aNameThatIsNotAnAgentNameCannotWriteOutsideTheStore(@TempDir Path root) throws IOException {
        // The name arrives from a request. Every real one is lower-case letters and hyphens.
        for (String hostile : new String[] {"../escape", "a/b", "..", "", "Bump-Doer", "x.txt"}) {
            assertFalse(Prompts.edited(root, hostile, TO_21), hostile);
            assertEquals("", Prompts.override(root, hostile, TO_21), hostile);
        }
        assertTrue(Files.notExists(root.resolve("escape.txt")));
        assertTrue(Files.notExists(root.getParent().resolve("escape.txt")));
    }

    @Test
    void aPlatformKeyedAgentIsStoredAsItsOwnPrompt(@TempDir Path root) throws IOException {
        // FOURTEEN OF THESE AGENTS EXIST ONCE PER PLATFORM and carry it in the name, because a pin
        // doer written for a module Spring Boot manages is a different agent from the one written
        // for a module nothing manages: they are handed different text and they are edited apart.
        // The store had to admit the name, and nothing else about it changed.
        Prompts.save(root, "before-pins-doer@spring-boot", TO_21, "for boot");

        assertEquals("for boot", Prompts.override(root, "before-pins-doer@spring-boot", TO_21));
        assertEquals("", Prompts.override(root, "before-pins-doer@adhoc", TO_21),
                "and an edit to one regime is not an edit to another");
        assertEquals("", Prompts.override(root, "before-pins-doer", TO_21),
                "nor to a bare name no bump ever asks for");

        // The tail admits what the name admits and no separator, so it still cannot leave the
        // store: these are the same hostile shapes the test below rejects, wearing an @.
        for (String hostile : new String[] {"a@../escape", "a@b/c", "a@", "@b", "a@B", "a@b@c"}) {
            assertEquals("", Prompts.override(root, hostile, TO_21), hostile);
        }
    }

    @Test
    void theEditedListIsWhatTheHeaderCounts(@TempDir Path root) throws IOException {
        Prompts.save(root, "survey-planner", TO_21, "a");
        Prompts.save(root, "bump-doer", TO_21, "b");
        Prompts.save(root, "bump-doer", TO_11, "c");

        assertEquals(2, Prompts.editedOn(root, TO_21).size());
        assertEquals(1, Prompts.editedOn(root, TO_11).size());
        assertTrue(Prompts.editedOn(root, TO_21).contains("survey-planner"));
    }

    @Test
    void savingIsARenameSoAReaderNeverSeesHalfAPrompt(@TempDir Path root) throws IOException {
        // An agent reads this file while the page may be writing it. Half a prompt is worse than
        // either version of it, and it is the failure that leaves no trace of having happened.
        Prompts.save(root, "step-doer", TO_21, "first");
        Prompts.save(root, "step-doer", TO_21, "second");

        assertEquals("second", Prompts.override(root, "step-doer", TO_21));
        assertTrue(Files.notExists(root.resolve("17-21").resolve("step-doer.txt.staged")),
                "no litter left beside it");
    }

    @Test
    void withNoStoreConfiguredNothingIsEverEdited() {
        // The agent runs in a container that may not have the store mounted at all, and "I cannot
        // see the overrides" must read as "there are none", not as a crash mid-bump.
        assertEquals("", Prompts.override(null, "bump-doer", TO_21));
        assertFalse(Prompts.edited(null, "bump-doer", TO_21));
        assertTrue(Prompts.editedOn(null, TO_21).isEmpty());
    }
}
