package tech.mikhailov.bjv.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The gate reads bytecode, so the bytecode it reads must be this run's. Maven's test-compile skips
 * a compile whose output is newer than its sources, so the baseline's classes survive into the gate
 * and the target is measured at the level the project started from.
 */
class TheGateMeasuresFreshBytecodeTest {

    @Test
    void compiledOutputGoesBeforeAGateBuild_andSourcesDoNot(@TempDir Path ws) throws Exception {
        Path classes = ws.resolve("target/classes/com/x");
        Path testClasses = ws.resolve("mod/target/test-classes");
        Path gradle = ws.resolve("build/classes/java/main");
        Path deps = ws.resolve("target/dependency");
        Path src = ws.resolve("src/main/java/com/x");
        for (Path p : new Path[]{classes, testClasses, gradle, deps, src}) {
            Files.createDirectories(p);
        }
        Files.writeString(classes.resolve("A.class"), "stale");
        Files.writeString(testClasses.resolve("ATest.class"), "stale");
        Files.writeString(gradle.resolve("B.class"), "stale");
        Files.writeString(deps.resolve("lib.jar"), "collected");
        Files.writeString(src.resolve("A.java"), "class A {}");

        new Runner(ws, "/nonexistent/hoptools").clearClasses();

        assertFalse(Files.exists(classes), "target/classes must go: it is the stale measurement");
        assertFalse(Files.exists(testClasses), "a module's test-classes too");
        assertFalse(Files.exists(gradle), "build/classes as well");
        assertTrue(Files.exists(src.resolve("A.java")), "sources are never touched");
        assertTrue(Files.exists(deps.resolve("lib.jar")),
                "the collected dependency jars are not compiled output; the scan re-collects them");
    }
}
