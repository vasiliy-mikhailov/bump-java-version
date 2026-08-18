package tech.mikhailov.bjv.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A HALF-STAGED DISTRIBUTION LOOKS EXACTLY LIKE A WORKING ONE.
 *
 * <p>Builds here are sealed, so a Gradle wrapper resolves its distribution from a staged cache and
 * cannot download. Staging can stop half way, and what it leaves is a directory Gradle happily
 * finds, uses, and then dies inside:
 *
 * <pre>
 * NoSuchFileException: /dists/gradle-8.7-bin/.../gradle-8.7/lib/plugins/httpclient-4.5.14.jar
 * FAILURE: java.lang.ExceptionInInitializerError (no error message)
 * </pre>
 *
 * <p>The harness called that "no-baseline: the project does not build under its own JDK 21" about a
 * project not one line of which had run. Four of thirty-one no-baseline verdicts in this corpus
 * were that, and 5jyo/message-queue is the one that surfaced it.
 *
 * <p>The check itself already existed: {@code gradle_versions} will not offer a distribution
 * without an {@code .ok} marker. It was agent-facing, and the build path never asked it. A check
 * beside the road stops nothing.
 */
class AHalfStagedDistributionIsNotTheProjectsFaultTest {

    private static void wrapper(Path ws, String url) throws IOException {
        Path p = ws.resolve("gradle/wrapper");
        Files.createDirectories(p);
        Files.writeString(p.resolve("gradle-wrapper.properties"),
                "distributionBase=GRADLE_USER_HOME\ndistributionUrl=" + url + "\n");
    }

    private static Path staged(Path dists, String name, boolean complete) throws IOException {
        Path d = dists.resolve(name).resolve("bhs2wmbdwecv87pi65oeuq5iu");
        Files.createDirectories(d.resolve(name.replaceAll("-(bin|all)$", "")));
        if (complete) {
            Files.writeString(d.resolve(name + ".zip.ok"), "");
        }
        return d;
    }

    @Test
    void anIncompleteDistributionIsNamedAndBlamed(@TempDir Path ws, @TempDir Path dists)
            throws IOException {
        wrapper(ws, "https\\://services.gradle.org/distributions/gradle-8.7-bin.zip");
        staged(dists, "gradle-8.7-bin", false);

        String said = Staged.problem(ws, dists.toString());

        assertTrue(said.contains("gradle-8.7-bin"), said);
        assertTrue(said.contains("incomplete"), said);
        // AND WHOSE FAULT IT IS, in the sentence, because the verdict this replaces said the
        // opposite and a reader believed it four times.
        assertTrue(said.contains("not the project"), said);
    }

    @Test
    void aCompleteOneSaysNothing(@TempDir Path ws, @TempDir Path dists) throws IOException {
        wrapper(ws, "https\\://services.gradle.org/distributions/gradle-8.5-bin.zip");
        staged(dists, "gradle-8.5-bin", true);

        assertEquals("", Staged.problem(ws, dists.toString()));
    }

    @Test
    void aDistributionNobodyStagedIsLeftAlone(@TempDir Path ws, @TempDir Path dists)
            throws IOException {
        // A DIFFERENT FAILURE, AND A LEGIBLE ONE. The wrapper says it cannot download, which is
        // true and points at the cache. Only a directory that EXISTS and is half-built lies.
        wrapper(ws, "https\\://services.gradle.org/distributions/gradle-9.9-bin.zip");

        assertEquals("", Staged.problem(ws, dists.toString()));
    }

    @Test
    void aMavenProjectIsNotAskedAboutGradle(@TempDir Path ws, @TempDir Path dists) {
        assertEquals("", Staged.problem(ws, dists.toString()));
    }

    @Test
    void theAllDistributionIsRecognisedToo(@TempDir Path ws, @TempDir Path dists)
            throws IOException {
        // Three of the four real cases in this corpus were -all, not -bin: 5.2, 5.4 and 8.0.2.
        wrapper(ws, "https\\://services.gradle.org/distributions/gradle-8.0.2-all.zip");
        staged(dists, "gradle-8.0.2-all", false);

        assertTrue(Staged.problem(ws, dists.toString()).contains("gradle-8.0.2-all"));
    }

    @Test
    void noDistsRootConfiguredMeansNoOpinion(@TempDir Path ws) throws IOException {
        // An install with no staged cache downloads normally and this has nothing to say about it.
        wrapper(ws, "https\\://services.gradle.org/distributions/gradle-8.7-bin.zip");

        assertEquals("", Staged.problem(ws, null));
        assertEquals("", Staged.problem(ws, ""));
    }
}
