package tech.mikhailov.bjv.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.sun.net.httpserver.HttpExchange;

import tech.mikhailov.ratchet.record.Json;

/**
 * THE RECORD AS JSON, which is all the frontend needs and all it gets.
 *
 * <p>The dashboard used to build HTML in Java: 1580 lines in which a colour, a layout decision and a
 * fact about a bump were the same expression, so none of the three could be changed without reading
 * the other two. What crosses this boundary now is only the facts. A verdict travels as
 * {@code FAIL_test_conservation}, never as a colour — the moment a server sends a tone it has made a
 * design decision that cannot be tested for, and the page stops being the only place that knows what
 * a state looks like.
 *
 * <p>The shapes are declared once, in the frontend's {@code @bjv/types}, and are ADDITIVE ONLY: a
 * reader's open tab will be older than the server as often as not.
 *
 * <p>WHAT IS LEFT HERE IS THE ROUTING. Six jobs shared this file because they shared a request
 * router: the corpus list, one bump's record, the security tables, the settings surface, the event
 * stream, and the writes that reach back into the sweep, which are handing a settled bump to a lane
 * again and setting one aside from it. Each is now the class the switch below names, and the switch
 * is the whole of what they have in common. Reading a path off the table and reading how a number
 * is counted are different acts and no longer the same file.
 */
final class Api {

    private final Path results;
    private final Corpus corpus;
    private final Detail detail;
    private final Exposure exposure;
    private final Settings settings;
    private final Rerun rerun;
    private final Postpone postpone;
    private final Feed feed;

    Api(Path results) {
        this.results = results;
        this.corpus = new Corpus(results);
        this.detail = new Detail(results, corpus);
        this.exposure = new Exposure(results);
        this.settings = new Settings(results);
        this.rerun = new Rerun(results);
        this.postpone = new Postpone(results);
        this.feed = new Feed(results, corpus);
    }

    /** Route within the zone. Returns false when nothing here answers, so static serving can try. */
    boolean handle(HttpExchange x, String path) throws IOException {
        switch (path) {
            case "/.well-known/microfrontend.json" -> Zone.json(x, Zone.manifest());
            case "/api/health" -> health(x);
            case "/api/badges" -> Zone.json(x, corpus.badges());
            case "/api/bumps" -> Zone.json(x, corpus.bumps(Zone.param(x, "since")));
            case "/api/summary" -> Zone.json(x, corpus.overview());
            case "/api/live" -> feed.open(x, Zone.param(x, "slug"), Zone.param(x, "have"));
            case "/api/rerun" -> Zone.json(x, rerun.ask(Zone.param(x, "slug")));
            // SET ASIDE AND TAKE BACK. Two routes and one class: the direction belongs in the path,
            // where a stale tab cannot get it wrong, while the code that writes the marker and the
            // code that removes it stay the same code, because the failure worth preventing is the
            // two of them disagreeing about which file the launcher reads. Either route takes an
            // explicit `state` for a caller that would rather carry the direction in the query.
            case "/api/postpone" -> Zone.json(x, postpone.ask(Zone.param(x, "slug"),
                    Zone.param(x, "state"), Zone.param(x, "why"), true));
            case "/api/resume" -> Zone.json(x, postpone.ask(Zone.param(x, "slug"),
                    Zone.param(x, "state"), Zone.param(x, "why"), false));
            // WHAT IS SET ASIDE RIGHT NOW, so a toggle can render in the state it is already in
            // rather than in the state the last click left in one reader's tab.
            case "/api/postponed" -> Zone.json(x, postpone.listing());
            case "/api/security" -> Zone.json(x, exposure.report());
            case "/api/bump" -> Zone.json(x, detail.bump(Zone.param(x, "slug")));
            case "/api/settings" -> Zone.json(x, settings.agents(Zone.param(x, "hop")));
            case "/api/settings/prompt" -> settings.prompt(x);
            case "/api/settings/run" -> settings.run(x);
            // GET AND POST ON ONE PATH, the way the run settings above already are. The key is
            // read and written here now, and a separate path for the write would let a stale tab
            // post to one while it is reading the other.
            case "/api/settings/model" -> settings.model(x);
            // THE CORPUS, ON THE SETTINGS PAGE. What the sweep is working through is a fact about
            // the queue rather than a setting anybody edits, so it is answered by the class that
            // owns the queue and not by the one that owns the boxes.
            case "/api/settings/subject" -> Zone.json(x, corpus.subject());
            case "/api/settings/registry" -> settings.registry(x);
            case "/api/settings/supervisor" -> Zone.json(x, settings.supervisor());
            case "/api/settings/bom" -> settings.bom(x);
            default -> {
                return false;
            }
        }
        return true;
    }

    /**
     * IT REPORTS ON THE RECORD, NOT ON THE MODEL ENDPOINT.
     *
     * <p>The dashboard is worth serving when inference is unreachable: the whole record is still
     * readable and that is most of what anybody comes for. A health check that went red because a
     * model was down would have a shell hiding a tool that was working.
     */
    private void health(HttpExchange x) throws IOException {
        boolean ok = Files.isDirectory(results);
        Zone.send(x, ok ? 200 : 503, "application/json; charset=utf-8",
                (ok
                        ? Json.object(Json.field("ok", "true"),
                                Json.field("version", Json.string(Zone.version())))
                        : Json.object(Json.field("ok", "false"),
                                Json.field("why", Json.string("the results directory is not readable"))))
                        .getBytes(StandardCharsets.UTF_8));
    }
}
