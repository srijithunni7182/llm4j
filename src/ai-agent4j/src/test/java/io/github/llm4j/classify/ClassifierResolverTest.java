package io.github.llm4j.classify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.classify.fixtures.ScriptedChat;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.config.RetryPolicy;
import io.github.llm4j.exception.AuthenticationException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * One function turns a model name into a classifier: Jev by name, any other name through the host's
 * chat clients (DESIGN 4.4, Q6).
 */
class ClassifierResolverTest {

    private static final String JEV_REPLY =
            "{\"model\": \"jev-1.13.0\", \"answers\": {\"q\": {\"type\": \"noul\", \"noul\": 0.9}}, \"usage\": {\"input_tokens\": 5, \"output_tokens\": 1}}";
    private static final ClassifyRequest ASK =
            ClassifyRequest.builder().state("x").yesNo("q", "yes?").build();

    private MockWebServer server;

    @BeforeEach
    void start() throws IOException {
        server = new MockWebServer();
        server.start();
        server.enqueue(new MockResponse().setBody(JEV_REPLY));
    }

    @AfterEach
    void stop() throws IOException {
        server.shutdown();
    }

    private java.util.function.Function<String, LLMConfig> jevAt(List<String> modelsSeen) {
        return model -> {
            modelsSeen.add(model);
            return LLMConfig.builder()
                    .apiKey("k")
                    .defaultModel(model)
                    .baseUrl(server.url("/").toString().replaceAll("/$", ""))
                    .retryPolicy(RetryPolicy.builder().maxRetries(0).build())
                    .build();
        };
    }

    private String modelSent() throws Exception {
        return new ObjectMapper()
                .readTree(server.takeRequest().getBody().readUtf8())
                .get("model")
                .asText();
    }

    @Test
    void aJevAliasWithAModelResolvesToJev() throws Exception {
        List<String> seen = new ArrayList<>();
        ClassifierResolver resolver = ClassifierResolver.builder().jev(jevAt(seen)).build();

        ClassifyResult r = resolver.resolve("Jev/jev-1.13.0").classify(ASK);

        assertThat(r.usage().backend()).isEqualTo("jev");
        assertThat(r.yesNo("q").calibrated()).isTrue();
        assertThat(seen).containsExactly("jev-1.13.0");
        assertThat(modelSent()).isEqualTo("jev-1.13.0");
    }

    @Test
    void theAliasIsMatchedIgnoringCaseAndABareJevNameWorksToo() throws Exception {
        List<String> seen = new ArrayList<>();
        ClassifierResolver resolver = ClassifierResolver.builder().jev(jevAt(seen)).build();
        server.enqueue(new MockResponse().setBody(JEV_REPLY));

        resolver.resolve("JEV/jev-latest").classify(ASK);
        resolver.resolve("jev-latest").classify(ASK);

        assertThat(seen).containsExactly("jev-latest", "jev-latest");
    }

    @Test
    void aDeclaredAliasOfKindJevResolvesToJevWhateverItIsCalled() throws Exception {
        List<String> seen = new ArrayList<>();
        ClassifierResolver resolver =
                ClassifierResolver.builder().jevAlias("Scorer", jevAt(seen)).build();

        resolver.resolve("Scorer/jev-latest").classify(ASK);

        assertThat(seen).containsExactly("jev-latest");
        assertThat(resolver.isJev("Scorer/jev-latest")).isTrue();
        assertThat(resolver.isJev("Other/jev-latest")).isFalse();
    }

    @Test
    void aDeciderNameResolvesToPerplexityDecisionsWithoutTheVendorPrefixToo() throws Exception {
        List<String> seen = new ArrayList<>();
        ClassifierResolver resolver =
                ClassifierResolver.builder().perplexityDecisions(jevAt(seen)).build();
        server.enqueue(new MockResponse().setBody(JEV_REPLY));

        ClassifyResult r = resolver.resolve("pplx-decider-flash-latest").classify(ASK);
        resolver.resolve("Perplexity/pplx-decider-latest").classify(ASK);

        assertThat(r.usage().backend()).isEqualTo("perplexity_decisions");
        assertThat(r.yesNo("q").calibrated()).isTrue();
        assertThat(seen).containsExactly("pplx-decider-flash-latest", "pplx-decider-latest");
        assertThat(resolver.isPerplexityDecisions("PPLX-decider-latest")).isTrue();
        // a Perplexity chat model is not a decider, and Jev is not either
        assertThat(resolver.isPerplexityDecisions("perplexity/openai/gpt-5.6-sol")).isFalse();
        assertThat(resolver.isPerplexityDecisions("Jev/jev-latest")).isFalse();
    }

    @Test
    void aDeciderNameWithoutPerplexitySettingsSaysWhatIsMissing() {
        assertThatThrownBy(
                        () -> ClassifierResolver.builder().build().resolve("pplx-decider-latest"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no Perplexity settings");
    }

    @Test
    void anyOtherNameGoesToTheHostsChatClientsAsWritten() {
        List<String> asked = new ArrayList<>();
        ClassifierResolver resolver =
                ClassifierResolver.builder()
                        .chat(
                                name -> {
                                    asked.add(name);
                                    return new ScriptedChat()
                                            .reply(
                                                    "{\"answers\": {\"q\": {\"answer\": \"yes\", \"confidence\": 0.8}}}");
                                })
                        .build();

        ClassifyResult a = resolver.resolve("gemini-2.5-flash").classify(ASK);
        resolver.resolve("Box/llama3").classify(ASK);

        assertThat(asked).containsExactly("gemini-2.5-flash", "Box/llama3");
        assertThat(a.usage().backend()).isEqualTo("llm");
        assertThat(a.yesNo("q").calibrated()).isFalse();
        assertThat(resolver.isJev("gemini-2.5-flash")).isFalse();
    }

    @Test
    void aJevNameWithoutJevSettingsOrAChatNameWithoutChatClientsIsExplained() {
        ClassifierResolver none = ClassifierResolver.builder().build();

        assertThatThrownBy(() -> none.resolve("Jev/jev-latest"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Jev")
                .hasMessageContaining("settings");
        assertThatThrownBy(() -> none.resolve("gemini-2.5-flash"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("chat");
        assertThatThrownBy(() -> none.resolve(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no model");
    }

    @Test
    void fromTheEnvironmentTheKeyAndEndpointAreRead() throws Exception {
        Map<String, String> env =
                Map.of(
                        "JEV_API_KEY",
                        "env-key",
                        "JEV_BASE_URL",
                        server.url("/").toString().replaceAll("/$", ""));
        ClassifierResolver resolver = ClassifierResolver.fromEnvironment(env::get, null);

        ClassifyResult r = resolver.resolve("jev/jev-latest").classify(ASK);

        assertThat(r.usage().backend()).isEqualTo("jev");
        assertThat(server.takeRequest().getHeader("Authorization")).isEqualTo("Bearer env-key");
    }

    @Test
    void aMissingEnvironmentKeyNamesTheVariableNotTheValue() {
        ClassifierResolver resolver = ClassifierResolver.fromEnvironment(name -> null, null);

        assertThatThrownBy(() -> resolver.resolve("jev/jev-latest"))
                .isInstanceOf(AuthenticationException.class)
                .hasMessageContaining("Jev API key is required");
    }
}
