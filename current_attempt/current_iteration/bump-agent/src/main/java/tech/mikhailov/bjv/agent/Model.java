package tech.mikhailov.bjv.agent;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

/**
 * The one model, from the same OC_* environment the whole bump infrastructure uses.
 *
 * <p>Temperature zero everywhere: most of the replies in this program are branched on, and a
 * certification that varies between runs certifies nothing. The token cap turns "how long will
 * this take" into arithmetic; unset, a reasoning model generates until it runs out of context.
 */
final class Model {

    private static final int MAX_TOKENS = 16_000;

    /**
     * How long one call may take.
     *
     * <p>GENEROUS, NOT TIGHT. This is a request timeout, and a request here is not one generation:
     * it is the whole accumulated conversation re-prefilled, which on a shared local GPU is the slow
     * part and grows with every tool call the agent has already made. A 12 minute cap cut off a
     * survey that was working, and the client's own retry then spent the same 12 minutes twice more
     * on a prompt that was only ever going to get longer.
     *
     * <p>The cap exists to bound a stuck lane, not to hurry a working one. Overridable so a loaded
     * host can be given more without a rebuild.
     */
    private static final Duration PATIENCE = Duration.ofMinutes(
            Integer.parseInt(envOr("BJV_PATIENCE_MINUTES", "45")));

    private Model() {
    }

    /**
     * The model a PRODUCER uses: thinking left on, because diagnosing a wall or choosing an edit is
     * the work.
     */
    static ChatModel forProducer() {
        return build(true);
    }

    /**
     * The model a CRITIC uses: thinking OFF.
     *
     * <p>A critic answers one word from a closed list. Measured on this endpoint, the same question
     * costs 537 completion tokens with thinking on and 3 with it off, and the reasoning is not
     * returned in the content at all: it lands in a separate field the runtime never reads. So the
     * budget is spent producing something nothing consumes, and when it runs out mid-thought the
     * content comes back EMPTY.
     *
     * <p>That is not a cosmetic loss. An empty reply falls through the word list to its default,
     * and the default for every critic is the approving word, so a critic that ran out of budget
     * silently approves. Across the live sweep 13% of replies were empty and 13 of those 15 were
     * critics: a share of the feedback loops were not running at all.
     */
    static ChatModel forCritic() {
        return build(false);
    }

    static ChatModel fromEnv() {
        String base = env("OC_BASE", "https://inference.mikhailov.tech/qwen-3.6-35b-a3b-awq/v1");
        HttpClient.Version version = base.startsWith("https://")
                ? HttpClient.Version.HTTP_2
                : HttpClient.Version.HTTP_1_1;
        return build(true);
    }

    private static ChatModel build(boolean thinking) {
        String base = env("OC_BASE", "https://inference.mikhailov.tech/qwen-3.6-35b-a3b-awq/v1");
        HttpClient.Version version = base.startsWith("https://")
                ? HttpClient.Version.HTTP_2
                : HttpClient.Version.HTTP_1_1;
        var b = OpenAiChatModel.builder()
                .httpClientBuilder(new JdkHttpClientBuilder()
                        .httpClientBuilder(HttpClient.newBuilder().version(version)))
                .baseUrl(base)
                .apiKey(env("OC_KEY", ""))
                .modelName(env("OC_MODEL", "qwen-3.6-35b-a3b-awq"))
                .temperature(0.0)
                .maxTokens(MAX_TOKENS)
                .timeout(PATIENCE);
        if (!thinking) {
            // The server template's own switch, not a prompt asking nicely for brevity.
            b.customParameters(Map.of("chat_template_kwargs",
                    Map.of("enable_thinking", Boolean.FALSE)));
        }
        return b.build();
    }

    private static String envOr(String name, String fallback) {
        return env(name, fallback);
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }
}
