package io.github.llm4j.loom.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.LLMClient;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Phase 22 R5.2: a variable is replaced where braces say so. The default stays the old behaviour
 * (the bare name too) until the next major version; {@code substitution: braces} makes only {@code
 * {name}} replaced.
 */
class SubstitutionSettingTest {

    private static final String BODY =
            "agent A { model: \"gemini-2.5-flash\" system: \"You help.\" }\n"
                    + "workflow W(out) {\n"
                    + "    delegate \"length-out-of-range: {out} and out\" to A -> r\n"
                    + "    return r\n"
                    + "}\n";

    private static LoomScript parse(String source) {
        return new LoomParser(new Lexer(source).tokenize()).parseScript();
    }

    private static String sent(String source) {
        List<String> prompts = new CopyOnWriteArrayList<>();
        LLMClientFactory factory =
                model ->
                        new LLMClient() {
                            @Override
                            public LLMResponse chat(LLMRequest request) {
                                prompts.add(
                                        request.getMessages()
                                                .get(request.getMessages().size() - 1)
                                                .getContent());
                                return LLMResponse.builder().content("ok").model(model).build();
                            }

                            @Override
                            public Stream<LLMResponse> chatStream(LLMRequest request) {
                                return Stream.empty();
                            }
                        };
        HarnessExecutor executor = new HarnessExecutor(parse(source), new ToolRegistry(), factory);
        executor.initialize();
        executor.executeWorkflow("W", Map.of("out", "7"));
        return prompts.get(0);
    }

    @Test
    void theDefaultIsLegacyAndReplacesTheBareWordToo() {
        assertThat(parse(BODY).getSubstitution()).isEqualTo("legacy");
        assertThat(sent(BODY)).contains("length-7-of-range: 7 and 7");
    }

    @Test
    void braceModeReplacesOnlyTheBracedName() {
        String braces = "substitution: braces\n" + BODY;

        assertThat(parse(braces).getSubstitution()).isEqualTo("braces");
        assertThat(sent(braces)).contains("length-out-of-range: 7 and out");
    }

    @Test
    void legacyCanBeWrittenOutAndAnythingElseIsRefusedNamingTheChoices() {
        assertThat(parse("substitution: legacy\n" + BODY).getSubstitution()).isEqualTo("legacy");
        assertThatThrownBy(() -> parse("substitution: curly\n" + BODY))
                .hasMessageContaining("substitution")
                .hasMessageContaining("braces")
                .hasMessageContaining("legacy");
    }

    @Test
    void theWordSubstitutionStillWorksAsAName() {
        LoomScript script =
                parse(
                        "agent substitution { model: \"gemini-2.5-flash\" }\nworkflow W(substitution) {\n delegate \"x\" to substitution -> r\n}\n");

        assertThat(script.getSubstitution()).isEqualTo("legacy");
    }

    /**
     * Every weave command loads a script through the loader, which copies what the script declares.
     */
    @Test
    void theSettingSurvivesTheLoaderThatEveryCommandUses(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Path file =
                java.nio.file.Files.writeString(
                        dir.resolve("main.loom"), "substitution: braces\n" + BODY);

        assertThat(new LoomLoader().load(file.toString()).getSubstitution()).isEqualTo("braces");
    }

    @Test
    void anImportedFilesSettingIsNotImposedOnTheFileThatImportsIt(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Files.writeString(
                dir.resolve("lib.loom"),
                "substitution: braces\nagent L { model: \"gemini-2.5-flash\" }\n");
        java.nio.file.Path main =
                java.nio.file.Files.writeString(
                        dir.resolve("main.loom"),
                        "import \"lib.loom\"\n" + BODY.replace("agent A", "agent B"));

        assertThat(new LoomLoader().load(main.toString()).getSubstitution()).isEqualTo("legacy");
    }
}
