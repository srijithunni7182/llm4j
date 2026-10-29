package io.github.llm4j.loom.field;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.cli.FieldCli;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * F03: with a budget active and no {@code per_call}, every call used to be capped at 1,024 output
 * tokens and the run still said "success". F03 also needs the opposite guarantee: when a provider
 * does cut an answer at its limit, the run summary says so and names the agent.
 */
class NoSilentClipTest {
    @TempDir Path dir;

    private Path script(RecordingModelServer server, String budget, String agents)
            throws IOException {
        Path file = dir.resolve("clip.loom");
        Files.writeString(
                file,
                "provider Fake { use: openai_compatible base_url: \""
                        + server.baseUrl()
                        + "\" }\n"
                        + budget
                        + agents
                        + "workflow Main() {\n"
                        + "  delegate \"one\" to A -> a\n"
                        + "  delegate \"two\" to B -> b\n"
                        + "  note \"done\"\n}\n");
        return file;
    }

    private static final String AGENTS =
            "agent A { model: \"Fake/m\" system: \"You are A.\" }\n"
                    + "agent B { model: \"Fake/m\" system: \"You are B.\" }\n";

    @Test
    void aBudgetDoesNotQuietlyCapEveryAnswerAtOneThousandTokens() throws Exception {
        try (var server =
                new RecordingModelServer(r -> RecordingModelServer.Answer.say("word ".repeat(10)))) {
            FieldCli.weave(
                    "run", script(server, "budget { tokens: 100000 calls: 20 }\n", AGENTS).toString());

            assertThat(server.requests()).isNotEmpty();
            assertThat(server.requests().get(0).outputLimit()).isGreaterThan(1024);
        }
    }

    @Test
    void withoutABudgetNoLimitIsSent() throws Exception {
        try (var server =
                new RecordingModelServer(r -> RecordingModelServer.Answer.say("word ".repeat(10)))) {
            FieldCli.weave("run", script(server, "", AGENTS).toString());

            assertThat(server.requests().get(0).outputLimit()).isEqualTo(-1);
        }
    }

    @Test
    void anAnswerCutAtTheLimitIsNamedInTheRunSummary() throws Exception {
        try (var server =
                new RecordingModelServer(
                        r ->
                                r.allText().contains("You are B")
                                        ? RecordingModelServer.Answer.cut("half a sent")
                                        : RecordingModelServer.Answer.say("whole"))) {
            var result = FieldCli.weave("run", script(server, "", AGENTS).toString());

            assertThat(result.out()).contains("1 answer was cut at the output limit: B (1)");
        }
    }

    @Test
    void aRunWithNoCutAnswerSaysNothingAboutIt() throws Exception {
        try (var server = new RecordingModelServer(r -> RecordingModelServer.Answer.say("whole"))) {
            var result = FieldCli.weave("run", script(server, "", AGENTS).toString());

            assertThat(result.out()).doesNotContain("cut at the output limit");
        }
    }
}
