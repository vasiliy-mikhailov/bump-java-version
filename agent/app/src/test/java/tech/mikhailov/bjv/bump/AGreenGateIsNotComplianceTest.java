package tech.mikhailov.bjv.bump;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A GREEN GATE AND A COMPLIANT PROJECT ARE DIFFERENT CLAIMS.
 *
 * <p>The gate asks whether the project builds under the target JDK and kept every test that passed
 * before. Both can be true of a project still sitting on a Lombok that survives only because nothing
 * exercised it, or on a Spring Boot twelve patch releases behind its own line. That second case is
 * measured, not hypothetical: a repository passed at Boot 3.5.4 against a 3.5.16 head and carried
 * Tomcat 10.1.43 with eleven CRITICAL+HIGH through a PASS that moved nothing.
 *
 * <p>EVERY ROW IS THE HEAD OF A LINE, and that is what makes the list safe to act on without
 * judgement. A row answers only for projects already on its line: same major, same minor, lower
 * patch. Nothing here ever asks a project to cross a line, because crossing one is a migration and
 * this is a patch.
 */
class AGreenGateIsNotComplianceTest {

    private static final List<Hop> HOPS =
            List.of(new Hop(8, 11), new Hop(11, 17), new Hop(17, 21), new Hop(21, 25));

    @Test
    void everyHopHasAFileAndEveryRowIsWellFormed() {
        // A reader that drops what it cannot read produces a shorter list and a healthier looking
        // number, which is how a percentage lies. Loading throws instead.
        for (Hop hop : HOPS) {
            List<Bom.Floor> floors = Bom.of(hop);
            assertTrue(floors.size() >= 14, hop + " has " + floors.size() + " rows");
            for (Bom.Floor f : floors) {
                assertTrue(f.coordinates().contains(":"), f.toString());
                assertTrue(f.version().matches("\\d+\\.\\d+\\.\\d+.*|\\d+\\.\\d+"),
                        "a head is written major.minor.patch: " + f);
                assertTrue(f.spellings().contains(f.coordinates()),
                        "the canonical name is one of its own spellings: " + f);
            }
        }
    }

    @Test
    void theFileIsKeyedByHopBecauseThePhaseColumnNeedsBothEnds() {
        // "before the JDK" and "after the JDK" mean nothing without knowing which JDK is being
        // left as well as which is being reached.
        assertEquals("8-11", Bom.name(new Hop(8, 11)));
        assertEquals("21-25", Bom.name(new Hop(21, 25)));
        // A hop the corpus does not run is answered by the nearest rung at or below its target,
        // because a 17 to 19 move crosses the walls a 17 to 21 move crosses.
        assertEquals("11-17", Bom.name(new Hop(11, 18)));
        // A MULTI-RUNG HOP IS JUDGED BY WHERE IT LANDS, which is what Floors.forTarget already
        // does: a project going 8 to 25 in one move has to meet what 25 needs, and meeting what 11
        // needed on the way is not a thing anybody can check afterwards.
        assertEquals("21-25", Bom.name(new Hop(8, 25)));
    }

    @Test
    void springBootIsTheLineTheTargetCanRun() {
        // Boot 3 needs Java 17, so 11 is held at the last of the 2.x line and everything above it
        // reads the 3.5 line. This is the version the corpus asked for, hop by hop.
        assertEquals("2.7.18", headOf(new Hop(8, 11), "spring-boot-starter-parent"));
        for (Hop hop : List.of(new Hop(11, 17), new Hop(17, 21), new Hop(21, 25))) {
            assertEquals("3.5.16", headOf(hop, "spring-boot-starter-parent"), hop.toString());
        }
    }

    @Test
    void tomcatCarriesAHeadForEveryLineTheCorpusMeETS() {
        // 10.1.55 on every hop, because Boot 3 brings a Tomcat 10.1 of its own and 10.1.43 has
        // eleven CRITICAL+HIGH that 10.1.55 does not, and nothing was asking that line to move.
        for (Hop hop : HOPS) {
            assertTrue(headsOf(hop, "tomcat-embed-core").contains("10.1.55"),
                    hop + " has no 10.1 head: " + headsOf(hop, "tomcat-embed-core"));
        }
        // AND THE 9.0 HEAD SURVIVES BESIDE IT WHERE IT WAS. They do not compete: one answers for
        // projects on 9.0 and the other for projects on 10.1. Collapsing them into one row would
        // ask a Tomcat 9 project to cross into Tomcat 10, which is the jakarta rename.
        //
        // 9.0.118 AND NOT 9.0.105, because the head was itself carrying fifteen CRITICAL+HIGH.
        // Measured 2026-08-22 against the database the corpus is scored with: 9.0.105 scores 15 and
        // 9.0.118 scores none. A row that names a vulnerable version asks a project to move onto
        // one, which is the opposite of what this list is for.
        assertTrue(headsOf(new Hop(21, 25), "tomcat-embed-core").containsAll(
                List.of("9.0.118", "10.1.55")),
                headsOf(new Hop(21, 25), "tomcat-embed-core").toString());
    }

