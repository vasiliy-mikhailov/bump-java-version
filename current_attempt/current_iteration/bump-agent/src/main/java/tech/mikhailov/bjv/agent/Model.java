package tech.mikhailov.bjv.agent;

import java.net.http.HttpClient;
import java.time.Duration;

import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;

/**
 * The one model, from the same OC_* environment the whole bump infrastructure uses.
 *
 * <p>Temperature zero everywhere: most of the replies in this program are branched on, and a
 * certification that varies between runs certifies nothing.
 *
 * <p>THINKING IS ON, AND IT IS RECORDED. The endpoint runs a reasoning parser, so the model emits
 * its reasoning into a field separate from the content, and the runtime returns only the content.
 * Left alone that reasoning is generated, paid for and thrown away, and when a call ends mid-thought
 * the empty content is all anyone downstream sees, with no way to tell a model that declined from
 * one that ran out of room. Measured here, one closed-list critic answer costs 537 completion
 * tokens with thinking on and 3 with it off: switching it off is the cheap answer and the wrong
 * one, because the reasoning is the most informative thing an agent produces and a judgement whose
 * grounds are not recorded cannot be audited or tuned. So it stays on, {@link Streamed} captures it
 * off the stream, and an empty answer is re-asked rather than read as agreement.
 */
final class Model {

    private static final int MAX_TOKENS = 16_000;

    /**
     * The transport's own read timeout.
     *
     * <p>Generous, and no longer the instrument that matters: with the answer streamed, the guard
     * that decides whether a call is alive is {@link Streamed}'s time-since-last-token. This only
     * bounds the wait for the very first byte.
     */
    private static final Duration PATIENCE = Duration.ofMinutes(
            Integer.parseInt(env("BJV_PATIENCE_MINUTES", "45")));

    private Model() {
    }

    /** Producers and critics share a configuration; what differs is what the chain does with them. */
    static ChatModel forProducer(Trace trace) {
        return build(trace);
    }

    static ChatModel forCritic(Trace trace) {
        return build(trace);
    }

    /** For a caller with no trace to write to; the reasoning is then simply not recorded. */
    static ChatModel fromEnv() {
        return build(null);
    }

    private static ChatModel build(Trace trace) {
        String base = env("OC_BASE", "https://inference.mikhailov.tech/qwen-3.6-35b-a3b-awq/v1");
        HttpClient.Version version = base.startsWith("https://")
                ? HttpClient.Version.HTTP_2
                : HttpClient.Version.HTTP_1_1;
        var jdk = new JdkHttpClientBuilder()
                .httpClientBuilder(HttpClient.newBuilder().version(version));
        // STREAMED, so the guard can be time since the last token rather than time for the whole
        // request. A blocking client sends and receives nothing while the server prefills a large
        // context, which is exactly when a proxy reaps the socket and a total timeout fires on work
        // that is progressing.
        var s = OpenAiStreamingChatModel.builder()
                .httpClientBuilder(jdk)
                .baseUrl(base)
                .apiKey(env("OC_KEY", ""))
                .modelName(env("OC_MODEL", "qwen-3.6-35b-a3b-awq"))
                .temperature(0.0)
                .maxTokens(MAX_TOKENS)
                .timeout(PATIENCE)
                .returnThinking(Boolean.TRUE)
                .build();
        return new Streamed(s, trace);
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }
}
