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
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import tech.mikhailov.ratchet.record.Json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE PAGE SHOWS THE KEY AND CAN SET IT, WHICH REVERSES WHAT IT USED TO DO.
 *
 * <p>The reversal is recorded in {@code Settings.model}, alongside what it costs. What is asserted
 * here is the half a comment cannot hold: that the key really does travel to the page, that the
 * file it lands in is readable by nobody else, that it lands outside the directory this server
 * hands out, and that a save which would empty or mangle the key is refused rather than stored.
 *
 * <p>The last one is the reason the endpoint is worth a test at all. A settings page that can empty
 * the key is a settings page that can stop the next sweep and report success doing it.
 */
class TheModelKeyIsSetOnThePageNowTest {

    private static final String KEY = "sk-1f2e3d4c5b6a798877665544332211aabbccddeeff00112233445566778899";
    private static final String OTHER = "sk-00112233445566778899aabbccddeeff1f2e3d4c5b6a79887766554433221100";

    /**
     * The real endpoint over a real socket, because half of what is being asserted is in the
     * headers and in the method dispatch rather than in a value some helper could return.
     */
    private record Answer(int code, String body, String cacheControl) {

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
            return new Answer(reply.statusCode(), reply.body(),
                    reply.headers().firstValue("Cache-Control").orElse(""));
        } finally {
            http.stop(0);
        }
    }

    private static Path results(Path runRoot) throws IOException {
        return Files.createDirectories(runRoot.resolve("results"));
    }

    @Test
    void theKeyItselfTravelsToThePage(@TempDir Path runRoot) throws Exception {
        Path results = results(runRoot);
        Files.writeString(runRoot.resolve("model_key"), KEY + "\n");

        Answer got = ask(results, "GET", null);

        assertEquals(KEY, got.fields().get("key"), "the whole point of the reversal");
        assertEquals("true", got.fields().get("keySet"));
        assertEquals("this page", got.fields().get("keySource"));
    }

    @Test
    void aSaveLandsBesideMaxLanesAndNowhereTheServerHandsOut(@TempDir Path runRoot)
            throws Exception {
        Path results = results(runRoot);

        Answer got = ask(results, "POST", "{\"key\":\"" + KEY + "\"}");

        assertEquals("true", got.fields().get("saved"), got.body());
        assertEquals(KEY, Files.readString(runRoot.resolve("model_key")).trim());
        // results/ is the directory this server serves. A credential in it is a credential
        // published, and no endpoint has to be careless for that to happen.
        try (Stream<Path> under = Files.walk(results)) {
            List<Path> files = under.filter(Files::isRegularFile).toList();
            for (Path f : files) {
                assertFalse(Files.readString(f).contains(KEY), f + " is inside what is served");
            }
        }
        // And the staging file it was renamed from is gone, rather than left world-readable beside
        // the one whose permissions were the point.
        assertFalse(Files.exists(runRoot.resolve("model_key.staged")));
    }

    @Test
    void theStoredKeyIsReadableByNobodyButTheUserTheSweepRunsAs(@TempDir Path runRoot)
            throws Exception {
        Path results = results(runRoot);

        ask(results, "POST", "{\"key\":\"" + KEY + "\"}");

        Set<PosixFilePermission> mode =
                Files.getPosixFilePermissions(runRoot.resolve("model_key"));
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), mode,
                "the run root is a bind mount, so the mode is the whole of the protection");
    }

    @Test
    void anEmptyBoxIsRefusedRatherThanEmptyingTheKey(@TempDir Path runRoot) throws Exception {
        Path results = results(runRoot);
        Files.writeString(runRoot.resolve("model_key"), KEY + "\n");

        Answer got = ask(results, "POST", "{\"key\":\"\"}");

        assertEquals("false", got.fields().get("saved"));
        assertTrue(got.fields().get("why").contains("empty box"), got.body());
        // THE ONE THAT MATTERS: the key that was there is still there. A refusal that had already
        // truncated the file would be a refusal in name only.
        assertEquals(KEY, Files.readString(runRoot.resolve("model_key")).trim());
        assertEquals(KEY, got.fields().get("key"), "and the page is told what is still in force");
    }

    @Test
    void aPasteAccidentIsRefusedWithoutBeingRepeatedBack(@TempDir Path runRoot) throws Exception {
        Path results = results(runRoot);
        String pasted = "export OC_KEY=" + KEY;

        Answer got = ask(results, "POST", "{\"key\":\"" + pasted + "\"}");

        assertEquals("false", got.fields().get("saved"));
        assertTrue(got.fields().get("why").contains("printable characters"), got.body());
        // A REFUSAL IS THE EASIEST THING ON A PAGE TO SCREENSHOT. Every other refusal here quotes
        // the text it would not take; this one may not, because the text is a credential.
        assertFalse(got.body().contains(KEY), "the reply repeated the key back");
        assertFalse(Files.exists(runRoot.resolve("model_key")));
    }

    @Test
    void halfAKeyIsRefusedBecauseItWouldStopTheNextSweepJustAsQuietly(@TempDir Path runRoot)
            throws Exception {
        Path results = results(runRoot);

        Answer got = ask(results, "POST", "{\"key\":\"sk-1f2e3d4c\"}");

        assertEquals("false", got.fields().get("saved"));
        assertTrue(got.fields().get("why").contains("shorter"), got.body());
    }

    @Test
    void aSecondSaveReplacesTheFirstBecauseARotatedKeyIsMeantToTakeEffect(@TempDir Path runRoot)
            throws Exception {
        Path results = results(runRoot);

        ask(results, "POST", "{\"key\":\"" + KEY + "\"}");
        Answer got = ask(results, "POST", "{\"key\":\"" + OTHER + "\"}");

        assertEquals(OTHER, got.fields().get("key"));
        assertEquals(OTHER, Files.readString(runRoot.resolve("model_key")).trim());
    }

    @Test
    void aKeySavedHereIsNotTheKeyAnythingIsRunningWith(@TempDir Path runRoot) throws Exception {
        Path results = results(runRoot);

        Answer got = ask(results, "POST", "{\"key\":\"" + KEY + "\"}");

        // run.sh reads its key once at startup and hands it to each lane on the command line, and
        // this process was handed its own at deploy. Neither notices a file written now. The page
        // says so in a sentence; the flag is what lets it say so only when it is true.
        assertEquals("true", got.fields().get("differsFromLaunch"));
        assertNotEquals("0", got.fields().get("storedAt"), "and when it was written");
    }

    @Test
    void withNoFileTheAnswerIsTheEnvironmentsAndSaysSo(@TempDir Path runRoot) throws Exception {
        Path results = results(runRoot);

        Answer got = ask(results, "GET", null);

        // Whether this JVM has OC_KEY set is not this test's business; what it must never do is
        // claim a file's value is in force when there is no file.
        assertNotEquals("this page", got.fields().get("keySource"));
        assertEquals("0", got.fields().get("storedAt"));
        assertEquals("model_key", got.fields().get("storedIn"));
    }

    @Test
    void nothingBetweenHereAndTheBrowserMayKeepACopy(@TempDir Path runRoot) throws Exception {
        Path results = results(runRoot);
        Files.writeString(runRoot.resolve("model_key"), KEY + "\n");

        Answer got = ask(results, "GET", null);

        // The response now carries a credential, so the header that was a tidiness measure while it
        // carried a boolean is load-bearing: a cache anywhere in front of this would hold the key.
        assertEquals("no-store", got.cacheControl());
        assertEquals(200, got.code());
    }
}
