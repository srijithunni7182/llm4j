package io.github.llm4j.loom.depth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.agent.prompt.MarkdownFolderPromptRegistry;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.execution.ScriptValidator;
import io.github.llm4j.loom.explain.ScriptExplainer;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.loom.prompt.PromptCatalog;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code shared_prompt}: text every agent of the workflow is told first. */
class SharedPromptTest {

    @TempDir Path dir;

    private static LoomScript parse(String source) {
        return new LoomParser(new Lexer(source).tokenize()).parseScript();
    }

    private PromptCatalog catalog() {
        return new PromptCatalog(
                new MarkdownFolderPromptRegistry(dir.resolve("prompts")), Map.of());
    }

    private void promptFile(String name, String text) throws IOException {
        Files.createDirectories(dir.resolve("prompts"));
        Files.writeString(dir.resolve("prompts").resolve(name), text);
    }

    private static final String AGENTS =
            """
            agent Plain { model: "m" system: "You are Plain." }
            agent Tooled { model: "m" system: "You are Tooled." tools: [calculator] }
            workflow Main() {
                delegate "one" to Plain -> a_text
                delegate "two" to Tooled -> b_text
            }
            """;

    private List<String> systemPromptsSeen(String script, boolean withCatalog) {
        Harness h = new Harness(dir);
        var e =
                h.executor(
                        script,
                        x -> {
                            if (withCatalog) x.setPromptCatalog(catalog());
                        });
        e.initialize();
        e.executeWorkflow("Main", Map.of());
        return h.requests.stream().map(r -> r.getMessages().get(0).getContent()).toList();
    }

    // ---- syntax ----

    @Test
    void theTextOrAPromptFileIsNamedOnce() {
        assertThat(
                        parse(
                                        "shared_prompt: \"All of us work for Acme.\"\nagent A { model: \"m\" }")
                                .getSharedPrompt())
                .isEqualTo("All of us work for Acme.");
        LoomScript byFile =
                parse("shared_prompt: prompt \"house-rules\"\nagent A { model: \"m\" }");
        assertThat(byFile.getSharedPromptRef()).isEqualTo("house-rules");
        assertThat(byFile.getSharedPrompt()).isNull();
        assertThatThrownBy(() -> parse("shared_prompt: \"a\"\nshared_prompt: \"b\""))
                .hasMessageContaining("Only one shared_prompt");
        assertThatThrownBy(() -> parse("shared_prompt: 5"))
                .hasMessageContaining("shared_prompt: \"text for every agent\"");
        assertThatThrownBy(() -> parse("shared_prompt: \"  \"")).hasMessageContaining("empty");
    }

    @Test
    void sharedPromptStaysUsableAsAName() {
        LoomScript s =
                parse(
                        "agent A { model: \"m\" } workflow W() { delegate \"x\" to A -> shared_prompt\n note \"{shared_prompt}\" }");
        assertThat(s.getSharedPrompt()).isNull();
    }

    // ---- what every agent runs ----

    @Test
    void everyAgentStartsWithTheSharedTextAndThenItsOwn() {
        List<String> seen =
                systemPromptsSeen("shared_prompt: \"HOUSE RULES: be brief.\"\n" + AGENTS, false);
        assertThat(seen).hasSize(2);
        for (String system : seen) assertThat(system).startsWith("HOUSE RULES: be brief.\n\n");
        assertThat(seen.get(0)).contains("You are Plain.");
        assertThat(seen.get(1)).contains("You are Tooled.").contains("calculator");
        assertThat(seen.get(0)).doesNotContain("You are Tooled.");
    }

    @Test
    void theSharedStartIsTheSameBytesForEveryAgent() {
        List<String> seen =
                systemPromptsSeen(
                        "shared_prompt: \"  HOUSE RULES: be brief.  \"\n" + AGENTS, false);
        String sharedStart = "HOUSE RULES: be brief.\n\n";
        assertThat(seen.get(0)).startsWith(sharedStart);
        assertThat(seen.get(1)).startsWith(sharedStart);
    }

    @Test
    void aPromptFileCanBeTheSharedText() throws IOException {
        promptFile("house-rules.md", "FROM THE FILE: never promise refunds.");
        List<String> seen =
                systemPromptsSeen("shared_prompt: prompt \"house-rules\"\n" + AGENTS, true);
        assertThat(seen)
                .allSatisfy(
                        s -> assertThat(s).startsWith("FROM THE FILE: never promise refunds.\n\n"));
    }

    @Test
    void withoutSharedPromptNothingChanges() {
        List<String> seen = systemPromptsSeen(AGENTS, false);
        assertThat(seen.get(0)).startsWith("You are Plain.");
    }

    // ---- checking ----

    private List<ScriptValidator.Problem> problems(String source, PromptCatalog prompts) {
        ScriptValidator.Context context =
                new ScriptValidator.Context()
                        .prompts(prompts)
                        .registeredTools(java.util.Set.of("calculator"));
        return new ScriptValidator().validate(parse(source), context);
    }

    @Test
    void aPromptFileThatIsNotThereIsAnErrorThatSaysWhatToCreate() throws IOException {
        promptFile("other.md", "x");
        var found = problems("shared_prompt: prompt \"house-rules\"\n" + AGENTS, catalog());
        assertThat(found)
                .anyMatch(
                        p ->
                                p.severity() == ScriptValidator.Severity.ERROR
                                        && p.message().contains("house-rules")
                                        && p.message().contains("no file"));
        var noFolder = problems("shared_prompt: prompt \"house-rules\"\n" + AGENTS, null);
        assertThat(noFolder)
                .anyMatch(
                        p ->
                                p.severity() == ScriptValidator.Severity.ERROR
                                        && p.message().contains("needs prompt files"));
    }

    @Test
    void theSharedPromptFileCountsAsUsed() throws IOException {
        promptFile("house-rules.md", "x");
        var found = problems("shared_prompt: prompt \"house-rules\"\n" + AGENTS, catalog());
        assertThat(found).noneMatch(p -> p.message().contains("not used by any agent"));
    }

    // ---- what it means for the rest ----

    @Test
    void changingTheSharedTextMakesEveryAgentADifferentAgentToALadder() {
        var a =
                io.github.llm4j.loom.autonomy.AgentIdentity.of(
                        parse(
                                "shared_prompt: \"one\"\nagent X { model: \"m\" }\ndecision D { proposed by: X choices: a, b }\n"),
                        decision("one"),
                        dir);
        var b =
                io.github.llm4j.loom.autonomy.AgentIdentity.of(
                        parse(
                                "shared_prompt: \"two\"\nagent X { model: \"m\" }\ndecision D { proposed by: X choices: a, b }\n"),
                        decision("two"),
                        dir);
        assertThat(a).isNotEqualTo(b);
    }

    private io.github.llm4j.loom.ast.DecisionDef decision(String shared) {
        return parse(
                        "shared_prompt: \""
                                + shared
                                + "\"\nagent X { model: \"m\" }\ndecision D { proposed by: X choices: a, b }\n")
                .getDecisions()
                .get(0);
    }

    @Test
    void explainSaysEveryAgentIsToldFirst() {
        String text =
                ScriptExplainer.explain(
                        parse("shared_prompt: \"Be brief.\"\n" + AGENTS), "t.loom", null);
        assertThat(text).contains("Every agent is first told: an inline text (9 characters)");
        assertThat(
                        ScriptExplainer.explain(
                                parse("shared_prompt: prompt \"house\"\n" + AGENTS),
                                "t.loom",
                                null))
                .contains("prompt file \"house\"");
    }
}
