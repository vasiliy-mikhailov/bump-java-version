package tech.mikhailov.bjv.web;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import tech.mikhailov.bjv.agent.Json;

/**
 * HOW MUCH OF WHAT THE TARGET NEEDS EACH BUMP REACHED, joined by bump.
 *
 * <p>A GREEN GATE IS NOT COMPLIANCE. The verdict says the project builds under the target and kept
 * its tests; this says how much of the bill of materials it actually met, and the two are different
 * questions about the same run.
 *
 * <p>IT SITS BESIDE settlements.jsonl RATHER THAN INSIDE IT, because a live sweep appends to that
 * file and two readers take the last line per bump to decide what state it is in. A measurement
 * does not belong in the path of a verdict.
 *
 * <p>Last line wins, so a bump measured again against a changed bill of materials shows the newer
 * number while the older one stays on the record.
 */
final class Compliance {

    private final Results results;

    private Map<String, Map<String, String>> held;
    private long heldAt = -1;

    Compliance(Results results) {
        this.results = results;
    }

    /**
     * One field of one bump's compliance, or null when it was never measured.
     *
     * <p>Cached, because forty-five bumps times three fields is a hundred and thirty-five reads of
     * one file to render one table. KEYED ON THE FILE'S OWN CLOCK, because this object outlives a
     * request: a cache that never expires would hold the numbers as they were when the dashboard
     * started and go on showing them while a sweep wrote new ones, which is worse than showing
     * none. The file only grows, so its timestamp is a complete description of its contents.
     */
    synchronized String of(String slug, String field) {
        long stamp;
        try {
            Path file = results.dir().resolve("bom.jsonl");
            stamp = Files.isRegularFile(file) ? Files.getLastModifiedTime(file).toMillis() : 0;
        } catch (IOException unreadable) {
            stamp = 0;
        }
        if (held == null || stamp != heldAt) {
            held = read();
            heldAt = stamp;
        }
        Map<String, String> row = held.get(slug);
        if (row == null) {
            return null;
        }
        String value = row.get(field);
        if (value == null || value.isBlank()) {
            return null;
        }
        // The counts travel as raw numbers; the outstanding floors travel as prose, so only that
        // one is quoted here.
        return field.equals("outstanding") ? Json.string(value) : value;
    }

    /** The whole file, read once and keyed by slug so a row costs a lookup rather than a walk. */
    private Map<String, Map<String, String>> read() {
        Map<String, Map<String, String>> byBump = new LinkedHashMap<>();
        Path file = results.dir().resolve("bom.jsonl");
        if (!Files.isRegularFile(file)) {
            return byBump;
        }
        try {
            for (String line : Files.readAllLines(file, java.nio.charset.StandardCharsets.UTF_8)) {
                Map<String, String> row = Json.row(line);
                String bump = row.get("bump");
                if (bump != null && !bump.isBlank()) {
                    byBump.put(Results.slug(bump), row);
                }
            }
        } catch (IOException unreadable) {
            return byBump;
        }
        return byBump;
    }
}
