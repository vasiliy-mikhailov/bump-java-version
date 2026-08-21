package tech.mikhailov.bjv.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A LANE HAS A WALL CLOCK NOW, AND A READER HAS TO BE ABLE TO SEE WHAT IT COST.
 *
 * <p>When a lane's budget runs out the bump stops between two stages, keeps its checkout and its
 * journal, and goes back to the queue with its round one higher. Six bumps in seven never reach
 * that at all; the seventh is the one somebody wants to find, and before this the page had no way
 * to tell it apart from a bump that had never started.
 *
 * <p>WHAT THESE PIN IS THE PART THAT WOULD GO WRONG QUIETLY. A state folded into another state, a
 * round number that is not on the wire, and a record page that shows the last boundary rather than
 * all of them, would each leave a page that looks entirely healthy and answers the wrong question.
 */
class ARoundIsOnTheRowAndTheRecordTest {

    private static final String BUMP = "owner/thing|abc123|17|21";
    private static final String SLUG = "owner_thing_abc123_17_21";

    private static String bumps(Path results) throws Exception {
        Method m = Corpus.class.getDeclaredMethod("bumps", long.class);
        m.setAccessible(true);
        return (String) m.invoke(new Corpus(results), 0L);
    }

    private static String detail(Path results) {
        Corpus corpus = new Corpus(results);
        return new Detail(results, corpus).bump(SLUG);
    }

    /** A settlement row as the sweep writes them, with the round appended last as both sides do. */
    private static void settle(Path results, String state, long at, String round, String prompts)
            throws IOException {
        Files.writeString(results.resolve("settlements.jsonl"),
                "{\"at\":\"" + at + "\",\"bump\":\"" + BUMP + "\",\"state\":\"" + state
                        + "\",\"because\":\"" + state + " because\",\"baseline\":false,"
                        + "\"gate\":false,\"resumed\":false,\"commit\":\"ff7a4ab3\",\"image\":\"\","
                        + "\"prompts\":\"" + prompts + "\",\"boms\":\"bb42094f\""
                        + (round == null ? "" : ",\"round\":\"" + round + "\"") + "}\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /**
     * PAUSED IS NOT QUEUED, however alike the two look from a distance.
     *
     * <p>{@code requeued} is folded into {@code queued} on purpose: it exists so the runner knows an
     * old verdict no longer counts, and a reader already has a word for waiting. A round boundary is
     * the opposite case. A bump that has never started and a bump that has burned three lane budgets
     * are both waiting, and the second one is the row a reader is looking for.
     */
    @Test
    void aPausedBumpIsNotFoldedIntoQueued(@TempDir Path results) throws Exception {
        settle(results, "paused", 1_000, "2", "54906737");

        String row = bumps(results);

        assertTrue(row.contains("\"verdict\":\"paused\""), row);
        assertTrue(row.contains("\"round\":\"2\""), row);
    }

    /**
     * AND AN ABSENT ROUND IS ABSENT RATHER THAN ONE.
     *
     * <p>Most of this corpus settled before lanes had a budget. Reporting those rows as round one
     * would be inventing a measurement nobody took, and it is the reading that would make the
     * column useless: every row would carry a number and none of them would mean anything.
     */
    @Test
    void aRowFromBeforeRoundsExistedCarriesNoRound(@TempDir Path results) throws Exception {
        settle(results, "PASS", 1_000, null, "54906737");

        assertTrue(bumps(results).contains("\"round\":null"), bumps(results));
    }

    /**
     * THE RECORD PAGE GETS EVERY BOUNDARY, NOT THE LAST ONE.
     *
     * <p>The corpus map keeps the newest row per bump, which is the right answer to the question the
     * list asks and the wrong one here. A round number resets when the pipeline changes under a
     * paused bump, so the repository that has been picked up five times and never once continued
     * reads as round one, and only the whole list says so.
     */
    @Test
    void theRecordCarriesEveryBoundaryAndWhichPipelineEndedIt(@TempDir Path results)
            throws Exception {
        Files.createDirectories(results.resolve(SLUG));
        settle(results, "bumping", 900, "1", "54906737");
        settle(results, "paused", 1_000, "1", "54906737");
        settle(results, "paused", 2_000, "1", "c3d4e5f6");
        settle(results, "out-of-rounds", 3_000, "1", "c3d4e5f6");
        // Somebody else's boundary shares the file and is not this bump's round.
        Files.writeString(results.resolve("settlements.jsonl"),
                "{\"at\":\"2500\",\"bump\":\"other/repo|def|17|21\",\"state\":\"paused\"}\n",
                StandardOpenOption.APPEND);

        String record = detail(results);
        String rounds = record.substring(record.indexOf("\"rounds\""));

        assertFalse(rounds.contains("other/repo"), rounds);
        assertTrue(rounds.contains("\"at\":1000"), rounds);
        assertTrue(rounds.contains("\"at\":2000"), rounds);
        assertTrue(rounds.contains("\"state\":\"out-of-rounds\""), rounds);
        assertFalse(rounds.contains("\"at\":900"),
                "a progress note is not a round that ended: " + rounds);
        // THE FINGERPRINT IS THE DIAGNOSIS. Two boundaries both numbered one look like a
        // coincidence until the page can see that the pipeline moved between them.
        assertTrue(rounds.contains("\"prompts\":\"54906737\""), rounds);
        assertTrue(rounds.contains("\"prompts\":\"c3d4e5f6\""), rounds);
    }

    /** A bump nobody ever paused says so with an empty list rather than with a missing field. */
    @Test
    void aBumpThatNeverPausedCarriesAnEmptyHistory(@TempDir Path results) throws Exception {
        Files.createDirectories(results.resolve(SLUG));
        settle(results, "PASS", 1_000, null, "54906737");

        assertTrue(detail(results).contains("\"rounds\":[]"), detail(results));
    }
}
