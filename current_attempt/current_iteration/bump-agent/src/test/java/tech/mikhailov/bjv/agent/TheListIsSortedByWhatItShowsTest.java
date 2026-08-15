package tech.mikhailov.bjv.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE LIST WAS SORTED ON A FIELD IT DOES NOT SEND.
 *
 * <p>{@code bumps()} ordered rows by the settlement's own {@code at} and then emitted
 * {@code lastEventAt} in its place. Those are two different facts: a settlement is written when a
 * bump STARTS and again when it ends, while the row shows the last write to the trace. So the list
 * arrived in an order that matched nothing on screen.
 *
 * <p>It looked stable, which is why it survived: the page merges deltas in place to avoid
 * reshuffling rows under a reader's cursor, so the disorder only became visible on a refresh, which
 * refetches and reorders every running row at once.
 *
 * <p>The delta filter had the same fault for the same reason. A caller's mark comes from the row it
 * was given, so comparing it against a field that row never carried drops rows that have in fact
 * moved.
 */
class TheListIsSortedByWhatItShowsTest {

    private static String bumps(Path results, long mark) throws Exception {
        Method m = Api.class.getDeclaredMethod("bumps", long.class);
        m.setAccessible(true);
        return (String) m.invoke(new Api(results), mark);
    }

    /**
     * A settled bump whose SETTLEMENT time and TRACE time disagree, which is the ordinary case: the
     * settlement is written first and the trace goes on being appended to.
     */
    private static void bump(Path results, String repo, long settledAt, long lastEvent)
            throws IOException {
        bump(results, repo, settledAt, lastEvent, "bumping");
    }

    private static void bump(Path results, String repo, long settledAt, long lastEvent,
                             String state) throws IOException {
        Files.writeString(results.resolve("settlements.jsonl"),
                "{\"at\":\"" + settledAt + "\",\"bump\":\"" + repo + "|sha|17|21\","
                        + "\"kind\":\"settled\",\"state\":\"" + state + "\"}\n",
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        Path dir = results.resolve(repo.replaceAll("[^A-Za-z0-9]+", "_") + "_sha_17_21");
        Files.createDirectories(dir);
        Path trace = dir.resolve("trace.jsonl");
        Files.writeString(trace, "{\"at\":\"" + lastEvent + "\",\"kind\":\"progress\"}\n");
        Files.setLastModifiedTime(trace, FileTime.fromMillis(lastEvent));
    }

    private static List<Long> atsInOrder(String json) {
        List<Long> out = new ArrayList<>();
        Matcher m = Pattern.compile("\"at\":(\\d+)").matcher(json);
        while (m.find()) {
            out.add(Long.parseLong(m.group(1)));
        }
        return out;
    }

    @Test
    void theRowsComeBackInTheOrderOfTheirOwnTimestamps(@TempDir Path results) throws Exception {
        // Settlement order is deliberately the REVERSE of trace order here. Sorting on the wrong
        // one is then not a subtle difference, it is exactly backwards.
        bump(results, "a/oldest-trace", 9_000, 1_000);
        bump(results, "b/newest-trace", 1_000, 9_000);
        bump(results, "c/middle", 5_000, 5_000);

        List<Long> ats = atsInOrder(bumps(results, 0));

        assertEquals(List.of(9_000L, 5_000L, 1_000L), ats,
                "newest last-event first, which is the column the page draws");
    }

    @Test
    void everyRowIsInDescendingOrderAndNotJustTheFirst(@TempDir Path results) throws Exception {
        for (int i = 0; i < 12; i += 1) {
            // Settlement times ascending, trace times descending: any sort touching the wrong field
            // produces a visibly wrong list rather than an accidentally right one.
            bump(results, "r/" + i, 1_000 + i, 20_000 - i);
        }

        List<Long> ats = atsInOrder(bumps(results, 0));

        assertEquals(12, ats.size());
        for (int i = 1; i < ats.size(); i += 1) {
            assertTrue(ats.get(i - 1) >= ats.get(i),
                    "row " + i + " is out of order: " + ats);
        }
    }

    @Test
    void theDeltaFilterUsesTheSameFieldTheCallerWasGiven(@TempDir Path results) throws Exception {
        // A caller's mark is the newest `at` it holds, and `at` is the trace time. Filtering on the
        // settlement time would drop this row: its settlement is old, its trace is not.
        bump(results, "moved/recently", 1_000, 9_000);

        String delta = bumps(results, 5_000);

        assertTrue(delta.contains("moved/recently"),
                "a row whose last event is newer than the mark must come back: " + delta);
    }

    @Test
    void aRowOlderThanTheMarkStaysOutOfTheDelta(@TempDir Path results) throws Exception {
        // SETTLED, not running. A running bump is returned whatever its timestamp says, because one
        // agent call takes minutes and the row a reader is watching is exactly the one that has not
        // written lately. That rule is tested elsewhere; this is the other half of it, and writing
        // this fixture as "bumping" tested the first rule twice and the second not at all.
        bump(results, "settled/long-ago", 9_000, 1_000, "PASS");

        assertFalse(bumps(results, 5_000).contains("settled/long-ago"),
                "an unmoved settled row costs nothing to skip");
    }

    @Test
    void aRunningRowComesBackHoweverStaleItsTimestamp(@TempDir Path results) throws Exception {
        bump(results, "still/going", 9_000, 1_000, "bumping");

        assertTrue(bumps(results, 5_000).contains("still/going"),
                "the row a reader is watching is the one that has not written lately");
    }
}
