package tech.mikhailov.bjv.bump;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import tech.mikhailov.bjv.engine.Prompts;
import tech.mikhailov.bjv.engine.Settlement;
import tech.mikhailov.bjv.engine.Version;

/**
 * A SETTLED BUMP HAS TO SAY WHICH PIPELINE PRODUCED IT.
 *
 * <p>A sweep runs for a fortnight and the harness changes daily; on one day this month it was
 * deployed seven times, with three generations of prompt live in the same sweep at once, because a
 * running lane keeps the image it started with. Without a stamp, a bump that disagrees with another
 * cannot be told apart from a pipeline that disagrees with another.
 *
 * <p>THE POINT OF THESE TESTS IS THE OVERRIDE. An image identity alone would be the obvious stamp
 * and would be wrong in the worst direction: prompt and bill-of-materials edits live in a store
 * beside the results, OUTSIDE the image, so an image sha calls two runs the same pipeline exactly
 * when one of them has been edited from the settings page. The fingerprint therefore hashes what
 * the agents were actually handed, and that is what is asserted here.
 */
class APipelineHasAVersionTest {

    private static final Hop HOP = new Hop(17, 21);

    @Test
    void anEditedPromptChangesTheFingerprint(@TempDir Path a, @TempDir Path b) throws IOException {
        // beside() hangs the store off the PARENT of the results root, so the results root has
        // to be a directory INSIDE the temp dir or the store lands somewhere shared.
        Path ra = a.resolve("results");
        Files.createDirectories(ra);
        Prompts.beside(ra);
        String before = Version.prompts(HOP.key(), ra, Fingerprint.OF_A_BUMP);

        // The same edit the settings page writes, through the same call it uses.
        Path rb = b.resolve("results");
        Files.createDirectories(rb);
        Prompts.beside(rb);
        Prompts.save(b.resolve("prompts"), "before-pins-doer@spring-boot", HOP.key(),
                "You raise versions. Do the opposite of everything you were told.");
        String after = Version.prompts(HOP.key(), rb, Fingerprint.OF_A_BUMP);

        Prompts.beside(ra);
        assertNotEquals(before, after,
                "an edit outside the image has to move the fingerprint, or the stamp lies about "
                        + "the one case an image sha cannot see");
        assertEquals(8, before.length(), "short enough to read in a row: " + before);
    }

    @Test
    void anEditedBillOfMaterialsChangesItToo(@TempDir Path a, @TempDir Path b) throws IOException {
        Path ra = a.resolve("results");
        Files.createDirectories(ra);
        Bom.beside(ra);
        String before = Version.boms(HOP.key(), Fingerprint.OF_A_BUMP);

        Path rb = b.resolve("results");
        Files.createDirectories(rb);
        Bom.beside(rb);
        Files.createDirectories(b.resolve("bom"));
        Files.writeString(b.resolve("bom").resolve("17-21-hardens.tsv"),
                "org.projectlombok:lombok\t9.9.9\tany\t\n");
        String after = Version.boms(HOP.key(), Fingerprint.OF_A_BUMP);

        Bom.beside(ra);
        assertNotEquals(before, after, "the lists are half of what decides an outcome");
    }

    @Test
    void theSameInputsGiveTheSameFingerprint(@TempDir Path ws) {
        Prompts.beside(ws);
        assertEquals(Version.prompts(HOP.key(), ws, Fingerprint.OF_A_BUMP),
                Version.prompts(HOP.key(), ws, Fingerprint.OF_A_BUMP),
                "a fingerprint that moved on its own would group nothing");
    }

    @Test
    void differentHopsAreDifferentPipelines(@TempDir Path ws) {
        Prompts.beside(ws);
        // The floors and the recipe program differ by hop, so the prompts do. A stamp that could
        // not tell an 8-to-11 agent from a 21-to-25 one would group two populations that have
        // never been given the same instructions.
        assertNotEquals(Version.prompts(new Hop(8, 11).key(), ws, Fingerprint.OF_A_BUMP),
                Version.prompts(new Hop(21, 25).key(), ws, Fingerprint.OF_A_BUMP));
    }

