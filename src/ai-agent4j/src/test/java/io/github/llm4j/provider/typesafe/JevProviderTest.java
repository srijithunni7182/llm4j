package io.github.llm4j.provider.typesafe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.classify.ClassificationException;
import io.github.llm4j.classify.Classifier;
import io.github.llm4j.classify.ClassifyRequest;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.config.RetryPolicy;
import io.github.llm4j.exception.AuthenticationException;
import io.github.llm4j.exception.InvalidRequestException;
import io.github.llm4j.exception.LLMException;
import io.github.llm4j.exception.RateLimitException;
import io.github.llm4j.exception.ServiceUnavailableException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link JevProvider} against a local server that replays what the real API sent (recorded
 * fixtures) and what it is documented to send.
 */
class JevProviderTest {

    /** A key that must never be found in an exception, a log line or an event. */
    private static final String CANARY = "jev-canary-7f3a9c1e5b2d";

    private MockWebServer server;
    private final List<Duration> slept = new ArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stop() throws IOException {
        server.shutdown();
    }

    private LLMConfig.Builder config() {
        return LLMConfig.builder()
                .apiKey(CANARY)
                .baseUrl(base())
                .retryPolicy(RetryPolicy.builder().maxRetries(0).build());
    }

    private String base() {
        return server.url("/").toString().replaceAll("/$", "");
    }

    private Classifier classifier(LLMConfig cfg) {
        return Classifier.using(new JevProvider(cfg));
    }

    private static MockResponse fixtureResponse(String name) {
        return new MockResponse()
                .setResponseCode(Integer.parseInt(JevWireTest.fixture(name + ".status").trim()))
                .setBody(JevWireTest.fixture(name + ".response.json"))
                .setHeader("Content-Type", "application/json");
    }

    // ---- the request on the wire

    @Test
    void postsToTheSystemOneEndpointWithABearerKeyAndTheDocumentedBody() throws Exception {
        server.enqueue(fixtureResponse("choice"));

        classifier(config().build()).classify(JevWireTest.choiceRequest());

        RecordedRequest sent = server.takeRequest();
        assertThat(sent.getMethod()).isEqualTo("POST");
        assertThat(sent.getPath()).isEqualTo("/v1/systemone");
        assertThat(sent.getHeader("Authorization")).isEqualTo("Bearer " + CANARY);
        assertThat(sent.getHeader("Content-Type")).startsWith("application/json");
        assertThat(new ObjectMapper().readTree(sent.getBody().readUtf8()))
                .isEqualTo(new ObjectMapper().readTree(JevWireTest.fixture("choice.request.json")));
    }

    @Test
    void theModelIsTheConfiguredDefaultOrJevLatest() throws Exception {
        server.enqueue(fixtureResponse("yesno"));
        server.enqueue(fixtureResponse("yesno"));

        classifier(config().build()).classify(JevWireTest.yesNoRequest());
        classifier(config().defaultModel("jev-1.13.0").build())
                .classify(JevWireTest.yesNoRequest());

        assertThat(
                        new ObjectMapper()
                                .readTree(server.takeRequest().getBody().readUtf8())
                                .get("model")
                                .asText())
                .isEqualTo("jev-latest");
        assertThat(
                        new ObjectMapper()
                                .readTree(server.takeRequest().getBody().readUtf8())
                                .get("model")
                                .asText())
                .isEqualTo("jev-1.13.0");
    }

    @Test
    void theRecordedAnswersComeBackCalibrated() {
        server.enqueue(fixtureResponse("multi"));

        var r = classifier(config().build()).classify(JevWireTest.multiRequest());

        assertThat(r.choice("department").label()).isEqualTo("technical");
        assertThat(r.answers().values()).allMatch(a -> a.calibrated());
    }

    // ---- requests that are invalid by our rules send nothing (R6)

