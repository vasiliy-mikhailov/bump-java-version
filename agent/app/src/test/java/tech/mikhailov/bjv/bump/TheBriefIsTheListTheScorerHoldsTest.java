package tech.mikhailov.bjv.bump;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE AGENT IS SHOWN THE ROWS IT WILL BE SCORED ON.
 *
 * <p>For most of this corpus it was not, and that was the largest single reason a floor went
 * unmet. The scorer reads the bill of materials, 337 rows on a hop, one per artifact per version
 * line. The brief was seventeen lines of hand-written prose in {@link Floors}, introduced by
 * pins.md as "THESE, AND NOTHING ELSE". Everything in the gap was scored on every bump and named
 * to the agent on none: netty and postgresql between them carried 199 of the 1,118 CRITICAL+HIGH
 * left standing across the corpus and appeared in no after-pins prompt once.
 *
 * <p>Where an artifact did appear the collapse did its own damage. jackson-databind is scored on
 * twenty-two rows and the prose named one, 2.21.4, so a project on 2.3 was shown a version that
 * the scorer's own line rule then refuses to apply to it, while the row it was actually graded on
 * went unmentioned. Reading sixty-eight bumps that passed with floors unmet, the commonest finding
 * was an agent that had done exactly what it was asked.
 */
class TheBriefIsTheListTheScorerHoldsTest {

    private static final List<Hop> HOPS =
            List.of(new Hop(8, 11), new Hop(11, 17), new Hop(17, 21), new Hop(21, 25));

    @Test
    void everyRowTheScorerHoldsIsNamedInTheBrief(@TempDir Path ws) {
        // THE WHOLE POINT, ASSERTED DIRECTLY. Not "the lists resemble each other": the agent is
        // graded row by row, so a row it cannot see is a row it cannot meet.
        for (Hop hop : HOPS) {
            String brief = afterPins(hop, ws);
            for (Bom.Floor floor : Bom.of(hop, "hardens")) {
                assertTrue(brief.contains(floor.coordinates() + " " + floor.version()),
                        hop + ": the scorer holds " + floor.coordinates() + " " + floor.version()
                                + " and the brief never names it");
            }
        }
    }

    @Test
    void theArtifactsThatCarriedTheResidualAreNamedNow(@TempDir Path ws) {
        // MEASURED RATHER THAN CHOSEN. These are the coordinates at the top of the corpus's own
        // residual ranking that Floors mentions zero times.
        String brief = afterPins(new Hop(17, 21), ws);
        for (String artifact : List.of("io.netty:netty-codec-http", "io.netty:netty-handler",
                "io.netty:netty-codec", "org.postgresql:postgresql")) {
            assertTrue(brief.contains(artifact),
                    artifact + " is scored on every bump and is not in the brief");
        }
    }

    @Test
    void anArtifactCarriesOneRowPerLineSoAProjectCanFindItsOwn(@TempDir Path ws) {
        String brief = afterPins(new Hop(17, 21), ws);
        long rows = brief.lines()
                .filter(l -> l.contains("com.fasterxml.jackson.core:jackson-databind ")).count();
        assertTrue(rows > 10, "one row per line rather than one per artifact, got " + rows);
        // THE ROW A PROJECT ON 2.3 IS ACTUALLY GRADED ON, which is the one it never used to see.
        assertTrue(brief.contains("com.fasterxml.jackson.core:jackson-databind 2.3.5"),
                "the head of the 2.3 line is missing from the brief");
    }

    @Test
    void theReasonSurvivesOntoTheRowItArguesFor(@TempDir Path ws) {
        // A FLOOR WITHOUT A REASON IS INDISTINGUISHABLE FROM A SUPERSTITION, which is why the
        // prose stays even though it is no longer the list.
        assertTrue(afterPins(new Hop(11, 17), ws).contains("UpgradeSpringBoot"),
                "the instruction to run the recipe rather than type a number reaches the brief");

        // AND IT IS KEYED PER LINE, because tomcat's 9.0 row and its 10.1 row are two different
        // arguments: one says stay on the line Spring is not managing, the other says this is what
        // Boot 3.5.16 pins. A map keyed by artifact alone keeps whichever came last.
        Map<String, String> why = Floors.reasons(21);
        assertNotEquals(why.get("org.apache.tomcat.embed:tomcat-embed-core@9.0"),
                why.get("org.apache.tomcat.embed:tomcat-embed-core@10.1"),
                "one artifact, two lines, two reasons");
    }

    @Test
    void theDialectAndTheOtherSpellingsTravelWithTheRow(@TempDir Path ws) {
        // BOTH WERE INVISIBLE AND BOTH COST FLOORS. An agent shown only the maven plugin
        // coordinate for jacoco reported it not applicable on a Gradle module, while the row that
        // scored it carries org.jacoco:* and answers to the whole family; and a gradle-only row on
        // a Maven module is a turn spent ruling out something that was never a floor here.
        String brief = afterPins(new Hop(17, 21), ws);
        assertTrue(brief.contains("also spelled"), "the other spellings of a row are not shown");
        assertTrue(brief.contains("gradle only") || brief.contains("maven only"),
                "a row that answers for one build system does not say so");
    }

    private static String afterPins(Hop hop, Path ws) {
        return Agents.forHop(hop, ws).stream()
                .filter(d -> d.name().equals(Agents.named("after-pins-doer", "adhoc")))
                .findFirst().orElseThrow().systemPrompt();
    }
}
