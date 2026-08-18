package tech.mikhailov.bjv.web;

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

    @Test
    void livenessIsKeyedTheSameWayTheLauncherKeysIt(@TempDir Path results) throws IOException {
        // THE BUG THIS EXISTS FOR. A lane's container is named after its manifest id
        // (bjvagent_rr_17_354) and its results directory after repo, sha and hop. Neither name can
        // be derived from the other, so matching container names against slugs reported every
        // running lane as dead: the first supervisor round announced fifteen bumps had "died
        // without filing a verdict" while three of them were working.
        String bump = "owner/live|abc|17|21";
        String key = bump.replaceAll("[^A-Za-z0-9]+", "_");
        write(results, key, bump, System.currentTimeMillis(), "bump", null);

        // The claim is what run.sh writes while a lane holds it, under exactly this name.
        Files.createDirectories(results.resolve("claims"));
        Files.writeString(results.resolve("claims").resolve(key), "");

        var lane = new Sweep(results).lanes().get(0);
        // Liveness needs an agent container to exist too, which there is not in a test, so the
        // claim reads as stale. What is pinned here is the KEY: the claim and the results directory
        // must be the same string, or nothing can ever match.
        assertTrue(Files.isRegularFile(results.resolve("claims").resolve(lane.slug())),
                "the claim the launcher writes must be findable from the lane's own slug");
    }

    @Test
    void aStaleClaimIsNotAliveLane(@TempDir Path results) throws Exception {
        // A lane that died leaves its claim behind and stops heartbeating it. Staleness used to be
        // "no agent container is running anywhere", which needed the docker socket; it is now the
        // claim's own age, which the watcher can read without one.
        String bump = "owner/ghost|abc|17|21";
        String key = bump.replaceAll("[^A-Za-z0-9]+", "_");
        write(results, key, bump, System.currentTimeMillis() - 7_200_000, "gate", null);
        Files.createDirectories(results.resolve("claims"));
        Path claim = results.resolve("claims").resolve(key);
        Files.writeString(claim, "bjvagent_rr_17_9");
        Files.setLastModifiedTime(claim,
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 3_600_000));

        assertFalse(new Sweep(results).lanes().get(0).live(),
                "a claim nobody has touched for an hour is leftover, not evidence");
    }

    @Test
    void livenessIsTheHeartbeatsAgeAndNothingAsksTheDaemon(@TempDir Path results) throws Exception {
        // The watcher shares a container with a page on the public internet, so it must not hold a
        // docker socket. A lane heartbeats its own claim every thirty seconds and stops itself when
        // it sees a postponement, which makes "running" a file's age instead of a question for the
        // daemon -- and the same conclusion, from something the watcher can already reach.
        String bump = "owner/beating|abc|17|21";
        String key = bump.replaceAll("[^A-Za-z0-9]+", "_");
        write(results, key, bump, System.currentTimeMillis(), "bump", null);
        Files.createDirectories(results.resolve("claims"));
        Path claim = results.resolve("claims").resolve(key);
        Files.writeString(claim, "bjvagent_rr_17_1");

        assertTrue(new Sweep(results).lanes().get(0).live(), "a fresh claim is a running lane");

        // A lane that died leaves its claim behind and stops touching it.
        Files.setLastModifiedTime(claim,
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 600_000));
        assertFalse(new Sweep(results).lanes().get(0).live(),
                "a claim nobody has touched for ten minutes is a corpse, not a lane");
    }
}
