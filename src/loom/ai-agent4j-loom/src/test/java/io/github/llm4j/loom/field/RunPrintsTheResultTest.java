package io.github.llm4j.loom.field;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.cli.FieldCli;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** F07: {@code weave run} printed {@code note} lines but never what a workflow {@code return}s. */
class RunPrintsTheResultTest {
    @TempDir Path dir;

    private Path script(RecordingModelServer server, String ending) throws Exception {
        Path file = dir.resolve("result.loom");
        Files.writeString(
                file,
                "provider Fake { use: openai_compatible base_url: \""
                        + server.baseUrl()
                        + "\" }\nagent A { model: \"Fake/m\" system: \"You are A.\" }\n"
                        + "workflow Main() {\n delegate \"hi\" to A -> out\n "
                        + ending
                        + "\n}\n");
        return file;
    }

    @Test
    void theReturnedValueIsPrintedUnderAHeading() throws Exception {
        try (var server = new RecordingModelServer(r -> RecordingModelServer.Answer.say("the article"))) {
            var result = FieldCli.weave("run", script(server, "return out").toString());

            assertThat(result.out()).contains("Result:").contains("the article");
        }
    }

    @Test
    void quietLeavesTheResultOut() throws Exception {
        try (var server = new RecordingModelServer(r -> RecordingModelServer.Answer.say("the article"))) {
            var result = FieldCli.weave("run", script(server, "return out").toString(), "--quiet");

            assertThat(result.out()).doesNotContain("Result:");
        }
    }

    @Test
    void aWorkflowThatReturnsNothingAndSaysNothingIsToldSo() throws Exception {
        try (var server = new RecordingModelServer(r -> RecordingModelServer.Answer.say("x"))) {
            var result = FieldCli.weave("run", script(server, "set done = \"yes\"").toString());

            assertThat(result.out()).contains("The workflow returned nothing.");
        }
    }
}
