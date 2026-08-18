package tech.mikhailov.bjv.bump;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE SPRING BOOT FLOOR HAD NEVER FIRED, ON ANYTHING, EVER.
 *
 * <p>Measured across every archive and every run: {@code after-pins} reported "0 of N modules had
 * work; every pin met" one hundred per cent of the time, on 65 bumps carrying a Spring Boot parent.
 * The phase ran, cost its agent calls, and was structurally incapable of finding work.
 *
 * <p>Two causes, and the second is the one that mattered.
 *
 * <p>THE FLOOR NAMED AN ARTIFACT NOTHING DECLARES. {@code org.springframework.boot:spring-boot} is
 * real and is never written in an application's pom: a Maven project inherits
 * {@code spring-boot-starter-parent} or imports the {@code spring-boot-dependencies} BOM. What this
 * file now asserts is that the floors name those.
 *
 * <p>A REGEX DECIDED WHETHER AN AGENT SAW THE FLOOR AT ALL. That is gone rather than fixed. There is
 * no deterministic check to assert against here any more, because the comparison belongs to the
 * planner: it reads these lines as prose and {@code declared_versions} as fact. What that tool can
 * see is covered by {@link ADeclaredVersionIsAFactTest}, including the parent block the old check
 * was blind to.
 */
class TheSpringBootPinFiresTest {

    /** The version each Boot line pins, which is the token after the coordinates. */
    private static List<String> pinned(int target) {
        return bootLines(target).stream()
                .map(l -> l.replaceFirst("^\\[after\\]\\s*", "").split("\\s+")[1])
                .toList();
    }

    @Test
    void theBootFloorNamesARecipeAtEveryTargetIncludingEleven() {
        // MEASURED ON THE CORPUS. Across every 8-to-11 bump, after-pins reached only for
        // version-writing recipes -- ChangePropertyValue six times, UpgradeDependencyVersion and
        // UpgradeParentVersion once each -- and UpgradeSpringBoot_2_7 not once. The agents were
        // doing exactly what this floor said: it named a number and nothing else, while the 17, 21
        // and 25 floors named a recipe.
        //
        // The number is the part that cannot work on its own. Boot 2.1 to 2.7 is six minor
        // releases of renamed properties and withdrawn APIs; UpgradeSpringBoot_2_7 chains 23
        // recipes down through 2.0 and carries newVersion 2.7.x, so it resolves the head of the
        // line and moves the maven plugin and the BOM with the parent. A version typed into the
        // parent block crosses none of that and fails later, at the gate, as the project's fault.
        assertTrue(Floors.after(11).contains("UpgradeSpringBoot_2_7"),
                "target 11 names the recipe that does the 2.x migration: " + Floors.after(11));
        assertTrue(Floors.after(11).contains("rather than writing a version"),
                "and rules out the literal, as the 3.5 line does");
        for (int target : new int[] {17, 21, 25}) {
            assertTrue(Floors.after(target).contains("UpgradeSpringBoot_3_5"),
                    "and the Boot 3 line still names its own, at " + target);
        }
    }

    @Test
    void theRecipeNamedAtElevenIsOneThatCanRunOnJavaEleven() {
        // UpgradeSpringBoot_3_5 would take a Java 11 project to a Boot that needs 17, so naming
        // the wrong recipe here is worse than naming none.
        assertFalse(Floors.after(11).contains("UpgradeSpringBoot_3_5"),
                "Boot 3 is unreachable at target 11: " + Floors.after(11));
        assertFalse(Floors.after(11).contains("UpgradeSpringBoot_4"),
                "and so is Boot 4");
    }

    @Test
    void everyFloorLineSitsAtTheSameIndent() {
        // A TEXT BLOCK STRIPS THE COMMON INDENT, so a line indented further than its neighbours
        // carries the difference into the prompt. Four jaxb-api lines did, one per target, and an
        // agent reads this list as prose where a misaligned row is noise it has to account for.
        for (int target : new int[] {11, 17, 21, 25}) {
            for (String line : Floors.forTarget(target).lines().filter(l -> !l.isBlank()).toList()) {
                assertFalse(line.startsWith(" "),
                        "a floor line is indented past the rest, at " + target + ": "
                                + line.substring(0, Math.min(70, line.length())));
            }
        }
    }

    /** The lines of one hop's floors that mention Spring Boot. */
    private static List<String> bootLines(int target) {
        return Floors.forTarget(target).lines()
                .filter(l -> l.contains("spring-boot"))
                .map(String::strip)
                .toList();
    }

    @Test
    void theFloorNamesWhatAMavenProjectActuallyDeclares() {
        for (int target : new int[] {11, 17, 21, 25}) {
            List<String> boot = bootLines(target);
            assertFalse(boot.isEmpty(), "every hop pins Spring Boot, at " + target);

            assertTrue(boot.stream().anyMatch(l -> l.contains("spring-boot-starter-parent")),
                    "the parent is how a project inheriting Boot says which one, at " + target
                            + ": " + boot);
            assertTrue(boot.stream().anyMatch(l -> l.contains("spring-boot-dependencies")),
                    "and the BOM is how a project importing it says so, at " + target + ": " + boot);
        }
    }

    @Test
    void theUnmatchableNameIsGone() {
        for (int target : new int[] {11, 17, 21, 25}) {
            for (String line : bootLines(target)) {
                // `spring-boot 4.1.0` as a whole coordinate. The suffixed names are the real ones.
                assertFalse(line.matches(".*:spring-boot\\s.*"),
                        "target " + target + " still pins an artifact nothing declares: " + line);
            }
        }
    }

