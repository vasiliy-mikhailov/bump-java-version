package tech.mikhailov.bjv.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A supervisor is only worth having if what it reads is true.
 *
 * <p>Its whole value is standing outside a single bump, and everything it says rests on this layer:
 * which lanes are running, which died without filing a verdict, how long each has been silent. Get
 * that wrong and it reports confidently about a fleet that does not exist.
 */
class TheSupervisorSeesWhatOneBumpCannotTest {

    @Test
    void aLaneThatDiedWithoutSettlingIsNotTheSameAsOneStillWorking(@TempDir Path results)
            throws IOException {
        long now = System.currentTimeMillis();
        write(results, "owner_alive_abc_17_21", "owner/alive|abc|17|21", now - 600_000, "bump", null);
        write(results, "owner_dead_def_17_21", "owner/dead|def|17|21", now - 7_200_000, "gate", null);
        write(results, "owner_done_ghi_17_21", "owner/done|ghi|17|21", now - 60_000, "close", "PASS");

        Sweep sweep = new Sweep(results);
        var lanes = sweep.lanes();
        assertEquals(3, lanes.size());

        var dead = lanes.stream().filter(l -> l.repo().equals("owner/dead")).findFirst().orElseThrow();
        assertFalse(dead.settled(), "it never filed a verdict");
        assertEquals(120, dead.ageMinutes(now), "and has been silent for two hours");

        var done = lanes.stream().filter(l -> l.repo().equals("owner/done")).findFirst().orElseThrow();
        assertTrue(done.settled());
        assertEquals("PASS", done.state());

        // The digest must surface the one that needs attention, not bury it under the other two.
        String digest = sweep.digest(now);
        assertTrue(digest.contains("NEITHER RUNNING NOR SETTLED"), digest);
        assertTrue(digest.contains("owner/dead"), digest);
        assertTrue(digest.contains("1  PASS"), "and it counts the verdicts: " + digest);
    }

    @Test
    void postponingIsRecordedWithItsReasonAndCanBeUndone(@TempDir Path results) throws IOException {
        long now = System.currentTimeMillis();
        write(results, "owner_slow_abc_17_21", "owner/slow|abc|17|21", now - 9_000_000, "bump", null);
        Sweep sweep = new Sweep(results);

        assertNull(sweep.postponedReason("owner_slow_abc_17_21"), "nothing is set aside to begin with");

        sweep.postpone("owner_slow_abc_17_21", "silent for 150 minutes on the same gate turn");

        assertEquals("silent for 150 minutes on the same gate turn",
                sweep.postponedReason("owner_slow_abc_17_21"),
                "the reason is the point: it is what someone reads later");
        assertTrue(sweep.lanes().get(0).postponed());
        assertTrue(sweep.digest(now).contains("POSTPONED"), "and it is visible in the digest");

        assertTrue(sweep.resume("owner_slow_abc_17_21"));
        assertNull(sweep.postponedReason("owner_slow_abc_17_21"));
        assertFalse(sweep.resume("owner_slow_abc_17_21"), "resuming twice is not an error, just false");
    }

    @Test
    void theMarkerNameIsTheOneTheLauncherWillLookFor(@TempDir Path results) throws IOException {
        // run.sh derives its key from repo|sha|from|to with every non-alphanumeric run collapsed to
        // an underscore, and the results directory is named the same way. If these two ever drift,
        // a postponement is written that nothing ever reads.
        String bump = "0xiaoyu/XiaoYu|103e58d1ea|17|21";
        String launcherKey = bump.replaceAll("[^A-Za-z0-9]+", "_");
        write(results, launcherKey, bump, System.currentTimeMillis(), "bump", null);

        Sweep sweep = new Sweep(results);
        sweep.postpone(sweep.lanes().get(0).slug(), "because");

        assertTrue(Files.isRegularFile(results.resolve("postponed").resolve(launcherKey)),
                "the launcher looks for exactly this name");
    }

    @Test
    void aTraceBeingWrittenWhileItIsReadDoesNotThrow(@TempDir Path results) throws IOException {
        Path dir = results.resolve("owner_partial_abc_17_21");
        Files.createDirectories(dir);
        // A half-written final line is normal: the supervisor reads while lanes append.
        Files.writeString(dir.resolve("trace.jsonl"),
                "{\"at\":\"1786500000000\",\"bump\":\"owner/partial|abc|17|21\",\"kind\":\"progress\"}\n"
                        + "{\"at\":\"1786500060000\",\"bump\":\"owner/par");

        var lanes = new Sweep(results).lanes();
        assertEquals(1, lanes.size(), "it takes what parsed rather than giving up");
        assertEquals("owner/partial", lanes.get(0).repo());
    }

    private static void write(Path results, String slug, String bump, long at, String stage,
                              String settled) throws IOException {
        Path dir = results.resolve(slug);
        Files.createDirectories(dir);
        StringBuilder rows = new StringBuilder();
        rows.append("{\"at\":\"").append(at - 300_000).append("\",\"bump\":\"").append(bump)
                .append("\",\"kind\":\"progress\",\"note\":\"survey: does the project agree\"}\n");
        rows.append("{\"at\":\"").append(at).append("\",\"bump\":\"").append(bump)
                .append("\",\"kind\":\"applied\",\"stage\":\"").append(stage).append("\"}\n");
        if (settled != null) {
            rows.append("{\"at\":\"").append(at).append("\",\"bump\":\"").append(bump)
                    .append("\",\"kind\":\"settled\",\"state\":\"").append(settled).append("\"}\n");
        }
        Files.writeString(dir.resolve("trace.jsonl"), rows.toString());
    }
}
