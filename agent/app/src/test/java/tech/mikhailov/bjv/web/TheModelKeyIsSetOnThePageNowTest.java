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
 * hands out, and that a save which would mangle the key is refused rather than stored.
 *
 * <p>WHAT A BLANK SAVE DOES MOVED, and the test that pinned it moved with it. It used to be
 * refused, on the stated grounds that a save which silently did nothing is its own lie, and that
 * was right while there was no other way to drop a saved key. There is one now, so blank means
 * leave it alone and the checkbox means drop it. That pair is asserted next door in
 * {@code WhatTheNextLaneReadsIsWhatThisPageSavedTest}, together with the reason the pair had to
 * arrive at once.
 */
class TheModelKeyIsSetOnThePageNowTest {

    /**
     * FABRICATED, AND IT HAS TO BE. A fixture that borrows the front of a real key puts credential
     * material in a public repository, where a test file is the last place anybody looks for it.
     * This repository has done it once already and had to amend the commit away.
     */
    private static final String KEY = "sk-testonly-1f2e3d4c5b6a798877665544332211aabbccddeeff0011";
    private static final String OTHER = "sk-testonly-00112233445566778899aabbccddeeff1f2e3d4c5b6a";

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

    private static Path store(Path runRoot) {
        return runRoot.resolve(ModelSettings.FILE);
    }

    @Test
    void theKeyItselfTravelsToThePage(@TempDir Path runRoot) throws Exception {
        Path results = results(runRoot);
        Files.writeString(store(runRoot), "key=" + KEY + "\n");

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
        assertEquals("key=" + KEY, Files.readString(store(runRoot)).trim());
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
        assertFalse(Files.exists(runRoot.resolve(ModelSettings.FILE + ".staged")));
    }

    @Test
    void theStoredKeyIsReadableByNobodyButTheUserTheSweepRunsAs(@TempDir Path runRoot)
            throws Exception {
        Path results = results(runRoot);

        ask(results, "POST", "{\"key\":\"" + KEY + "\"}");

        Set<PosixFilePermission> mode = Files.getPosixFilePermissions(store(runRoot));
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), mode,
                "the run root is a bind mount, so the mode is the whole of the protection");
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
        assertFalse(Files.exists(store(runRoot)));
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
        assertEquals("key=" + OTHER, Files.readString(store(runRoot)).trim());
    }

    @Test
    void aKeySavedHereIsNotYetTheKeyTheSupervisorInThisContainerIsUsing(@TempDir Path runRoot)
            throws Exception {
        Path results = results(runRoot);

        Answer got = ask(results, "POST", "{\"key\":\"" + KEY + "\"}");

        // Lanes read this store per lane now, so the page's claim about THEM is true. The
        // supervisor is a separate case and stays one: it built its models when the container
        // started and a JVM cannot change its own environment. differsFromLaunch is the evidence
        // for that sentence, and the card only prints it when this is true.
        assertEquals("true", got.fields().get("differsFromLaunch"));
        assertNotEquals("0", got.fields().get("storedAt"), "and when it was written");
    }

    @Test
    void aKeyTheOlderPageSavedIsStillHonouredRatherThanQuietlyDropped(@TempDir Path runRoot)
            throws Exception {
        Path results = results(runRoot);
        Files.writeString(runRoot.resolve(ModelSettings.LEGACY_KEY_FILE), KEY + "\n");

        Answer got = ask(results, "GET", null);

        // The file the previous release wrote is still the owner's most recent deliberate statement
        // of what the key should be. Upgrading to a page that reads a different file must not be
        // this page unsetting a credential without being asked.
        assertEquals(KEY, got.fields().get("key"));
        assertEquals("this page", got.fields().get("keySource"));
    }

    @Test
    void withNoFileTheAnswerIsTheEnvironmentsAndSaysSo(@TempDir Path runRoot) throws Exception {
        Path results = results(runRoot);

        Answer got = ask(results, "GET", null);

        // Whether this JVM has OC_KEY set is not this test's business; what it must never do is
        // claim a file's value is in force when there is no file.
        assertNotEquals("this page", got.fields().get("keySource"));
        assertEquals("0", got.fields().get("storedAt"));
        assertEquals("model", got.fields().get("storedIn"));
        assertEquals("false", got.fields().get("edited"));
    }

    @Test
    void nothingBetweenHereAndTheBrowserMayKeepACopy(@TempDir Path runRoot) throws Exception {
        Path results = results(runRoot);
        Files.writeString(store(runRoot), "key=" + KEY + "\n");

        Answer got = ask(results, "GET", null);

        // The response now carries a credential, so the header that was a tidiness measure while it
        // carried a boolean is load-bearing: a cache anywhere in front of this would hold the key.
        assertEquals("no-store", got.cacheControl());
        assertEquals(200, got.code());
    }
}
