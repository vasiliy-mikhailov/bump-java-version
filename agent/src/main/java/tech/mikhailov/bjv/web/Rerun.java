package tech.mikhailov.bjv.web;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import tech.mikhailov.bjv.agent.Json;

/**
 * ASK FOR A BUMP TO BE RUN AGAIN.
 *
 * <p>A settled bump is skipped forever, which is right while the harness is unchanged and wrong
 * the moment it is not. The floors, the prompts and the tools all moved today, so a verdict
 * from this morning was reached by an agent that no longer exists. Until now the only way to
 * revisit one was to hand-edit settlements.jsonl on the host.
 *
 * <p>AN APPEND, NOT A REWRITE. Both readers of that file already take the LAST line for a bump
 * and ignore what came before, so requeueing is one line added rather than a file rewritten
 * underneath a sweep that is appending to it. A rewrite would race, and the window is precisely
 * the moment a lane settles.
 *
 * <p>"requeued" rather than "bumping", which would also have worked because it is the one state
 * run.sh treats as unfinished. A bump waiting for a lane is not a bump in flight, and this
 * corpus has spent the day removing numbers that were true only by accident. The page reads it
 * as queued, which is what it is.
 *
 * <p>THE ONE WRITE INTO A LIVE SWEEP that this server makes, which is why it is not filed with the
 * list it appears beside. Everything in {@link Corpus} reads; this hands work back to run.sh.
 */
final class Rerun {

    private final Results results;

    Rerun(Path results) {
        this.results = new Results(results);
    }

    String ask(String slug) {
        if (slug == null || slug.isBlank()) {
            return Json.object(Json.field("queued", "false"),
                    Json.field("why", Json.string("no slug given")));
        }
        Map<String, String> settled = results.settlements().get(slug);
        if (settled == null) {
            return Json.object(Json.field("queued", "false"),
                    Json.field("why", Json.string("nothing settled under that slug")));
        }
        String bump = settled.getOrDefault("bump", "");
        String[] parts = bump.split("\\|");
        if (parts.length < 4) {
            return Json.object(Json.field("queued", "false"),
                    Json.field("why", Json.string("that settlement names no hop")));
        }
        if (alreadyQueued(slug)) {
            // TRUE, AND NOT A REFUSAL. The work is going to happen; a second row for it would only
            // make a lane discover it was already in flight and skip it.
            return Json.object(Json.field("queued", "true"),
                    Json.field("repo", Json.string(parts[0])),
                    Json.field("hop", Json.string(parts[2] + " -> " + parts[3])),
                    Json.field("was", Json.string("already waiting for a lane")));
        }
        try {
            Files.writeString(results.dir().resolve("rerun.tsv"),
                    slug + "\t" + parts[0] + "\t" + parts[1] + "\t" + parts[2] + "\t" + parts[3]
                            + "\n",
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
            Files.writeString(results.dir().resolve("settlements.jsonl"),
                    "{\"at\":\"" + System.currentTimeMillis() + "\",\"bump\":\"" + bump
                            + "\",\"kind\":\"settled\",\"state\":\"requeued\"}\n",
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException couldNotWrite) {
            return Json.object(Json.field("queued", "false"),
                    Json.field("why", Json.string(String.valueOf(couldNotWrite.getMessage()))));
        }
        return Json.object(
                Json.field("queued", "true"),
                Json.field("repo", Json.string(parts[0])),
                Json.field("hop", Json.string(parts[2] + " -> " + parts[3])),
                Json.field("was", Json.string(settled.getOrDefault("state", ""))));
    }

    /**
     * Is this bump already waiting in the drainer's queue?
     *
     * <p>ASKING TWICE IS A FAIR THING TO DO. A requeued bump waits for a free lane and a lane runs
     * for hours, so the honest answer to a second click is "it is already queued", not a second row
     * in the manifest for the same work. The queue is two files: what has not been picked up yet,
     * and the batch a drainer has taken but not finished.
     */
    private boolean alreadyQueued(String slug) {
        List<Path> queues = new ArrayList<>();
        queues.add(results.dir().resolve("rerun.tsv"));
        try (var batches = Files.list(results.root())) {
            batches.filter(p -> p.getFileName().toString().startsWith("rerun-batch"))
                    .forEach(queues::add);
        } catch (IOException unreadable) {
            // A queue that cannot be read is not evidence of an empty one, but refusing every
            // rerun on that basis would be worse than the duplicate row it prevents.
        }
        for (Path queue : queues) {
            try {
                if (Files.isRegularFile(queue) && Files.readAllLines(queue).stream()
                        .anyMatch(l -> l.startsWith(slug + "\t"))) {
                    return true;
                }
            } catch (IOException unreadable) {
                continue;
            }
        }
        return false;
    }
}
