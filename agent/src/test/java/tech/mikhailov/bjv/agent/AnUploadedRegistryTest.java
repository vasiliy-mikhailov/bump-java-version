package tech.mikhailov.bjv.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A REGISTRY ARRIVES WITH MISTAKES IN IT, AND THE LOADER MUST SAY WHICH.
 *
 * <p>Five comma-separated fields — url, sha, from, to, key — and every comma is mandatory, because
 * two of the five may be blank and a row with a comma missing is a row whose columns cannot be
 * identified. Guessing which optional was omitted is how a credential ends up parsed as a JDK level.
 *
 * <p>The rule for bad rows is the sibling tool's: reported back with the others rather than dropped.
 */
class AnUploadedRegistryTest {

    private static final String SHA = "bdc86ebe64e2ec6d65e0caaff6a93c008b6a4fc5";
    private static final String GOOD =
            "https://github.com/causalnet/autojdk-maven-plugin," + SHA + ",11,17,\n"
            + "https://github.com/0xiaoyu/XiaoYu,,17,21,\n";

    @Test
    void itParsesTheFiveFields() {
        Registry.Parsed p = Registry.parse(GOOD);

        assertEquals(2, p.rows().size(), "rejected: " + p.rejected());
        assertEquals("causalnet/autojdk-maven-plugin", p.rows().get(0).repo());
        assertEquals(SHA, p.rows().get(0).sha());
        assertEquals(11, p.rows().get(0).from());
        assertEquals(17, p.rows().get(0).to());
    }

    @Test
    void aBlankShaMeansTheDefaultBranch() {
        Registry.Parsed p = Registry.parse(GOOD);

        assertFalse(p.rows().get(1).pinned(), "the second row left its sha blank");
        assertEquals(1, p.unpinned(), "and the count is reported so a reader can be warned");
    }

    @Test
    void everyCommaIsMandatoryIncludingTheTrailingOne() {
        // Without the trailing comma the key column is missing, and a four-field row cannot say
        // whether the absent one was the sha or the key.
        Registry.Parsed p = Registry.parse("https://github.com/o/n," + SHA + ",11,17");

        assertTrue(p.rows().isEmpty());
        assertTrue(p.rejected().get(0).why().contains("exactly 5"), p.rejected().get(0).why());
        assertTrue(p.rejected().get(0).why().contains("found 4"));
    }

    @Test
    void aBlankFieldIsNotAMissingField() {
        // Four commas, three values: this is the shape the format is FOR.
        Registry.Parsed p = Registry.parse("https://github.com/o/n,,11,17,");

        assertEquals(1, p.rows().size(), "rejected: " + p.rejected());
        assertFalse(p.rows().get(0).pinned());
        assertTrue(p.rows().get(0).key().isBlank());
    }

    @Test
    void aKeyIsCarriedButCountedOnlyInAggregate() {
        Registry.Parsed p = Registry.parse("https://github.com/o/n," + SHA + ",11,17,ghp_secret");

        assertEquals(1, p.keyed());
        // It is on the row because the lane needs it; nothing above this ever reports its value.
        assertEquals("ghp_secret", p.rows().get(0).key());
    }

    @Test
    void aBranchNameIsRefusedEvenThoughBlankIsAllowed() {
        // Blank says "resolve the default branch NOW and record what you got". A branch name says
        // "resolve it again next time", and the same row would then mean a different tree.
        Registry.Parsed p = Registry.parse("https://github.com/o/n,main,11,17,");

        assertTrue(p.rows().isEmpty());
        assertTrue(p.rejected().get(0).why().contains("commit or blank"), p.rejected().get(0).why());
    }

    @Test
    void anErrorMessageNeverEchoesAWholeLine() {
        // A rejected row may hold a credential, and the reason is rendered on a page.
        Registry.Parsed p = Registry.parse("https://github.com/o/n," + SHA + ",eleven,17,ghp_secret");

        assertFalse(p.rejected().get(0).why().contains("ghp_secret"), p.rejected().get(0).why());
    }

    @Test
    void theUrlIsReducedToTheIdentityEverythingElseIsKeyedBy() {
        // The bump key is repo|sha|from|to and every settlement in the corpus already uses it, so
        // this cannot become a URL without orphaning the record.
        assertEquals("owner/name", Registry.repoOf("https://github.com/owner/name.git"));
        assertEquals("owner/name", Registry.repoOf("https://github.com/owner/name/"));
        assertEquals("owner/name", Registry.repoOf("git@github.com:owner/name.git"));
        assertEquals("owner/name", Registry.repoOf("ssh://git@git.internal/owner/name"));
        assertEquals("owner/name", Registry.repoOf("owner/name"));
        assertEquals("", Registry.repoOf("github.com"));
        assertEquals("", Registry.repoOf(""));
    }

    @Test
    void aRowThatIsNotAUrlIsRefused() {
        Registry.Parsed p = Registry.parse("nonsense," + SHA + ",11,17,");

        assertTrue(p.rows().isEmpty());
        assertTrue(p.rejected().get(0).why().contains("repository URL"));
    }

    @Test
    void aHopThatGoesNowhereIsRefused() {
        Registry.Parsed p = Registry.parse("https://github.com/o/n," + SHA + ",21,17,");

        assertTrue(p.rejected().get(0).why().contains("above from"));
    }

