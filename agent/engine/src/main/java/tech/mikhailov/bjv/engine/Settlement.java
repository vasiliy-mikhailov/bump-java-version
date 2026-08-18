package tech.mikhailov.bjv.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * The last word per bump, in a file a reader can open without replaying anything.
 *
 * <p>{@code trace.jsonl} answers "how"; {@code settlements.jsonl} answers "what happened". One row
 * per note, append-only, latest row wins: rewriting in place would lose the path a bump took through
 * its states, and that path is half of what the dashboard shows.
 */
public final class Settlement {

    private Settlement() {
    }

    static void note(Path file, String bump, String state, String because) {
        note(file, bump, state, because, false, false);
    }

    static void note(Path file, String bump, String state, String because,
                     boolean baselineGreen, boolean gateGreen) {
        note(file, bump, state, because, baselineGreen, gateGreen, "");
    }

    /**
     * The same row, carrying which pipeline produced it.
     *
     * <p>A sweep runs for a fortnight and the harness changes daily, so a settled row without this
     * cannot be told apart from a settled row produced by different code. See {@link Version}.
     */
    public static void note(Path file, String bump, String state, String because,
                     boolean baselineGreen, boolean gateGreen, String version) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            String row = "{\"at\":\"" + System.currentTimeMillis() + "\",\"bump\":\"" + escape(bump)
                    + "\",\"state\":\"" + escape(state) + "\",\"because\":\"" + escape(because)
                    + "\",\"baseline\":" + baselineGreen + ",\"gate\":" + gateGreen
                    + (version.isEmpty() ? "" : "," + version) + "}\n";
            Files.writeString(file, row, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("settlement: " + e.getMessage());
        }
    }

    /** JSON string escaping, shared by every writer in the program so there is exactly one. */
    public static String escape(String s) {
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.toString();
    }
}
