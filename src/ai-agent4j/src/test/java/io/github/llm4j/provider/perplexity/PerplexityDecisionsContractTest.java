package io.github.llm4j.provider.perplexity;

import io.github.llm4j.classify.Classifier;
import io.github.llm4j.classify.ClassifierContract;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.config.RetryPolicy;
import java.io.IOException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/**
 * 8k, A20: {@link PerplexityDecisionsProvider} meets the same {@link ClassifierContract} as the
 * chat backend and Jev, against a local server speaking the shared decision-model wire format.
 */
class PerplexityDecisionsContractTest extends ClassifierContract {

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
    protected Classifier answering(java.util.List<A> answers) {
        server.enqueue(
                new MockResponse()
                        .setBody(
                                io.github.llm4j.provider.typesafe.JevProviderContractTest.wire(
                                                answers)
                                        .replace("jev-1.13.0", "pplx-decider-v1.2"))
                        .setHeader("Content-Type", "application/json"));
        return Classifier.using(
                new PerplexityDecisionsProvider(
                        LLMConfig.builder()
                                .apiKey("test-key")
                                .baseUrl(server.url("/").toString().replaceAll("/$", ""))
                                .retryPolicy(RetryPolicy.builder().maxRetries(0).build())
                                .build()));
    }

    @Override
    protected int callsSent() {
        return server.getRequestCount();
    }

    @Override
    protected boolean calibrated() {
        return true;
    }
}
