package tech.mikhailov.bjv.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * A DISTRIBUTION THAT IS STAGED BUT NOT COMPLETE FAILS THE BUILD AND BLAMES THE PROJECT.
 *
 * <p>Builds here are sealed: the Gradle wrapper resolves its distribution out of a staged cache and
 * cannot download. Staging can stop half way, and what it leaves behind is a directory that looks
 * exactly like a distribution. Gradle finds it, uses it, and dies reaching for a jar that was never
 * unpacked:
 *
 * <pre>
 * java.nio.file.NoSuchFileException:
 *   /dists/gradle-8.7-bin/.../gradle-8.7/lib/plugins/httpclient-4.5.14.jar
 *   ...
 * FAILURE: Build failed with an exception.
 * java.lang.ExceptionInInitializerError (no error message)
 * </pre>
 *
 * <p>The harness recorded that as "no-baseline: the project does not build under its own JDK 21",
 * which is a false accusation: no line of the project ran. Measured across this corpus, 4 of 31
 * no-baseline verdicts are this.
 *
 * <p>THE CHECK ALREADY EXISTED AND WAS NOT ON THE ROAD. {@code gradle_versions} refuses to offer a
 * distribution with no {@code .ok} marker, and says why in as many words. But that tool is
 * agent-facing, and the build path is the project's own wrapper reading a directory. A check beside
 * the road stops nothing.
 */
final class Staged {

    private Staged() {
    }

    /** {@code distributionUrl=...gradle-8.7-bin.zip} in the project's wrapper properties. */
    private static final Pattern DISTRIBUTION =
            Pattern.compile("distributionUrl=.*?(gradle-[\\w.\\-]+?-(?:bin|all))\\.zip");

    /**
     * The complaint about this workspace's wrapper, or empty when there is nothing to say.
     *
     * <p>Empty covers every ordinary case: a Maven project, a Gradle project with no wrapper, a
     * wrapper naming a distribution that was never staged (which fails with a plain "cannot
     * download" a reader can act on), and a wrapper naming one that is staged and complete.
     */
    static String problem(Path ws, String distsRoot) {
        if (distsRoot == null || distsRoot.isBlank()) {
            return "";
        }
        Path properties = ws.resolve("gradle/wrapper/gradle-wrapper.properties");
        if (!Files.isRegularFile(properties)) {
            return "";
        }
        String named;
        try {
            Matcher m = DISTRIBUTION.matcher(Files.readString(properties));
            if (!m.find()) {
                return "";
            }
            named = m.group(1);
        } catch (IOException unreadable) {
            return "";
        }
        Path staged = Path.of(distsRoot).resolve(named);
        if (!Files.isDirectory(staged)) {
            // NOT STAGED AT ALL is a different failure and a legible one: the wrapper says it
            // cannot download, which is true and points at the cache rather than at the project.
            return "";
        }
        return complete(staged) ? ""
                : "the staged Gradle distribution " + named + " is incomplete: it has no .ok marker"
                        + " and the wrapper cannot download in a sealed build. This is the"
                        + " distribution cache, not the project. Stage it and re-run.";
    }

    /** Gradle writes {@code <name>.zip.ok} beside the unpacked tree once it has finished. */
    private static boolean complete(Path staged) {
        try (Stream<Path> tree = Files.walk(staged, 3)) {
            return tree.anyMatch(p -> p.getFileName().toString().endsWith(".ok"));
        } catch (IOException unreadable) {
            // Unreadable is not evidence of incompleteness, and refusing a build on a stat error
            // would trade a rare false failure for a common one.
            return true;
        }
    }
}
