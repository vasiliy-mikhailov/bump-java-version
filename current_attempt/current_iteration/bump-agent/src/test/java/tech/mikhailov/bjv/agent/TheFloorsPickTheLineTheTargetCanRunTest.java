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
        assertTrue(to17.stream().anyMatch(r -> r.contains("boot2.UpgradeSpringBoot_2_7")),
                "below 21 the 2.7 line is still the right ceiling");
        assertTrue(to21.stream().anyMatch(r -> r.contains("boot3.UpgradeSpringBoot_3_5")),
                "at 21 the project must leave the 2.x line");
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
}
