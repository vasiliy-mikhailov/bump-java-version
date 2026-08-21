package tech.mikhailov.bjv.web;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import tech.mikhailov.ratchet.record.Json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE SENTENCES ON THE CARD, ASSERTED AS BEHAVIOUR RATHER THAN LEFT AS PROSE.
 *
 * <p>THIS CLASS EXISTS BECAUSE THE PROSE WAS FALSE ONCE. The card said "what is saved here is what
 * the next launch reads" while the file it wrote was opened by nothing at all, and the pill above
 * it said the agents were using a key no agent had ever read. Those are not wording bugs, they are
 * claims, and a claim is testable.
 *
 * <p>THE HALF THAT LIVES IN A SHELL IS TESTED IN A SHELL. {@code run.sh} is what gives a lane its
 * key, its endpoint and its model name, and {@code agent/test/lanes_test.sh settings} asserts the
 * same precedence from the only side that can see it, the {@code docker run} argument list. What is
 * asserted here is everything on this side of the file: the two different meanings of a blank box,
 * the checkbox that is the only way to drop a key, and the invariant that a green pill can never be
 * shown over a credential nothing would be given.
 */
class WhatTheNextLaneReadsIsWhatThisPageSavedTest {

    /**
     * FABRICATED, AND IT HAS TO BE. Two of this repository's own keys are public in a thousand
     * revisions because they were committed inside something that looked like scaffolding, and a
     * test fixture is exactly that.
     */
    private static final String KEY = "sk-testonly-0f1e2d3c4b5a69788796a5b4c3d2e1f00112233445566";
    private static final String OTHER = "sk-testonly-99887766554433221100ffeeddccbbaa0f1e2d3c4b5a";

    private record Answer(int code, String body) {

        Map<String, String> fields() {
            return Json.row(body);
        }
    }

