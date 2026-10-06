package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.LLMClient;
import io.github.llm4j.loom.prompt.PromptSettings;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** R4.3 of loom-prompt-files: two runs of the same script with different pins differ in the prompt and in nothing else. */
class PromptAbTest {

    private static final String ANSWER = "```json\n{\"thought\":\"t\",\"final_answer\":\"{\\\"verdict\\\":\\\"OK\\\",\\\"advice\\\":\\\"fine\\\"}\"}\n```";

    /** Every request of one run of the sample: the system message and the rest of the conversation, in order. */
    private List<List<String>> runSample(Map<String, String> pins) {
        List<List<String>> requests = new ArrayList<>();
        io.github.llm4j.loom.execution.LLMClientFactory models = model -> new LLMClient() {
            @Override public LLMResponse chat(LLMRequest r) {
                List<String> messages = new ArrayList<>();
                r.getMessages().forEach(m -> messages.add(m.getContent()));
                requests.add(messages);
                return LLMResponse.builder().content(ANSWER).model(model).tokenUsage(1, 1, 2).build();
            }
            @Override public Stream<LLMResponse> chatStream(LLMRequest r) { return Stream.of(chat(r)); }
        };
        PrintStream sink = new PrintStream(new ByteArrayOutputStream(), true);
        WeaveEnv env = new WeaveEnv(models, m -> "yes", sink, sink, Clock.systemUTC(), d -> { },
                c -> new io.github.llm4j.loom.trigger.system.CommandRunner.Result(0, "", ""), List.of("weave"), k -> "key")
                .withPrompts(new PromptSettings(null, pins));
        int code = WeaveCLI.run(Path.of("../../examples/newsletter/main.loom").toFile(), null, "Main", Map.of("topic", "home composting"),
                null, null, null, null, null, null, false, env);
        assertThat(code).isZero();
        return requests;
    }

    @Test
    void pinningAnotherVersionChangesTheResearchersPromptAndNothingElse() {
        List<List<String>> v1 = runSample(Map.of("researcher", "v1"));
        List<List<String>> v2 = runSample(Map.of("researcher", "v2"));

        assertThat(v1).hasSameSizeAs(v2).hasSize(3);
        int differing = 0;
        for (int i = 0; i < v1.size(); i++) {
            if (!v1.get(i).equals(v2.get(i))) {
                differing++;
                assertThat(i).as("only the researcher's own request differs").isZero();
                assertThat(v1.get(i).get(0)).doesNotContain("kind of source").contains("three most useful facts");
                assertThat(v2.get(i).get(0)).contains("Label every one with the kind of source");
                assertThat(v1.get(i).subList(1, v1.get(i).size())).isEqualTo(v2.get(i).subList(1, v2.get(i).size()));
            }
        }
        assertThat(differing).isEqualTo(1);
    }

    @Test
    void noPinRunsTheLatestAndAPinOfTheLatestIsTheSameRun() {
        assertThat(runSample(Map.of())).isEqualTo(runSample(Map.of("researcher", "v2")));
    }
}
