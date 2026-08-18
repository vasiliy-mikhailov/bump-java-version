package tech.mikhailov.bjv.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A LIVE READER RESUMES WHERE IT STOPPED, WHICH IS NEITHER END OF THE FILE.
 *
 * <p>The page loads the record from {@code /api/bump} and then subscribes. Streaming from byte zero
 * puts every event already on screen there a second time. Streaming from the end loses anything the
 * lane wrote between the fetch and the subscription, and on a busy lane that is the interesting
 * second. The trace is append-only, so the line the reader has counted to is a stable place to
 * resume, and unlike a byte offset the page can compute it without knowing the file.
 */
class TheLiveStreamResumesWhereTheReaderStoppedTest {

    private static long after(Path trace, String have) throws Exception {
        Method m = Feed.class.getDeclaredMethod("after", Path.class, String.class);
        m.setAccessible(true);
        return (long) m.invoke(null, trace, have);
    }

    private static Path trace(Path dir, String... lines) throws IOException {
        Path t = dir.resolve("trace.jsonl");
        for (String l : lines) {
            Files.writeString(t, l + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        return t;
    }

    @Test
    void aReaderWithNothingStartsAtTheBeginning(@TempDir Path dir) throws Exception {
        Path t = trace(dir, "{\"at\":\"1\"}", "{\"at\":\"2\"}");

        assertEquals(0, after(t, "0"));
        assertEquals(0, after(t, ""));
        assertEquals(0, after(t, null));
    }

    @Test
    void aReaderWithTwoLinesResumesAfterExactlyThoseTwo(@TempDir Path dir) throws Exception {
        Path t = trace(dir, "{\"at\":\"1\"}", "{\"at\":\"2\"}", "{\"at\":\"3\"}");

        long from = after(t, "2");

        assertEquals("{\"at\":\"3\"}\n", from(t, from), "the third line and nothing before it");
    }

    @Test
    void itCountsBytesRatherThanCharacters(@TempDir Path dir) throws Exception {
        // A TRACE CARRIES PROMPTS AND BUILD OUTPUT, so non-ASCII is ordinary rather than exotic:
        // the corpus has Chinese comments and box-drawing in build logs. An offset counted in
        // characters lands mid-character and the next line parses as rubbish.
        Path t = trace(dir, "{\"note\":\"пример — построение\"}", "{\"at\":\"2\"}");

        long from = after(t, "1");

        // AND THE ASSERTION HAS TO COUNT BYTES TOO. The first version of this test sliced the file
        // with String.substring, which counts characters, and blew up on its own fixture: the
        // offset was right and the check was wrong. The production path reads through a
        // FileChannel, which is bytes.
        assertEquals("{\"at\":\"2\"}\n", from(t, from));
    }

    @Test
    void rubbishInTheParameterMeansStartOver(@TempDir Path dir) throws Exception {
        // Hand-typed url, or a client that lost count. Replaying is a visible duplicate; seeking to
        // a guessed offset is a silently corrupt stream.
        Path t = trace(dir, "{\"at\":\"1\"}");

        assertEquals(0, after(t, "not-a-number"));
        assertEquals(0, after(t, "-4"));
    }

    @Test
    void aReaderAheadOfTheFileIsNotSentPastItsEnd(@TempDir Path dir) throws Exception {
        // The run root is cleared under readers between sweeps, so a page can hold more lines than
        // the file now has.
        Path t = trace(dir, "{\"at\":\"1\"}");

        long from = after(t, "99");

        assertTrue(from <= Files.size(t), "never past the end: " + from + " of " + Files.size(t));
    }

    @Test
    void aMissingTraceIsNotAnError(@TempDir Path dir) throws Exception {
        // A queued bump has no trace at all, and a reader may open its page before a lane claims it.
        assertEquals(0, after(dir.resolve("nothing.jsonl"), "3"));
    }

    /** The file from a BYTE offset, which is what the stream seeks with. */
    private static String from(Path trace, long offset) throws IOException {
        byte[] all = Files.readAllBytes(trace);
        return new String(all, (int) offset, all.length - (int) offset, StandardCharsets.UTF_8);
    }
}
