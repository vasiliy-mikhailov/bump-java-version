package tech.mikhailov.bjv.agent;

import java.time.Duration;

import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;

/**
 * CAPTURES THE REASONING THE CLIENT WOULD OTHERWISE DISCARD, at the transport.
 *
 * <p>The server puts its reasoning in a field called {@code reasoning}; the client reads a field
 * called {@code reasoning_content} and finds nothing. So {@code returnThinking} returns nothing,
 * the model's own listener never fires, and the reasoning is generated, paid for, and dropped:
 * eighty-four calls produced eighty-four blanks before this existed.
 *
 * <p>Reading the body here works whatever either side calls the field, because it does not depend
 * on the client's mapping at all. It is also the only layer that sees the whole answer: by the time
 * the runtime returns a String, the content is all that is left, and a call cut off mid-thought is
 * indistinguishable from one that declined to answer.
 *
 * <p>It touches nothing. The response is passed through exactly as received, and a failure to parse
 * is silent by design: a trace that cannot be written must never be the reason a bump fails.
 */
final class Reasoning {

    private Reasoning() {
    }

    /** Wrap a client builder so every response is read for reasoning on its way past. */
    static HttpClientBuilder tee(HttpClientBuilder delegate, Trace trace) {
        return new HttpClientBuilder() {
            @Override
            public Duration connectTimeout() {
                return delegate.connectTimeout();
            }

            @Override
            public HttpClientBuilder connectTimeout(Duration t) {
                delegate.connectTimeout(t);
                return this;
            }

            @Override
            public Duration readTimeout() {
                return delegate.readTimeout();
            }

            @Override
            public HttpClientBuilder readTimeout(Duration t) {
                delegate.readTimeout(t);
                return this;
            }

            @Override
            public HttpClient build() {
                HttpClient inner = delegate.build();
                return new HttpClient() {
                    @Override
                    public SuccessfulHttpResponse execute(HttpRequest request) {
                        SuccessfulHttpResponse response = inner.execute(request);
                        record(trace, response.body());
                        return response;
                    }

                    @Override
                    public void execute(HttpRequest request, ServerSentEventParser parser,
                                        ServerSentEventListener listener) {
                        // Streaming is not used by this chain; pass it straight through rather than
                        // reassembling a stream nobody reads.
                        inner.execute(request, parser, listener);
                    }
                };
            }
        };
    }

    static void record(Trace trace, String body) {
        try {
            if (body == null || body.isEmpty()) {
                return;
            }
            String thinking = field(body, "reasoning");
            if (thinking.isBlank()) {
                thinking = field(body, "reasoning_content");
            }
            if (thinking.isBlank()) {
                return;
            }
            trace.thought(field(body, "finish_reason"), thinking, field(body, "content"));
        } catch (RuntimeException unparseable) {
            // A body we cannot read is not a reason to fail a bump.
        }
    }

    /**
     * The named string field, unescaped. Handwritten because the shape is fixed and a JSON library
     * would be this module's only dependency beyond the agent framework itself.
     */
    static String field(String json, String name) {
        int at = json.indexOf("\"" + name + "\":");
        if (at < 0) {
            return "";
        }
        int i = at + name.length() + 3;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
            i++;
        }
        if (i >= json.length() || json.charAt(i) != '"') {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (int p = i + 1; p < json.length(); p++) {
            char c = json.charAt(p);
            if (c == '\\' && p + 1 < json.length()) {
                char n = json.charAt(++p);
                switch (n) {
                    case 'n' -> out.append('\n');
                    case 't' -> out.append('\t');
                    case 'r' -> out.append('\r');
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case 'u' -> {
                        out.append((char) Integer.parseInt(json, p + 1, p + 5, 16));
                        p += 4;
                    }
                    default -> out.append(n);
                }
            } else if (c == '"') {
                break;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
