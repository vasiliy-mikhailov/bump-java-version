package tech.mikhailov.bjv.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

import com.deepagents.langchain4j.flow.DeepAgentFlowListener;

/**
 * The trace as one append-only file per bump, plus the settlements file beside it.
 *
 * <p>Two files, because they answer different questions. {@code trace.jsonl} is everything, for
 * analysis and for prompt tuning. {@code settlements.jsonl} is the last word per bump, for a reader
 * who wants to know what happened rather than how.
 */
final class JsonlTrace implements Trace, DeepAgentFlowListener {

    private final Path trace;
    private final Path settlements;
    private final String bump;

    JsonlTrace(Path trace, Path settlements, String bump) {
        this.trace = trace;
        this.settlements = settlements;
        this.bump = bump;
    }

    @Override
    public void asked(String agent, String prompt, String reply) {
        // IN FULL, both of them. Truncating here would save disk and cost the corpus.
        write("asked", of("agent", agent, "prompt", prompt, "reply", reply));
    }

    @Override
    public void applied(String stage, String what) {
        write("applied", of("stage", stage, "what", what));
    }

    @Override
    public void thought(String finishReason, String thinking, String content) {
        write("thought", of("finish", finishReason, "thinking", thinking, "content", content));
    }

    @Override
    public void tool(String agent, String tool, String arguments, String result) {
        write("tool", of("agent", agent, "tool", tool, "arguments", arguments, "result", result));
    }

    // --- the library's own report; its payloads arrive truncated, Tools records them in full ---

    @Override
    public void onToolInvocation(String context, String toolName, Object memoryId,
                                 String argumentsTruncated, String resultTruncated) {
        // Recorded already at the executor; writing the shortened duplicate would double the file.
    }

    @Override
    public void onOrchestratorSystemReady(String assembledSystemPrompt) {
        write("system", of("prompt", assembledSystemPrompt));
    }

    @Override
    public void built(String phase, Runner.Result result) {
        write("built", of("phase", phase, "infra", String.valueOf(result.infra()),
                "passed", String.valueOf(result.passed()), "summary", result.summary()));
    }

    @Override
    public void settled(String bumpKey, String state, String because, boolean baseline, boolean gate) {
        write("settled", of("state", state, "because", because,
                "baseline", String.valueOf(baseline), "gate", String.valueOf(gate)));
        Settlement.note(settlements, bumpKey, state, because, baseline, gate);
    }

    @Override
    public void failed(String bumpKey, Throwable cause) {
        String what = cause.getClass().getSimpleName() + ": " + cause.getMessage();
        StringBuilder where = new StringBuilder(what);
        for (StackTraceElement s : cause.getStackTrace()) {
            where.append("\n  at ").append(s);
        }
        if (cause.getCause() != null) {
            where.append("\ncaused by ").append(cause.getCause());
        }
        write("failed", of("cause", what, "stack", where.toString()));
        Settlement.note(settlements, bumpKey, "infra", what);
    }

    @Override
    public void progress(String bumpKey, String note) {
        write("progress", of("note", note));
        Settlement.note(settlements, bumpKey, "bumping", note);
    }

    @Override
    public void priced(String bumpKey, String minutes, String itemisation) {
        write("priced", of("minutes", minutes, "itemisation", itemisation));
    }

    /** A map that tolerates a null value, because an empty model answer is a judgement, not a crash. */
    private static Map<String, String> of(String... pairs) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            m.put(pairs[i], pairs[i + 1] == null ? "" : pairs[i + 1]);
        }
        return m;
    }

    private void write(String kind, Map<String, String> fields) {
        Map<String, String> row = new LinkedHashMap<>();
        // WHEN, on every event: "how long" is half of what anyone reads a trace for.
        row.put("at", String.valueOf(System.currentTimeMillis()));
        row.put("bump", bump);
        row.put("kind", kind);
        row.putAll(fields);
        StringBuilder b = new StringBuilder("{");
        row.forEach((k, v) -> {
            if (b.length() > 1) {
                b.append(',');
            }
            b.append('"').append(k).append("\":\"").append(Settlement.escape(v)).append('"');
        });
        try {
            if (trace.getParent() != null) {
                Files.createDirectories(trace.getParent());
            }
            Files.writeString(trace, b.append("}\n"),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // A trace that cannot be written must not end a bump that is otherwise fine, but say so:
            // a silently absent trace is worse than a loud one.
            System.err.println("trace: " + e.getMessage());
        }
    }
}
