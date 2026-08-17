package tech.mikhailov.bjv.agent;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE RECORD BECOMES WHAT HAPPENED, NOT WHAT WAS SAVED.
 *
 * <p>Every other event on the trace exists because the harness called a method. That is a curated
 * account, and three things this corpus wanted were missing from it for exactly that reason: the
 * server's own token counts, so the thinking budget could be checked against what was spent rather
 * than against a character estimate; which agent produced a piece of reasoning, since 737 thought
 * events in one sweep attributed to nobody; and calls that failed before any answer existed to
 * report. A listener under the model sees all three.
 *
 * <p>The part that needed care is identity. Two models serve every agent in the chain, so the
 * listener cannot be told who is speaking, and the streaming path makes a thread-local a guess.
 * What travels with every request is the system message, and every agent's prompt is distinct.
 */
class TheRecordIsTheWireTest {

    @Test
    void anAgentIsRecognisedFromThePromptItWasBuiltWith() {
        Listening.register("survey-doer", "You establish which JDK this project is on.");

        assertEquals("survey-doer",
                Listening.agentOf("You establish which JDK this project is on."));
    }

    @Test
    void itMatchesWhenTheFrameworkWrapsThePrompt() {
        // The system message is not always the prompt verbatim: the agent framework may add its own
        // preamble or trailer. Containment survives that; equality would not.
        Listening.register("bump-doer", "You move a Java project from JDK 17 to JDK 21.");

        assertEquals("bump-doer", Listening.agentOf(
                "SYSTEM: You move a Java project from JDK 17 to JDK 21.\n\nTools available: ..."));
    }

    @Test
    void theLongestMatchWinsSoAPrefixCannotSteal() {
        // THE REASON IT IS LONGEST-MATCH. Agent prompts share their opening lines by construction:
        // a pin pair differs only in a paragraph near the end. Matching the first prompt that fits
        // would attribute every one of them to whichever was registered first.
        Listening.register("before-pins-doer", "Raise these versions.");
        Listening.register("after-pins-doer", "Raise these versions. The JDK has already moved.");

        assertEquals("after-pins-doer",
                Listening.agentOf("Raise these versions. The JDK has already moved."));
    }

    @Test
    void anUnknownPromptNamesNobodyRatherThanGuessing() {
        assertEquals("", Listening.agentOf("a prompt nothing registered"));
        assertEquals("", Listening.agentOf(null));
    }

    @Test
    void aTraceDoubleDoesNotHaveToKnowAboutTheWire() {
        // The method is a default on the interface on purpose: a trace double in a test exists to
        // answer a question about something else, and adding a wire fact should not break it.
        Trace quiet = new Quiet();

        quiet.exchanged(new Trace.Exchange("back", "survey-doer", 4, "", "got", "", "STOP",
                1200, 340, 900, ""));
    }

    @Test
    void whatTheExchangeCarriesIsWhatTheCuratedEventsCouldNot() {
        List<Trace.Exchange> seen = new ArrayList<>();
        Trace capturing = new Quiet() {
            @Override
            public void exchanged(Trace.Exchange e) {
                seen.add(e);
            }
        };

        capturing.exchanged(new Trace.Exchange("back", "after-pins-doer", 12, "",
                "the answer", "apply_recipe", "TOOL_EXECUTION", 24_000, 512, 8_400, ""));

        Trace.Exchange e = seen.get(0);
        assertEquals("after-pins-doer", e.agent(), "the attribution thought events never had");
        assertEquals(24_000, e.inTokens(), "the server's count, not a character estimate");
        assertEquals(512, e.outTokens());
        assertEquals("apply_recipe", e.tools(), "and what it asked to run");
        assertTrue(e.ms() > 0, "and what it cost in wall time");
    }

    /** A trace that keeps nothing; these tests are about the wire, not about storage. */
    private static class Quiet implements Trace {
        public void asked(String a, String p, String r) {
        }

        public void applied(String s, String w) {
        }

        public void tool(String a, String t, String args, String result) {
        }

