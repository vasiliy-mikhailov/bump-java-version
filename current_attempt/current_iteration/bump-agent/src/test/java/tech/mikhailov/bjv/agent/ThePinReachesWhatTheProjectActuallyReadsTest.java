package tech.mikhailov.bjv.agent;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A pin that writes a property nothing reads is not a pin.
 *
 * <p>Every Maven Spring project in this corpus declares its Boot line through
 * {@code spring-boot-starter-parent}, and none reference {@code ${spring-boot.version}}. The floor
 * that was meant to guarantee the measured-best patch set only the property, so the version was
 * decided by whatever the OpenRewrite recipe happened to target, and the floor was decoration.
 */
class ThePinReachesWhatTheProjectActuallyReadsTest {

    @Test
    void theParentVersionMovesAndTheModulesOwnVersionDoesNot() throws Exception {
        Path ws = Files.createTempDirectory("pin");
        Files.writeString(ws.resolve("pom.xml"), """
                <project>
                  <parent>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-starter-parent</artifactId>
                    <version>2.7.3</version>
                  </parent>
                  <groupId>com.yu</groupId>
                  <artifactId>XiaoYu</artifactId>
                  <version>0.0.1-SNAPSHOT</version>
                </project>
                """);

        assertTrue(Pom.parentVersion(ws, "spring-boot-starter-parent", "3.5.16"));

        String out = Files.readString(ws.resolve("pom.xml"));
        assertTrue(out.contains("<version>3.5.16</version>"), "the parent moved");
        assertFalse(out.contains("<version>2.7.3</version>"), "nothing left behind");
        assertTrue(out.contains("<version>0.0.1-SNAPSHOT</version>"),
                "the module's own version sits just below the parent block and must not be touched");
    }

    @Test
    void aDifferentParentIsLeftAlone() throws Exception {
        Path ws = Files.createTempDirectory("pin");
        Files.writeString(ws.resolve("pom.xml"), """
                <project>
                  <parent>
                    <groupId>org.apache</groupId>
                    <artifactId>apache</artifactId>
                    <version>31</version>
                  </parent>
                </project>
                """);

        assertFalse(Pom.parentVersion(ws, "spring-boot-starter-parent", "3.5.16"),
                "a project parented on something else has no Boot parent to pin");
        assertTrue(Files.readString(ws.resolve("pom.xml")).contains("<version>31</version>"));
    }
}