    @Test
    void anInvalidRequestCannotBeBuiltSoNothingIsSent() {
        Classifier jev = classifier(config().build());

        assertThatThrownBy(
                        () ->
                                ClassifyRequest.builder()
                                        .state("x")
                                        .yesNo("a", "q?")
                                        .yesNo("a", "again?")
                                        .build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                ClassifyRequest.builder()
                                        .state("x")
                                        .choice("k", "q?", "only")
                                        .build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(jev).isNotNull();
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void aMissingKeyIsRefusedBeforeAnyRequest() {
        LLMConfig noKey = LLMConfig.builder().baseUrl(base()).build();

        assertThatThrownBy(() -> new JevProvider(noKey))
                .isInstanceOf(AuthenticationException.class)
                .hasMessageContaining("Jev API key is required");
        assertThat(server.getRequestCount()).isZero();
    }

    // ---- failures, with the real shapes where we have them

    @Test
    void aWrongKeyIsAnAuthenticationExceptionWithTheApisMessage() {
        server.enqueue(fixtureResponse("unauthorized_401"));

        assertThatThrownBy(() -> classifier(config().build()).classify(JevWireTest.yesNoRequest()))
                .isInstanceOf(AuthenticationException.class)
                .hasMessageContaining("Cannot authenticate");
    }

    @Test
    void aValidationErrorNamesTheFieldButNeverEchoesTheState() {
        server.enqueue(fixtureResponse("invalid_422"));

        assertThatThrownBy(() -> classifier(config().build()).classify(JevWireTest.yesNoRequest()))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("criteria")
                .hasMessageContaining("at least 1 item");
    }

    @Test
    void theStateIsNotInAValidationErrorMessageEvenWhenTheApiEchoesIt() {
        server.enqueue(
                new MockResponse()
                        .setResponseCode(422)
                        .setBody(
                                "{\"detail\": [{\"type\": \"x\", \"loc\": [\"body\", \"state\"], \"msg\": \"bad\", \"input\": \"SECRET CUSTOMER TEXT\"}]}"));

        assertThatThrownBy(() -> classifier(config().build()).classify(JevWireTest.yesNoRequest()))
                .isInstanceOf(InvalidRequestException.class)
                .satisfies(
                        e -> {
                            assertThat(e.getMessage()).doesNotContain("SECRET CUSTOMER TEXT");
                            assertThat(((LLMException) e).getResponseBody())
                                    .doesNotContain("SECRET CUSTOMER TEXT");
                        });
    }

    @Test
    void aRateLimitIsRetriedThenSucceeds() {
        server.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After", "1"));
        server.enqueue(fixtureResponse("yesno"));
        RetryPolicy policy =
                RetryPolicy.builder()
                        .maxRetries(2)
                        .addRetryableStatusCode(429)
                        .sleeper(slept::add)
                        .build();

        var r =
                classifier(config().retryPolicy(policy).build())
                        .classify(JevWireTest.yesNoRequest());

        assertThat(r.yesNo("destructive").probability()).isEqualTo(0.01);
        assertThat(server.getRequestCount()).isEqualTo(2);
        assertThat(slept).hasSize(1);
    }

    @Test
    void aRateLimitThatPersistsIsARateLimitException() {
        server.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After", "1"));

        assertThatThrownBy(() -> classifier(config().build()).classify(JevWireTest.yesNoRequest()))
                .isInstanceOf(RateLimitException.class);
    }

    @Test
    void anOverloadedApiIsServiceUnavailableAfterItsRetries() {
        server.enqueue(
                new MockResponse().setResponseCode(529).setBody("{\"detail\": \"overloaded\"}"));
        server.enqueue(
                new MockResponse().setResponseCode(529).setBody("{\"detail\": \"overloaded\"}"));
        RetryPolicy policy =
                RetryPolicy.builder()
                        .maxRetries(1)
                        .addRetryableStatusCode(529)
                        .sleeper(slept::add)
                        .build();

        assertThatThrownBy(
                        () ->
                                classifier(config().retryPolicy(policy).build())
                                        .classify(JevWireTest.yesNoRequest()))
                .isInstanceOf(ServiceUnavailableException.class);
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    void aTimeoutIsAnLlmExceptionNotAHang() {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));

        assertThatThrownBy(
                        () ->
                                classifier(config().timeout(Duration.ofMillis(300)).build())
                                        .classify(JevWireTest.yesNoRequest()))
                .isInstanceOf(LLMException.class);
    }

    @Test
    void aMalformedOrEmptySuccessBodyIsAClassificationException() {
        server.enqueue(new MockResponse().setBody("<html>gateway</html>"));
        server.enqueue(
                new MockResponse()
                        .setBody(
                                "{\"model\": \"jev-1.13.0\", \"answers\": {}, \"usage\": {\"input_tokens\": 1, \"output_tokens\": 1}}"));
        Classifier jev = classifier(config().build());

        assertThatThrownBy(() -> jev.classify(JevWireTest.yesNoRequest()))
                .isInstanceOf(ClassificationException.class);
        assertThatThrownBy(() -> jev.classify(JevWireTest.yesNoRequest()))
                .isInstanceOf(ClassificationException.class)
                .hasMessageContaining("destructive");
    }

    // ---- the key goes only to the configured endpoint and is never shown (R6, A5)

    @Test
    void theKeyIsNeverInAnExceptionALogLineOrAnEvent() throws Exception {
        PrintStream realErr = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true));
        List<String> seen = new ArrayList<>();
        try {
            // the server echoes the key back in every kind of failure body, as some providers do
            server.enqueue(
                    new MockResponse()
                            .setResponseCode(401)
                            .setBody("{\"detail\": {\"message\": \"bad key " + CANARY + "\"}}"));
            server.enqueue(
                    new MockResponse()
                            .setResponseCode(422)
                            .setBody(
                                    "{\"detail\": [{\"loc\": [\"header\", \"authorization\"], \"msg\": \"Bearer "
                                            + CANARY
                                            + "\"}]}"));
            server.enqueue(new MockResponse().setResponseCode(500).setBody("oops " + CANARY));
            server.enqueue(new MockResponse().setBody("{\"echo\": \"" + CANARY + "\"}"));
            Classifier jev =
                    classifier(config().enableLogging(true).build())
                            .observed(e -> seen.add(e.toString()));

            for (int i = 0; i < 4; i++) {
                try {
                    jev.classify(JevWireTest.yesNoRequest());
                } catch (RuntimeException e) {
                    seen.add(e.toString());
                    for (Throwable t = e; t != null; t = t.getCause())
                        seen.add(String.valueOf(t.getMessage()));
                    if (e instanceof LLMException l && l.getResponseBody() != null)
                        seen.add(l.getResponseBody());
                }
            }
        } finally {
            System.setErr(realErr);
        }

        assertThat(seen).as("exceptions and events").noneMatch(s -> s.contains(CANARY));
        assertThat(captured.toString()).as("log output").doesNotContain(CANARY);
    }

    @Test
    void theKeyIsSentOnlyToTheConfiguredEndpoint() throws Exception {
        server.enqueue(fixtureResponse("yesno"));
        MockWebServer other = new MockWebServer();
        other.start();
        try {
            classifier(config().build()).classify(JevWireTest.yesNoRequest());

            assertThat(server.takeRequest().getHeader("Authorization"))
                    .isEqualTo("Bearer " + CANARY);
            assertThat(other.getRequestCount()).isZero();
        } finally {
            other.shutdown();
        }
    }

    @Test
    void theProviderIsNamedJev() {
        assertThat(new JevProvider(config().build()).getProviderName()).isEqualTo("jev");
    }
}