    private static Answer ask(Path results, String method, String body) throws Exception {
        Settings settings = new Settings(results);
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/api/settings/model", x -> settings.model(x));
        http.start();
        try {
            HttpRequest.BodyPublisher sending = body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);
            HttpResponse<String> reply = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://127.0.0.1:" + http.getAddress().getPort()
                                    + "/api/settings/model"))
                            .method(method, sending)
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            return new Answer(reply.statusCode(), reply.body());
        } finally {
            http.stop(0);
        }
    }

    private static Path results(Path runRoot) throws IOException {
        return Files.createDirectories(runRoot.resolve("results"));
    }

    private static String stored(Path runRoot) throws IOException {
        Path file = runRoot.resolve(ModelSettings.FILE);
        return Files.isRegularFile(file) ? Files.readString(file) : "";
    }

    @Test
    void aBlankKeyBoxLeavesTheSavedKeyExactlyWhereItWas(@TempDir Path runRoot) throws Exception {
        Path results = results(runRoot);
        ask(results, "POST", "{\"key\":\"" + KEY + "\"}");

        Answer got = ask(results, "POST", "{\"key\":\"\",\"model\":\"qwen\"}");

        // THE WHOLE REASON BLANK STOPPED BEING A REFUSAL. A browser that clears this box, or a
        // reader who saves the model name without retyping a credential, must not be able to unset
        // the key and leave every lane talking to an endpoint that refuses it.
        assertEquals("true", got.fields().get("saved"), got.body());
        assertEquals(KEY, got.fields().get("key"));
        assertTrue(stored(runRoot).contains("key=" + KEY), stored(runRoot));
        assertTrue(stored(runRoot).contains("model=qwen"), "and the field that was sent still saved");
    }

    @Test
    void theCheckboxIsTheOnlyWayToDropAKeySavedHere(@TempDir Path runRoot) throws Exception {
        Path results = results(runRoot);
        ask(results, "POST", "{\"key\":\"" + KEY + "\"}");

        Answer got = ask(results, "POST", "{\"key\":\"\",\"forget\":\"1\"}");

        assertEquals("true", got.fields().get("saved"), got.body());
        assertFalse(stored(runRoot).contains(KEY), "the key survived the checkbox");
        assertFalse(got.body().contains(KEY), "and it came back in the reply");
    }

    @Test
    void forgettingWinsOverAKeySentInTheSameRequest(@TempDir Path runRoot) throws Exception {
        Path results = results(runRoot);
        ask(results, "POST", "{\"key\":\"" + KEY + "\"}");

        Answer got = ask(results, "POST", "{\"key\":\"" + OTHER + "\",\"forget\":\"1\"}");

        // A checkbox left ticked from the previous save must not be beaten by the box below it.
        // The removal is applied after the value for exactly this, which is the sibling's rule and
        // is the only order in which the control means what its label says.
        assertFalse(stored(runRoot).contains(OTHER), "a key sent with forget was kept anyway");
        assertFalse(got.body().contains(OTHER));
    }

    @Test
    void forgettingAlsoDropsTheKeyTheOlderPageSaved(@TempDir Path runRoot) throws Exception {
        Path results = results(runRoot);
        Files.writeString(runRoot.resolve(ModelSettings.LEGACY_KEY_FILE), KEY + "\n");

        Answer got = ask(results, "POST", "{\"forget\":\"1\"}");

        // A CHECKBOX THAT LEFT ONE OF THE TWO FILES BEHIND WOULD BE THE CLEAREST POSSIBLE LIE ON A
        // card whose whole subject is which value is really in force. run.sh reads the older file
        // under the store and over the environment, so a key left there is a key still in use.
        assertFalse(Files.exists(runRoot.resolve(ModelSettings.LEGACY_KEY_FILE)));
        assertFalse(got.body().contains(KEY));
    }

    @Test
    void whatIsSavedOnThePageWinsAndTheEnvironmentIsUnderneath(@TempDir Path runRoot)
            throws Exception {
        Path results = results(runRoot);

        Answer saved = ask(results, "POST",
                "{\"model\":\"page-model\",\"endpoint\":\"https://page.invalid/v1\"}");

        assertEquals("page-model", saved.fields().get("model"));
        assertEquals("https://page.invalid/v1", saved.fields().get("endpoint"));
        assertEquals("true", saved.fields().get("edited"), "and the card says the values are its own");

        // AND BLANK MEANS THE OPPOSITE THING HERE FROM WHAT IT MEANS FOR THE KEY. Emptying one of
        // these boxes is how an override is undone without a shell, so it removes the line and the
        // environment's value comes back. Copying the key's sentence onto these fields would teach
        // a rule that is false of them.
        Answer cleared = ask(results, "POST", "{\"model\":\"\",\"endpoint\":\"\"}");

        assertEquals("true", cleared.fields().get("saved"), cleared.body());
        assertFalse(stored(runRoot).contains("page-model"), stored(runRoot));
        assertFalse(stored(runRoot).contains("page.invalid"), stored(runRoot));
        assertEquals(System.getenv().getOrDefault("OC_MODEL", ""), cleared.fields().get("model"));
        assertEquals(System.getenv().getOrDefault("OC_BASE", ""), cleared.fields().get("endpoint"));
    }

    @Test
    void aFieldTheFormDidNotMentionIsLeftAloneRatherThanCleared(@TempDir Path runRoot)
            throws Exception {
        Path results = results(runRoot);
        ask(results, "POST",
                "{\"model\":\"page-model\",\"endpoint\":\"https://page.invalid/v1\"}");

        Answer got = ask(results, "POST", "{\"model\":\"other-model\"}");

        // Absent and present-but-empty are opposite instructions, and the server has to be able to
        // tell them apart before either sentence on the card is true.
        assertEquals("other-model", got.fields().get("model"));
        assertEquals("https://page.invalid/v1", got.fields().get("endpoint"));
    }

    @Test
    void anEndpointThatIsNotAnEndpointIsRefusedWhereItWasTyped(@TempDir Path runRoot)
            throws Exception {
        Path results = results(runRoot);

        Answer got = ask(results, "POST", "{\"endpoint\":\"inference.mikhailov.tech/v1\"}");

        // The scheme is not decoration: it decides whether the client negotiates HTTP/2 or stays on
        // 1.1. A value that cannot be used is better refused here than three hours later in a
        // lane's log.
        assertEquals("false", got.fields().get("saved"));
        assertTrue(got.fields().get("why").contains("http://"), got.body());
        assertFalse(Files.exists(runRoot.resolve(ModelSettings.FILE)));
    }

    @Test
    void anEmptyCredentialIsNeverPresentedAsSet(@TempDir Path runRoot) throws Exception {
        Path results = results(runRoot);

        // Whether this JVM happens to have OC_KEY set is not this test's business, and hard-coding
        // an answer either way would make it pass on one machine. The invariant is what matters and
        // it holds on both: the pill is exactly the emptiness of the key the page is showing, which
        // is the same chain run.sh computes before it decides whether to open a lane at all.
        for (String body : List.of("{\"forget\":\"1\"}", "{\"key\":\"" + KEY + "\"}",
                "{\"key\":\"\",\"forget\":\"1\"}")) {
            Answer got = ask(results, "POST", body);
            boolean blank = got.fields().getOrDefault("key", "").isBlank();
            assertEquals(String.valueOf(!blank), got.fields().get("keySet"),
                    "a green pill over an empty credential, after " + body);
            if (blank) {
                assertEquals("", got.fields().get("keySource"),
                        "with no key anywhere there is no source to report, and guessing one arms "
                                + "a control that offers to drop a key saved here");
            }
        }
    }

    @Test
    void nothingSavedHereReachesTheDirectoryThisServerHandsOut(@TempDir Path runRoot)
            throws Exception {
        Path results = results(runRoot);

        ask(results, "POST", "{\"key\":\"" + KEY + "\",\"endpoint\":\"https://page.invalid/v1\"}");

        // The results tree is what the page serves and what a settlement row, a trace and the event
        // stream are all written into. A credential in it is a credential published, and the run
        // root exists as somewhere outside it precisely so this can be asserted.
        try (Stream<Path> under = Files.walk(results)) {
            for (Path f : under.filter(Files::isRegularFile).toList()) {
                assertFalse(Files.readString(f).contains(KEY), f + " is inside what is served");
            }
        }
    }

    @Test
    void aLaneThatHasNotStartedSinceTheSaveIsNotReportedAsHavingRead(@TempDir Path runRoot)
            throws Exception {
        Path results = results(runRoot);

        // NO RECORD IS NOT A YES, and this is the state a launcher started before any of this
        // leaves behind: nothing has said what any lane read, so the page may not say one read
        // this.
        assertEquals("false", ask(results, "GET", null).fields().get("laneHasThis"));

        Files.writeString(runRoot.resolve(ModelSettings.SEEN_FILE), "0\n");

        Answer got = ask(results, "POST", "{\"key\":\"" + KEY + "\"}");

        // THE ONE FACT ON THE CARD ABOUT THE PAST. A launcher already inside its loop is executing
        // the script it started with, so the honest thing for the page to say is that no lane has
        // read this yet rather than that every agent is using it.
        assertEquals("false", got.fields().get("laneHasThis"), got.body());

        Path store = runRoot.resolve(ModelSettings.FILE);
        long seconds = Files.getLastModifiedTime(store).toMillis() / 1000L;
        Files.writeString(runRoot.resolve(ModelSettings.SEEN_FILE), seconds + "\n");

        assertEquals("true", ask(results, "GET", null).fields().get("laneHasThis"),
                "a lane that recorded this store's own mtime did read it");
    }
}
