package tech.mikhailov.bjv.agent;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * THE ARBITER — the only thing in a bump that is not somebody's opinion.
 *
 * <p>A bump gets past this by building and keeping its tests green under the TARGET JDK. No agent
 * may invoke it: {@link Bump} runs it, and whether the gate ran after the fixer's edit is not a
 * model's choice. It shells to the harness's own {@code jvm-run <jdk> jvmjob build|test}, so the
 * agent chain and the bash sweeps measure through the identical sealed container.
 *
 * <p>THREE OUTCOMES, NOT TWO. "The tests failed" and "the build never ran" are different facts, and
 * collapsing them is how a repo whose reactor died at module 2 gets recorded as having lost its
 * tests rather than as never having run them.
 */
final class Runner {

    record Result(boolean infra, boolean passed, String summary) {
    }

    /**
     * How long one build may take.
     *
     * <p>Matches what the bash sweeps have always used, so a repo that times out here would have
     * timed out there and the corpus stays comparable. It is a real cap on real work, unlike the
     * model guards: a build produces no liveness signal this side of its own output, so there is
     * nothing to watch for silence. Overridable for a host or a reactor that needs longer.
     */
    private static final Duration PATIENCE = Duration.ofSeconds(
            Long.parseLong(System.getenv().getOrDefault("BJV_BUILD_SECONDS", "1900")));
    private static final Pattern TESTS = Pattern.compile("Tests run: (\\d+)");

    private final Path ws;
    private final String jvmRun;
    private final Map<String, String> env;

    Runner(Path ws, String hoptools) {
        this.ws = ws;
        this.jvmRun = hoptools + "/jvm-run";
        this.env = env(ws);
    }

    /** The sealed-container environment, shared with anything else that shells to the harness. */
    static Map<String, String> env(Path ws) {
        Map<String, String> env = new HashMap<>();
        env.put("BJV_WS", ws.toString());
        env.put("BJV_NET", "mvn-cache");
        env.put("BJV_M2", "/home/vmihaylov/.m2-fitness");
        env.put("BJV_SETTINGS", "/home/vmihaylov/maven-config/settings.xml");
        env.put("BJV_GRADLE_RO", "/home/vmihaylov/.gradle-fitness");
        env.put("BJV_GRADLE_DISTS", "/home/vmihaylov/.gradle-dists");
        env.put("BJV_HANG_GUARD",
                System.getenv().getOrDefault("BJV_HANG_GUARD", "1800"));
        return env;
    }

    /**
     * Delete compiled output before a GATE build.
     *
     * <p>THE GATE MEASURES BYTECODE, SO IT MUST COMPILE BYTECODE. Maven's build here is
     * {@code test-compile} with no {@code clean}, so it compares timestamps, finds the baseline's
     * class files newer than the sources, prints "Nothing to compile - all classes are up to date"
     * and leaves them alone. Those classes were compiled at the OLD target, so
     * {@link Gate#effectiveTarget} reads the level the project started at however correctly the
     * poms were raised, and the bump is failed for a target it actually reached.
     *
     * <p>Measured over the live sweep: 46 of 117 gate builds carried that message, and 13 bumps
     * were failed FAIL_target_not_bumped with a correctly raised pom. The reflect loop then spends
     * its turns on it, since the troubleshooter is briefed "the effective bytecode target is 11,
     * not 17" about a project where nothing is wrong. The Gradle path never had this: it already
     * passes --rerun-tasks. This is the Maven half of the same idea.
     */
    /**
     * The names the corpus scorer already strips, for the same reason.
     *
     * <p>An annotation processor or a code generator writes into these, and what it writes depends
     * on the plugin version. causalnet/autojdk-maven-plugin: the baseline generated
     * {@code generated-sources/plugin/HelpMojo.java}, the migration raised maven-plugin-plugin,
     * and the raised plugin generated the same mojo into the project's own package. Both survived,
     * both were compiled, two classes claimed the goal {@code help}, and the descriptor step
     * aborted. Nothing was wrong with the project or the migration.
     */
    private static final Set<String> GENERATED = Set.of("generated", "generated-sources",
            "generated-src", "generated-java", "generated-test-sources");

