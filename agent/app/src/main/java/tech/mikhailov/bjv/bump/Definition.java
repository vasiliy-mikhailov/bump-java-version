package tech.mikhailov.bjv.bump;

import java.util.Map;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;

/**
 * ONE AGENT AS DATA: what it is called, what it is for, what it is told, and what it can reach.
 *
 * <p>{@link Agents} used to hand-roll a worse shape, sixteen factory methods that each assembled a
 * name, a prompt and a tool set and then threw the assembly away, which is why the prompts could
 * only be read by opening that file and why there was no way to ask what a different hop would be
 * told. As data they can be listed, rendered, diffed between hops and hashed into the fingerprint a
 * run puts its name to.
 *
 * <p>FOUR FIELDS, NOT FIVE. The record this replaces carried a {@code useBuiltInFileTools} flag as
 * well, and it was dead here from the first day: the one construction site passed false and the
 * tools always arrived in the same field they arrive in now. A carrier for a framework's optional
 * behaviour is not worth keeping once the framework is gone, and the field is renamed to
 * {@code tools} for the same reason: there are no built-ins left for it to be extra to.
 */
public record Definition(String name, String description, String systemPrompt,
                         Map<ToolSpecification, ToolExecutor> tools) {
}
