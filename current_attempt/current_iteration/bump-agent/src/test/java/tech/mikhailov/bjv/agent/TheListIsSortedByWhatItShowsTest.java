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
 * moved. That half is unchanged and is most of this file.
 *
 * <p>THE ORDER ITSELF IS NO LONGER A TIMESTAMP. Sorting by whatever moved most recently floated the
 * running bumps to the top and rearranged the table as the sweep worked: a repository somebody was
 * reading moved because a different one finished. It is run.sh's own comparator now, so the page and
 * the queue agree, and the two assertions that pinned the descending order are rewritten rather than
 * deleted, because what they were really guarding is that the order is deliberate and not whatever
 * the map happened to yield.
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

    private static List<String> reposInOrder(String json) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("\"repo\":\"([^\"]*)\"").matcher(json);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
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
    void noTimestampDecidesWhereARowGoes(@TempDir Path results) throws Exception {
        // The timestamps here are deliberately the reverse of the alphabet, so a sort that still
        // touched one would produce exactly the wrong list rather than an accidentally right one.
        bump(results, "a/first", 9_000, 9_000);
        bump(results, "b/second", 5_000, 5_000);
        bump(results, "c/third", 1_000, 1_000);

        assertEquals(List.of("a/first", "b/second", "c/third"), reposInOrder(bumps(results, 0)),
                "alphabetical, and the busiest row does not lead");
    }

    @Test
    void itIsTheComparatorTheSweepQueuesBy(@TempDir Path results) throws Exception {
        // run.sh:233 is `sort -k2,2f -k2,2 -k4,4n`: repository case-folded, then case-sensitive to
        // break the ties the fold creates, then the source JDK. Case-folded because that is what
        // alphabetical means to a reader; a plain byte sort puts every capital-initial repository
        // before every lowercase one, which is how aartiPl/tablevis sat at row 524 of the queue
        // while the page showed it fourteenth. Same corpus, two orders, and the sweep read as
        // skipping rows it had simply not reached.
        // THE TIE-BREAK ONLY FIRES ON A REAL TIE, which is two names differing in nothing but
        // case. A first attempt at this used Alpha/upper and alpha/lower, which are not tied at
        // all once the fold reaches the seventh character, and it tested the fold twice.
        bump(results, "zeta/last", 1_000, 1_000);
        bump(results, "alpha/same", 2_000, 2_000);
        bump(results, "Alpha/same", 3_000, 3_000);
        bump(results, "aardvark/first", 4_000, 4_000);

        assertEquals(List.of("aardvark/first", "Alpha/same", "alpha/same", "zeta/last"),
                reposInOrder(bumps(results, 0)),
                "case-folded first; the byte order decides only where the fold ties");
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
