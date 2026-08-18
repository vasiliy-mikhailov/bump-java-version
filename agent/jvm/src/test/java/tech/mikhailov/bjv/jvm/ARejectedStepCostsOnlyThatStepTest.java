package tech.mikhailov.bjv.jvm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import tech.mikhailov.bjv.engine.Shell;

/**
 * WHAT AN OBJECTION IS ALLOWED TO COST.
 *
 * <p>Nothing in the chain committed, so a revert was {@code git checkout -- .} against the original
 * clone: one critic saying "gaming" deleted the OpenRewrite rewrite, the parent bump, the target
 * propagation and every floor that had landed hours earlier. Five bumps hit it, and three settled
 * blocked-dependency arguing about a workspace that no longer contained the work being argued over.
 *
 * <p>Three scopes, and each is pinned here: a step can be undone, a campaign can be undone back to
 * where it began, and a landed stage can be undone by neither.
 */
class ARejectedStepCostsOnlyThatStepTest {

    private Path ws;
    private Tree tree;
    private final List<String> notes = new ArrayList<>();

    @BeforeEach
    void repo() throws Exception {
        ws = Files.createTempDirectory("tree");
        git("init", "-q", "-b", "main");
        git("config", "user.email", "t@t");
        git("config", "user.name", "t");
        write("pom.xml", "<project><properties><java.version>17</java.version></properties></project>");
        git("add", "-A");
        git("commit", "-q", "-m", "the project as cloned");
        tree = new Tree(ws, notes::add);
        tree.excludeBuildOutput();
    }

    @Test
    void aLandedStageSurvivesAnObjectionToTheStepAfterIt() throws Exception {
        // migrate lands: the deterministic pass, which no critic may reach back past.
        write("pom.xml", "<project><properties><java.version>21</java.version></properties></project>");
        tree.land("migrate");

        // a step then makes a mess and is rejected.
        write("pom.xml", "<project><properties><java.version>99</java.version></properties></project>");
        write("Stub.java", "class Stub {}");
        assertFalse(tree.diff().isBlank(), "the step is visible before it is judged");

        tree.revert();

        assertTrue(read("pom.xml").contains("21"), "the migration must still be there");
        assertFalse(Files.exists(ws.resolve("Stub.java")), "a new file the step added goes too");
        assertTrue(tree.diff().isBlank(), "and nothing of the step is left");
    }

    @Test
    void aCampaignCanBeUndoneBackToWhereItBegan() throws Exception {
        write("pom.xml", "<project><properties><java.version>21</java.version></properties></project>");
        tree.land("migrate");
        String entry = tree.head();

        write("A.java", "class A {}");
        tree.land("step one");
        write("B.java", "class B {}");
        tree.land("step two");
        assertFalse(tree.diffSince(entry).isBlank(), "the campaign is two steps wide");

        tree.revertTo(entry);

        assertFalse(Files.exists(ws.resolve("A.java")), "both steps go");
        assertFalse(Files.exists(ws.resolve("B.java")));
        assertTrue(read("pom.xml").contains("21"), "but not the stage the campaign started from");
        assertEquals(entry, tree.head());
    }

    @Test
    void theStepCriticSeesTheStepAndTheLoopCriticSeesTheCampaign() throws Exception {
        write("pom.xml", "<project><properties><java.version>21</java.version></properties></project>");
        tree.land("migrate");
        String entry = tree.head();

        write("A.java", "class A {}");
        tree.land("step one");
        write("B.java", "class B {}");

        // Uncommitted is the step under judgement; since-entry is everything the campaign did.
        assertTrue(tree.diff().contains("B.java"), "the step critic sees only B");
        assertFalse(tree.diff().contains("A.java"), "not the step already accepted");
        assertTrue(tree.diffSince(entry).contains("A.java"), "the loop critic sees both");
        assertTrue(tree.diffSince(entry).contains("B.java"));
    }

    @Test
    void buildOutputIsNeitherCommittedNorDeletedByARevert() throws Exception {
        Files.createDirectories(ws.resolve("target/classes"));
        write("target/classes/Foo.class", "not really bytecode");
        write("rewrite.yml", "type: specs.openrewrite.org/v1beta/recipe");

        assertTrue(tree.diff().isBlank(), "build output is not a change anyone should review");
        tree.land("migrate");
        tree.revert();

        // A revert that wiped target/ would make the next build look like a fresh checkout, which
        // is the stale-class trap in reverse: it hides whether a compile actually ran.
        assertTrue(Files.exists(ws.resolve("target/classes/Foo.class")), "build output survives");
        assertTrue(Files.exists(ws.resolve("rewrite.yml")), "and so does the recipe file");
    }

    private void write(String path, String body) throws IOException {
        Path f = ws.resolve(path);
        Files.createDirectories(f.getParent());
        Files.writeString(f, body);
    }

    private String read(String path) throws IOException {
        return Files.readString(ws.resolve(path));
    }

    private void git(String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        Shell.run(ws, Map.of(), Duration.ofMinutes(1), cmd.toArray(new String[0]));
    }

    @Test
    void aCampaignCannotRewindPastItsOwnFloor() throws Exception {
        write("pom.xml", "<project><properties><java.version>21</java.version></properties></project>");
        tree.land("migrate");
        String migrate = tree.head();
        write("A.java", "class A {}");
        tree.land("step one");
        String floor = migrate;

        // Inside the campaign: reachable.
        assertTrue(tree.isAtOrAfter(tree.head(), floor), "the latest step is at or after the floor");
        assertTrue(tree.isAtOrAfter(floor, floor), "the floor itself is reachable");

        // Older than the campaign: the migration, which no critic may undo by naming a commit.
        String beforeMigrate = tree.resolve(migrate + "~1");
        assertFalse(beforeMigrate.isBlank(), "there is an older commit to try to reach");
        assertFalse(tree.isAtOrAfter(beforeMigrate, floor),
                "rewinding there would delete the deterministic migration");
    }

    @Test
    void aNameThatIsNotACommitAnswersRatherThanThrows() {
        assertEquals("", tree.resolve("not-a-sha"), "a typo must come back as an answer");
    }
}
