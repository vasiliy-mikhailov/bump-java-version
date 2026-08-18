package tech.mikhailov.bjv.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A BUMP THAT HAS NOT WRITTEN YET IS NOT A BUMP THAT STARTED AT NOUGHT.
 *
 * <p>startedAt cached through computeIfAbsent, which memoises whatever the function returns, and
 * the function returns nought when the trace is not there yet. A page load seconds after a sweep
 * begins finds most lanes still cloning, so nought was remembered for the life of the process and
 * the duration column read "-" for the whole run while the traces grew to hundreds of lines.
 *
 * <p>Observed on a live sweep: three of sixteen rows showed a duration and thirteen never would.
 */
class ATookTest {

    private static long startedAt(Corpus c, String slug) throws Exception {
        Method m = Corpus.class.getDeclaredMethod("startedAt", String.class);
        m.setAccessible(true);
        return (long) m.invoke(c, slug);
    }

    @Test
    void anAbsentTraceIsNotRememberedAsAStartTime(@TempDir Path root) throws Exception {
        Path results = Files.createDirectories(root.resolve("results"));
        Corpus c = new Corpus(results);

        // Asked before the lane has written anything, exactly as a page load seconds after launch.
        assertEquals(0L, startedAt(c, "o_r_sha_17_21"), "nothing written yet");

        Path dir = Files.createDirectories(results.resolve("o_r_sha_17_21"));
        Files.writeString(dir.resolve("trace.jsonl"), "{\"at\":\"1787000000000\",\"kind\":\"progress\"}\n");

        assertEquals(1787000000000L, startedAt(c, "o_r_sha_17_21"),
                "once the trace exists the answer is there; caching the earlier nought is what "
                        + "made the duration column read a dash for a whole run");
    }

    @Test
    void aKnownStartTimeIsNotReReadEveryCall(@TempDir Path root) throws Exception {
        Path results = Files.createDirectories(root.resolve("results"));
        Path dir = Files.createDirectories(results.resolve("o_r_sha_21_25"));
        Files.writeString(dir.resolve("trace.jsonl"), "{\"at\":\"1787000000001\",\"kind\":\"progress\"}\n");
        Corpus c = new Corpus(results);

        assertEquals(1787000000001L, startedAt(c, "o_r_sha_21_25"));
        // Deleting the file must not change the answer: a real start time is still cached, which is
        // the half of the old behaviour that was right.
        Files.delete(dir.resolve("trace.jsonl"));
        assertEquals(1787000000001L, startedAt(c, "o_r_sha_21_25"), "a real answer stays cached");
    }
}
