package tech.mikhailov.bjv.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WHAT A REPOSITORY IS MADE OF, WHICH IS NOT THE SAME AS WHAT IT IS.
 *
 * <p>The bump was scored on the repository as a whole, and that hid the thing that most often went
 * wrong. {@link Gate#effectiveTarget} takes a single minimum across the entire tree, so one module
 * left on the old target reads as an unbumped repository with no indication of which module is
 * responsible. Two thirds of this corpus is single-module and never noticed; the other third was
 * being judged on a number it could not act on.
 *
 * <p>Splitting it costs nothing at build time. The reactor build already writes every module's
 * class files to {@code <module>/target/classes} and every module's results to
 * {@code <module>/target/surefire-reports}, and both gate functions take a root and walk it. A
 * module's verdict is therefore the same two functions called one directory lower, off output the
 * single build already produced. There is no second build here, and there must not be one: builds
 * are the expensive part of a bump and the reactor amortises them.
 *
 * <p>Enumeration is deliberately offline. Maven can be asked for its reactor order, but that means
 * resolving the whole project against a network the sealed hop images do not have, to learn
 * something the build files already state. The declared order is what this reads.
 */
final class Modules {

    /** Every {@code <module>} entry, including the ones only a profile activates. */
    private static final Pattern MAVEN_MODULE =
            Pattern.compile("<module>\\s*([^<]+?)\\s*</module>");

    /** Gradle's {@code include ':a:b'} in either dialect, quoted either way. */
    private static final Pattern GRADLE_INCLUDE =
            Pattern.compile("include\\s*\\(?\\s*['\"]:?([A-Za-z0-9_.:\\-]+)['\"]");

    /**
     * One buildable unit, addressed by its path relative to the workspace.
     *
     * <p>The root is a module too, and is always present. A single-module repository enumerates to
     * exactly the root, so everything downstream loops once rather than branching on whether the
     * project happens to aggregate.
     */
    record Module(String path, String name) {

        boolean isRoot() {
            return path.isEmpty();
        }

        /** Where this module lives, which is what the gate functions want. */
        Path in(Path ws) {
            return path.isEmpty() ? ws : ws.resolve(path);
        }

        @Override
        public String toString() {
            return path.isEmpty() ? name + " (root)" : path;
        }
    }

    private Modules() {
    }

    /**
     * The modules of a workspace, root first, in the order the build files declare them.
     *
     * <p>Parent before child, which is the order that matters: a child referencing a property its
     * parent defines should not be reasoned about before the parent has been read. Maven's true
     * reactor order additionally sorts by inter-module dependencies, and that difference does not
     * reach us. Every module here is compiled by one JDK in one reactor pass, so javac reads a
     * sibling's class files whatever order they were written in, and the edits all land before any
     * build runs.
     */
    static List<Module> of(Path ws) throws IOException {
        List<Module> out = new ArrayList<>();
        out.add(new Module("", ws.getFileName() == null ? "root" : ws.getFileName().toString()));
        if (Files.isRegularFile(ws.resolve("pom.xml"))) {
            collectMaven(ws, "", out, new LinkedHashSet<>());
        } else {
            collectGradle(ws, out);
        }
        return out;
    }

    /**
     * Follow {@code <modules>} down the aggregation tree.
     *
     * <p>Guarded against a module declaring a path that resolves back to somewhere already seen,
     * which is rare but is a non-terminating walk when it happens.
     */
    private static void collectMaven(Path ws, String prefix, List<Module> out, Set<String> seen)
            throws IOException {
        Path pom = (prefix.isEmpty() ? ws : ws.resolve(prefix)).resolve("pom.xml");
        if (!Files.isRegularFile(pom) || !seen.add(pom.normalize().toString())) {
            return;
        }
        Matcher m = MAVEN_MODULE.matcher(Files.readString(pom));
        while (m.find()) {
            String child = m.group(1).replace('\\', '/').replaceAll("/+$", "");
            if (child.isBlank() || child.startsWith("..")) {
                continue;
            }
            String path = prefix.isEmpty() ? child : prefix + "/" + child;
            if (!Files.isDirectory(ws.resolve(path))
                    || out.stream().anyMatch(x -> x.path().equals(path))) {
                continue;
            }
            out.add(new Module(path, child.substring(child.lastIndexOf('/') + 1)));
            collectMaven(ws, path, out, seen);
        }
    }

    /** Gradle states the whole set in one settings file rather than a tree of them. */
    private static void collectGradle(Path ws, List<Module> out) throws IOException {
        for (String name : List.of("settings.gradle", "settings.gradle.kts")) {
            Path settings = ws.resolve(name);
            if (!Files.isRegularFile(settings)) {
                continue;
            }
            Matcher m = GRADLE_INCLUDE.matcher(Files.readString(settings));
            while (m.find()) {
                String path = m.group(1).replace(':', '/');
                if (!Files.isDirectory(ws.resolve(path))
                        || out.stream().anyMatch(x -> x.path().equals(path))) {
                    continue;
                }
                out.add(new Module(path, path.substring(path.lastIndexOf('/') + 1)));
            }
        }
    }

    /**
     * The build files that belong to THIS module and not to one nested inside it.
     *
     * <p>The reason {@link Pins} could report a floor as met while most of the project sat below it:
     * it concatenated every build file in the tree and returned the first version it matched
     * anywhere. Sixteen of the twenty-seven multi-module repositories in this corpus resolve some
     * package to more than one version, so first-match-wins was answering about an arbitrary module,
     * chosen by filesystem walk order.
     *
     * <p>Scoping the read to one module is what makes the answer mean anything, and a nested module
     * is a different module, so its files are excluded here rather than counted twice.
     */
    static List<Path> buildFilesOf(Path ws, Module module, List<Module> all) throws IOException {
        Path dir = module.in(ws);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<Path> nested = all.stream()
                .filter(x -> !x.path().equals(module.path()))
                .filter(x -> module.isRoot() || x.path().startsWith(module.path() + "/"))
                .map(x -> x.in(ws).normalize())
                .toList();
        List<Path> out = new ArrayList<>();
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                String name = p.getFileName().toString();
                if (!isBuildFile(name)) {
                    continue;
                }
                String s = p.toString().replace('\\', '/');
                if (s.contains("/target/") || s.contains("/build/") || s.contains("/node_modules/")) {
                    continue;
                }
                // A POM UNDER src/ IS DATA, NOT THIS MODULE'S BUILD. Plugin integration tests,
                // archetype resources and test fixtures all ship poms, and they are usually pinned
                // to old versions on purpose. Counting them makes a module read as declaring
                // whatever its fixtures declare, and the lowest-wins rule then hands the phase a
                // floor it can never satisfy by editing anything that matters.
                if (s.contains("/src/test/") || s.contains("/src/it/")
                        || s.contains("/archetype-resources/") || s.contains("/src/main/resources/")) {
                    continue;
                }
                if (nested.stream().anyMatch(n -> p.normalize().startsWith(n))) {
                    continue;
                }
                out.add(p);
            }
        }
        return out;
    }

    private static boolean isBuildFile(String name) {
        return name.equals("pom.xml") || name.equals("build.gradle")
                || name.equals("build.gradle.kts") || name.equals("gradle.properties")
                || name.equals("gradle-wrapper.properties") || name.equals("libs.versions.toml");
    }
}
