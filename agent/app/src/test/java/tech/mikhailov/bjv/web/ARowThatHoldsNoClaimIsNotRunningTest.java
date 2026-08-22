package tech.mikhailov.bjv.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A SETTLEMENT SAYS WHAT LAST HAPPENED, NOT WHAT IS HAPPENING.
 *
 * <p>The record is append-only and a lane that dies writes no terminal state, so its last row is
 * whatever progress note it was on and reads as {@code bumping} for ever. Counting those rows is
 * what the page did, and it put 36 running agents beside 14 containers: the 22 in between had been
 * dead for between half an hour and seven hours, each frozen on the stage it stopped in. The count
 * had no way down, because the only thing that removes a row from it is a verdict the dead lane
 * never filed.
 *
 * <p>The claims directory is the fact and it clears itself. It is what the launcher's own
 * {@code inflight()} consults, and it was sitting in the same results tree the whole time.
 */
class ARowThatHoldsNoClaimIsNotRunningTest {

    private static void settle(Path results, String repo, String state) throws IOException {
        Files.writeString(results.resolve("settlements.jsonl"),
                "{\"at\":\"1000\",\"bump\":\"" + repo + "|sha|17|21\",\"state\":\"" + state + "\"}\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** A claim, as the lane heartbeats it: named for the slug, holding its container's name. */
    private static void claim(Path results, String repo) throws IOException {
        Path claims = Files.createDirectories(results.resolve("claims"));
        String slug = Results.slug(repo + "|sha|17|21");
        Files.writeString(claims.resolve(slug), "bjvagent_" + slug);
    }

    private static String bumps(Path results) throws Exception {
        Method m = Corpus.class.getDeclaredMethod("bumps", long.class);
        m.setAccessible(true);
        return (String) m.invoke(new Corpus(results), 0L);
    }

    @Test
    void theBadgeCountsClaimsRatherThanRows(@TempDir Path results) throws Exception {
        settle(results, "live/one", "bumping");
        settle(results, "dead/two", "bumping");
        settle(results, "dead/three", "bumping");
        claim(results, "live/one");

        assertTrue(new Corpus(results).badges().contains("\"running\":1"),
                "three rows say bumping and one lane exists: " + new Corpus(results).badges());
    }

    @Test
    void aBumpingRowWithNoClaimReadsAsALaneThatDied(@TempDir Path results) throws Exception {
        settle(results, "live/one", "bumping");
        settle(results, "dead/two", "bumping");
        claim(results, "live/one");

        String list = bumps(results);

        assertTrue(list.contains("\"verdict\":\"lane-died\""),
                "the row nothing is working on says so: " + list);
        assertEquals(1, count(list, "\"verdict\":\"bumping\""),
                "and exactly one row still reads as running");
    }

    @Test
    void aStaleHeartbeatIsADeadLane(@TempDir Path results) throws Exception {
        // SIX HEARTBEATS OF MARGIN. The lane touches its claim every thirty seconds, so three
        // minutes of silence is a lane that is gone rather than one that was briefly slow. This is
        // the same window the sweep digest has always used to answer the same question.
        settle(results, "stale/one", "bumping");
        claim(results, "stale/one");
        Path held = results.resolve("claims").resolve(Results.slug("stale/one|sha|17|21"));
        Files.setLastModifiedTime(held, java.nio.file.attribute.FileTime.fromMillis(
                System.currentTimeMillis() - Results.HEARTBEAT_STALE_MS - 1_000));

        assertTrue(bumps(results).contains("\"verdict\":\"lane-died\""),
                "a claim nobody has touched for three minutes is not a running lane");
        assertTrue(new Corpus(results).badges().contains("\"running\":0"));
    }

    @Test
    void aVerdictIsNeverOverwrittenByThisRule(@TempDir Path results) throws Exception {
        // ONLY `bumping` IS READ THIS WAY. A settled bump holds no claim either, and reporting
        // PASS as a dead lane would be a far worse error than the one being fixed.
        settle(results, "done/one", "PASS");
        settle(results, "waiting/two", "queued");

        String list = bumps(results);

        assertTrue(list.contains("\"verdict\":\"PASS\""), list);
        assertTrue(list.contains("\"verdict\":\"queued\""), list);
        assertFalse(list.contains("lane-died"), "neither of these is a dead lane: " + list);
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + 1)) {
            n++;
        }
        return n;
    }
}
