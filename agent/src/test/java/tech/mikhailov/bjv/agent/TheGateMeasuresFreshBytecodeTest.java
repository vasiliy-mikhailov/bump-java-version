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

    @Test
    void generatedSourcesGoTooOrTheOldGeneratorsOutputCollidesWithTheNewOne(@TempDir Path ws)
            throws Exception {
        // causalnet/autojdk-maven-plugin: the baseline generated HelpMojo into one package, the
        // raised maven-plugin-plugin generated it into another, both were compiled, and two classes
        // claimed the same goal. The build aborted on a project where nothing was wrong.
        Path gen = ws.resolve("target/generated-sources/plugin");
        Files.createDirectories(gen);
        Files.writeString(gen.resolve("HelpMojo.java"), "class HelpMojo {}");
        Path classes = ws.resolve("target/classes");
        Files.createDirectories(classes);
        Files.writeString(classes.resolve("Old.class"), "stale");
        Path src = ws.resolve("src/main/java");
        Files.createDirectories(src);
        Files.writeString(src.resolve("Keep.java"), "class Keep {}");

        new Runner(ws, "/nonexistent").clearClasses();

        assertFalse(Files.exists(gen.resolve("HelpMojo.java")), "generated source must go");
        assertFalse(Files.exists(classes.resolve("Old.class")), "stale class must go");
        assertTrue(Files.exists(src.resolve("Keep.java")), "real sources must survive");
    }
}
