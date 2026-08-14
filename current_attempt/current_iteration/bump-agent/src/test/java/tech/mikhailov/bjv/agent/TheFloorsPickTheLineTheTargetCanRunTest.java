package tech.mikhailov.bjv.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Nothing in a Java bump ever looked at a dependency's CVE count: Tomcat moved because Spring
 * moved. These two floors are the first steps that pick a version on the evidence rather than
 * inheriting whatever the framework happened to carry.
 */
class TheFloorsPickTheLineTheTargetCanRunTest {

    @Test
    void versionsCompareByNumberNotAlphabetically() {
        // The bug this exists to prevent: 9.0.83 sorting after 9.0.105 and the floor never firing.
        assertTrue(Migrate.compare("9.0.105", "9.0.83") > 0);
        assertTrue(Migrate.compare("9.0.65", "9.0.105") < 0);
        assertEquals(0, Migrate.compare("9.0.105", "9.0.105"));
        assertTrue(Migrate.compare("10.1.55", "9.0.120") > 0, "10 is newer than 9");
        assertTrue(Migrate.compare("4.1.101.Final", "4.1.79.Final") > 0, "a suffix must not break it");
    }

    @Test
    void aJava21TargetLeavesTheBootLineThatCannotRunIt() throws Exception {
        // Boot 2.7 is EOL and does not support Java 21, so raising 2.7.3 to 2.7.18 moves a project
        // to the newest patch of a line that still cannot run its own target.
        var m = Migrate.class.getDeclaredMethod("program", int.class, int.class);
        m.setAccessible(true);
        Migrate spring = withBoot(2);
        @SuppressWarnings("unchecked")
        var to17 = (java.util.List<String>) m.invoke(spring, 11, 17);
        @SuppressWarnings("unchecked")
        var to21 = (java.util.List<String>) m.invoke(spring, 17, 21);
        assertTrue(to17.stream().anyMatch(r -> r.contains("boot4.UpgradeSpringBoot_4_0")),
                "Boot 4 declares java.version 17, so 17 is where the 2.x line stops being viable");
        assertTrue(to21.stream().anyMatch(r -> r.contains("boot4.UpgradeSpringBoot_4_0")),
                "and everything above 17 goes the same way");

        @SuppressWarnings("unchecked")
        var to11 = (java.util.List<String>) m.invoke(spring, 8, 11);
        assertTrue(to11.stream().anyMatch(r -> r.contains("boot2.UpgradeSpringBoot_2_7")),
                "below 17 nothing newer can run, so 2.7 remains the ceiling");
        assertEquals("2.7.18", Floors.version("spring-boot", 11), "and the floor agrees");
        assertEquals("4.1.0", Floors.version("spring-boot", 17), "as it does above");
        assertEquals("4.1.0", Floors.version("spring-boot", 25));
    }

    /** A workspace whose root pom declares the given Spring Boot major. */
    private static Migrate withBoot(int line) throws Exception {
        java.nio.file.Path ws = java.nio.file.Files.createTempDirectory("boot");
        java.nio.file.Files.writeString(ws.resolve("pom.xml"),
                "<project><parent><groupId>org.springframework.boot</groupId>"
                        + "<artifactId>spring-boot-starter-parent</artifactId>"
                        + "<version>" + line + ".7.3</version></parent></project>");
        return new Migrate(ws, "/nonexistent", null);
    }

    @Test
    void theSpellingsRealPomsUseAreTheOnesThatCount() throws Exception {
        // Every one of these is copied from a repo in the corpus. The detector's property arm used
        // to enumerate three exact tag names, matched none of them, and reported six Spring
        // projects as having no Spring at all.
        assertEquals("2.7", detect("pom.xml",
                "<project><properties><spring.boot.version>2.7.4</spring.boot.version>"
                        + "</properties></project>"), "mosip/commons and jaxxy spell it this way");
        assertEquals("2.7", detect("pom.xml",
                "<project><properties><spring-boot-dependencies.version>2.7.4"
                        + "</spring-boot-dependencies.version></properties></project>"),
                "apache/hertzbeat spells it this way");
        assertEquals("2.7", detect("build.gradle",
                "buildscript { dependencies { classpath("
                        + "\"org.springframework.boot:spring-boot-gradle-plugin:2.7.17\") } }"),
                "a buildscript classpath coordinate is still a declaration");
        assertEquals("3.2", detect("pom.xml",
                "<project><parent><groupId>org.springframework.boot</groupId>"
                        + "<artifactId>spring-boot-starter-parent</artifactId>"
                        + "<version>3.2.5</version></parent></project>"), "the parent still wins");
        assertEquals("", detect("pom.xml",
                "<project><build><plugins><plugin><artifactId>maven-compiler-plugin</artifactId>"
                        + "<version>3.8.0</version></plugin></plugins></build></project>"),
                "a compiler plugin is not a Spring line");
    }

    @Test
    void theDistanceIsKeptBecauseTwoPointZeroIsNotTwoPointSeven() throws Exception {
        // Both are line 2 and both take the same branch, but 2.0 is seven minors from 3.x and is
        // the profile of the run that lost 1916 of 2409 tests. Collapsing them to "2" made the
        // corpus unable to tell afterwards which distance had failed.
        assertEquals("2.0", detect("pom.xml", parentPom("2.0.2")));
        assertEquals("2.7", detect("pom.xml", parentPom("2.7.18")));
    }

    private static String parentPom(String v) {
        return "<project><parent><groupId>org.springframework.boot</groupId>"
                + "<artifactId>spring-boot-starter-parent</artifactId>"
                + "<version>" + v + "</version></parent></project>";
    }

    /** The declared Boot version a workspace containing exactly this one build file reports. */
    private static String detect(String name, String body) throws Exception {
        java.nio.file.Path ws = java.nio.file.Files.createTempDirectory("detect");
        java.nio.file.Files.writeString(ws.resolve(name), body);
        return new Migrate(ws, "/nonexistent", null).bootVersion();
    }
}
