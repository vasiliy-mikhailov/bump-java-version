package tech.mikhailov.bjv.jvm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE MODULE GATE HAS TO COMPILE SOMETHING TO BE A GATE.
 *
 * <p>It shipped without clearing the module's compiled output, and a bump is a pom-only edit, so
 * nothing is newer than its class and Maven answers "Nothing to compile - all classes are up to
 * date". Measured on the first three Maven bumps to run the walk: every module gate compiled zero
 * files while the repository gate, seconds later on the same tree, compiled five, seven and five.
 * On one repository seven module gates flipped from all-red to all-green purely because the
 * baseline build had run first.
 *
 * <p>SCOPED, THOUGH. The repository-wide wipe is correct and re-pays the whole reactor's compile
 * once per module per turn, which is the cost the walk exists to avoid. So this covers the half a
 * unit test can reach: that the wipe takes one module's output and leaves its siblings alone.
 *
 * <p>WHAT IT DOES NOT COVER, said plainly because a green suite is read as coverage. The bug was a
 * missing CALL, not a wrong function, and nothing here would have caught it: {@code moduleGate}
 * builds its own {@link Runner} inside {@link Bump#run()}, so there is no seam to hand it a fake
 * and no test observes the order of its two calls. What found it was ten agents probing real
 * repositories.
 */
class AModuleGateMeasuresOneModuleTest {

    private static Path classes(Path ws, String module, String name) throws IOException {
        Path dir = (module.isEmpty() ? ws : ws.resolve(module)).resolve("target/classes");
        Files.createDirectories(dir);
        Path f = dir.resolve(name);
        Files.writeString(f, "stale");
        return f;
    }

    @Test
    void itTakesOneModulesOutputAndLeavesItsSiblingsAlone(@TempDir Path ws) throws IOException {
        Path mine = classes(ws, "core", "Mine.class");
        Path theirs = classes(ws, "web", "Theirs.class");
        Path root = classes(ws, "", "Root.class");

        new Runner(ws, "/nonexistent/hoptools").clearClasses("core");

        assertFalse(Files.exists(mine), "the module being gated is recompiled from source");
        assertTrue(Files.exists(theirs),
                "and a sibling's output survives, or the walk re-pays the whole reactor per module");
        assertTrue(Files.exists(root), "as does the root's");
    }

    @Test
    void anEmptyPathMeansTheWholeTree() throws IOException {
        // jvmjob turns an empty path into the unscoped build, so the wipe has to agree with it:
        // clearing one module and compiling all of them would leave the gate reading stale classes
        // for every module but one, which is the defect this exists to prevent wearing a disguise.
        Path ws = Files.createTempDirectory("bjv-wipe");
        Path a = classes(ws, "core", "A.class");
        Path b = classes(ws, "web", "B.class");

        new Runner(ws, "/nonexistent").clearClasses("");

        assertFalse(Files.exists(a), "an empty path is the whole tree");
        assertFalse(Files.exists(b));
    }

    @Test
    void aModuleThatIsNotThereIsNotAnError(@TempDir Path ws) throws IOException {
        // Modules.of() reads module names out of pom text with a regex, and it collects entries
        // from inside comments and from inactive profiles: 11 of 244 multi-module Maven roots in
        // this corpus name at least one directory that is not there. The wipe must not throw on
        // them; deciding what to do about them is a separate and unfinished problem.
        Path kept = classes(ws, "core", "Kept.class");

        new Runner(ws, "/nonexistent").clearClasses("does-not-exist");

        assertTrue(Files.exists(kept), "nothing else was touched, and nothing was thrown");
    }

    @Test
    void sourcesAreNeverTouched(@TempDir Path ws) throws IOException {
        // A wipe that reached the source tree would be a much worse bug than the one it fixes, and
        // this one walks a directory the caller names.
        Path src = ws.resolve("core/src/main/java/demo");
        Files.createDirectories(src);
        Path java = src.resolve("App.java");
        Files.writeString(java, "class App {}");
        classes(ws, "core", "App.class");

        new Runner(ws, "/nonexistent").clearClasses("core");

        assertTrue(Files.exists(java), "the source that produces the class stays");
    }
}