    @Test
    void aRowAnswersOnlyForProjectsOnItsOwnLine() {
        // THE RULE THE WHOLE LIST RESTS ON. A project on Tomcat 9.0.65 is a patch away from 9.0.118
        // and a migration away from 10.1.55, and only the first is this list's business.
        Bom.Compliance nine = Bom.against(new Hop(21, 25), Map.of(),
                Map.of("org.apache.tomcat.embed:tomcat-embed-core", "9.0.65"), "maven");

        assertEquals(1, nine.applicable(), "one row applies, not two: " + nine.verdicts());
        assertEquals(1, nine.missed());
        assertEquals("9.0.118", nine.outstanding().get(0).floor().version(),
                "and it is the head of the line the project is actually on");

        Bom.Compliance ten = Bom.against(new Hop(21, 25), Map.of(),
                Map.of("org.apache.tomcat.embed:tomcat-embed-core", "10.1.43"), "maven");

        assertEquals(1, ten.applicable(), ten.verdicts().toString());
        assertEquals("10.1.55", ten.outstanding().get(0).floor().version());
    }

    @Test
    void aProjectOnADifferentLineIsNotFailedForBeingOnIt() {
        // A Boot 2.5 project on a hop whose head is 3.5.16 needs a migration, and saying so with a
        // compliance miss would be an accusation about work this list cannot do.
        Bom.Compliance c = Bom.against(new Hop(17, 21), Map.of(),
                Map.of("org.springframework.boot:spring-boot-starter-parent", "2.5.15"), "maven");

        assertEquals(0, c.applicable(), c.verdicts().toString());
        assertEquals(-1, c.percent(), "no percentage, rather than nought per cent");
    }

    @Test
    void aProjectOnTheLineAndBelowTheHeadIsTheOneCaseThisIsFor() {
        Bom.Compliance c = Bom.against(new Hop(17, 21), Map.of(),
                Map.of("org.springframework.boot:spring-boot-starter-parent", "3.5.4"), "maven");

        assertEquals(1, c.applicable());
        assertEquals(1, c.missed(), "3.5.4 is twelve patches behind its own line: " + c.verdicts());
    }

    @Test
    void aGradleProjectIsNotFailedForNotWritingMavenWords() {
        // Floors names Spring Boot as spring-boot-starter-parent, a string that cannot appear in a
        // Gradle build. A Gradle project already on the head would read as below it, forever.
        Bom.Compliance c = Bom.against(new Hop(17, 21),
                Map.of("org.springframework.boot", "3.5.16"), Map.of(), "gradle");

        assertTrue(c.met() >= 1, "the plugins-block spelling counts: " + c);
        assertEquals(0, c.missed(), c.outstanding().toString());
    }

    @Test
    void aRowOneBuildSystemCannotHaveDoesNotCountAgainstIt() {
        // maven-compiler-plugin has no Gradle analogue and the wrapper has no Maven one. Counting
        // an impossible row as a miss turns a percentage into an accusation.
        for (String build : new String[] {"maven", "gradle"}) {
            Bom.Compliance c = Bom.against(new Hop(17, 21), Map.of(), Map.of(), build);
            assertEquals(0, c.applicable(), "nothing declared, nothing failed, on " + build);
            assertEquals(-1, c.percent());
        }
    }

    @Test
    void theLowestModuleDecidesRatherThanTheLuckiestOne() {
        // One module at the head does not lift the repository. Reading it the other way is how this
        // corpus once reported every pin met while most of it sat below.
        Hop hop = new Hop(11, 17);
        assertEquals(0, Bom.against(hop, Map.of(),
                Map.of("org.projectlombok:lombok", "1.18.20"), "maven").met());
        assertEquals(1, Bom.against(hop, Map.of(),
                Map.of("org.projectlombok:lombok", "1.18.30"), "maven").met());
        assertEquals(1, Bom.against(hop, Map.of(),
                Map.of("org.projectlombok:lombok", "1.18.46"), "maven").met(),
                "newer than the head is met, not missed");
    }

    @Test
    void aVersionThatIsNotANumberIsNotJudged() {
        // ${lombok.version} and (managed elsewhere) are facts about the file, not versions.
        for (String opaque : new String[] {"${lombok.version}", "(managed elsewhere)", "LATEST"}) {
            Bom.Compliance c = Bom.against(new Hop(11, 17), Map.of(),
                    Map.of("org.projectlombok:lombok", opaque), "maven");
            assertEquals(0, c.applicable(), opaque + " decides nothing: " + c.verdicts());
        }
    }

