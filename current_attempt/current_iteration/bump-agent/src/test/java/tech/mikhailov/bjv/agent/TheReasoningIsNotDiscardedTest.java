package tech.mikhailov.bjv.agent;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The server calls the field `reasoning`; the client looks for `reasoning_content`. Eighty-four
 * calls produced eighty-four blanks before the body was read directly, so this pins the field
 * names and the mid-thought case that motivates recording them at all.
 */
class TheReasoningIsNotDiscardedTest {

    private record Thought(String finish, String thinking, String content) {
    }

    private static final class Recorder implements Trace {
        final List<Thought> thoughts = new ArrayList<>();

        public void thought(String f, String t, String c) {
            thoughts.add(new Thought(f, t, c));
        }

        public void asked(String a, String p, String r) {
        }

        public void applied(String s, String w) {
        }

        public void tool(String a, String t, String args, String result) {
        }

        public void built(String phase, Runner.Result r) {
        }

        public void settled(String b, String s, String w, boolean x, boolean y) {
        }

        public void failed(String b, Throwable c) {
        }

        public void progress(String b, String n) {
        }

        public void priced(String b, String m, String i) {
        }
    }

    @Test
    void theServersFieldNameIsRead() {
        Recorder r = new Recorder();
        Reasoning.record(r, """
                {"choices":[{"finish_reason":"stop","message":{"role":"assistant",
                 "content":"sound","reasoning":"The parent pom pins 11, and no module overrides."}}]}
                """);
        assertEquals(1, r.thoughts.size());
        assertEquals("stop", r.thoughts.get(0).finish());
        assertTrue(r.thoughts.get(0).thinking().contains("no module overrides"));
        assertEquals("sound", r.thoughts.get(0).content());
    }

    @Test
    void theClientsFieldNameAlsoWorks() {
        Recorder r = new Recorder();
        Reasoning.record(r, "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":"
                + "{\"content\":\"ok\",\"reasoning_content\":\"weighed it\"}}]}");
        assertEquals("weighed it", r.thoughts.get(0).thinking());
    }

    @Test
    void anAnswerCutOffMidThoughtIsRecordedAsSuch() {
        // The case that matters: all budget spent thinking, nothing left to answer with. Without
        // this the empty content reaches a word list and is read as its default, which approves.
        Recorder r = new Recorder();
        Reasoning.record(r, "{\"choices\":[{\"finish_reason\":\"length\",\"message\":"
                + "{\"content\":\"\",\"reasoning\":\"Let me consider each trigger in turn...\"}}]}");
        assertEquals("length", r.thoughts.get(0).finish());
        assertEquals("", r.thoughts.get(0).content());
        assertTrue(r.thoughts.get(0).thinking().startsWith("Let me consider"));
    }

    @Test
    void aBodyWithNoReasoningRecordsNothing() {
        Recorder r = new Recorder();
        Reasoning.record(r, "{\"choices\":[{\"message\":{\"content\":\"plain\"}}]}");
        Reasoning.record(r, "");
        Reasoning.record(r, null);
        assertTrue(r.thoughts.isEmpty());
    }
}
