package tech.mikhailov.bjv.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The wall table fires on the signature, lands the fix, and never fires twice. */
class RoutingAWallTest {

    @Test
    void theLombokSignatureLandsAManagedFloorOnce(@TempDir Path ws) throws Exception {
        Files.writeString(ws.resolve("pom.xml"),
                "<project>\n  <dependencyManagement>\n    <dependencies>\n"
                        + "    </dependencies>\n  </dependencyManagement>\n</project>");
        Walls walls = new Walls(ws);
        String log = "Fatal error compiling: java.lang.ExceptionInInitializerError: "
                + "com.sun.tools.javac.code.TypeTags";
        Walls.Turn first = walls.match(log, 17);
        assertTrue(first.fixed());
        assertTrue(Files.readString(ws.resolve("pom.xml")).contains("1.18.30"));
        assertFalse(walls.match(log, 17).fixed(), "the same row must not fire twice");
    }

    @Test
    void theOpensPackageComesFromTheErrorItself(@TempDir Path ws) throws Exception {
        Files.writeString(ws.resolve("pom.xml"),
                "<project>\n  <properties>\n  </properties>\n</project>");
        String log = "InaccessibleObjectException: module java.base does not \"opens java.nio.file\"";
        assertTrue(new Walls(ws).match(log, 17).fixed());
        assertTrue(Files.readString(ws.resolve("pom.xml"))
                .contains("--add-opens=java.base/java.nio.file=ALL-UNNAMED"));
    }
}
