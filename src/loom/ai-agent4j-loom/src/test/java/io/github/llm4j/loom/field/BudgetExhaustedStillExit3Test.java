package io.github.llm4j.loom.field;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.cli.FieldCli;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Choosing a model-sized output limit does not change what happens when a budget runs out. */
class BudgetExhaustedStillExit3Test {
    @TempDir Path dir;

    @Test
    void aRunThatSpendsItsBudgetStopsWithExitThreeAndKeepsTheFirstAnswer() throws Exception {
        try (var server = new RecordingModelServer(r -> RecordingModelServer.Answer.say("ok"))) {
            Path file = dir.resolve("out-of-budget.loom");
            Files.writeString(
                    file,
                    "provider Fake { use: openai_compatible base_url: \""
                            + server.baseUrl()
                            + "\" }\nbudget { calls: 1 }\n"
                            + "agent A { model: \"Fake/m\" system: \"You are A.\" }\n"
                            + "workflow Main() {\n delegate \"one\" to A -> a\n delegate \"two\" to A -> b\n}\n");

            var result = FieldCli.weave("run", file.toString());

            assertThat(result.exit()).isEqualTo(3);
            assertThat(server.requests()).hasSize(1);
        }
    }
}
