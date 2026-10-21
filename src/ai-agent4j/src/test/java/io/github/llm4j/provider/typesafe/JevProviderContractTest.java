package io.github.llm4j.provider.typesafe;

import io.github.llm4j.classify.Classifier;
import io.github.llm4j.classify.ClassifierContract;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.config.RetryPolicy;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/**
 * {@link JevProvider} meets the same {@link ClassifierContract} as the chat backend, against a
 * local server speaking Jev's wire format.
 */
class JevProviderContractTest extends ClassifierContract {

    private MockWebServer server;

    @BeforeEach
    void start() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stop() throws IOException {
        server.shutdown();
    }

    @Override
    protected Classifier answering(List<A> answers) {
        server.enqueue(
                new MockResponse()
                        .setBody(wire(answers))
                        .setHeader("Content-Type", "application/json"));
        LLMConfig cfg =
                LLMConfig.builder()
                        .apiKey("test-key")
                        .baseUrl(server.url("/").toString().replaceAll("/$", ""))
                        .retryPolicy(RetryPolicy.builder().maxRetries(0).build())
                        .build();
        return Classifier.using(new JevProvider(cfg));
    }

    @Override
    protected int callsSent() {
        return server.getRequestCount();
    }

    @Override
    protected boolean calibrated() {
        return true;
    }

    private static final List<String> SCALE = List.of("calm", "annoyed", "angry");

    /** The neutral answers of the contract, as Jev would write them. */
    static String wire(List<A> answers) {
        return "{\"model\": \"jev-1.13.0\", \"answers\": {"
                + answers.stream()
                        .map(JevProviderContractTest::one)
                        .collect(Collectors.joining(", "))
                + "}, \"usage\": {\"input_tokens\": 100, \"output_tokens\": 20}}";
    }

    private static String one(A a) {
        double c = a.confidence() == null ? 0.5 : a.confidence();
        return switch (a.kind()) {
            case CHOICE -> String.format(
                    Locale.ROOT,
                    "\"%s\": {\"type\": \"choice\", \"choice\": \"%s\", \"confidence\": %s, \"probabilities\": {\"%s\": 1.0}}",
                    a.name(),
                    a.label(),
                    c,
                    a.label());
            case YES_NO -> String.format(
                    Locale.ROOT,
                    "\"%s\": {\"type\": \"noul\", \"noul\": %s}",
                    a.name(),
                    a.number());
            case SCORE -> {
                int i = SCALE.indexOf(a.label());
                // a point that is not on the scale has no index: Jev can only say it as a score
                // outside 0..n-1
                yield String.format(
                        Locale.ROOT,
                        "\"%s\": {\"type\": \"score\", \"score\": %s, \"confidence\": %s, \"legend\": {\"0\": \"calm\", \"1\": \"annoyed\", \"2\": \"angry\"},"
                                + " \"probabilities\": {\"%s\": 1.0}}",
                        a.name(),
                        i < 0 ? 7.0 : (double) i,
                        c,
                        i < 0 ? 0 : i);
            }
        };
    }
}
