package tech.mikhailov.bjv.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The trace as one append-only file per bump, plus the settlements file beside it.
 *
 * <p>Two files, because they answer different questions. {@code trace.jsonl} is everything, for
 * analysis and for prompt tuning. {@code settlements.jsonl} is the last word per bump, for a reader
 * who wants to know what happened rather than how.
 */
public final class JsonlTrace implements Trace, FlowListening {

    private final Path trace;
    private final Path settlements;
    private final String bump;
    private final Version.Parts parts;

    /**
     * WHICH PIPELINE THIS ROW CAME FROM, computed here because this is where the row is written.
     *
     * <p>Everything it needs is already held: the bump string carries the hop, and the settlements
     * file sits in the results root the prompt and bill-of-materials stores hang off. A failure to
     * work it out must not cost a settlement, so it answers with nothing and the row is written
     * either way: a bump recorded without its version is worse than one recorded with it, and a
     * bump not recorded at all is worse than both.
     */
    /**
     * COMPUTED ONCE PER LANE, because it is asked for on every progress write.
     *
     * <p>Version.prompts builds all sixty-five agents for the hop and hashes what each is handed,
     * which is the right answer and far too much work to repeat per event. A lane keeps the image
     * it started with, so the value it would compute later is the value it computed first.
     */
    private volatile String version;

    private String version() {
        if (version != null) {
            return version;
        }
        try {
            String[] p = bump.split("\\|");
            if (parts == null || p.length < 4) {
                version = "";
                return version;
            }
            // THE HOP AS A KEY AND NOTHING MORE. What p[2] and p[3] mean is the pipeline's
            // business; all this needs is a name for the variant, spelt the way the prompt store
            // and the settings page have always spelt it.
            version = Version.fields(p[2] + "-" + p[3],
                    settlements.getParent() == null ? settlements : settlements.getParent(), parts);
            return version;
        } catch (RuntimeException cannotTell) {
            version = "";
            return version;
        }
    }

    /**
     * A record with no pipeline behind it, whose rows carry no fingerprint.
     *
     * <p>For a writer that is not one bump: the supervisor's own journal, and a reader opening a
     * lane's file to summarise it. Neither settles anything, so neither has a pipeline to name.
     */
    public JsonlTrace(Path trace, Path settlements, String bump) {
        this(trace, settlements, bump, null);
    }

    /**
     * The same, for a bump, with the parts of the fingerprint only the pipeline can supply.
     *
     * <p>{@code parts} is what makes a settled row say which pipeline produced it. Writing without
     * it is not an error, it is a row that does not claim one.
     */
    public JsonlTrace(Path trace, Path settlements, String bump, Version.Parts parts) {
        this.trace = trace;
        this.settlements = settlements;
        this.bump = bump;
        this.parts = parts;
    }

