package io.github.llm4j.loom.field;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.cli.FieldCli;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * F05: a run in which steps used their {@code on_failure} fallback ended "completed successfully"
 * and saved a stub. A degraded run says which steps fell back; a run with no model contribution at
 * all is a failure; a script can ask for exit code 7 on a degraded run.
 */
class DegradedRunTest {
    @TempDir Path dir;

    private Path script(RecordingModelServer server) throws Exception {
        Path file = dir.resolve("degraded.loom");
        Files.writeString(
                file,
                "provider Fake { use: openai_compatible base_url: \""
                        + server.baseUrl()
                        + "\" }\n"
                        + "agent Researcher { model: \"Fake/m\" system: \"You research.\" }\n"
                        + "agent Writer { model: \"Fake/m\" system: \"You write.\" }\n"
                        + "workflow Main() {\n"
                        + "  delegate \"alpha question\" to Researcher -> facts"
                        + " on_failure { set facts = \"none\" }\n"
                        + "  delegate \"beta question\" to Writer -> draft"
                        + " on_failure { set draft = \"stub\" }\n"
                        + "  note \"saved {draft}\"\n}\n");
        return file;
    }

    private static RecordingModelServer.Answer rejectOnly(
            RecordingModelServer.Recorded r, String... markers) {
        for (String marker : markers)
            if (r.allText().contains(marker)) return RecordingModelServer.reject();
        return RecordingModelServer.Answer.say("fine");
    }

    @Test
    void aRunThatUsedAFallbackNamesTheStepsThatFellBackAndStillExitsZero() throws Exception {
        try (var server = new RecordingModelServer(r -> rejectOnly(r, "beta question"))) {
            var result = FieldCli.weave("run", script(server).toString());

            assertThat(result.exit()).isZero();
            assertThat(result.out())
                    .contains("Degraded: Writer took its fallback (1 of 2 model steps)");
        }
    }

    @Test
    void exitDegradedMakesThatRunExitSeven() throws Exception {
        try (var server = new RecordingModelServer(r -> rejectOnly(r, "beta question"))) {
            var result = FieldCli.weave("run", script(server).toString(), "--exit-degraded");

            assertThat(result.exit()).isEqualTo(7);
        }
    }

    @Test
    void aRunWithNoFallbackSaysNothingAboutDegradation() throws Exception {
        try (var server = new RecordingModelServer(r -> rejectOnly(r))) {
            var result = FieldCli.weave("run", script(server).toString(), "--exit-degraded");

            assertThat(result.exit()).isZero();
            assertThat(result.out()).doesNotContain("Degraded");
        }
    }

    @Test
    void aRunWhoseEveryModelStepFellBackIsAFailureNotASuccess() throws Exception {
        try (var server = new RecordingModelServer(r -> rejectOnly(r, "alpha", "beta"))) {
            var result = FieldCli.weave("run", script(server).toString());

            assertThat(result.exit()).isEqualTo(1);
            assertThat(result.out() + result.err())
                    .contains("all model steps failed")
                    .doesNotContain("completed successfully");
        }
    }
}
