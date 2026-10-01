package io.github.llm4j.loom.generic.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The places a user or an editor learns about tool kinds all know the six generic ones. */
class KindsMentionedTest {

    static final List<String> KINDS = List.of("webhook", "email", "http", "file", "shell", "sql");

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }

    private static void mentionsAll(String name, String text) {
        for (String kind : KINDS) assertThat(text).as(name + " mentions use: " + kind).containsPattern("\\b" + kind + "\\b");
    }

    @Test
    @Tag("V10.6")
    void readmesPromptEditorAndGapAnalysisNameTheKinds() throws Exception {
        mentionsAll("the repository README", read("../../README.md"));
        mentionsAll("the Loom README", read("../README.md"));
        mentionsAll("the Loom module README", read("README.md"));
        mentionsAll("LOOM_PROMPT.md", read("LOOM_PROMPT.md"));
        mentionsAll("the VS Code hover for use:", read("../vscode-loom/src/lsp/server.ts"));
        assertThat(read("../../.kiro/specs/loom-capability-parity/gap-analysis.md")).contains("webhook").contains("loom-generic-tools");
    }

    @Test
    @Tag("V10.2")
    void theEditorKnowsTheNewOptionsToo() throws Exception {
        String server = read("../vscode-loom/src/lsp/server.ts");
        for (String option : List.of("on_unknown", "idempotency", "allow_to", "allow_paths", "allow_private", "allow_interpreters", "unattended", "env_pass", "outbox")) {
            assertThat(server).as("hover for " + option).contains("    " + option + ":\n");
        }
    }

    @Test
    @Tag("V10.3")
    void theSampleIsComplete() throws Exception {
        for (String file : List.of("core.loom", "digest.loom", "digest-slack.loom", "run.sh")) {
            assertThat(Path.of("samples/digest", file)).exists();
        }
        assertThat(Files.isExecutable(Path.of("samples/digest/run.sh"))).isTrue();
        assertThat(read("samples/digest/digest.loom")).contains("schedule Morning");
    }
}
