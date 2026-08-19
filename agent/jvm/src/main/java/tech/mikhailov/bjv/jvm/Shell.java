package tech.mikhailov.bjv.jvm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * One way to run a command, with the two decisions every caller would otherwise make differently:
 * output is captured merged (a build interleaves its error with its progress, and separating them
 * re-orders the story), and a timeout kills the whole process group (a maven that outlives its
 * parent keeps a lane occupied for an hour).
 */
public final class Shell {

    public record Output(int code, String text) {
        public boolean ok() {
            return code == 0;
        }
    }

    private Shell() {
    }

    public static Output run(Path cwd, Map<String, String> env, Duration patience, String... cmd)
            throws IOException, InterruptedException {
        return exec(cwd, env, patience, true, cmd);
    }

    /**
     * The same, with stderr discarded rather than merged.
     *
     * <p>For a command whose stdout contract is a document. Merging is right for a build, where the
     * error and the progress are one story; it is fatal for a scanner emitting JSON, since a single
     * warning line makes the output unparseable.
     */
    public static Output runSeparate(Path cwd, Map<String, String> env, Duration patience, String... cmd)
            throws IOException, InterruptedException {
        return exec(cwd, env, patience, false, cmd);
    }

    private static Output exec(Path cwd, Map<String, String> env, Duration patience,
                               boolean mergeErr, String... cmd)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(List.of(cmd))
                .directory(cwd.toFile())
                .redirectErrorStream(mergeErr);
        if (!mergeErr) {
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        }
        pb.environment().putAll(env);
        Process p = pb.start();
        // Read concurrently: a build writes more than a pipe buffers, and a full pipe deadlocks
        // against a parent that is politely waiting for exit before reading.
        StringBuilder text = new StringBuilder();
        Thread reader = new Thread(() -> {
            try {
                new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                        .chars().forEach(c -> text.append((char) c));
            } catch (IOException ignored) {
                // The process died; whatever was read is the record.
            }
        });
        reader.start();
        if (!p.waitFor(patience.toSeconds(), TimeUnit.SECONDS)) {
            p.destroyForcibly();
            reader.join(5000);
            return new Output(124, text + "\n[killed: exceeded " + patience.toMinutes() + "m]");
        }
        reader.join(30000);
        return new Output(p.exitValue(), text.toString());
    }
}
