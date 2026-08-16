package tech.mikhailov.bjv.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A VERDICT IS ONLY TRUE OF THE HARNESS THAT REACHED IT.
 *
 * <p>A settled bump is skipped forever, which is right while nothing changes and wrong the moment
 * something does. In one day the floors gained a recipe at every target, apply_recipe stopped
 * reporting a failed run as success, the pins phases learned to ask which build system they are on,
 * and an incomplete Gradle distribution stopped being blamed on the project. Every verdict from
 * before those was reached by an agent that no longer exists.
 *
 * <p>REQUEUEING IS AN APPEND. Both readers of settlements.jsonl already take the LAST line for a
 * bump, so this adds one rather than rewriting a file a live sweep is appending to. A rewrite would
 * race, and the window is exactly the moment a lane settles.
 */
class ASettledBumpCanBeAskedForAgainTest {

    private static String rerun(Path results, String slug) throws Exception {
        Method m = Api.class.getDeclaredMethod("rerun", String.class);
        m.setAccessible(true);
        return (String) m.invoke(new Api(results), slug);
    }

    private static String settle(Path results, String repo, String state) throws IOException {
        String bump = repo + "|abc123|8|11";
        Files.writeString(results.resolve("settlements.jsonl"),
                "{\"at\":\"1\",\"bump\":\"" + bump + "\",\"kind\":\"settled\",\"state\":\"" + state
                        + "\"}\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        return bump.replaceAll("[^A-Za-z0-9]+", "_");
    }

    @Test
    void itQueuesTheRowARunnerNeedsAndSaysWhatItWas(@TempDir Path results) throws Exception {
        String slug = settle(results, "agwlvssainokuni/springapp3", "PASS");

        String said = rerun(results, slug);

        assertTrue(said.contains("\"queued\":true"), said);
        assertTrue(said.contains("agwlvssainokuni/springapp3"), said);
        assertTrue(said.contains("8 -> 11"), said);
        assertTrue(said.contains("\"was\":\"PASS\""), "the verdict it replaces is named: " + said);

        // slug, repo, sha, from, to: exactly the five columns run.sh reads.
        String row = Files.readString(results.resolve("rerun.tsv"));
        assertEquals(5, row.strip().split("\t").length, row);
        assertTrue(row.contains("agwlvssainokuni/springapp3\tabc123\t8\t11"), row);
    }

    @Test
    void itAppendsRatherThanRewritingWhatIsAlreadyThere(@TempDir Path results) throws Exception {
        String slug = settle(results, "aartiPl/tablevis", "PASS");
        int before = Files.readAllLines(results.resolve("settlements.jsonl")).size();

        rerun(results, slug);

        var lines = Files.readAllLines(results.resolve("settlements.jsonl"));
        assertEquals(before + 1, lines.size(), "one line added, none removed");
        assertTrue(lines.get(0).contains("\"PASS\""), "the old verdict is still on the record");
        assertTrue(lines.get(lines.size() - 1).contains("\"requeued\""), "and the new state is last");
    }

    @Test
    void requeuedIsNotBumping(@TempDir Path results) throws Exception {
        // Both states make run.sh treat a bump as unfinished, so "bumping" would have worked and
        // needed no change to run.sh. It would also have said a bump was in flight while it sat in
        // a file waiting for a lane, which is the kind of number this corpus keeps having to unpick.
        String slug = settle(results, "adorsys/kc-oid4vci-deployment", "FAIL_test_conservation");

        rerun(results, slug);

        List<String> all = Files.readAllLines(results.resolve("settlements.jsonl"));
        String last = all.get(all.size() - 1);
        assertTrue(last.contains("\"state\":\"requeued\""), last);
        assertFalse(last.contains("\"bumping\""), last);
    }

    @Test
    void askingAboutSomethingThatNeverSettledIsRefusedNotInvented(@TempDir Path results)
            throws Exception {
        String said = rerun(results, "never_ran_at_all_8_11");

        assertTrue(said.contains("\"queued\":false"), said);
        assertTrue(said.contains("nothing settled"), said);
        assertFalse(Files.exists(results.resolve("rerun.tsv")), "and nothing is queued");
    }

    @Test
    void anEmptySlugIsRefused(@TempDir Path results) throws Exception {
        for (String slug : new String[] {null, "", "   "}) {
            assertTrue(rerun(results, slug).contains("\"queued\":false"), String.valueOf(slug));
        }
    }
}
