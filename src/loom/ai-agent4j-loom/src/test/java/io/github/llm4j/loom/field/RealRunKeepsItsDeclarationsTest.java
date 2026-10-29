package io.github.llm4j.loom.field;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.cli.FieldCli;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 17, F01, F02 and F66: what a script declares must reach the real run. These go through the
 * real {@code weave} command line against a recording fake provider on loopback, because the
 * earlier unit test parsed the script directly and never went through {@code LoomLoader}, which is
 * where the declarations were lost.
 */
class RealRunKeepsItsDeclarationsTest {
    private static final String CANARY = "CANARY-7QX";

    @TempDir Path dir;

    private Path script(String name, String text) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, text);
        return file;
    }

    private static String provider(RecordingModelServer server) {
        return "provider Fake { use: openai_compatible base_url: \"" + server.baseUrl() + "\" }\n";
    }

    // P1 (F01): every request of every agent form carries the shared prompt.
    @Test
    void aSharedPromptTextReachesTheRequestOfARealRun() throws Exception {
        try (var server = new RecordingModelServer(r -> RecordingModelServer.Answer.say("ok"))) {
            Path file =
                    script(
                            "plain.loom",
                            provider(server)
                                    + "shared_prompt: \"Begin every answer with "
                                    + CANARY
                                    + ".\"\n"
                                    + "agent A { model: \"Fake/m\" system: \"You are A.\" }\n"
                                    + "workflow Main() { delegate \"hi\" to A -> out\n note \"{out}\" }\n");
            FieldCli.weave("run", file.toString());
            assertThat(server.requests()).isNotEmpty();
            assertThat(server.requests().get(0).allText()).contains(CANARY);
        }
    }

    // P1 (F01) file form, and P4 (F02): the same script through `weave check`.
    private Path promptFolderScript(RecordingModelServer server) throws IOException {
        Files.createDirectories(dir.resolve("prompts"));
        Files.writeString(dir.resolve("prompts/house-rules.md"), "Begin every answer with " + CANARY + ".");
        Files.writeString(dir.resolve("prompts/helper.md"), "You are a helper.");
        return script(
                "files.loom",
                provider(server)
                        + "prompts: \"prompts\"\nshared_prompt: prompt \"house-rules\"\n"
                        + "agent A { model: \"Fake/m\" prompt: \"helper\" }\n"
                        + "workflow Main() { delegate \"hi\" to A -> out\n note \"{out}\" }\n");
    }

    @Test
    void aSharedPromptFileReachesTheRequestOfARealRun() throws Exception {
        try (var server = new RecordingModelServer(r -> RecordingModelServer.Answer.say("ok"))) {
            FieldCli.weave("run", promptFolderScript(server).toString());
            assertThat(server.requests()).isNotEmpty();
            assertThat(server.requests().get(0).allText()).contains(CANARY);
        }
    }

    @Test
    void checkDoesNotCallANamedSharedPromptUnused() throws Exception {
        try (var server = new RecordingModelServer(r -> RecordingModelServer.Answer.say("ok"))) {
            var result = FieldCli.weave("check", promptFolderScript(server).toString());
            assertThat(result.out() + result.err()).doesNotContain("not used by any agent");
        }
    }

    // Found while tracing F01 (not in the register; recorded as F66): LoomScript.merge also dropped
    // the script-level supervise and intervention blocks, so a loaded script was not supervised.
    @Test
    void superviseAndInterventionBlocksSurviveLoading() throws Exception {
        try (var server = new RecordingModelServer(r -> RecordingModelServer.Answer.say("ok"))) {
            Path file =
                    script(
                            "supervised.loom",
                            provider(server)
                                    + "intervention { timeout: retry }\n"
                                    + "agent A { model: \"Fake/m\" system: \"You are A.\" }\n"
                                    + "supervise A { when steps > 15 { cancel use partial } }\n"
                                    + "workflow Main() { delegate \"hi\" to A -> out\n note \"{out}\" }\n");
            var result = FieldCli.weave("explain", file.toString());
            assertThat(result.out()).contains("is watched by rules").contains("stopped for timeout");
        }
    }
}
