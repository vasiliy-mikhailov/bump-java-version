package tech.mikhailov.bjv.web;

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
        Method m = Rerun.class.getDeclaredMethod("ask", String.class);
        m.setAccessible(true);
        return (String) m.invoke(new Rerun(results), slug);
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

    @Test
    void askingTwiceQueuesOnceAndSaysSo(@TempDir Path runRoot) throws Exception {
        Path results = Files.createDirectories(runRoot.resolve("results"));
        // A REQUEUED BUMP WAITS, AND THE WAIT CAN BE LONG. The drainer wants a free lane and a lane
        // runs for hours; two repositories sat requeued and unclaimed for ninety minutes because
        // one batch was still going. Asking again is a fair thing to do in that state, so the page
        // keeps offering it, and the honest answer is that it is already queued rather than a
        // second row for the same work.
        String slug = settle(results, "agwlvssainokuni/springapp3", "PASS");

        String first = rerun(results, slug);
        String second = rerun(results, slug);

        assertTrue(first.contains("\"queued\":true"), first);
        assertTrue(second.contains("\"queued\":true"), "it is queued, which is not a refusal");
        assertTrue(second.contains("already waiting"), second);
        assertEquals(1, Files.readAllLines(results.resolve("rerun.tsv")).size(),
                "and the manifest gained one row, not two");
    }

    @Test
    void aRowADrainerHasAlreadyTakenStillCounts(@TempDir Path runRoot) throws Exception {
        // The drainer MOVES rerun.tsv aside before running it, so a bump in flight sits in a batch
        // file and in no queue a naive check would see. Missing that is a duplicate row every time
        // somebody clicks while their own bump is being picked up.
        //
        // A RUN ROOT OF ITS OWN, because the check reads the directory ABOVE results and the batch
        // files live there. Writing one into a shared parent made a sibling test see a bump queued
        // that it had never queued, which is the same way this would misfire in production if
        // anything unrelated ever left a rerun-batch file beside the run root.
        Path results = Files.createDirectories(runRoot.resolve("results"));
        String slug = settle(results, "aartiPl/tablevis", "PASS");
        Files.writeString(runRoot.resolve("rerun-batch-1.tsv"),
                slug + "\taartiPl/tablevis\tabc123\t8\t11\n");

        String said = rerun(results, slug);

        assertTrue(said.contains("already waiting"), said);
        assertFalse(Files.exists(results.resolve("rerun.tsv")), "nothing was queued a second time");
    }

    @Test
    void theTableIsInTheOrderTheSweepTakesThem(@TempDir Path results) throws Exception {
        // IT SORTED BY WHATEVER MOVED LAST, so the running bumps floated to the top and the table
        // rearranged itself as the sweep worked: a repository you were reading moved because a
        // different one finished. It is run.sh's own comparator now (run.sh:233), repository
        // case-folded, then case-sensitive to break the ties the fold creates, then the source JDK.
        //
        // CASE-FOLDED BECAUSE THAT IS WHAT ALPHABETICAL MEANS. A plain byte sort puts every
        // capital-initial repository before every lowercase one, which is how aartiPl/tablevis sat
        // at row 524 of the queue while the page showed it fourteenth: same corpus, two orders,
        // and the sweep read as skipping rows it had simply not reached.
        settle(results, "zeta/last", "PASS");
        settle(results, "aartiPl/tablevis", "bumping");
        settle(results, "Alpha/first", "PASS");
        settle(results, "alpha/first", "PASS");

        java.lang.reflect.Method m = Corpus.class.getDeclaredMethod("bumps", String.class);
        m.setAccessible(true);
        List<String> shown = reposIn((String) m.invoke(new Corpus(results), ""));

        assertEquals(List.of("aartiPl/tablevis", "Alpha/first", "alpha/first", "zeta/last"), shown,
                "case-folded first, and a bumping row does not jump the queue");
    }

    /** The repository of every row, in the order the endpoint returned them. */
    private static List<String> reposIn(String json) {
        List<String> out = new java.util.ArrayList<>();
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("\"repo\":\"([^\"]*)\"").matcher(json);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }
}
