package io.github.llm4j.loom.review;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.LLMClient;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.LLMClientFactory;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.loom.review.cost.Calibration;
import io.github.llm4j.loom.review.cost.CostEstimator;
import io.github.llm4j.loom.review.settings.ReviewSettings;
import io.github.llm4j.loom.runtime.FileRunJournal;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * R16 of spec 09, against a real (mocked-provider) run: the usage the runtime really journals is
 * what calibration reads, so the two cannot drift apart unnoticed.
 */
class CalibrationEndToEndTest {

    @TempDir Path dir;

    static final String SCRIPT =
            "budget { tokens: 1000000 }\n"
                    + "agent A { model: \"m\"  system: \"You are A.\" }\n"
                    + "workflow Main(q) {\n"
                    + "  delegate \"first\" to A -> a\n"
                    + "  delegate \"second\" to A -> b\n"
                    + "  note \"{a}{b}\"\n"
                    + "}\n";

    @Test
    void whatARealRunJournalsIsWhatCalibrationReads() throws Exception {
        LoomScript script = new LoomParser(new Lexer(SCRIPT).tokenize()).parseScript();
        LLMClientFactory factory =
                model ->
                        new LLMClient() {
                            @Override
                            public LLMResponse chat(LLMRequest request) {
                                return LLMResponse.builder()
                                        .content(
                                                "```json\n{\"thought\": \"t\", \"final_answer\": \"ok\"}\n```")
                                        .model(model)
                                        .tokenUsage(100, 50, 150)
                                        .build();
                            }

                            @Override
                            public Stream<LLMResponse> chatStream(LLMRequest request) {
                                return Stream.of(chat(request));
                            }
                        };
        HarnessExecutor executor = new HarnessExecutor(script, new ToolRegistry(), factory);
        executor.setJournal(new FileRunJournal(dir.resolve("journal.json")));
        executor.initialize();
        executor.executeWorkflow("Main", Map.of());
        executor.shutdown();

        Path file = dir.resolve("main.loom");
        java.nio.file.Files.writeString(file, SCRIPT);
        var estimate =
                new CostEstimator()
                        .estimate(ReviewFixtures.input(dir, SCRIPT), ReviewSettings.defaults());
        Calibration.Result r = Calibration.compare(dir, estimate);

        assertThat(r.workflows()).hasSize(1);
        assertThat(r.workflows().get(0).name()).isEqualTo("Main");
        assertThat(r.workflows().get(0).actualCalls()).isEqualTo(2);
        assertThat(r.workflows().get(0).actualTokens()).isEqualTo(300);
        assertThat(r.models()).extracting(Calibration.Row::name).containsExactly("m");
        assertThat(r.workflows().get(0).estimatedTokens()).isGreaterThan(0);
    }
}
