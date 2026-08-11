package tech.mikhailov.bjv.agent;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
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

    private static final Duration PATIENCE = Duration.ofSeconds(1900);
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
        env.put("BJV_HANG_GUARD", "1800");
        return env;
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
