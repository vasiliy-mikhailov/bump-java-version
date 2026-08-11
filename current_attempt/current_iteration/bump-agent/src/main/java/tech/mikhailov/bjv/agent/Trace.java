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
}