    @Test
    void blankLinesAndCommentsAreNotMistakes() {
        Registry.Parsed p = Registry.parse("# the 11 to 17 corpus\n\n" + GOOD);

        assertEquals(2, p.rows().size());
        assertTrue(p.rejected().isEmpty(), "a comment is not a bad row: " + p.rejected());
    }

    @Test
    void aRepeatWithinOneFileIsNamedNotSilentlyFolded() {
        Registry.Parsed p = Registry.parse(GOOD + GOOD);

        assertEquals(2, p.rows().size());
        assertEquals(2, p.rejected().size());
        assertTrue(p.rejected().get(0).why().contains("already in this file"));
    }

    @Test
    void theManifestKeepsTheShapeTheSweepReads(@TempDir Path dir) throws IOException {
        Path manifest = dir.resolve("m.tsv");

        Registry.mergeInto(manifest, Registry.parse(GOOD).rows());

        // slug repo sha from to, whitespace-split by run.sh — so a blank sha needs a mark, or every
        // column after it shifts left and the hop is read out of the wrong field.
        List<String> lines = Files.readAllLines(manifest);
        assertEquals(2, lines.size());
        for (String line : lines) {
            assertEquals(5, line.split("\t").length, line);
        }
        assertTrue(lines.stream().anyMatch(l -> l.contains("\t-\t")), "blank sha is marked: " + lines);
    }

    @Test
    void mergingAddsOnlyWhatIsNew(@TempDir Path dir) throws IOException {
        Path manifest = dir.resolve("m.tsv");
        Registry.mergeInto(manifest, Registry.parse(GOOD).rows());

        int added = Registry.mergeInto(manifest,
                Registry.parse(GOOD + "https://github.com/zz/last," + SHA + ",21,25,").rows());

        assertEquals(1, added);
        assertEquals(3, Files.readAllLines(manifest).size());
    }

    @Test
    void anExistingRowKeepsItsCommit(@TempDir Path dir) throws IOException {
        Path manifest = dir.resolve("m.tsv");
        Registry.mergeInto(manifest, Registry.parse(GOOD).rows());

        Registry.mergeInto(manifest, Registry.parse(
                "https://github.com/causalnet/autojdk-maven-plugin,"
                        + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa,11,17,").rows());

        assertTrue(Files.readString(manifest).contains("bdc86ebe"), "the queued commit stands");
        assertFalse(Files.readString(manifest).contains("aaaaaaaa"));
    }

    @Test
    void theMergeReplacesByRenameSoAReaderMidRoundIsUnharmed(@TempDir Path dir) throws IOException {
        // The sweep holds the manifest open for a whole round, and this project has already lost a
        // 1439-row run to a truncate landing under that descriptor.
        Path manifest = dir.resolve("m.tsv");
        Registry.mergeInto(manifest, Registry.parse(GOOD).rows());
        Object before = Files.readAttributes(manifest, "unix:ino").get("ino");

        Registry.mergeInto(manifest,
                Registry.parse("https://github.com/zz/last," + SHA + ",21,25,").rows());

        assertFalse(before.equals(Files.readAttributes(manifest, "unix:ino").get("ino")),
                "same inode means it was written in place");
        assertTrue(Files.notExists(manifest.resolveSibling("m.tsv.staged")), "no litter");
    }

    @Test
    void aCredentialGoesOutsideTheServedDirectory(@TempDir Path dir) throws IOException {
        Path root = Files.createDirectories(dir.resolve("runroot"));
        Path served = Files.createDirectories(root.resolve("results"));
        Path credentials = root.resolve("credentials.tsv");

        Registry.recordKeys(credentials,
                Registry.parse("https://github.com/o/n," + SHA + ",11,17,ghp_secret").rows());

        assertTrue(Files.exists(credentials));
        // One careless endpoint that lists the served directory would otherwise publish every token
        // in the corpus.
        assertFalse(Files.exists(served.resolve("credentials.tsv")));
        try (var walk = Files.walk(served)) {
            assertTrue(walk.noneMatch(p -> {
                try {
                    return Files.isRegularFile(p) && Files.readString(p).contains("ghp_secret");
                } catch (IOException unreadable) {
                    return false;
                }
            }), "nothing under results/ may contain a key");
        }
    }

    @Test
    void aRotatedKeyReplacesTheOldOne(@TempDir Path dir) throws IOException {
        // Unlike a commit, which is pinned deliberately, a token is re-uploaded precisely because it
        // changed. Keeping the first one would leave every clone failing with a stale credential.
        Path credentials = dir.resolve("c.tsv");
        Registry.recordKeys(credentials,
                Registry.parse("https://github.com/o/n," + SHA + ",11,17,old").rows());

        Registry.recordKeys(credentials,
                Registry.parse("https://github.com/o/n," + SHA + ",11,17,new").rows());

        assertTrue(Files.readString(credentials).contains("new"));
        assertFalse(Files.readString(credentials).contains("old"));
    }

    @Test
    void anOriginIsKeptForAnythingNotOnTheObviousHost(@TempDir Path dir) throws IOException {
        Path origins = dir.resolve("origins.tsv");

        Registry.recordOrigins(origins,
                Registry.parse("https://git.internal/team/thing," + SHA + ",11,17,").rows());

        assertTrue(Files.readString(origins).contains("team/thing\thttps://git.internal/team/thing"));
    }
}