        public void thought(String f, String t, String c) {
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
    void theRequestIsRecordedWhenItIsSentAndTheReplyWhenItReturns() throws Exception {
        // ORDER IS THE POINT. Both halves used to be written from onResponse, which stamps them at
        // completion, so a seventeen-second call filed its own prompt seventeen seconds late --
        // after the streamed reasoning that prompt had caused. Read top to bottom, the record
        // showed the model thinking and then what it had been asked.
        java.lang.reflect.Method m = Api.class.getDeclaredMethod("event", java.util.Map.class);
        m.setAccessible(true);
        Api api = new Api(java.nio.file.Path.of("/tmp"));

        String out = (String) m.invoke(api, fields(
                "kind", "exchange", "at", "1", "direction", "to", "agent", "survey-planner",
                "messages", "2", "sent", "state what would settle whether it is on 21"));
        String back = (String) m.invoke(api, fields(
                "kind", "exchange", "at", "2", "direction", "back", "agent", "survey-planner",
                "in", "2654", "out", "1138", "ms", "17205", "finish", "TOOL_EXECUTION",
                "tools", "glob", "got", "reading the root pom"));

        assertTrue(out.contains("sent, 2 message(s)"), out);
        assertTrue(out.contains("state what would settle"), "the prompt is in the request: " + out);
        assertFalse(out.contains("out tokens"), "and the counts are not, they are not known yet");

        assertTrue(back.contains("2654 in / 1138 out tokens"), back);
        assertTrue(back.contains("17205ms"), back);
        assertFalse(back.contains("state what would settle"),
                "the reply half does not repeat the prompt: " + back);
    }

    @Test
    void aFailedCallIsStillTheReplyHalf() throws Exception {
        java.lang.reflect.Method m = Api.class.getDeclaredMethod("event", java.util.Map.class);
        m.setAccessible(true);

        String json = (String) m.invoke(new Api(java.nio.file.Path.of("/tmp")), fields(
                "kind", "exchange", "at", "1", "direction", "back", "agent", "bump-doer",
                "ms", "31000", "finish", "ERROR", "in", "0", "out", "0",
                "error", "SocketTimeoutException: read timed out"));

        // A CALL THAT NEVER ANSWERED USED TO LEAVE NO EVENT AT ALL, because the harness only wrote
        // one once it had a reply to write down. Its request is already on the record above it.
        assertTrue(json.contains("FAILED SocketTimeoutException"), json);
        assertTrue(json.contains("31000ms"), json);
    }


    @Test
    void theRequestCarriesThePromptTheFirstTimeAndNamesItAfter() throws Exception {
        // "2 message(s)" was true and told a reader nothing about the one they could not see. On a
        // first call the pair is [system, user], and recording only the LAST message showed the
        // task while dropping the instruction that governs what the agent does with it.
        //
        // Once per agent, though: the system prompt is identical on every one of that agent's calls
        // and runs to thousands of characters, so repeating it would put the same paragraphs on
        // disk a hundred times in a bump.
        Listening listener = new Listening(new Quiet());
        java.lang.reflect.Method m = Listening.class.getDeclaredMethod(
                "outbound", String.class, java.util.List.class);
        m.setAccessible(true);

        var system = dev.langchain4j.data.message.SystemMessage.from(
                "You establish which JDK this project is on.");
        var user = dev.langchain4j.data.message.UserMessage.from("The root build files: pom.xml");

        String first = (String) m.invoke(listener, "survey-planner",
                java.util.List.of(system, user));
        assertTrue(first.contains("You establish which JDK"), "the prompt is there: " + first);
        assertTrue(first.contains("[system: survey-planner"), first);
        assertTrue(first.contains("[user]"), "and the task, labelled: " + first);
        assertTrue(first.contains("The root build files"), first);

        String second = (String) m.invoke(listener, "survey-planner",
                java.util.List.of(system, user));
        assertFalse(second.contains("You establish which JDK"),
                "not written twice: " + second);
        assertTrue(second.contains("unchanged"), "but said to be the same one: " + second);
        assertTrue(second.contains("The root build files"),
                "while the turn's own message still travels: " + second);
    }

    @Test
    void aToolResultIsLabelledAndKeepsTheToolThatProducedIt() throws Exception {
        Listening listener = new Listening(new Quiet());
        java.lang.reflect.Method m = Listening.class.getDeclaredMethod(
                "outbound", String.class, java.util.List.class);
        m.setAccessible(true);

        var result = dev.langchain4j.data.message.ToolExecutionResultMessage.from(
                "call-1", "glob", "no files match **/*.gradle");

        String said = (String) m.invoke(listener, "survey-planner", java.util.List.of(result));

        // A RESULT WITH NO NAME READS AS AN ANSWER FROM NOWHERE, and by the third turn a request
        // carries several of them.
        assertTrue(said.contains("[tool result]"), said);
        assertTrue(said.contains("glob"), said);
        assertTrue(said.contains("no files match"), said);
    }

    /** Map.of stops at ten pairs and an exchange carries more than that. */
    private static java.util.Map<String, String> fields(String... pairs) {
        java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            m.put(pairs[i], pairs[i + 1]);
        }
        return m;
    }
}
