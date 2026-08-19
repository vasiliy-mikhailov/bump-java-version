package tech.mikhailov.bjv.web;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tech.mikhailov.ratchet.record.Json;

/**
 * THE RESULTS DIRECTORY, READ RATHER THAN DESCRIBED.
 *
 * <p>Every endpoint here is a different question asked of one tree: {@code settlements.jsonl} at
 * the top, a directory per bump beneath it, a {@code trace.jsonl} inside each of those, and the
 * queue beside them all. The list, the record, the security tables and the settings page each used
 * to carry their own copy of how to walk it, which is how a bump key came to be flattened into a
 * slug in four places.
 *
 * <p>NOTHING IS REMEMBERED HERE. A sweep appends to these files while they are being read, so a
 * reader that cached them would answer with the corpus as it stood when the server started. What is
 * worth remembering is remembered by the caller that knows what its own numbers cost and what
 * invalidates them, keyed on the file's own clock; see {@link Corpus} and {@link Compliance}.
 */
final class Results {

    private final Path dir;

    Results(Path dir) {
        this.dir = dir;
    }

    /** Where the bumps are: one directory per slug, with the settlement file beside them. */
    Path dir() {
        return dir;
    }

    /**
     * The run root, which is the directory ABOVE results.
     *
     * <p>The queue, the prompts, the uploaded manifests and {@code max_lanes} all live there rather
     * than inside results, because results is the directory this server hands out.
     */
    Path root() {
        return dir.getParent() == null ? dir : dir.getParent();
    }

    /** Where one bump wrote what it did. Absent until a lane picks the bump up. */
    Path trace(String slug) {
        return dir.resolve(slug).resolve("trace.jsonl");
    }

    /**
     * The latest settlement per bump, keyed by slug. A bump settles more than once as it runs.
     *
     * <p>NOT EVERY ROW IN THAT FILE IS A BUMP. The supervisor writes its own rows there — its last
     * one on this corpus is an authentication failure against the model endpoint — and rendering
     * those as repositories put a bump called "supervisor" at the top of the table, verdict
     * `bumping`, forever. A bump key is {@code repo|sha|from|to} and anything else is another kind
     * of row that happens to share the file.
     */
    Map<String, Map<String, String>> settlements() {
        Map<String, Map<String, String>> latest = new LinkedHashMap<>();
        for (String line : lines(dir.resolve("settlements.jsonl"))) {
            Map<String, String> r = Json.row(line);
            String bump = r.getOrDefault("bump", "");
            if (isBump(bump)) {
                latest.put(slug(bump), r);
            }
        }
        return latest;
    }

    /** Four fields, and the last two are the hop, which is what makes it a bump and not a note. */
    private static boolean isBump(String key) {
        String[] parts = key.split("\\|");
        if (parts.length < 4) {
            return false;
        }
        try {
            Integer.parseInt(parts[2].trim());
            Integer.parseInt(parts[3].trim());
            return true;
        } catch (NumberFormatException notAHop) {
            return false;
        }
    }

    /** The directory name a bump's trace lives under: the key with everything unsafe flattened. */
    static String slug(String bump) {
        return bump.replaceAll("[^A-Za-z0-9]+", "_");
    }

    /** A bump that has not settled yet still has a key, in every event it wrote. */
    static String unslug(List<Map<String, String>> events) {
        return events.stream().map(e -> e.getOrDefault("bump", "")).filter(s -> !s.isBlank())
                .findFirst().orElse("");
    }

    static long num(String s) {
        try {
            return s == null || s.isBlank() ? 0 : Long.parseLong(s.trim());
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }

    static String first(Map<String, String> row, String... keys) {
        for (String k : keys) {
            String v = row.get(k);
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return "";
    }

    static List<String> lines(Path p) {
        try {
            return Files.isRegularFile(p) ? Files.readAllLines(p) : List.of();
        } catch (IOException unreadable) {
            return List.of();
        }
    }
}
