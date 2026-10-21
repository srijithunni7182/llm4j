package io.github.llm4j.loom.validation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.LLMClient;
import io.github.llm4j.loom.cli.CliProbe;
import io.github.llm4j.loom.execution.LLMClientFactory;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code weave check} on the backend of a classifier: which models it may name, what key it needs, and that Jev is not a chat model. */
class ClassifierModelChecksTest {

    @TempDir
    Path dir;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    /** Chat models: everything is accepted except the name "nonsense". */
    private static final LLMClientFactory CHAT = new LLMClientFactory() {
        @Override public LLMClient createClient(String modelName) { throw new IllegalStateException("no models in check"); }
        @Override public String problem(String modelName) { return modelName.equals("nonsense") ? "unknown model \"nonsense\"" : null; }
    };

    private int check(String source, Map<String, String> env) throws Exception {
        out.reset();
        Path f = dir.resolve("s.loom");
        Files.writeString(f, source);
        return CliProbe.check(f.toFile(), new PrintStream(out, true), env::get, CHAT);
    }

    private static String classifier(String model) {
        return "intentclassifier Intent { model: \"" + model + "\" choose kind: refund | other }\n"
                + "workflow W(m) { intentclassify Intent on m -> r\n note \"{r.kind}\" }\n";
    }

    @Test
    void theBuiltInJevNeedsItsKeyAndNamesTheVariable() throws Exception {
        assertThat(check(classifier("Jev/jev-latest"), Map.of())).isNotZero();
        assertThat(out.toString()).contains("intentclassifier Intent").contains("JEV_API_KEY");
    }

    @Test
    void theBuiltInJevPassesWithItsKeyInTheEnvironment() throws Exception {
        assertThat(check(classifier("Jev/jev-latest"), Map.of("JEV_API_KEY", "x"))).as(out.toString()).isZero();
        assertThat(out.toString()).contains("ready to run");
    }

    @Test
    void aProviderDeclaredWithUseJevIsAValidHomeForAClassifier() throws Exception {
        String script = "provider Scorer { use: jev  api_key: env.TYPESAFE_KEY }\n" + classifier("Scorer/jev-latest");

        assertThat(check(script, Map.of("TYPESAFE_KEY", "x"))).as(out.toString()).isZero();
        assertThat(check(script, Map.of())).isNotZero();
        assertThat(out.toString()).contains("TYPESAFE_KEY");
    }

    @Test
    void aJevProviderWithoutAKeyIsRefusedLikeAnyOther() throws Exception {
        assertThat(check("provider Scorer { use: jev }\n" + classifier("Scorer/jev-latest"), Map.of())).isNotZero();
        assertThat(out.toString()).contains("jev needs api_key");
    }

    @Test
    void theBuiltInJevNameCannotBeTakenByAProvider() throws Exception {
        assertThat(check("provider Jev { use: jev  api_key: env.JEV_API_KEY }\n" + classifier("Jev/jev-latest"), Map.of("JEV_API_KEY", "x"))).isNotZero();
        assertThat(out.toString()).contains("reserved for the built-in jev");
    }

    @Test
    void theUnknownKindMessageNowListsJev() throws Exception {
        assertThat(check("provider P { use: nope  api_key: env.K }\n", Map.of("K", "x"))).isNotZero();
        assertThat(out.toString()).contains("gemini, anthropic, ollama, sarvam, jev");
    }

    @Test
    void jevIsNotAChatModelForAnAgentATaskOrARoutingPolicy() throws Exception {
        String agent = "agent A { model: \"Jev/jev-latest\" system: \"x\" }\nworkflow W() { delegate \"x\" to A -> r }\n";

        assertThat(check(agent, Map.of("JEV_API_KEY", "x"))).isNotZero();
        assertThat(out.toString()).contains("agent A").contains("Jev answers typed questions").contains("intentclassifier");
    }

    @Test
    void aDeclaredJevProviderIsNotAChatModelEither() throws Exception {
        String script = "provider Scorer { use: jev  api_key: env.K }\nagent A { model: \"Scorer/jev-latest\" system: \"x\" }\nworkflow W() { delegate \"x\" to A -> r }\n";

        assertThat(check(script, Map.of("K", "x"))).isNotZero();
        assertThat(out.toString()).contains("Jev answers typed questions");
    }

    @Test
    void aChatModelForAClassifierIsCheckedLikeAnAgentsModel() throws Exception {
        assertThat(check(classifier("gemini-2.5-flash"), Map.of())).as(out.toString()).isZero();
        assertThat(check(classifier("nonsense"), Map.of())).isNotZero();
        assertThat(out.toString()).contains("intentclassifier Intent").contains("unknown model \"nonsense\"");
    }

    @Test
    void aModelWithNothingAfterTheProviderIsRefused() throws Exception {
        assertThat(check("provider Scorer { use: jev  api_key: env.K }\n" + classifier("Scorer/"), Map.of("K", "x"))).isNotZero();
        assertThat(out.toString()).contains("names no model after the provider");
    }

    @Test
    void aClassifierOnADeclaredChatProviderPasses() throws Exception {
        assertThat(check("provider Box { use: ollama }\n" + classifier("Box/llama3"), Map.of())).as(out.toString()).isZero();
    }
}
