package tech.mikhailov.bjv.agent;

/**
 * EVERYTHING THAT HAPPENS, THROUGH ONE OBJECT.
 *
 * <p>Injected once and handed to every stage, so nothing in this program prints, appends or logs on
 * its own. A stage that writes its own line decides its own format, and a reader assembling a bump
 * out of six formats is doing archaeology.
 *
 * <p>{@link #asked} CARRIES THE PAIR, UNTRUNCATED. Prompt tuning replays a recorded (prompt, reply)
 * pair and scores the reply, so a trace that abbreviates either one is a trace nothing can be
 * trained from. This is the whole reason the interface exists rather than a logger.
 *
 * <p>THE DISTINCTION THAT MUST SURVIVE: {@link #built} and {@link #applied} report facts, and
 * {@link #asked} reports an opinion. A reader who cannot tell which of the two decided a settlement
 * cannot audit it.
 */
interface Trace {

    /** A model call and its answer, both in full. The unit prompt training replays. */
    void asked(String agent, String prompt, String reply);

    /** A deterministic stage did something: a recipe ran, a floor landed, a wall was fixed. A fact. */
    void applied(String stage, String what);

    /** A tool an agent used, payloads in full: the argument to edit_file IS the migration step. */
    void tool(String agent, String tool, String arguments, String result);

    /**
     * WHAT HAS ALREADY HAPPENED IN THIS BUMP, readable by the agents living inside it.
     *
     * <p>The trace records every stage, every answer, every objection and every tool result, and
     * until now it was written for the corpus and for people, never for the run itself. So a loop
     * deciding what to try next could be told what LANDED, from git, and never what was tried and
     * rejected -- which is the more useful half. A troubleshooter that already established a
     * dependency is javax-only should not rediscover it, and a proposer ordering a fourth variation
     * on a step three critics have already refused is not reasoning, it is looping.
     *
     * <p>Returns an empty string rather than throwing when nothing is readable: an agent that asks
     * what happened and gets an exception loses its turn.
     */
    default String happened(String stage, String agent, int limit) {
        return "";
    }

    /**
     * The reasoning behind an answer, and why the answer ended.
     *
     * <p>An opinion like {@link #asked}, and the one that explains it. Recorded separately because
     * the runtime returns only the content: without this the thinking is paid for and discarded,
     * and an answer that ended mid-thought is indistinguishable from one that declined.
     */
    void thought(String finishReason, String thinking, String content);

    /** A build under a named JDK. The only arbiter in the program. */
    void built(String phase, Runner.Result result);

    /** What the bump became, the argument for it, and what the builds actually did. */
    void settled(String bump, String state, String because, boolean baselineGreen, boolean gateGreen);

    /** The bump did not finish. A dropped connection must not look like nothing having happened. */
    void failed(String bump, Throwable cause);

    /** Where the bump is up to, for anything watching while it runs. */
    void progress(String bump, String note);

    /** What the same migration would have cost a person, and the itemisation behind the number. */
    void priced(String bump, String minutes, String itemisation);

    /**
     * ONE EXCHANGE WITH THE MODEL, AS THE CLIENT SAW IT.
     *
     * <p>Everything else on this interface is the harness reporting what it chose to report. This
     * is the wire: recorded by a listener under all of it, so the record holds what happened rather
     * than what somebody remembered to save. It carries the two things the curated events could not
     * -- the server's own token counts, and which agent produced a given piece of reasoning.
     *
     * <p>A DEFAULT, deliberately. A trace double in a test exists to answer a question about
     * something else, and should not have to grow a method every time the wire learns a new fact.
     */
    default void exchanged(Exchange exchange) {
    }

    /** What went, what came back, what it cost. Summaries: the full conversation is not kept. */
    record Exchange(String agent, int messages, String sent, String got, String tools,
                    String finish, long inTokens, long outTokens, long ms, String error) {
    }
}
