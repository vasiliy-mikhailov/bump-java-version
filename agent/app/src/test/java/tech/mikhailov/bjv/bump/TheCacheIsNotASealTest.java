package tech.mikhailov.bjv.bump;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * WHAT THE CACHE CAN TELL AN AGENT, AND WHAT IT MUST NOT CLAIM.
 *
 * <p>A bump died on this. The after-pins doer wrote {@code distributionUrl=.../9.1.0-bin.zip},
 * dropping the {@code gradle-} prefix; the url 404s, so the download left an eight kilobyte
 * {@code .part} and the build failed. The doer then asked this tool, was told the distributions
 * listed "are the only ones obtainable" and that "anything not on this list cannot be downloaded",
 * saw no 9.1.0, and concluded the environment could not serve it. It defended that against a
 * reviewer and the lane settled infra.
 *
 * <p>Every step after the typo was competent. The tool made them all wrong, twice over: it asserted
 * a seal that does not exist, and it hid the one piece of evidence that named the real cause.
 */
class TheCacheIsNotASealTest {

    private static void complete(Path root, String name) throws IOException {
        Path hash = root.resolve(name).resolve("abc123");
        Files.createDirectories(hash);
        Files.writeString(hash.resolve(name + ".zip.ok"), "");
        Files.createDirectories(hash.resolve(name.replaceAll("-(bin|all)$", "")));
    }

    private static void halfDownloaded(Path root, String name) throws IOException {
        Path hash = root.resolve(name).resolve("def456");
        Files.createDirectories(hash);
        Files.writeString(hash.resolve(name + ".zip.part"), "");
    }

    @Test
    void handsBackTheUrlRatherThanAVersionToRebuild() throws IOException {
        Path root = Files.createTempDirectory("dists");
        complete(root, "gradle-8.14.3-bin");

        String said = Outside.distributions(root);

        assertTrue(said.contains("https://services.gradle.org/distributions/gradle-8.14.3-bin.zip"),
                "the url is the thing that gets pasted, and rebuilding it from the version is where "
                        + "the prefix was dropped three times: " + said);
    }

    @Test
    void aHalfDownloadedDistributionIsNotOffered() throws IOException {
        Path root = Files.createTempDirectory("dists");
        complete(root, "gradle-8.14.3-bin");
        halfDownloaded(root, "gradle-8.8-bin");

        String said = Outside.distributions(root);

        String offered = said.substring(0, said.indexOf("Asked for here"));
        assertFalse(offered.contains("8.8"),
                "an incomplete download must not be offered as usable, because handing it back "
                        + "sends the build to the download that already failed: " + said);
        assertTrue(said.contains("gradle-8.8-bin.zip"),
                "but it stays visible as a request that did not finish: " + said);
    }

    @Test
    void aUrlThatDidNotResolveIsNamedAsSuch() throws IOException {
        Path root = Files.createTempDirectory("dists");
        complete(root, "gradle-9.7.0-bin");
        // THE SHAPE THE TYPO LEAVES. Gradle names the directory after the url's filename, so a
        // request for `9.1.0-bin.zip` leaves a directory called `9.1.0-bin` and nothing else does.
        halfDownloaded(root, "9.1.0-bin");

        String said = Outside.distributions(root);

        assertTrue(said.contains("9.1.0-bin.zip"),
                "the failed request is the evidence that names the real cause, and hiding it is what "
                        + "turned a typo into an infra verdict: " + said);
    }

    @Test
    void neverClaimsTheEnvironmentIsSealed() throws IOException {
        Path root = Files.createTempDirectory("dists");
        complete(root, "gradle-9.7.0-bin");

        String said = Outside.distributions(root).toLowerCase();

        assertFalse(said.contains("cannot be downloaded"),
                "the cache fills on demand and fourteen distributions arrived on their own in one "
                        + "day; telling an agent otherwise is what makes a 404 read as impossibility");
        assertFalse(said.contains("sealed"), "same claim, other word: " + said);
    }
}
