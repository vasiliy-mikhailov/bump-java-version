package tech.mikhailov.bjv.agent;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A RECIPE THAT DOES NOT EXIST IS NOT A SUCCESSFUL RUN.
 *
 * <p>OpenRewrite skips a recipe it cannot resolve, prints the reason, says "Execution will continue
 * regardless", and exits 0. Reported as {@code rc=0}, an agent is told its edit worked, sees the
 * workspace unchanged, and tries the same thing again.
 *
 * <p>That is not hypothetical. On {@code 0xiaoyu/XiaoYu} an agent needed to pin a version in
 * dependencyManagement, was given a tool description listing six recipes and none that does it, and
 * constructed {@code org.openrewrite.maven.AddDependencyManagementDependency} — a plausible name for
 * a real concept. It does not exist; the real one is {@code AddManagedDependency}. Four calls, 24
 * error lines, every one reported as success, and the bump spent its gate turns on a wall nothing
 * had touched.
 *
 * <p>The plugin's exact wording is reproduced below because it is what this parses, and confirmed
 * against the real plugin before this test was written:
 *
 * <pre>
 * [ERROR] Recipe validation error in com.bjv.Check for property
 *         org.openrewrite.maven.AddDependencyManagementDependency:
 *         Recipe class org.openrewrite.maven.AddDependencyManagementDependency cannot be found
 * [ERROR] Recipe validation errors detected as part of one or more activeRecipe(s).
 *         Execution will continue regardless.
 * [INFO] BUILD SUCCESS
 * </pre>
 */
class AMissingRecipeIsNotASuccessTest {

    private static String unknown(String output) throws Exception {
        Method m = Migrate.class.getDeclaredMethod("unknownRecipes", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, output);
    }

    @Test
    void itNamesTheRecipeThatCouldNotBeFound() throws Exception {
        String output = """
                [ERROR] Recipe validation error in com.bjv.Bump for property \
                org.openrewrite.maven.AddDependencyManagementDependency: Recipe class \
                org.openrewrite.maven.AddDependencyManagementDependency cannot be found
                [ERROR] Recipe validation errors detected as part of one or more activeRecipe(s). \
                Execution will continue regardless.
                [INFO] BUILD SUCCESS
                """;

        assertEquals("org.openrewrite.maven.AddDependencyManagementDependency", unknown(output));
    }

    @Test
    void oneNamePerRecipeHoweverManyTimesItIsRepeated() throws Exception {
        // The real run printed the same error five times, once per entry in the recipeList. A
        // reader does not need it five times; they need to know which name was wrong.
        String line = "[ERROR] Recipe class org.openrewrite.maven.Nope cannot be found\n";

        assertEquals("org.openrewrite.maven.Nope", unknown(line.repeat(5)));
    }

    @Test
    void severalMissingRecipesAreAllNamed() throws Exception {
        String output = "[ERROR] Recipe class org.openrewrite.maven.OneThing cannot be found\n"
                + "[ERROR] Recipe class org.openrewrite.gradle.OtherThing cannot be found\n";

        String said = unknown(output);
        assertTrue(said.contains("OneThing") && said.contains("OtherThing"), said);
    }

    @Test
    void aCleanRunSaysNothing() throws Exception {
        assertEquals("", unknown("[INFO] Project [x] Parsing source files\n[INFO] BUILD SUCCESS\n"));
    }

    @Test
    void theToolDescriptionNamesTheRecipeThatWasMissingFromIt() {
        // The agent's guess was reasonable BECAUSE the list omitted what it needed. Naming the
        // recipe is half the fix; refusing to call a skipped recipe a success is the other half.
        String description = Tools.pinning(java.nio.file.Path.of("/tmp"), null, null, "17",
                        new NoTrace(), "before-pins-doer")
                .keySet().stream()
                .filter(t -> t.name().equals("apply_recipe"))
                .findFirst().orElseThrow()
                .description();

        assertTrue(description.contains("AddManagedDependency"),
                "the recipe an agent needs for a dependencyManagement pin: " + description);
        assertTrue(description.contains("ChangePropertyValue"),
                "and the one for a version that lives in a property");
        assertTrue(description.contains("still reports success"),
                "and the reason a guessed name is dangerous rather than merely wrong");
    }

    /** A trace that records nothing; this test is about a description, not about a run. */
    private record NoTrace() implements Trace {
        public void asked(String a, String p, String r) {
        }

        public void applied(String s, String w) {
        }

        public void tool(String a, String t, String args, String result) {
        }

        public void thought(String f, String t, String c) {
        }

        public void built(String phase, Runner.Result r) {
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