    void clearClasses() {
        // Compiled output AND anything a generator wrote. Deleting the classes alone leaves the
        // sources that produced them, which is the half of the state that collides.
        wipe(name -> name.equals("classes") || name.equals("test-classes")
                || GENERATED.contains(name));
    }

    /**
     * Delete every surefire report before a run.
     *
     * <p>A gate that reads leftover XML from the baseline scores tests it never ran. The reports are
     * the evidence, so stale evidence is worse than none: it reads as conservation holding.
     */
    void clearReports() {
        try (var s = java.nio.file.Files.walk(ws)) {
            for (Path f : s.filter(java.nio.file.Files::isRegularFile).toList()) {
                String n = f.getFileName().toString();
                if (n.startsWith("TEST-") && n.endsWith(".xml")) {
                    java.nio.file.Files.deleteIfExists(f);
                }
            }
        } catch (IOException e) {
            System.err.println("clearReports: " + e.getMessage());
        }
    }

    /**
     * Remove every output directory whose name the test accepts, under target/ or build/ only.
     *
     * <p>Scoped to build output on purpose: a wipe that reached the source tree would be a
     * different and much worse bug than the one it fixes.
     */
    private void wipe(java.util.function.Predicate<String> named) {
        try (var s = java.nio.file.Files.walk(ws)) {
            List<Path> doomed = s.filter(java.nio.file.Files::isDirectory)
                    .filter(d -> {
                        Path parent = d.getParent();
                        String p = parent == null ? "" : parent.getFileName().toString();
                        return (p.equals("target") || p.equals("build"))
                                && named.test(d.getFileName().toString());
                    })
                    .toList();
            for (Path d : doomed) {
                try (var inner = java.nio.file.Files.walk(d)) {
                    inner.sorted(java.util.Comparator.reverseOrder())
                            .forEach(f -> {
                                try {
                                    java.nio.file.Files.deleteIfExists(f);
                                } catch (IOException stubborn) {
                                    // root-owned leftovers from a container build; the compile
                                    // will overwrite what it can and the gate reads what exists.
                                }
                            });
                }
            }
        } catch (IOException e) {
            System.err.println("wipe: " + e.getMessage());
        }
    }

    Result build(String jdk) {
        return run(jdk, "build");
    }

    Result test(String jdk) {
        return run(jdk, "test");
    }

    private Result run(String jdk, String goal) {
        try {
            Shell.Output out = Shell.run(ws, env, PATIENCE, jvmRun, jdk, "jvmjob", goal);
            String text = out.text();
            boolean ranTests = TESTS.matcher(text).find();
            if (!out.ok() && !ranTests) {
                // Died before any test executed: a compile error, a resolution failure, a timeout.
                return new Result(true, false, tail(text));
            }
            return new Result(false, out.ok(), tail(text));
        } catch (IOException | InterruptedException e) {
            return new Result(true, false, "runner: " + e.getMessage());
        }
    }

    /** Total tests the last run reported, so a settlement can carry the count, not an impression. */
    static int testsIn(String summary) {
        int n = 0;
        Matcher m = TESTS.matcher(summary);
        while (m.find()) {
            n += Integer.parseInt(m.group(1));
        }
        return n;
    }

    /**
     * The tail, sized for a prompt. The FIRST error matters more than the last line, so the cut
     * keeps everything from the first [ERROR] when one exists, and the plain tail otherwise.
     */
    static String tail(String text) {
        int firstError = text.indexOf("[ERROR]");
        String cut = firstError >= 0 ? text.substring(firstError) : text;
        return cut.length() > 12000 ? cut.substring(0, 6000) + "\n...\n"
                + cut.substring(cut.length() - 6000) : cut;
    }
}