    @Test
    void whatResolvedBeatsWhatWasAskedFor(@TempDir Path ws) throws IOException {
        // A build file is a request and the tree is the answer. A Gradle project holds its Boot
        // version in an ext variable, so every build-file reader sees ${versionSpringBoot} and no
        // number, and reports a project plainly on Boot 3.5 as declaring no Boot.
        Path trace = ws.resolve("trace.jsonl");
        Files.writeString(trace, "{\"stage\":\"packages-after\",\"what\":\"."
                + "\\torg.springframework.boot:spring-boot-starter\\t3.5.16\\t0\\n."
                + "\\torg.projectlombok:lombok\\t1.18.30\\t0\"}\n");

        Map<String, String> resolved = Bom.resolvedIn(trace);
        assertEquals("3.5.16", resolved.get("org.springframework.boot:spring-boot-starter"),
                resolved.toString());

        Bom.Compliance c = Bom.against(new Hop(17, 21), resolved,
                Map.of("org.springframework.boot:spring-boot-gradle-plugin", "3.5.4"), "gradle");

        assertEquals(2, c.applicable(), c.verdicts().toString());
        assertEquals(2, c.met(), "the resolved 3.5.16 settles it, not the 3.5.4 in the file");
    }

    @Test
    void aBumpThatNeverReachedAGateResolvesNothingRatherThanZero(@TempDir Path ws) {
        // packages-after is only written on a green gate. Empty means "not scanned", and the
        // fallback to declarations is what stops that reading as a project with no dependencies.
        assertTrue(Bom.resolvedIn(ws.resolve("absent.jsonl")).isEmpty());
    }

    @Test
    void onlyFloorsJudgeableOnBothSidesCountTowardTheMovement() {
        // The after-scan runs on a green gate and the before-scan does not, so the two totals are
        // not comparable: repositories came out reading "was 3 of 11, now 0 of 4", where the four
        // is all that was measured rather than all that was left.
        Bom.Floor f = new Bom.Floor("g:a", "1.2.3", "before", java.util.Set.of("g:a"), "any");
        Bom.Floor g = new Bom.Floor("g:b", "1.2.3", "before", java.util.Set.of("g:b"), "any");

        Bom.Compliance was = new Bom.Compliance(List.of(
                new Bom.Verdict(f, "1.2.0", true, false),
                new Bom.Verdict(g, "1.2.0", true, false)), 0, 2);
        Bom.Compliance now = new Bom.Compliance(List.of(
                new Bom.Verdict(f, "1.2.3", true, true),
                new Bom.Verdict(g, "", false, false)), 1, 0);

        Bom.Movement moved = Bom.between(was, now);

        assertEquals(1, moved.applied(), "only the floor judged on both sides");
        assertEquals(1, moved.missedBefore());
        assertEquals(0, moved.missedAfter());
    }

    @Test
    void anUnreadableWorkspaceMeasuresNothingRatherThanZero(@TempDir Path ws) {
        Bom.Compliance c = Bom.measure(ws.resolve("gone"), ws.resolve("gone/trace.jsonl"),
                new Hop(11, 17));

        assertEquals(0, c.applicable());
        assertEquals(-1, c.percent());
    }

    @Test
    void everyReasonTheProseCarriesReachesARowThatCanShowIt() {
        // THE PROSE IS NOT THE LIST ANY MORE, so this no longer checks that two hand-written lists
        // agree on a number. The brief is rendered from the bill of materials and {@link Floors}
        // supplies the reason for the rows it has an argument about, matched by coordinate and by
        // line. What can still go wrong is an orphan: a reason for a coordinate or a line the list
        // no longer carries is an argument no agent will ever be shown.
        //
        // KEYED PER LINE, WHICH THE VERSION THIS REPLACED WAS NOT. It collapsed the prose into a
        // Map keyed by artifact, so tomcat's 9.0 row was silently overwritten by its 10.1 row and
        // never compared at all; the 9.0 line drifted three ways between the prose and two TSVs
        // and this test passed throughout.
        for (Hop hop : HOPS) {
            for (String key : Floors.reasons(hop.to()).keySet()) {
                if (!key.contains("@")) {
                    continue;
                }
                String coordinate = key.substring(0, key.indexOf('@'));
                String line = key.substring(key.indexOf('@') + 1);
                assertTrue(Bom.of(hop).stream()
                                .anyMatch(f -> f.spellings().contains(coordinate)
                                        && Floors.lineOf(f.version()).equals(line)),
                        hop + ": Floors argues for " + coordinate + " on the " + line
                                + " line and the bill of materials carries no such row, so the "
                                + "reason reaches nobody");
            }
        }
    }

    private static boolean sameLine(String a, String b) {
        String[] x = a.split("[.-]");
        String[] y = b.split("[.-]");
        return x.length >= 2 && y.length >= 2 && x[0].equals(y[0]) && x[1].equals(y[1]);
    }

    private static String headOf(Hop hop, String artifact) {
        return headsOf(hop, artifact).stream().findFirst().orElse("");
    }

    private static List<String> headsOf(Hop hop, String artifact) {
        return Bom.of(hop).stream()
                .filter(f -> f.artifact().equals(artifact))
                .map(Bom.Floor::version)
                .toList();
    }
}
