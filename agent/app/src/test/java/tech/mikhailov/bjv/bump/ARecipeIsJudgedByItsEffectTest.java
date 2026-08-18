package tech.mikhailov.bjv.bump;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import tech.mikhailov.bjv.engine.Trace;
import tech.mikhailov.bjv.jvm.Rewrites;

/**
 * A RECIPE RUN IS JUDGED BY WHAT IT DID, NOT BY WHAT IT SAID.
 *
 * <p>This file used to assert that the harness recognised {@code Recipe class X cannot be found}
 * and reported it as a failure. That was the right instinct and the wrong mechanism, and the corpus
 * measured how wrong: across every trace, 87 runs came back with "Recipe validation errors
 * detected", 80 with a required argument missing, and 13 with the class not found. All exited 0.
 * The harness recognised the third wording and passed the other two straight through as success,
 * so agents were still being told their edit had landed when nothing had.
 *
 * <p>Matching wordings is unbounded: there is always another one, and the next OpenRewrite release
 * can add it. Whether the working tree MOVED is a single fact that covers all of them, including
 * failures nobody has met, and it costs no knowledge of the plugin's vocabulary. What to do about
 * it belongs to the plan-do-verify loop, which already re-reads the build files; the tool's only
 * job is to stop reporting the opposite of what happened.
 */
class ARecipeIsJudgedByItsEffectTest {

    private static final String LOG = "recipe run rc=0\n[INFO] BUILD SUCCESS\n";

    @Test
    void anUnchangedTreeSaysSoBeforeAnythingElse() {
        String said = Rewrites.reported("same", "same", LOG);

        // FIRST, because an agent reads the top of a tool result and the exit code is further down
        // saying the opposite.
        assertTrue(said.startsWith("NOTHING CHANGED IN THE WORKING TREE"), said);
        assertTrue(said.contains(LOG), "and the log still travels, so the reason is readable");
    }

    @Test
    void aChangedTreeSaysThatInstead() {
        String said = Rewrites.reported("", " M pom.xml", LOG);

        assertTrue(said.startsWith("the working tree changed"), said);
        assertFalse(said.contains("NOTHING CHANGED"), said);
    }

    @Test
    void itDoesNotCareWhyNothingHappened() {
        // The three wordings this corpus has actually produced, and one invented to stand for the
        // ones it has not. None of them is matched; all of them are caught.
        for (String reason : new String[] {
                "recipe run rc=0\n[ERROR] Recipe class org.openrewrite.maven.Nope cannot be found",
                "recipe run rc=0\n[ERROR] Recipe validation errors detected as part of one or more "
                        + "activeRecipe(s). Execution will continue regardless.",
                "recipe run rc=0\n[ERROR] ChangePropertyValue.newValue: is required",
                "recipe run rc=0\n[WARN] a wording no one has written yet",
        }) {
            assertTrue(Rewrites.reported("x", "x", reason).startsWith("NOTHING CHANGED"),
                    "not recognised, but caught: " + reason);
        }
    }

    @Test
    void aRecipeThatRanCleanlyAndMatchedNothingIsAlsoCaught() {
        // NOT AN ERROR ANYWHERE IN THE LOG, and still nothing to show for it: a correctly named
        // recipe whose pattern matched no file. No error-string check could ever have seen this
        // one, which is the argument for measuring the effect rather than the vocabulary.
        String clean = "recipe run rc=0\n[INFO] Applied recipe com.bjv.Bump\n[INFO] BUILD SUCCESS";

        assertTrue(Rewrites.reported("tree", "tree", clean).startsWith("NOTHING CHANGED"), clean);
    }

    @Test
    void theToolDescriptionStillNamesTheRecipesAnAgentNeeds() {
        // Unchanged by any of the above: the commonest cause of a no-op run is a guessed name, and
        // the cure for that is the list, not the check.
        String description = Tools.pinning(java.nio.file.Path.of("/tmp"), null, null, "17",
                        new NoTrace(), "before-pins-doer")
                .keySet().stream()
                .filter(t -> t.name().equals("apply_recipe"))
                .findFirst().orElseThrow()
                .description();

        assertTrue(description.contains("AddManagedDependency"), description);
        assertTrue(description.contains("ChangePropertyValue"), description);
        assertTrue(description.contains("UpgradeSpringBoot_3_5"), description);
    }

    private record NoTrace() implements Trace {
        public void asked(String a, String p, String r) {
        }

        public void applied(String s, String w) {
        }

        public void tool(String a, String t, String args, String result) {
        }

        public void thought(String f, String t, String c) {
        }

        public void built(String phase, Trace.Outcome r) {
        }

        public void settled(String b, String s, String w, boolean x, boolean y) {
        }

        public void failed(String b, Throwable c) {
        }

        public void progress(String b, String n) {
        }

        public void priced(String b, String m, String i) {
        }
    }
}
