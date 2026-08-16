package tech.mikhailov.bjv.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * measured, not hypothetical: a repository passed at Boot 3.5.4 against a 3.5.16 floor and carried
 * Tomcat 10.1.43 with eleven CRITICAL+HIGH through a PASS that moved nothing.
 *
 * <p>So the floors become a number the verdict does not contain.
 */
class AGreenGateIsNotComplianceTest {

    private static final int[] TARGETS = {11, 17, 21, 25};

    @Test
    void everyRowOfEveryFileIsWellFormed() {
        // THE WHOLE POINT OF THE STRICTNESS. A reader that drops what it cannot read produces a
        // shorter list and a healthier looking number, which is how a percentage lies.
        //
        // The count is not the prose's count. Floors states Spring Boot twice, once as the parent a
        // project inherits and once as the BOM it imports, because a planner reading prose benefits
        // from seeing both; here they are one artifact with two spellings, which is what they are.
        for (int target : TARGETS) {
            List<Bom.Floor> floors = Bom.of(target);
            assertTrue(floors.size() >= 14, "target " + target + " has " + floors.size() + " rows");
            for (Bom.Floor f : floors) {
                assertTrue(f.coordinates().contains(":"), f.toString());
                assertTrue(f.version().matches("\\d[\\w.\\-]*"), f.toString());
                assertTrue(f.spellings().contains(f.coordinates()),
                        "the canonical name is always one of its own spellings: " + f);
            }
        }
    }

    @Test
    void aRowNobodyCanReadFailsLoudlyRatherThanShrinkingTheList() {
        // If a row ever stops matching the shape, loading throws and the build says so, instead of
        // the corpus quietly measuring against fourteen floors where it measured against fifteen.
        // Named rather than counted, because a count is the thing that quietly shrinks. These
        // three are the floors an 8 to 11 hop cannot be right without.
        var names = Bom.of(11).stream().map(Bom.Floor::coordinates).toList();
        assertTrue(names.contains("org.projectlombok:lombok"), names.toString());
        assertTrue(names.contains("org.mockito:mockito-core"), names.toString());
        assertTrue(names.contains("org.springframework.boot:spring-boot-starter-parent"),
                names.toString());
    }

    @Test
    void thePhaseTravelsWithTheFloor() {
        for (int target : TARGETS) {
            List<Bom.Floor> boot = Bom.of(target).stream()
                    .filter(f -> f.coordinates().contains("spring-boot")).toList();
            assertFalse(boot.isEmpty(), "target " + target);
            assertTrue(boot.stream().allMatch(f -> f.phase().equals("after")),
                    "Boot needs the target JDK, so it is an after-pin: " + boot);
            assertTrue(Bom.of(target).stream()
                            .filter(f -> f.coordinates().contains("lombok"))
                            .allMatch(f -> f.phase().equals("before")),
                    "and lombok has to move first or javac dies before anything else runs");
        }
    }

    @Test
    void aGradleProjectIsNotFailedForNotWritingMavenWords() {
        // THE MEASUREMENT'S WHOLE CREDIBILITY. Floors names Spring Boot as
        // spring-boot-starter-parent, a string that cannot appear in a Gradle build. A Gradle
        // project already sitting on the floor would read as below it, on every sweep, forever.
        Map<String, String> declared = Map.of("org.springframework.boot", "3.5.16");

        Bom.Compliance gradle = Bom.against(21, Map.of(), declared, "gradle");

        assertTrue(gradle.met() >= 1, "the plugins-block spelling counts: " + gradle);
        assertEquals(0, gradle.missed(), "and nothing is held against it: " + gradle.outstanding());
    }

    @Test
    void aRowOneBuildSystemCannotHaveDoesNotCountAgainstIt() {
        // maven-compiler-plugin has no Gradle analogue and the wrapper has no Maven one. Counting
        // an impossible row as a miss turns a percentage into an accusation.
        Map<String, String> nothing = Map.of();
        for (String build : new String[] {"maven", "gradle"}) {
            Bom.Compliance c = Bom.against(21, Map.of(), nothing, build);
            assertEquals(0, c.applicable(),
                    "a project declaring none of these has failed none of them, on " + build);
            assertEquals(-1, c.percent(), "and has no percentage, rather than nought per cent");
        }
    }

