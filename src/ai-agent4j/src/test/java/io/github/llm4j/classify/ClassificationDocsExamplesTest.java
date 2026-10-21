package io.github.llm4j.classify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.LLMClient;
import io.github.llm4j.classify.fixtures.ScriptedChat;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.provider.ClassificationProvider;
import io.github.llm4j.provider.typesafe.JevProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

/**
 * The code samples in wiki/Classification.md, compiled and run. Change a sample here and in the
 * page together.
 */
class ClassificationDocsExamplesTest {

    private static final String REPLY =
            "{\"answers\": {\"intent\": {\"label\": \"refund\", \"confidence\": 0.93},"
                    + " \"urgent\": {\"answer\": \"no\", \"confidence\": 0.88}, \"tone\": {\"point\": \"annoyed\", \"confidence\": 0.7}}}";

    @Test
    void askAChatModel() {
        LLMClient chatClient = new ScriptedChat().reply(REPLY);
        String customerMessage = "I want my money back, order 8812 arrived broken";
        // --- sample: ask
        Classifier classifier = Classifier.using(chatClient);   // any LLMClient: Gemini, Claude, Sarvam, Ollama

        ClassifyResult r = classifier.classify(ClassifyRequest.builder()
                .state("message", customerMessage)
                .choice("intent", "What does the customer want?", "refund", "order_status", "complaint", "other")
                .yesNo("urgent", "Is the customer threatening to leave?")
                .score("tone", "How hostile is the message?", Scale.of("calm", "annoyed", "angry"))
                .build());

        String intent = r.choice("intent").label();          // always one of the four labels
        boolean urgent = r.yesNo("urgent").yes();            // probability() is the number behind it
        String tone = r.score("tone").nearest();             // position() is 0.0 to 1.0 along the scale
        // --- end sample
        assertThat(intent).isEqualTo("refund");
        assertThat(urgent).isFalse();
        assertThat(tone).isEqualTo("annoyed");
    }

    @Test
    void readTheNumbers() {
        LLMClient chatClient = new ScriptedChat().reply(REPLY);
        Classifier classifier = Classifier.using(chatClient);
        ClassifyRequest request =
                ClassifyRequest.builder()
                        .state("message", "where is my order")
                        .choice(
                                "intent",
                                "What does the customer want?",
                                "refund",
                                "order_status",
                                "complaint",
                                "other")
                        .yesNo("urgent", "Is the customer threatening to leave?")
                        .score(
                                "tone",
                                "How hostile is the message?",
                                Scale.of("calm", "annoyed", "angry"))
                        .build();
        // --- sample: numbers
        ClassifyResult r = classifier.classify(request);

        ChoiceAnswer intent = r.choice("intent");
        double confidence = intent.confidence().orElse(0.0);   // the model's own report here; empty when there is none
        boolean calibrated = intent.calibrated();              // false for a chat model, always
        ClassifyUsage usage = r.usage();                       // tokens, latency, backend and model of the call
        // --- end sample
        assertThat(confidence).isEqualTo(0.93);
        assertThat(calibrated).isFalse();
        assertThat(usage.inputTokens()).isEqualTo(100);
    }

    @Test
    void tuneTheChatBackend() {
        LLMClient chatClient = new ScriptedChat().reply(REPLY).reply(REPLY).reply(REPLY);
        // --- sample: options
        Classifier classifier = LlmClassifier.builder(chatClient)
                .model("gemini-2.5-flash")      // overrides the client's default model
                .samples(3)                     // three independent calls; the vote share becomes the probabilities
                .withRationale(true)            // also ask for a one-sentence reason (costs output tokens)
                .maxOutputTokens(300)           // a reply cut off by the cap is an error, never a guess
                .build();
        // --- end sample
        ClassifyResult r =
                classifier.classify(
                        ClassifyRequest.builder()
                                .state("m")
                                .choice(
                                        "intent",
                                        "x?",
                                        "refund",
                                        "order_status",
                                        "complaint",
                                        "other")
                                .yesNo("urgent", "u?")
                                .score("tone", "t?", Scale.of("calm", "annoyed", "angry"))
                                .build());
        assertThat(r.choice("intent").probabilities().get("refund")).isEqualTo(1.0);
    }

    @Test
    void whenTheReplyIsNotAnAnswer() {
        LLMClient chatClient =
                new ScriptedChat()
                        .reply("I think it is a refund.")
                        .reply("I think it is a refund.");
        Classifier classifier = Classifier.using(chatClient);
        ClassifyRequest request =
                ClassifyRequest.builder()
                        .state("m")
                        .choice("intent", "x?", "refund", "other")
                        .build();
        // --- sample: invalid
        try {
            classifier.classify(request);
        } catch (ClassificationException e) {
            // the reply was prose, cut off, a label that was not offered, a missing question...
            // nothing is returned in that case, so there is no "unknown label" branch to write
        }
        // --- end sample
        assertThatThrownBy(() -> classifier.classify(request))
                .isInstanceOf(io.github.llm4j.exception.LLMException.class);
    }

