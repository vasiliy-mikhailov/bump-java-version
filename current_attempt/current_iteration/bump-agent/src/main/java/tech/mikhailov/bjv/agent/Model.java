package tech.mikhailov.bjv.agent;

import java.net.http.HttpClient;
import java.time.Duration;

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
    private static final Duration PATIENCE = Duration.ofMinutes(12);

    private Model() {
    }

    static ChatModel fromEnv() {
        String base = env("OC_BASE", "https://inference.mikhailov.tech/qwen-3.6-35b-a3b-awq/v1");
        HttpClient.Version version = base.startsWith("https://")
                ? HttpClient.Version.HTTP_2
                : HttpClient.Version.HTTP_1_1;
        return OpenAiChatModel.builder()
                .httpClientBuilder(new JdkHttpClientBuilder()
                        .httpClientBuilder(HttpClient.newBuilder().version(version)))
                .baseUrl(base)
                .apiKey(env("OC_KEY", ""))
                .modelName(env("OC_MODEL", "qwen-3.6-35b-a3b-awq"))
                .temperature(0.0)
                .maxTokens(MAX_TOKENS)
                .timeout(PATIENCE)
                .build();
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }
}
