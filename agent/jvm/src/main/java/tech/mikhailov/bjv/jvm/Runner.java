package tech.mikhailov.bjv.jvm;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tech.mikhailov.ratchet.config.Env;
import tech.mikhailov.ratchet.record.Trace;

/**
 * THE ARBITER — the only thing in a bump that is not somebody's opinion.
 *
 * <p>A bump gets past this by building and keeping its tests green under the TARGET JDK. No agent
 * may invoke it: {@code Bump} runs it, and whether the gate ran after the fixer's edit is not a
 * model's choice. It shells to the harness's own {@code jvm-run <jdk> jvmjob build|test}, so the
 * agent chain and the bash sweeps measure through the identical sealed container.
 *
 * <p>THREE OUTCOMES, NOT TWO. "The tests failed" and "the build never ran" are different facts, and
 * collapsing them is how a repo whose reactor died at module 2 gets recorded as having lost its
 * tests rather than as never having run them.
 */
public final class Runner {

    public record Result(boolean infra, boolean passed, String summary) {

        /**
         * The same three facts, in the record's own vocabulary.
         *
         * <p>{@link Trace} takes {@link Trace.Outcome} rather than this, so that recording what
         * happened does not depend on the class that shells to Maven. The conversion is here
         * because this is the side that knows it is a build.
         */
        public Trace.Outcome outcome() {
            return new Trace.Outcome(infra, passed, summary);
        }
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
    /**
     * That tests RAN, in the vocabulary of whichever build system ran them.
     *
     * <p>THIS USED TO BE SUREFIRE'S PHRASING AND NOTHING ELSE. Gradle prints "8 tests completed, 1
     * failed", which never matched, so a Gradle run that executed its whole suite and had one
     * assertion fail exited non-zero, looked to this class like a build that died before testing,
     * and was reported as an infrastructure failure: "the tests could not be RUN". Maven, whose
     * baseline carries -Dmaven.test.failure.ignore=true, exits 0 and never reached the question.
     * That is why 28 of 30 no-baseline verdicts were Gradle against 2 Maven.
     */
    private static final Pattern TESTS = Pattern.compile(
            "Tests run: (\\d+)|(\\d+) tests? completed");

    private final Path ws;
    private final String jvmRun;
    private final Map<String, String> env;

    public Runner(Path ws, String hoptools) {
        this.ws = ws;
        this.jvmRun = hoptools + "/jvm-run";
        this.env = env(ws);
    }

    /** The sealed-container environment, shared with anything else that shells to the harness. */
    public static Map<String, String> env(Path ws) {
        Map<String, String> env = new HashMap<>();
        env.put("BJV_WS", ws.toString());
        Env.copy(env, "BJV_NET", "mvn-cache");
        Env.copyIfSet(env, "BJV_REPO_URL");
        Env.copyIfSet(env, "BJV_M2");
        Env.copyIfSet(env, "BJV_SETTINGS");
        Env.copyIfSet(env, "BJV_GRADLE_RO");
        Env.copyIfSet(env, "BJV_GRADLE_DISTS");
        Env.copyIfSet(env, "BJV_GRADLE_INIT");
        Env.copy(env, "BJV_HANG_GUARD", "1800");
        Env.copyIfSet(env, "BJV_JDK_IMAGE");
        return env;
    }

    /**
     * Delete compiled output before a GATE build.
     *
     * <p>THE GATE MEASURES BYTECODE, SO IT MUST COMPILE BYTECODE. Maven's build here is
     * {@code test-compile} with no {@code clean}, so it compares timestamps, finds the baseline's
     * class files newer than the sources, prints "Nothing to compile - all classes are up to date"
     * and leaves them alone. Those classes were compiled at the OLD target, so
     * {@code Gate.effectiveTarget} reads the level the project started at however correctly the
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

    /**
     * THE SAME WIPE, UNDER ONE MODULE.
     *
     * <p>The module gate was answering the wrong question entirely. A bump is a pom-only edit, so
     * no source is newer than its class, and maven-compiler-plugin 3.8.1 and 3.13.0 both answer
     * "Nothing to compile - all classes are up to date". Measured in vivo on the first three Maven
     * bumps to run the new walk: every module gate compiled 0 files, and the repository gate
     * seconds later compiled 5, 7 and 5. On one repository seven module gates flip from all-red to
     * all-green purely because the baseline build ran first.
     *
     * <p>Repository-wide would be correct and would also re-pay the whole reactor's compile once
     * per module per turn, which is the cost the walk exists to avoid.
     */
    public void clearClasses(String module) {
        Path under = module == null || module.isBlank() || module.equals(".")
                ? ws : ws.resolve(module);
        wipe(under, name -> name.equals("classes") || name.equals("test-classes")
                || GENERATED.contains(name));
    }

    public void clearClasses() {
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
    public void clearReports() {
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
        wipe(ws, named);
    }

    private void wipe(Path under, java.util.function.Predicate<String> named) {
        if (!java.nio.file.Files.isDirectory(under)) {
            return;
        }
        try (var s = java.nio.file.Files.walk(under)) {
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

    public Result build(String jdk) {
        return run(jdk, "build");
    }

    /**
     * COMPILE ONE MODULE, so a repair can happen where the diff still is.
     *
     * <p>The repair loop used to sit after every module had been bumped, which meant a break in the
     * first module surfaced as a reactor error after the last one, with no obvious owner and two
     * hundred lines of log. This is the check that lets it be found immediately.
     *
     * <p>Compile only, deliberately. Test conservation is a whole-suite fact measured against the
     * baseline, so a per-module test run cannot decide it and the repository gate has to run the
     * suite anyway; running it here as well would pay for that module's tests twice.
     *
     * @param module the module's path relative to the workspace, empty or {@code .} for the root
     */
    public Result buildModule(String jdk, String module) {
        return run(jdk, "build-module", module);
    }

    public Result test(String jdk) {
        return run(jdk, "test");
    }

    private Result run(String jdk, String goal, String... more) {
        try {
            String[] cmd = new String[4 + more.length];
            cmd[0] = jvmRun;
            cmd[1] = jdk;
            cmd[2] = "jvmjob";
            cmd[3] = goal;
            System.arraycopy(more, 0, cmd, 4, more.length);
            Shell.Output out = Shell.run(ws, env, PATIENCE, cmd);
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
    public static int testsIn(String summary) {
        int n = 0;
        Matcher m = TESTS.matcher(summary);
        while (m.find()) {
            // Whichever alternative matched is the one that carries the number.
            String surefire = m.group(1);
            n += Integer.parseInt(surefire != null ? surefire : m.group(2));
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