    @Test
    void useJev() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setBody("{\"model\": \"jev-1.13.0\", \"answers\": {\"intent\": {\"type\": \"choice\", \"choice\": \"refund\", \"confidence\": 1.0,"
                    + " \"probabilities\": {\"refund\": 1.0, \"order_status\": 0.0, \"complaint\": 0.0, \"other\": 0.0}}},"
                    + " \"usage\": {\"input_tokens\": 357, \"output_tokens\": 39}}"));
            String jevApiKey = "test-key";                         // System.getenv("JEV_API_KEY") in your application
            String jevUrl = server.url("/").toString().replaceAll("/$", "");
            String customerMessage = "I want my money back, order 8812 arrived broken";
            // --- sample: jev
            LLMConfig cfg = LLMConfig.builder()
                    .apiKey(jevApiKey)
                    .defaultModel("jev-latest")
                    .baseUrl(jevUrl)                                 // optional: the default is https://api.typesafe.ai
                    .build();
            Classifier jev = Classifier.using(new JevProvider(cfg));   // the same interface as a chat model

            ChoiceAnswer intent = jev.classify(ClassifyRequest.builder()
                    .state("message", customerMessage)
                    .choice("intent", "What does the customer want?", "refund", "order_status", "complaint", "other")
                    .build()).choice("intent");

            double sure = intent.probabilities().get(intent.label());   // a calibrated probability
            boolean calibrated = intent.calibrated();                   // true: Jev's probabilities are measured, not self-reported
            // --- end sample
            assertThat(intent.label()).isEqualTo("refund");
            assertThat(sure).isEqualTo(1.0);
            assertThat(calibrated).isTrue();
        }
    }

    @Test
    void chooseTheBackendByName() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setBody("{\"model\": \"jev-1.13.0\", \"answers\": {\"intent\": {\"type\": \"choice\", \"choice\": \"other\","
                    + " \"confidence\": 1.0, \"probabilities\": {\"refund\": 0.0, \"other\": 1.0}}}, \"usage\": {\"input_tokens\": 1, \"output_tokens\": 1}}"));
            Map<String, String> env = Map.of("JEV_API_KEY", "test-key", "JEV_BASE_URL", server.url("/").toString().replaceAll("/$", ""));
            LLMClient chatClient = new ScriptedChat().reply(REPLY);
            ClassifyRequest request = ClassifyRequest.builder().state("hi").choice("intent", "What?", "refund", "other").build();
            // --- sample: resolver
            ClassifierResolver resolver = ClassifierResolver.fromEnvironment(env::get,       // System::getenv in your application: JEV_API_KEY, JEV_BASE_URL
                    modelName -> chatClient);                                                 // how your application builds a chat client for a model name

            Classifier cheap = resolver.resolve("gemini-2.5-flash");   // any other name: a chat model, asked through LlmClassifier
            Classifier jev = resolver.resolve("Jev/jev-latest");       // Jev: one word different, same Classifier
            // --- end sample
            assertThat(resolver.isJev("Jev/jev-latest")).isTrue();
            assertThat(jev.classify(request).usage().backend()).isEqualTo("jev");
            assertThat(cheap).isNotNull();
        }
    }

    // --- sample: provider
    /** A classifier backend of your own: anything that can answer typed questions, here a keyword table. */
    static final class KeywordProvider implements ClassificationProvider {
        @Override
        public ClassifyResult classify(ClassifyRequest request) {
            String text = String.join(" ", request.state().values()).toLowerCase();
            Map<String, Answer> answers = new java.util.LinkedHashMap<>();
            for (Question q : request.questions()) {
                if (q instanceof Question.Choice c) {
                    String label = c.labels().stream().filter(l -> text.contains(l)).findFirst().orElse(c.labels().get(c.labels().size() - 1));
                    answers.put(q.name(), new ChoiceAnswer(label, Map.of(), OptionalDouble.empty(), false));
                }
                // yes/no and score questions are answered the same way
            }
            return new ClassifyResult(answers, new ClassifyUsage(0, 0, 0, "keywords", ""));
        }

        @Override
        public String getProviderName() {
            return "keywords";
        }

        @Override
        public void validate() {}
    }
    // --- end sample

    @Test
    void useYourOwnProvider() {
        // --- sample: use provider
        Classifier classifier = Classifier.using(new KeywordProvider());   // the result is checked against every request
        // --- end sample
        ClassifyResult r =
                classifier.classify(
                        ClassifyRequest.builder()
                                .state("please refund me")
                                .choice("intent", "What?", "refund", "other")
                                .build());
        assertThat(r.choice("intent").label()).isEqualTo("refund");
    }

    @Test
    void watchTheCalls() {
        LLMClient chatClient = new ScriptedChat().reply(REPLY);
        ClassifyRequest request =
                ClassifyRequest.builder()
                        .state("m")
                        .choice("intent", "x?", "refund", "order_status", "complaint", "other")
                        .yesNo("urgent", "u?")
                        .score("tone", "t?", Scale.of("calm", "annoyed", "angry"))
                        .build();
        List<String> lines = new ArrayList<>();
        // --- sample: observe
        Classifier observed = Classifier.using(chatClient).observed(event ->
                lines.add(event.outcome() + " " + event.usage().backend() + " " + event.usage().inputTokens() + " tokens "
                        + event.usage().latencyMillis() + " ms " + event.labels()));   // numbers and labels, never the state
        // --- end sample
        observed.classify(request);
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).startsWith("OK llm 100 tokens").contains("{intent=refund}");
    }

    /** Every sample above appears verbatim in the page. */
    @Test
    void thePageShowsTheSamples() throws Exception {
        String source =
                Files.readString(
                        Path.of(
                                "src/test/java/io/github/llm4j/classify/ClassificationDocsExamplesTest.java"));
        String page = Files.readString(Path.of("wiki/Classification.md"));
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile(
                                "// --- sample: ([^\\n]+)\\n(.*?)\\n\\s*// --- end sample",
                                java.util.regex.Pattern.DOTALL)
                        .matcher(source);
        int found = 0;
        while (m.find()) {
            found++;
            String sample = m.group(2).stripIndent().strip();
            assertThat(page).as("the page shows the '" + m.group(1) + "' sample").contains(sample);
        }
        assertThat(found).isGreaterThanOrEqualTo(6);
    }
}
