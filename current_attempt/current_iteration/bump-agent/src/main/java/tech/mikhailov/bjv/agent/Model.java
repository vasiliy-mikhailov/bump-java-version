package tech.mikhailov.bjv.agent;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.openai.OpenAiChatModel;

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
 * grounds are not recorded cannot be audited or tuned. So it stays on, {@link #listener} captures
 * it into the trace, and an empty answer is re-asked rather than read as agreement.
 */
final class Model {

    private static final int MAX_TOKENS = 16_000;

    /**
     * How long one call may take.
     *
     * <p>GENEROUS, NOT TIGHT. This is a request timeout, and a request here is not one generation:
     * it is the whole accumulated conversation re-prefilled, which on a shared local GPU is the slow
     * part and grows with every tool call already made. A 12 minute cap cut off a survey that was
     * working, and the client's own retry then spent the same 12 minutes twice more on a prompt only
     * ever going to get longer. The cap bounds a stuck lane; it does not hurry a working one.
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
        var b = OpenAiChatModel.builder()
                .httpClientBuilder(new JdkHttpClientBuilder()
                        .httpClientBuilder(HttpClient.newBuilder().version(version)))
                .baseUrl(base)
                .apiKey(env("OC_KEY", ""))
                .modelName(env("OC_MODEL", "qwen-3.6-35b-a3b-awq"))
                .temperature(0.0)
                .maxTokens(MAX_TOKENS)
                .timeout(PATIENCE)
                // Surface the reasoning instead of dropping it on the floor.
                .returnThinking(Boolean.TRUE);
        if (trace != null) {
            b.listeners(List.of(listener(trace)));
        }
        return b.build();
    }

    /**
     * Records the reasoning behind every answer, and why the answer ended.
     *
     * <p>At the model rather than at a call site, so a call made by anything — the chain, a
     * sub-agent runtime, a retry — is covered without either having to remember.
     */
    private static ChatModelListener listener(Trace trace) {
        return new ChatModelListener() {
            @Override
            public void onResponse(ChatModelResponseContext ctx) {
                var msg = ctx.chatResponse().aiMessage();
                String thinking = msg == null ? null : msg.thinking();
                if (thinking == null || thinking.isBlank()) {
                    return;
                }
                var meta = ctx.chatResponse().metadata();
                trace.thought(meta == null || meta.finishReason() == null ? ""
                                : meta.finishReason().toString(),
                        thinking, msg.text() == null ? "" : msg.text());
            }
        };
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }
}
