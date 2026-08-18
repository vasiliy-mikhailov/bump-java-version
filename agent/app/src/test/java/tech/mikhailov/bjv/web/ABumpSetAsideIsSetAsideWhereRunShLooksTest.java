package tech.mikhailov.bjv.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A POSTPONEMENT IS A FILE NAME, AND THE NAME IS THE WHOLE AGREEMENT.
 *
 * <p>Two processes share it and nothing else. run.sh decides whether to launch a manifest row with
 * {@code [ -e "$RESULTS/postponed/$1" ]} over a key it flattens itself, and its watchdog asks the
 * same question every thirty seconds inside a lane that is already running. The page writes that
 * file. There is no handshake, no schema and no error path between them: a marker one character off
 * is a button that reports success, changes what a reader sees, and lets the bump run anyway.
 *
 * <p>SO THE FLATTENING IS CHECKED AGAINST run.sh'S OWN, TWICE. Once against the rule written out
 * longhand from the line in run.sh, which is the check that runs everywhere, and once against sed
 * itself where there is a shell to run it in. Sharing {@link Results#slug}'s regular expression
 * with the test would have proved only that a string equals itself, which is exactly how a second
 * copy of this rule would get in.
 *
 * <p>AND IT HAS TO COME BACK. run.sh clears markers only on a pass that launched nothing else, so
 * on a long sweep a postponement set by hand and not undone by hand is a repository parked for the
 * rest of the fortnight.
 */
class ABumpSetAsideIsSetAsideWhereRunShLooksTest {

    /** A real row off the corpus: owner/name, a real sha, and the hop it is queued for. */
    private static final String BUMP = "agwlvssainokuni/springapp3|3f2a1b9c7d1e4f80|17|21";

    /** What {@code /api/postpone} asks for: set aside, with a reason. */
    private static String setAside(Postpone postpone, String key, String why) {
        return postpone.ask(key, "", why, true);
    }

    /** What {@code /api/resume} asks for: bring it back. */
    private static String bringBack(Postpone postpone, String key) {
        return postpone.ask(key, "", "", false);
    }

    /**
     * run.sh's own transformation, written out longhand rather than borrowed:
     *
     * <pre>bs=$(printf '%s' "$repo|$sha|$from|$to" | sed 's/[^A-Za-z0-9]\+/_/g')</pre>
     *
     * <p>from run.sh:361, the line the launcher keys {@code postponed()} on, and again at run.sh:179
     * for the watchdog inside a running lane. A run of anything that is not an ASCII letter or digit
     * becomes one underscore. A loop rather than the same regular expression, so that a change to
     * {@link Results#slug} which no longer agrees with sed fails here instead of agreeing with
     * itself.
     */
    private static String asRunShFlattensIt(String bumpKey) {
        StringBuilder out = new StringBuilder();
        boolean inRun = false;
        for (char c : bumpKey.toCharArray()) {
            boolean alnum = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
            if (alnum) {
                out.append(c);
                inRun = false;
            } else if (!inRun) {
                out.append('_');
                inRun = true;
            }
        }
        return out.toString();
    }

    @Test
    void theMarkerLandsOnTheNameRunShTestsFor(@TempDir Path results) {
        Postpone postpone = new Postpone(results);

        String said = setAside(postpone, BUMP, "the gate has not moved in two hours");

        // The path run.sh would test: run.sh's own rule, under run.sh's own directory.
        Path whatRunShLooksFor = results.resolve("postponed").resolve(asRunShFlattensIt(BUMP));
        assertTrue(Files.exists(whatRunShLooksFor),
                "nothing at " + whatRunShLooksFor + "; the endpoint said " + said);
        assertEquals("agwlvssainokuni_springapp3_3f2a1b9c7d1e4f80_17_21",
                whatRunShLooksFor.getFileName().toString(),
                "and that name is what the launcher's sed produces");
        assertTrue(said.contains("\"postponed\":true"), said);
        assertTrue(said.contains("\"marker\":\"" + whatRunShLooksFor + "\""),
                "the reply names the file it wrote: " + said);
        assertTrue(said.contains("the gate has not moved in two hours"), said);
    }

    @Test
    void theServerAndSedAgreeOnEveryShapeOfKeyThisCorpusHas() throws Exception {
        // THE SAME CHECK, ASKED OF THE PROGRAM ITSELF. The rule above is a reading of run.sh; this
        // one runs run.sh's pipeline. Skipped where there is no GNU sed to run it, since the loop
        // above already guards the build on any host.
        assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("linux"),
                "run.sh's sed is a GNU sed on the sweep host");
        for (String key : new String[] {
                BUMP,
                "adorsys/kc-oid4vci-deployment|-|8|11",
                "aartiPl/tablevis|abc123|11|17",
                "owner/name.with.dots|HEAD~1|21|25",
                "owner/name|feature/branch name|8|21"}) {
            String sed = sed(key);
            assumeTrue(!sed.isBlank(), "no shell to compare against");
            assertEquals(sed, Results.slug(key), "the dashboard and run.sh disagree about " + key);
        }
    }

    /** run.sh's line, run. */
    private static String sed(String key) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("sh", "-c",
                "printf '%s' \"$1\" | sed 's/[^A-Za-z0-9]\\+/_/g'", "sh", key);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        return p.waitFor() == 0 ? out : "";
    }

    @Test
    void whatOneRouteSetsAsideTheOtherBringsBack(@TempDir Path results) {
        // THE WAY BACK IS THE POINT. run.sh clears these itself only on a pass that launched
        // nothing else, so without this a mis-click parks a repository for the rest of the sweep.
        Postpone postpone = new Postpone(results);
        Path marker = results.resolve("postponed").resolve(asRunShFlattensIt(BUMP));

        String set = setAside(postpone, BUMP, "stalled");
        assertTrue(set.contains("\"postponed\":true"), set);
        assertTrue(Files.exists(marker));

        String back = bringBack(postpone, BUMP);

        assertTrue(back.contains("\"postponed\":false"), back);
        assertTrue(back.contains("\"was\":true"), "and it says what it was: " + back);
        assertFalse(Files.exists(marker), "the marker run.sh reads is gone, not emptied");
    }

    @Test
    void aCallerWithNoRowBehindItCanAskForTheOtherState(@TempDir Path results) {
        // A control that knows only that it is a control. The routes carry the direction because a
        // stale tab gets it wrong; this is for a caller that has no tab at all.
        Postpone postpone = new Postpone(results);

        String on = postpone.ask(BUMP, "flip", "on a hunch", true);
        String off = postpone.ask(BUMP, "flip", "", false);

        assertTrue(on.contains("\"postponed\":true"), on);
        assertTrue(off.contains("\"postponed\":false"), off);
        assertEquals(Set.of(), markers(results), "the flip really did remove the file");
    }

    @Test
    void askingTheSameRouteTwiceSettlesRatherThanUndoingItself(@TempDir Path results) {
        // A page that only flips acts on what it believed when it rendered, so two readers on one
        // bump undo each other and neither can tell. Asking a direction twice has to stay there.
        Postpone postpone = new Postpone(results);

        setAside(postpone, BUMP, "stalled");
        String again = setAside(postpone, BUMP, "stalled");
        assertTrue(again.contains("\"postponed\":true"), again);
        assertTrue(again.contains("\"changed\":false"), "nothing moved the second time: " + again);

        bringBack(postpone, BUMP);
        String backAgain = bringBack(postpone, BUMP);
        assertTrue(backAgain.contains("\"postponed\":false"), backAgain);
        assertTrue(backAgain.contains("\"changed\":false"), backAgain);
    }

    @Test
    void aStateItCannotReadIsRefusedRatherThanGuessed(@TempDir Path results) {
        Postpone postpone = new Postpone(results);

        String said = postpone.ask(BUMP, "maybe", "", true);

        assertTrue(said.contains("\"postponed\":false"), said);
        assertTrue(said.contains("\"error\":"), said);
        assertEquals(Set.of(), markers(results), "a typo in the query does not park a repository");
    }

    @Test
    void theKeyOrTheSlugLandOnOneFile(@TempDir Path results) {
        // The page holds the slug; a script, a log line or a manifest row holds the key.
        // Results.slug flattens RUNS of non-alphanumerics, so it is idempotent and both spellings
        // are the same request.
        Postpone postpone = new Postpone(results);

        setAside(postpone, BUMP, "by key");
        String bySlug = setAside(postpone, Results.slug(BUMP), "by slug");

        assertTrue(bySlug.contains("\"changed\":false"), "it was already set aside: " + bySlug);
        assertEquals(1, markers(results).size(), "one marker, not one per spelling");
        assertTrue(bringBack(postpone, Results.slug(BUMP)).contains("\"postponed\":false"));
        assertEquals(Set.of(), markers(results), "and the slug clears what the key set");
    }

    @Test
    void aKeyCannotWriteOutsideTheDirectoryTheLauncherReads(@TempDir Path results) {
        Postpone postpone = new Postpone(results);

        setAside(postpone, "../../etc/passwd|8|11", "");

        assertEquals(Set.of("_etc_passwd_8_11"), markers(results),
                "every separator flattens, so there is no path left to traverse");
    }

    @Test
    void aReasonIsRecordedAndThereIsAlwaysOne(@TempDir Path results) throws IOException {
        // run.sh prints the first 90 characters of this file when a lane stops for it, so an empty
        // marker is a lane that stopped and said nothing about why.
        Postpone postpone = new Postpone(results);

        setAside(postpone, BUMP, "");

        Path marker = results.resolve("postponed").resolve(asRunShFlattensIt(BUMP));
        assertFalse(Files.readString(marker).isBlank(), "an unexplained postponement still says so");
        assertTrue(setAside(postpone, BUMP, "").contains("\"why\":\""), "and it is read back");
    }

    @Test
    void theDirectoryIsMadeWhenNothingHasBeenSetAsideYet(@TempDir Path results) {
        // A fresh sweep has no postponed directory at all: run.sh only ever tests inside it, and
        // the supervisor creates it when it first uses it.
        Postpone postpone = new Postpone(results);
        assertFalse(Files.exists(results.resolve("postponed")));

        String said = setAside(postpone, BUMP, "first of the sweep");

        assertTrue(Files.isDirectory(results.resolve("postponed")), said);
        assertTrue(said.contains("\"postponed\":true"), said);
    }

    @Test
    void aDirectoryThatIsAlreadyThereIsNotAProblem(@TempDir Path results) throws IOException {
        // The supervisor usually gets there first, and its markers are not this server's to lose.
        Path dir = Files.createDirectories(results.resolve("postponed"));
        Files.writeString(dir.resolve("owner_other_abc_17_21"), "silent for 150 minutes");
        Postpone postpone = new Postpone(results);

        String said = setAside(postpone, BUMP, "mine");

        assertTrue(said.contains("\"postponed\":true"), said);
        assertEquals(Set.of("owner_other_abc_17_21", asRunShFlattensIt(BUMP)), markers(results),
                "the supervisor's postponement is still standing");
    }

    @Test
    void aWriteThatFailsIsReportedRatherThanClaimed(@TempDir Path results) throws IOException {
        // THE PROPERTY UNDER TEST IS THAT THE REPLY IS THE DISK. The failure modelled here is a
        // postponed path that is not a directory at all, because it is the one this build can
        // reach: the tests run as root inside a container, where no permission bit refuses
        // anything. The two below model the ownership case that actually occurs on the host, and
        // skip when whoever runs them cannot be refused either.
        Files.writeString(results.resolve("postponed"), "not a directory");

        String said = setAside(new Postpone(results), BUMP, "stalled");

        assertTrue(said.contains("\"postponed\":false"), "it did not happen, and says so: " + said);
        assertTrue(said.contains("\"changed\":false"), said);
        assertTrue(said.contains("could not set that bump aside"), said);
    }

    @Test
    void aRefusedWriteNamesTheOwnershipThatRefusedIt(@TempDir Path results) {
        // The exception the sweep host really produces, handed to the code that has to explain it.
        // Measured there: with results/postponed created by the supervisor as root from inside a
        // container, both writing a marker and unlinking one come back as this, carrying nothing
        // but the path.
        Postpone postpone = new Postpone(results);
        Path dir = results.resolve("postponed");

        String setting = postpone.explain(new AccessDeniedException(dir.toString()), true);
        String clearing = postpone.explain(new AccessDeniedException(dir.toString()), false);

        assertTrue(setting.contains("could not set that bump aside"), setting);
        assertTrue(setting.contains(dir.toString()), setting);
        assertTrue(setting.contains("belongs to another user"), setting);
        assertTrue(clearing.contains("could not bring that bump back"), clearing);
    }

    @Test
    void aDirectoryItCannotWriteIntoIsReportedRatherThanClaimed(@TempDir Path results)
            throws IOException {
        // THE ASYMMETRY THIS ENDPOINT LIVES WITH. The supervisor writes markers as root from inside
        // a container; the dashboard is uid 1000. Measured on the sweep host: when the supervisor
        // creates results/postponed first it is root-owned and mode 755, and the server can then
        // neither add a marker nor unlink one. A directory with the write bit off models exactly
        // that, and the reply has to read as a refusal, because a reply built from what was asked
        // for would show a bump as parked while the launcher went on launching it.
        Path dir = Files.createDirectories(results.resolve("postponed"));
        assumeTrue(dir.toFile().setWritable(false, false), "cannot model a directory we may not write");
        assumeTrue(!Files.isWritable(dir),
                "this build runs as root in a container, where a permission bit refuses nothing");
        try {
            String said = setAside(new Postpone(results), BUMP, "stalled");

            assertTrue(said.contains("\"postponed\":false"), "read back off disk: " + said);
            assertTrue(said.contains("\"error\":"), said);
            assertTrue(said.contains("could not set that bump aside"), said);
            assertTrue(said.contains("belongs to another user"), "it names the asymmetry: " + said);
        } finally {
            dir.toFile().setWritable(true, true);
        }
    }

    @Test
    void aMarkerItCannotRemoveIsReportedRatherThanClaimed(@TempDir Path results) throws IOException {
        // The other half: a postponement the server cannot clear, which is a bump only run.sh's own
        // clearing can free. Saying so is the difference between a stuck repository and a mystery.
        Path dir = Files.createDirectories(results.resolve("postponed"));
        Files.writeString(dir.resolve(asRunShFlattensIt(BUMP)), "set aside by the supervisor");
        assumeTrue(dir.toFile().setWritable(false, false), "cannot model a directory we may not write");
        assumeTrue(!Files.isWritable(dir),
                "this build runs as root in a container, where a permission bit refuses nothing");
        try {
            String said = bringBack(new Postpone(results), BUMP);

            assertTrue(said.contains("\"postponed\":true"), "it is still set aside: " + said);
            assertTrue(said.contains("could not bring that bump back"), said);
        } finally {
            dir.toFile().setWritable(true, true);
        }
    }

    @Test
    void nothingIsSetAsideWithoutABump(@TempDir Path results) {
        Postpone postpone = new Postpone(results);
        for (String key : new String[] {null, "", "   "}) {
            String said = setAside(postpone, key, "why not");
            assertTrue(said.contains("\"postponed\":false"), String.valueOf(said));
            assertTrue(said.contains("no bump given"), said);
        }
        assertFalse(Files.exists(results.resolve("postponed")), "and no directory was made for it");
    }

    @Test
    void theListingIsWhatTheLauncherWouldSkip(@TempDir Path results) {
        // A page has to draw the toggle in the state it is already in, and one directory listing
        // answers for the whole corpus rather than a question per row.
        Postpone postpone = new Postpone(results);
        assertTrue(postpone.listing().contains("\"postponed\":[]"), "nothing set aside yet");

        setAside(postpone, BUMP, "stalled");
        setAside(postpone, "aartiPl/tablevis|abc123|8|11", "a wall we already know");

        String said = postpone.listing();
        assertTrue(said.contains("\"aartiPl_tablevis_abc123_8_11\""), said);
        assertTrue(said.contains("\"" + asRunShFlattensIt(BUMP) + "\""), said);
        assertTrue(said.contains(results.resolve("postponed").toString()), said);
    }

    /** Whatever is in the directory run.sh reads, by name. */
    private static Set<String> markers(Path results) {
        try (var files = Files.list(results.resolve("postponed"))) {
            return files.map(p -> p.getFileName().toString()).collect(Collectors.toSet());
        } catch (IOException noDirectory) {
            return Set.of();
        }
    }
}