    @Test
    void theNumberIsWhatTheColumnShows() {
        Map<String, String> declared = Map.ofEntries(
                Map.entry("org.projectlombok:lombok", "1.18.30"),
                Map.entry("net.bytebuddy:byte-buddy", "1.14.12"),
                Map.entry("net.bytebuddy:byte-buddy-agent", "1.12.0"),
                Map.entry("org.mockito:mockito-core", "4.11.0"),
                Map.entry("org.hamcrest:hamcrest", "2.2"),
                Map.entry("org.springframework.boot:spring-boot-starter-parent", "2.5.15"));

        Bom.Compliance c = Bom.against(17, Map.of(), declared, "maven");

        assertEquals(6, c.applicable(), "six of these floors are declared: " + c.verdicts());
        assertEquals(3, c.met(), "lombok, byte-buddy and hamcrest are at or above: " + c.met());
        assertEquals(3, c.missed());
        assertEquals(50, c.percent());
        assertTrue(c.outstanding().stream().map(v -> v.floor().artifact()).toList()
                .containsAll(List.of("byte-buddy-agent", "mockito-core",
                        "spring-boot-starter-parent")), c.outstanding().toString());
    }

    @Test
    void theLowestModuleDecidesRatherThanTheLuckiestOne() {
        // One module at the floor does not lift the repository. Reading it the other way is
        // precisely how this corpus once reported every pin met while most of it sat below.
        assertEquals(0, Bom.against(17, Map.of(), Map.of("org.projectlombok:lombok", "1.18.20"), "maven")
                .met());
        assertEquals(1, Bom.against(17, Map.of(), Map.of("org.projectlombok:lombok", "1.18.30"), "maven")
                .met());
        assertEquals(1, Bom.against(17, Map.of(), Map.of("org.projectlombok:lombok", "1.18.46"), "maven")
                .met(), "newer than the floor is met, not missed");
    }

    @Test
    void aVersionThatIsNotANumberIsNotJudged() {
        // ${lombok.version} and (managed elsewhere) are facts about the file, not versions. Reading
        // one as below the floor invents a failure; reading it as above invents a pass.
        for (String opaque : new String[] {"${lombok.version}", "(managed elsewhere)", "LATEST"}) {
            Bom.Compliance c = Bom.against(17, Map.of(), Map.of("org.projectlombok:lombok", opaque), "maven");
            assertEquals(0, c.applicable(), opaque + " decides nothing: " + c.verdicts());
        }
    }

    @Test
    void itReadsARealWorkspaceInEitherDialect(@TempDir Path ws) throws IOException {
        Files.writeString(ws.resolve("settings.gradle"), "rootProject.name = 'x'");
        Files.writeString(ws.resolve("build.gradle"), """
                plugins {
                    id 'org.springframework.boot' version '3.5.16'
                }
                dependencies {
                    compileOnly 'org.projectlombok:lombok:1.18.20'
                }
                """);

        Bom.Compliance c = Bom.measure(ws, ws.resolve("no-trace.jsonl"), 21);

        assertEquals(2, c.applicable(), c.verdicts().toString());
        assertEquals(1, c.met(), "boot is at the floor");
        assertEquals(1, c.missed(), "and lombok is below it");
        assertEquals(50, c.percent());
    }

    @Test
    void anUnreadableWorkspaceMeasuresNothingRatherThanZero(@TempDir Path ws) {
        // The workspace of a settled bump is deleted. Asking anyway must produce "no measurement",
        // never "nought of nought met", which a reader would rank below a project that met half.
        Bom.Compliance c = Bom.measure(ws.resolve("gone"), ws.resolve("gone/trace.jsonl"), 17);

        assertEquals(0, c.applicable());
        assertEquals(-1, c.percent());
    }

