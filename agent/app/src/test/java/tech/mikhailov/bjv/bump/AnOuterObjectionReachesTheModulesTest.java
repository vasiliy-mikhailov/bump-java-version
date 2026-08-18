package tech.mikhailov.bjv.bump;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AN OUTER `again` MUST REACH THE WORK, OR IT IS A SECOND WALK IN THE DARK.
 *
 * <p>The modules stage is a triad whose doer is the whole walk. Its verifier can look at twenty
 * modules and answer {@code again}, and for as long as the walk was a hand-written lambda that
 * lambda took {@code (plan, feedback)} and used neither: the objection was accepted, discarded, and
 * the identical walk ran a second time having been told nothing. Every leaf doer in the system
 * splices its feedback into the task; the two nesting sites were the only ones that did not, and
 * they were the two where a wasted round costs the most.
 *
 * <p>THIS IS A SOURCE TEST, WHICH IS A COMPROMISE AND WORTH SAYING SO. Running the walk for real
 * needs a workspace, a runner, a scanner and a model, so the previous attempt at this test ran the
 * walk over ZERO modules and asserted only that a field had been written. That is green whether or
 * not the field reaches a single brief: deleting both splice sites left the whole suite passing,
 * which is the failure this file exists to prevent and not one it could detect.
 *
 * <p>So it reads the source. Crude, and it fails the moment somebody deletes the thing it guards,
 * which the honest alternative did not.
 */
class AnOuterObjectionReachesTheModulesTest {

    private static String bump() throws IOException {
        return Files.readString(
                Path.of("src/main/java/tech/mikhailov/bjv/bump/Bump.java"), StandardCharsets.UTF_8);
    }

    @Test
    void everyModuleScopedBriefCarriesTheObjection() throws IOException {
        String source = bump();

        // The two briefs a module-scoped agent can be started from: the pin phases build their own,
        // and everything else in the walk goes through moduleBrief.
        assertTrue(source.contains("+ walkObjection"),
                "no brief splices the objection, so an outer `again` re-runs the walk blind");
        assertEquals(2, source.split("\\+ walkObjection", -1).length - 1,
                "both module-scoped briefs carry it: the pin phases' and moduleBrief's. One is the "
                        + "shape of the bug this replaced, where the objection reached some of the "
                        + "walk and not the rest");
    }

    @Test
    void theObjectionIsWrittenByTheStageThatReceivesIt() throws IOException {
        String source = bump();

        // Written where the outer verifier's answer arrives, not somewhere convenient. A field set
        // by the walk itself would be the walk telling itself what it had been told.
        assertTrue(source.contains("walkObjection ="),
                "something assigns it, or the splices are quoting a permanently empty string");
        assertTrue(source.contains("private String walkObjection"),
                "and it is the bump's own state, per run rather than per module");
    }

    @Test
    void theFirstWalkCarriesNothing() throws IOException {
        String source = bump();

        // EMPTY ON THE FIRST PASS. A brief that always ends with a paragraph about a reviewer's
        // objection, on a walk where no reviewer has said anything, is an invitation to invent one.
        assertTrue(source.contains("private String walkObjection = \"\""),
                "it starts empty, so the first walk reads as a first walk");
    }
}
