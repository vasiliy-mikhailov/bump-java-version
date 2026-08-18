package tech.mikhailov.bjv.web;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tech.mikhailov.bjv.agent.Bump;
import tech.mikhailov.bjv.agent.Json;

/**
 * ONE BUMP: ITS CHAIN, EVERYTHING IT DID, AND WHAT IT MOVED.
 *
 * <p>The chain is {@link Bump#stages} with each step's speaking count filled in from the trace, so
 * the strip is walked off the same tree the harness runs. It used to be a hand-typed copy in the
 * dashboard, and then a declaration beside the program; both went stale, which is how the page came
 * to advertise a stage that had been deleted.
 *
 * <p>THE ROW AT THE TOP IS THE LIST'S OWN, rendered by {@link Corpus#summary}. A reader arrives here
 * by clicking a line in a table, and a detail page that computed the same six numbers a second way
 * would eventually disagree with the line that led to it.
 */
final class Detail {

    private final Results results;
    private final Corpus corpus;

    Detail(Path results, Corpus corpus) {
        this.results = new Results(results);
        this.corpus = corpus;
    }

    String bump(String slug) {
        List<String> raw = Results.lines(results.trace(slug));
        List<Map<String, String>> events = raw.stream().map(Json::row).toList();

        Map<String, Integer> spoke = new LinkedHashMap<>();
        for (Map<String, String> e : events) {
            if ("asked".equals(e.get("kind"))) {
                spoke.merge(e.getOrDefault("agent", ""), 1, Integer::sum);
            }
        }
        Map<String, String> settled = results.settlements().getOrDefault(slug, Map.of());

        return Json.object(
                Json.field("summary", corpus.summary(settled.isEmpty()
                        ? Map.of("bump", Results.unslug(events), "state", "bumping") : settled)),
                Json.field("chain", Json.array(Bump.stages(), s -> Json.object(
                        Json.field("title", Json.string(s.title())),
                        Json.field("within", Json.string(s.within())),
                        Json.field("steps", Json.array(s.steps(), step -> Json.object(
                                Json.field("name", Json.string(step.name())),
                                Json.field("role", Json.string(step.role())),
                                Json.field("agent", String.valueOf(step.agent())),
                                Json.field("spoke",
                                        String.valueOf(spoke.getOrDefault(step.name(), 0))))))))),
                Json.field("events", Json.array(events, Detail::event)),
                Json.field("packages", Inventory.packages(events)),
                Json.field("cves", Inventory.cves(events)));
    }

    /**
     * ONE EVENT ON THE WIRE, in the one place that knows what a kind's shape is.
     *
     * <p>Static and shared with {@link Feed}: the page loads the record here and then subscribes to
     * the stream, so an event that arrived a second later has to render identically to one that was
     * already on the trace. Two renderers would be two answers to that.
     */
    static String event(Map<String, String> e) {
        String kind = e.getOrDefault("kind", "");
        // The BODY is whichever field this kind of event carries it in. A page that had to know
        // which is which per kind would be a second copy of the record's shape.
        String text = Results.first(e, "note", "what", "reply", "result", "summary", "content",
                "thinking", "itemisation");
        // AN EXCHANGE CARRIES NO SINGLE BODY. It is the wire: what went, what came back, what it
        // cost. Composing the line here rather than in the page keeps the rule that a kind's shape
        // is known in one place, and gives a reader the numbers the curated events never had.
        if ("exchange".equals(kind)) {
            // TWO EVENTS, IN THE ORDER THEY HAPPENED. The request is written when it is sent and
            // the response when it returns, so a seventeen-second call no longer files its own
            // prompt after the reasoning that prompt produced.
            String error = e.getOrDefault("error", "");
            boolean outbound = "to".equals(e.getOrDefault("direction", "back"));
            text = outbound
                    ? "→ sent, " + e.getOrDefault("messages", "0") + " message(s)\n\n"
                            + e.getOrDefault("sent", "")
                    : (error.isBlank() ? "← " : "← FAILED " + error + " · ")
                            + e.getOrDefault("in", "0") + " in / " + e.getOrDefault("out", "0")
                            + " out tokens · " + e.getOrDefault("ms", "0") + "ms"
                            + (e.getOrDefault("finish", "").isBlank() ? ""
                                    : " · " + e.get("finish"))
                            + (e.getOrDefault("tools", "").isBlank() ? ""
                                    : " · asked for " + e.get("tools"))
                            + (e.getOrDefault("got", "").isBlank() ? ""
                                    : "\n\n" + e.get("got"));
        }
        return Json.object(
                Json.field("at", String.valueOf(Results.num(e.get("at")))),
                Json.field("kind", Json.string(kind)),
                Json.field("agent", Json.optional(e.getOrDefault("agent", ""))),
                Json.field("stage", Json.optional(e.getOrDefault("stage", ""))),
                Json.field("tool", Json.optional(e.getOrDefault("tool", ""))),
                Json.field("inTokens", String.valueOf((int) Results.num(e.get("in")))),
                Json.field("outTokens", String.valueOf((int) Results.num(e.get("out")))),
                Json.field("ms", String.valueOf((int) Results.num(e.get("ms")))),
                Json.field("text", Json.string(text)));
    }
}
