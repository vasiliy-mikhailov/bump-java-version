package tech.mikhailov.bjv.engine;

/**
 * WHAT A CONSUMER MAY BE TOLD WHILE AN AGENT IS WORKING, owned here rather than imported.
 *
 * <p>This module used to implement a third party's five-method listener in order to override two of
 * them, and that single import decided what the engine could be published as. Four of the five
 * signatures reported on an orchestrator this program never constructs, and the record proves it:
 * across the whole recorded corpus, including one trace of 5,665 rows, not one orchestrator row was
 * ever written. What is left is the one thing a tool loop can honestly report.
 *
 * <p>THE PAYLOADS ARRIVE SHORTENED, at eight thousand characters, because a listener is for
 * watching. Anything that wants a tool call whole records it at the executor instead, which is what
 * the bump's own {@code Tools} does and why {@link JsonlTrace}'s implementation of this is
 * deliberately empty: writing the shortened duplicate would double the file and lose the argument
 * that matters.
 */
public interface FlowListening {

    /**
     * One tool call, after it returned.
     *
     * @param context      the label the agent was built with, e.g. {@code agent:bump-doer}
     * @param toolName     what the model asked for, falling back to the specification's own name
     * @param memoryId     the conversation the call belongs to, as langchain4j knows it
     * @param argumentsTruncated the model's raw JSON arguments, shortened
     * @param resultTruncated    what the executor answered, shortened, and never null
     */
    void onToolInvocation(String context, String toolName, Object memoryId,
                          String argumentsTruncated, String resultTruncated);
}