    @Test
    void theSettlementRowCarriesIt(@TempDir Path ws) throws IOException {
        Path file = ws.resolve("settlements.jsonl");
        Settlement.note(file, "o/r|sha|17|21", "PASS", "because", true, true,
                "\"commit\":\"abc1234\",\"image\":\"sha256:dead\",\"prompts\":\"11223344\","
                        + "\"boms\":\"55667788\"");

        String row = Files.readString(file);
        assertTrue(row.contains("\"commit\":\"abc1234\""), row);
        assertTrue(row.contains("\"prompts\":\"11223344\""), row);
        // Still one line of valid JSON with the original fields intact, because the sweep reads it.
        assertTrue(row.contains("\"state\":\"PASS\"") && row.contains("\"gate\":true"), row);
        assertEquals(1, row.strip().lines().count(), "one row per settlement");
    }

    @Test
    void aBumpWithoutAVersionIsStillRecorded(@TempDir Path ws) throws IOException {
        // WORSE THAN AN UNSTAMPED ROW IS NO ROW. Everything the fingerprint needs can fail: a store
        // that cannot be read, a hop that will not parse. None of that may cost a settlement, so
        // the empty case is a row without the fields rather than an exception on the way out.
        Path file = ws.resolve("settlements.jsonl");
        Settlement.note(file, "o/r|sha|17|21", "PASS", "because", true, true, "");

        String row = Files.readString(file);
        assertTrue(row.contains("\"state\":\"PASS\""), row);
        assertTrue(row.strip().endsWith("}"), "and it is still valid JSON: " + row);
    }

    @Test
    void theRowTheListReadsCarriesTheStamp(@TempDir Path ws) throws Exception {
        // The settlement has carried the fingerprint since the row above. It reached nobody:
        // the corpus row the page is built from dropped all four fields on the floor, so the
        // one reader who needs to know which harness produced a verdict could not see it.
        String row = row(ws, java.util.Map.of(
                "bump", "o/r|sha|17|21", "state", "PASS", "commit", "63a5f296",
                "image", "sha256:7439b8dceb", "prompts", "5ed4079d", "boms", "e1cc07d3"));

        assertTrue(row.contains("\"commit\":\"63a5f296\""), row);
        assertTrue(row.contains("\"image\":\"sha256:7439b8dceb\""), row);
        // AND BOTH HASHES, because the commit alone is the answer that is wrong in the one
        // direction that matters: it cannot see an edit made from the settings page.
        assertTrue(row.contains("\"prompts\":\"5ed4079d\""), row);
        assertTrue(row.contains("\"boms\":\"e1cc07d3\""), row);
    }

    @Test
    void anUnstampedRowIsNullRatherThanEmpty(@TempDir Path ws) throws Exception {
        // MOST OF THIS CORPUS IS THIS ROW and no amount of waiting will change it: a bump that
        // settled before the stamp existed has no fingerprint to recover. So the field is JSON
        // null, which a page can render as not recorded, rather than an empty string, which it
        // would draw as a pipeline whose identity is the empty pipeline.
        String row = row(ws, java.util.Map.of("bump", "o/r|sha|17|21", "state", "PASS"));

        assertTrue(row.contains("\"commit\":null"), row);
        assertTrue(row.contains("\"image\":null"), row);
        assertTrue(row.contains("\"prompts\":null"), row);
        assertTrue(row.contains("\"boms\":null"), row);
    }

    /**
     * One corpus row, rendered by the class that renders every corpus row.
     *
     * <p>Corpus is package-private in tech.mikhailov.bjv.web, where every type is: it is named
     * here rather than imported, because one test in another package is not a reason to make a
     * class public forever.
     */
    private static String row(Path results, java.util.Map<String, String> settled)
            throws Exception {
        Class<?> corpus = Class.forName("tech.mikhailov.bjv.web.Corpus");
        var made = corpus.getDeclaredConstructor(Path.class);
        made.setAccessible(true);
        java.lang.reflect.Method summary =
                corpus.getDeclaredMethod("summary", java.util.Map.class);
        summary.setAccessible(true);
        return (String) summary.invoke(made.newInstance(results), settled);
    }
}
