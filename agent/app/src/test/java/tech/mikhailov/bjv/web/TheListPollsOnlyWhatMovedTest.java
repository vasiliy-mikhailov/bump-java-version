package tech.mikhailov.bjv.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE LIST IS FETCHED ONCE AND THE CLOCK IS NOT.
 *
 * <p>1439 rows is about a megabyte, so the page loaded the corpus once and polled only the summary
 * afterwards. The summary carries the header's "last event", which stayed correct; every row's
 * "last event" was computed from an {@code at} frozen at page load against a {@code now} the timer
 * advanced every fifteen seconds. So the column did not lag, it DRIFTED, one tick further from the
 * truth every fifteen seconds, and only a manual refresh corrected it. A column frozen at "2m ago"
 * would have been the smaller lie.
 *
 * <p>Refetching the corpus to fix six moving rows sends a megabyte to update six rows. This is the
 * delta that fixes it instead, and what it must and must not include.
 */
class TheListPollsOnlyWhatMovedTest {

    private static String bumps(Path results, long mark) throws Exception {
        Method m = Corpus.class.getDeclaredMethod("bumps", long.class);
        m.setAccessible(true);
        return (String) m.invoke(new Corpus(results), mark);
    }

    /** A settlement row as the sweep writes them. */
    private static void settle(Path results, String repo, String state, long at) throws IOException {
        Files.writeString(results.resolve("settlements.jsonl"),
                "{\"at\":\"" + at + "\",\"bump\":\"" + repo + "|sha|17|21\",\"kind\":\"settled\","
                        + "\"state\":\"" + state + "\"}\n",
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    }

    @Test
    void withNoMarkItIsTheWholeCorpus(@TempDir Path results) throws Exception {
        settle(results, "old/one", "PASS", 1_000);
        settle(results, "new/two", "PASS", 9_000);

        String all = bumps(results, 0);

        assertTrue(all.contains("old/one"), all);
        assertTrue(all.contains("new/two"), all);
    }

    @Test
    void withAMarkOnlyWhatIsNewerComesBack(@TempDir Path results) throws Exception {
        settle(results, "old/one", "PASS", 1_000);
        settle(results, "new/two", "PASS", 9_000);

        String moved = bumps(results, 5_000);

        assertFalse(moved.contains("old/one"), "a row that has not moved costs nothing: " + moved);
        assertTrue(moved.contains("new/two"), moved);
    }

    @Test
    void arunningBumpAlwaysComesBackEvenWhenItHasNotWritten(@TempDir Path results) throws Exception {
        // THE ROW A READER IS ACTUALLY WATCHING. One agent call takes minutes, so a lane mid-answer
        // has written nothing since the last poll and would be filtered out by its timestamp — the
        // one row whose "last event" a reader is checking to decide whether it is stuck.
        settle(results, "still/going", "bumping", 1_000);

        String moved = bumps(results, 5_000);

        assertTrue(moved.contains("still/going"),
                "a running lane is never filtered out by an old timestamp: " + moved);
    }

    @Test
    void aSettledRowStopsComingBackOnceItIsOlderThanTheMark(@TempDir Path results) throws Exception {
        // The other half of the rule above: "bumping" is what keeps a row in the delta, so a row
        // that has settled must fall out of it, or the delta grows without bound over a long sweep.
        settle(results, "done/here", "bumping", 1_000);
        settle(results, "done/here", "PASS", 2_000);

        String moved = bumps(results, 5_000);

        assertFalse(moved.contains("done/here"), "settled and stale is the one case to skip: " + moved);
    }

    @Test
    void rubbishInTheParameterIsTreatedAsNoMark(@TempDir Path results) throws Exception {
        settle(results, "old/one", "PASS", 1_000);
        Method m = Corpus.class.getDeclaredMethod("bumps", String.class);
        m.setAccessible(true);

        // A hand-typed url should degrade to the full list, not to an exception or an empty page.
        for (String bad : new String[] {null, "", "  ", "not-a-number", "9e9"}) {
            String out = (String) m.invoke(new Corpus(results), bad);
            assertTrue(out.contains("old/one"), "since=" + bad + " should mean the lot: " + out);
        }
    }
}
