package tech.mikhailov.bjv.jvm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import tech.mikhailov.ratchet.record.Trace;

/**
 * THE ONE WAY A PIN REACHES A PROJECT, WHICH HAD NEVER ONCE WORKED.
 *
 * <p>Measured on a live sweep: 86 apply_recipe calls across 7 bumps, none reaching rc=0. Two causes,
 * both the harness supplying a fixed header the agent has no reason to memorise.
 *
 * <p>The name travelled to Maven inside a command string as
 * {@code -Drewrite.activeRecipes=<name>}, and OpenRewrite allows spaces in a name, so
 * {@code Upgrade Lombok to 1.18.46} made Maven read "Lombok" as a goal. 58 of 77 named recipes had a
 * space. Separately, agents wrote the id of the recipe they wanted into {@code type:}, which is the
 * document kind and has exactly one legal value, so nothing registered and the run failed with
 * {@code Recipe(s) not found} naming a recipe that was in the file.
 *
 * <p>The YAML in these tests is copied from the traces rather than invented.
 */
class ARecipeFileIsMadeRunnableTest {

    @Test
    void aNameWithSpacesCannotReachMavenAsSeveralWords(@TempDir Path ws) throws IOException {
        // Verbatim from the bump that spent its whole pin phase on this.
        String written = apply(ws, """
                type: org.openrewrite.semver
                name: Upgrade Lombok to 1.18.46
                recipeList:
                  - org.openrewrite.maven.UpgradeDependencyVersion:
                      groupId: org.projectlombok
                      artifactId: lombok
                      newVersion: 1.18.46
                """);

        String name = nameIn(written);
        assertFalse(name.contains(" "), "a shell would split this into a goal: " + name);
        assertEquals("Upgrade.Lombok.to.1.18.46", name, "and it stays recognisable in a log");
    }

    @Test
    void theTypeIsTheDocumentKindNotTheRecipeYouWant(@TempDir Path ws) throws IOException {
        String written = apply(ws, """
                type: org.openrewrite.semver
                name: com.bjv.Bump
                recipeList:
                  - org.openrewrite.maven.UpgradeDependencyVersion:
                      groupId: org.projectlombok
                      artifactId: lombok
                      newVersion: 1.18.46
                """);

        assertTrue(written.contains("type: specs.openrewrite.org/v1beta/recipe"),
                "with any other type nothing registers: " + written);
        assertFalse(written.contains("type: org.openrewrite.semver"));
    }

    @Test
    void aFileWithNoHeaderGetsOneRatherThanFailingAsNotFound(@TempDir Path ws) throws IOException {
        // Five live failures named our own fallback, which is this case: no top-level name at all,
        // so activeRecipes pointed at a recipe the file never declared.
        String written = apply(ws, """
                recipeList:
                  - org.openrewrite.maven.UpgradeParentVersion:
                      groupId: org.springframework.boot
                      artifactId: spring-boot-starter-parent
                      newVersion: 4.1.0
                """);

        assertTrue(written.contains("type: specs.openrewrite.org/v1beta/recipe"));
        assertEquals("com.bjv.Bump", nameIn(written));
        assertTrue(written.contains("displayName:"), "OpenRewrite wants one");
        assertTrue(written.contains("UpgradeParentVersion"), "and the agent's work is untouched");
    }

    @Test
    void whatTheAgentActuallyDecidedIsNeverRewritten(@TempDir Path ws) throws IOException {
        String recipes = """
                  - org.openrewrite.maven.UpgradeDependencyVersion:
                      groupId: net.bytebuddy
                      artifactId: byte-buddy
                      newVersion: 1.17.6
                  - org.openrewrite.gradle.UpgradeDependencyVersion:
                      groupId: net.bytebuddy
                      artifactId: byte-buddy
                      newVersion: 1.17.6
                """;
        String written = apply(ws, "type: wrong\nname: Some Name\nrecipeList:\n" + recipes);

        assertTrue(written.contains(recipes.stripTrailing()),
                "only the header is the harness's business: " + written);
    }

    @Test
    void aSecondDocumentKeepsItsOwnHeader(@TempDir Path ws) throws IOException {
        // The header belongs to the first document; later ones are separate recipes.
        String written = apply(ws, """
                type: specs.openrewrite.org/v1beta/recipe
                name: com.bjv.Bump
                displayName: first
                recipeList:
                  - org.openrewrite.java.migrate.UpgradeToJava21
                ---
                type: specs.openrewrite.org/v1beta/recipe
                name: com.bjv.Second
                displayName: second
                recipeList:
                  - org.openrewrite.maven.UpgradePluginVersion
                """);

        assertEquals(1, written.split("name: com\\.bjv\\.Bump", -1).length - 1);
        assertTrue(written.contains("name: com.bjv.Second"), "the second is left alone");
    }

    /** Run a recipe file through the harness and return what actually landed on disk. */
    private static String apply(Path ws, String yaml) throws IOException {
        // No hoptools path, so the run itself fails immediately; what is under test is the file.
        new Migrate(ws, "", new Silent()).apply(yaml, "21");
        return Files.readString(ws.resolve("rewrite.yml"));
    }

    private static String nameIn(String yaml) {
        return yaml.lines().filter(l -> l.startsWith("name:"))
                .map(l -> l.substring(5).strip()).findFirst().orElseThrow();
    }

    private record Silent() implements Trace {
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