    /**
     * The run reading its own record back, one line per event, newest last.
     *
     * <p>SUMMARISED, HARD. A trace row carries whole prompts and whole tool results, and this is
     * read INTO a prompt: returning rows whole would put the conversation inside itself and grow
     * the context faster than anything else here. One line each is enough to see what was tried,
     * what was objected to and what a tool answered; the file itself keeps everything.
     */
    @Override
    public String happened(String stage, String agent, int limit) {
        if (!Files.isReadable(trace)) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        try (var rows = Files.lines(trace)) {
            for (String row : rows.toList()) {
                if (!row.contains("\"bump\":\"" + bump.replace("\"", "") + "\"")
                        && !row.contains(escape(bump))) {
                    continue;
                }
                String kind = value(row, "kind");
                String who = value(row, "agent");
                String where = value(row, "stage");
                if (!stage.isBlank() && !stage.equalsIgnoreCase(where)) {
                    continue;
                }
                if (!agent.isBlank() && !agent.equalsIgnoreCase(who)) {
                    continue;
                }
                String line = switch (kind) {
                    case "asked" -> "[" + who + "] answered: " + one(value(row, "reply"));
                    case "applied" -> "[" + where + "] " + one(value(row, "what"));
                    case "tool" -> "[" + who + "] " + value(row, "tool") + "("
                            + one(value(row, "arguments")) + ") -> " + one(value(row, "result"));
                    case "progress" -> "* " + one(value(row, "note"));
                    case "built" -> "[build " + value(row, "phase") + "] "
                            + (row.contains("\"infra\":true") ? "did not run" : "ran");
                    case "settled" -> "SETTLED " + value(row, "state") + ": "
                            + one(value(row, "because"));
                    default -> "";
                };
                if (!line.isBlank()) {
                    lines.add(rank(kind, line) + "\u0000" + line);
                }
            }
        } catch (IOException unreadable) {
            return "";
        }
        // MOST IMPORTANT FIRST, THEN BACK INTO ORDER. Returned chronologically, the budget went on
        // whatever happened to be recent: measured over five real calls, 13 of 349 returned lines
        // carried a decision and the rest was an hour-old reader reading poms. Ranking first and
        // re-sorting after means the same budget carries the verdicts, the objections and the
        // failures, with the routine filling whatever room is left.
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            order.add(i);
        }
        order.sort((a, b) -> {
            int byRank = lines.get(b).charAt(0) - lines.get(a).charAt(0);
            return byRank != 0 ? byRank : b - a;
        });
        List<Integer> kept = new ArrayList<>(order.subList(0, Math.min(order.size(),
                Math.max(1, limit))));
        kept.sort(Integer::compareTo);
        StringBuilder out = new StringBuilder();
        for (int i : kept) {
            String line = lines.get(i);
            out.append(line, line.indexOf('\u0000') + 1, line.length()).append('\n');
        }
        return out.toString().strip();
    }

    /**
     * How much a line is worth keeping when the budget runs out.
     *
     * <p>A verdict, an objection and a failure are what a reader came for. A tool call is the bulk
     * of any trace and almost never the point, which is why an unranked log spends its whole budget
     * on file reads.
     */
    private static char rank(String kind, String line) {
        String low = line.toLowerCase(java.util.Locale.ROOT);
        if (kind.equals("settled") || low.contains("fail_") || low.contains("pass (")) {
            return '5';
        }
        if (low.contains("gaming") || low.contains("off-target") || low.contains("blocked:")
                || low.contains("rejected") || low.contains("declined") || low.contains("reverted")) {
            return '4';
        }
        if (kind.equals("progress")) {
            return '3';
        }
        if (kind.equals("asked") || kind.equals("applied")) {
            return '2';
        }
        return '1';
    }

    /** One line, short enough that a hundred of them are still a summary. */
    private static String one(String text) {
        String flat = text.replace("\\n", " ").replace('\n', ' ').strip();
        return flat.length() > 180 ? flat.substring(0, 180) + " ..." : flat;
    }

    /** A field out of a written row, without parsing JSON the writer already knows the shape of. */
    private static String value(String row, String key) {
        String needle = "\"" + key + "\":\"";
        int at = row.indexOf(needle);
        if (at < 0) {
            return "";
        }
        int from = at + needle.length();
        StringBuilder out = new StringBuilder();
        for (int i = from; i < row.length(); i++) {
            char c = row.charAt(i);
            if (c == '\\' && i + 1 < row.length()) {
                char next = row.charAt(++i);
                out.append(next == 'n' ? ' ' : next == 't' ? ' ' : next);
                continue;
            }
            if (c == '"') {
                break;
            }
            out.append(c);
        }
        return out.toString();
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
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

    // --- what the agent loop reports while it runs; its payloads arrive shortened ---

    @Override
    public void onToolInvocation(String context, String toolName, Object memoryId,
                                 String argumentsTruncated, String resultTruncated) {
        // Recorded already at the executor; writing the shortened duplicate would double the file.
    }

    @Override
    public void built(String phase, Outcome result) {
        write("built", of("phase", phase, "infra", String.valueOf(result.infra()),
                "passed", String.valueOf(result.passed()), "summary", result.summary()));
    }

    @Override
    public void settled(String bumpKey, String state, String because, boolean baseline, boolean gate) {
        settled(bumpKey, state, because, baseline, gate, false);
    }

    @Override
    public void settled(String bumpKey, String state, String because, boolean baseline, boolean gate,
                        boolean resumed) {
        write("settled", of("state", state, "because", because,
                "baseline", String.valueOf(baseline), "gate", String.valueOf(gate),
                "resumed", String.valueOf(resumed)));
        Settlement.settled(settlements, bumpKey, state, because, baseline, gate, version(), resumed);
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
        Settlement.note(settlements, bumpKey, "bumping", note, false, false, version());
    }

    @Override
    public void priced(String bumpKey, String minutes, String itemisation) {
        write("priced", of("minutes", minutes, "itemisation", itemisation));
    }

    @Override
    public void exchanged(Exchange e) {
        write("exchange", of(
                "direction", e.direction(),
                "agent", e.agent(),
                "messages", String.valueOf(e.messages()),
                "sent", e.sent(),
                "got", e.got(),
                "tools", e.tools(),
                "finish", e.finish(),
                "in", String.valueOf(e.inTokens()),
                "out", String.valueOf(e.outTokens()),
                "ms", String.valueOf(e.ms()),
                "error", e.error()));
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
