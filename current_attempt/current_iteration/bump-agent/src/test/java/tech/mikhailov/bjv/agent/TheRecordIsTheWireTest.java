package tech.mikhailov.bjv.agent;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * <p>The part that needed care is identity. Two models serve all thirty-four agents, so the
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

        quiet.exchanged(new Trace.Exchange("survey-doer", 4, "sent", "got", "", "STOP",
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

        capturing.exchanged(new Trace.Exchange("after-pins-doer", 12, "the last message",
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
}