    @Test
    void bothHalvesOfTheFloorAgreeOnTheVersion() {
        // A project inherits the parent or imports the BOM, never both, so the two lines are one
        // floor written twice. Letting them drift would raise a project to a different Boot
        // depending on which way it happens to be wired.
        for (int target : new int[] {11, 17, 21, 25}) {
            List<String> versions = bootLines(target).stream()
                    .map(l -> l.replaceFirst("^\\[after\\]\\s*", "").split("\\s+")[1])
                    .distinct()
                    .toList();
            assertTrue(versions.size() == 1,
                    "the parent and the BOM disagree at " + target + ": " + versions);
        }
    }

    @Test
    void thePinStaysInTheAfterPhase() {
        // Boot's floor requires the target JDK, so it cannot be raised before the JDK moves.
        for (int target : new int[] {17, 21, 25}) {
            assertTrue(Floors.after(target).contains("spring-boot"),
                    "boot is an after-JDK pin at " + target);
            assertFalse(Floors.before(target).contains("spring-boot"),
                    "and must not be attempted before, at " + target);
        }
    }

    @Test
    void theFloorIsAVersionAFreeRecipeCanReach() {
        // The pin and the tooling have to agree. UpgradeSpringBoot_3_5 resolves the newest 3.5
        // patch and chains Framework 6, Security 6.5, Cloud 2025 and the property renames; the only
        // recipe that reaches 4.1 is proprietary, so a 4.1 floor would be a target the harness
        // could only approach by hand.
        for (int target : new int[] {17, 21, 25}) {
            assertTrue(bootLines(target).stream().allMatch(l -> l.contains("3.5.")),
                    "the 3.5 line is where the free tooling ends, at " + target + ": "
                            + bootLines(target));
        }
    }

    @Test
    void theVersionIsOneThatRunsOnTheTarget() {
        // Boot 3 and 4 both need Java 17, so 11 is held at the last of the 2.x line. A floor naming
        // a release the target JDK cannot run would fail every project it fired on.
        assertTrue(bootLines(11).stream().allMatch(l -> l.contains("2.7.18")),
                "11 cannot run Boot 3: " + bootLines(11));
        for (int target : new int[] {17, 21, 25}) {
            // THE PINNED VERSION, NOT ANY 2.x IN THE SENTENCE. This read the whole line, and the
            // line now carries the measurement that justifies it — "jackson-databind 2.19.2 to
            // 2.21.4" — so a substring search called a Boot 3.5 floor a Boot 2 floor. Same shape
            // as matching "ok" inside "lombok": check the field, not the prose around it.
            assertFalse(pinned(target).stream().anyMatch(v -> v.startsWith("2.")),
                    target + " should not be held at the 2.x line: " + pinned(target)
                            + ", because Spring 5.3's ASM cannot read class file 65 and Boot 2 "
                            + "cannot component-scan on 21 at all");
        }
    }

    @Test
    void theFloorSendsTheDoerToTheRecipeRatherThanToALiteralVersion() {
        // MEASURED IN ONE SWEEP, ON TWO 21-TO-25 BUMPS THAT DIFFERED ONLY IN WHERE THEY STARTED.
        // 26B-CSE438/order-service began at Boot 3.2.0, below the floor, so after-pins fired. Its
        // doer reached for UpgradeSpringBoot_3_5, the recipe resolved the newest patch by itself
        // and landed on 3.5.16, Tomcat went from 10.1.16 to a release with no CRITICAL+HIGH at
        // all, and the bump went 63 to 2. 2819461143wp/software2_B was already sitting on 3.5.4,
        // the exact literal this floor used to name, so after-pins answered NOTHING-OUTSTANDING,
        // correctly, and the project kept Tomcat 10.1.43 and its eleven CVEs: 24 to 24, a PASS
        // that moved nothing. Its own security-after agent called the result an artefact.
        //
        // A floor written as one patch release is satisfied by that patch and by nothing better,
        // and 3.5.4 is twelve releases behind the head of the line it belongs to. So the floor
        // names the recipe, and says in prose that an older 3.5 patch does not meet it.
        for (int target : new int[] {17, 21, 25}) {
            String after = Floors.after(target);
            assertTrue(after.contains("UpgradeSpringBoot_3_5"),
                    "the floor names the recipe that resolves the patch, at " + target);
            assertTrue(after.contains("never write a version into the parent block"),
                    "and rules out the literal that costs the patch releases, at " + target);
            // VERSION-INDEPENDENT, which the first attempt at this floor was not. Naming 3.5.4
            // made 3.5.4 compliant; naming 3.5.16 would make 3.5.16 compliant a month from now,
            // and the recipe would stop being applied to exactly the projects it helps most.
            // Measured on one such project: 24 CRITICAL+HIGH to 1, Tomcat 10.1.43 to 10.1.55.
            assertTrue(after.contains("ANY project on the Boot 3 line whatever patch it declares"),
                    "the instruction cannot depend on the patch number, at " + target);
            assertTrue(after.contains("NOT evidence of being current"),
                    "sitting on 3.5 is not compliance, at " + target);
        }
    }

    @Test
    void theLinesStillReadAsInstructionsToAPlanner() {
        // These are prose now, and prose is all they are: the planner is the only reader. A line
        // that lost its reason would be a version with no argument behind it.
        for (String line : bootLines(21)) {
            assertTrue(line.contains("—"), "no reason given: " + line);
            assertTrue(line.startsWith("[after]"), "the phase marker survives: " + line);
        }
    }
}