    @Test
    void theBillOfMaterialsAndTheProseSayTheSameThing() {
        // TWO HAND-WRITTEN LISTS, MADE TO AGREE OUT LOUD. Parsing the prose to avoid this is what
        // once decided every floor was met and skipped the phase for the whole corpus. Drift
        // between two lists is the risk taken instead, and this is the thing that makes it loud.
        for (int target : TARGETS) {
            Map<String, String> prose = new java.util.LinkedHashMap<>();
            for (String raw : Floors.forTarget(target).lines().toList()) {
                String line = raw.strip();
                if (line.isEmpty()) {
                    continue;
                }
                if (line.startsWith("[after]")) {
                    line = line.substring("[after]".length()).strip();
                }
                String[] token = line.split("\\s+", 3);
                prose.put(token[0], token[1]);
            }

            Map<String, String> bom = new java.util.LinkedHashMap<>();
            for (Bom.Floor f : Bom.of(target)) {
                for (String spelling : f.spellings()) {
                    bom.put(spelling, f.version());
                }
            }

            for (Map.Entry<String, String> row : prose.entrySet()) {
                assertTrue(bom.containsKey(row.getKey()),
                        "target " + target + ": Floors pins " + row.getKey()
                                + " and the bill of materials has never heard of it");
                assertEquals(row.getValue(), bom.get(row.getKey()),
                        "target " + target + ": " + row.getKey() + " disagrees");
            }
            for (Bom.Floor f : Bom.of(target)) {
                assertTrue(prose.containsKey(f.coordinates()),
                        "target " + target + ": the bill of materials pins " + f.coordinates()
                                + " and no agent is ever told about it");
            }
        }
    }

    @Test
    void everyRungHasAFileAndATargetBetweenRungsReadsTheOneBelow() {
        assertEquals(11, Bom.rung(11));
        assertEquals(11, Bom.rung(8), "nothing pins below 11, so 11 is the floor of the ladder");
        assertEquals(17, Bom.rung(18), "a target between rungs reads the rung it cleared");
        assertEquals(25, Bom.rung(26));
        for (int target : TARGETS) {
            assertFalse(Bom.of(target).isEmpty(), "target " + target + " has a file");
        }
    }

    @Test
    void whatResolvedBeatsWhatWasAskedFor(@TempDir Path ws) throws IOException {
        // A BUILD FILE IS A REQUEST AND THE TREE IS THE ANSWER, and on a managed project they are
        // routinely different. Measured on this corpus: a Gradle project holds its Spring Boot
        // version in an ext variable, so every build-file reader sees ${versionSpringBoot} and no
        // number at all, and reports a project plainly on Boot 2.1 as declaring no Boot.
        Path trace = ws.resolve("trace.jsonl");
        Files.writeString(trace, "{\"stage\":\"packages-after\",\"what\":\"."
                + "\\torg.springframework.boot:spring-boot-starter\\t3.5.16\\t0\\n."
                + "\\torg.projectlombok:lombok\\t1.18.30\\t0\"}\n");

        Map<String, String> resolved = Bom.resolvedIn(trace);
        assertEquals("3.5.16", resolved.get("org.springframework.boot:spring-boot-starter"),
                resolved.toString());

        // spring-boot-starter is not a name any floor is written as. The family spelling is.
        Bom.Compliance c = Bom.against(21, resolved,
                Map.of("org.springframework.boot:spring-boot-gradle-plugin", "2.1.6"), "gradle");

        assertEquals(2, c.applicable(), c.verdicts().toString());
        assertEquals(2, c.met(), "the resolved 3.5.16 settles it, not the 2.1.6 in the file");
        assertTrue(c.outstanding().isEmpty(), c.outstanding().toString());
    }

    @Test
    void aBumpThatNeverReachedAGateResolvesNothingRatherThanZero(@TempDir Path ws) {
        // packages-after is only written on a green gate. An empty answer here means "not scanned",
        // and the fallback to declarations is what stops that reading as a project with no
        // dependencies.
        assertTrue(Bom.resolvedIn(ws.resolve("absent.jsonl")).isEmpty());
    }
}
