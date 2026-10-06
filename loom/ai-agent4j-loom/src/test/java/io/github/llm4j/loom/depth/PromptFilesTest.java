package io.github.llm4j.loom.depth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.agent.prompt.MarkdownFolderPromptRegistry;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.loom.prompt.PromptCatalog;
import io.github.llm4j.loom.prompt.PromptFolderResolver;
import io.github.llm4j.loom.prompt.PromptFolderResolver.Source;
import io.github.llm4j.loom.prompt.PromptRef;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Prompt files in scripts: requirements R1 to R3 and R6 of .kiro/specs/loom-prompt-files. */
class PromptFilesTest {

    @TempDir Path dir;

    private Path prompt(String relative, String text) throws IOException {
        Path file = dir.resolve("prompts").resolve(relative);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, text);
    }

    private static LoomScript parse(String source) {
        return new LoomParser(new Lexer(source).tokenize()).parseScript();
    }

    private PromptCatalog catalog(Map<String, String> pins) {
        return new PromptCatalog(new MarkdownFolderPromptRegistry(dir.resolve("prompts")), pins);
    }

    // ── syntax ──────────────────────────────────────────────────────────────

    @Test
    void r2_1_anAgentNamesAPromptWithOrWithoutAVersion() {
        LoomScript s = parse("""
                agent A { model: "m" prompt: "researcher" }
                agent B { model: "m" prompt: "writer@v2" }
                agent C { model: "m" system_template: "old" }
                """);

        assertThat(s.getAgents()).extracting(a -> a.getPromptRef()).containsExactly("researcher", "writer@v2", "old");
        assertThat(s.getAgents().get(0).getPrompt()).isEqualTo("researcher");
        assertThat(s.getAgents().get(2).getPrompt()).isNull();
    }

    @Test
    void r2_1_aMalformedReferenceIsAParseErrorThatShowsTheForm() {
        for (String bad : List.of("Researcher", "a/b", "../x", "a@2", "a@v", "a@v1@v2", "")) {
            assertThatThrownBy(() -> parse("agent A { model: \"m\" prompt: \"" + bad + "\" }"))
                    .as(bad).hasMessageContaining("looks like \"researcher\" or \"researcher@v2\"");
        }
    }

    @Test
    void r2_4_aScriptNamesItsFolderOnce() {
        assertThat(parse("prompts: \"./prompts\"\nagent A { model: \"m\" }").getPromptsDir()).isEqualTo("./prompts");
        assertThatThrownBy(() -> parse("prompts: \"a\"\nprompts: \"b\"")).hasMessageContaining("Only one prompts: folder");
    }

    @Test
    void promptAndPromptsStayUsableAsOrdinaryNames() {
        LoomScript s = parse("""
                agent A { model: "m" }
                workflow W() {
                    delegate "x" to A -> prompt
                    delegate "{prompt}" to A -> prompts
                    note "{prompts}"
                }
                """);

        assertThat(s.getWorkflows()).hasSize(1);
        assertThat(s.getPromptsDir()).isNull();
    }

    // ── references and pins ─────────────────────────────────────────────────

    @Test
    void referencesParseAndPrintBack() {
        assertThat(PromptRef.parse("researcher")).isEqualTo(new PromptRef("researcher", null));
        assertThat(PromptRef.parse("researcher@v12")).isEqualTo(new PromptRef("researcher", "v12"));
        assertThat(PromptRef.parse("a-b_c@v1").toString()).isEqualTo("a-b_c@v1");
        assertThat(PromptRef.parse("x").pinned()).isFalse();
    }

    @Test
    void r2_5_pinsNeedAVersionAndAgreeWithThemselves() {
        assertThat(PromptCatalog.pins(List.of("a@v2", "b@v1", "a@v2"))).containsExactlyInAnyOrderEntriesOf(Map.of("a", "v2", "b", "v1"));
        assertThatThrownBy(() -> PromptCatalog.pins(List.of("a"))).hasMessageContaining("needs a version");
        assertThatThrownBy(() -> PromptCatalog.pins(List.of("a@v1", "a@v2"))).hasMessageContaining("pins a twice");
        assertThatThrownBy(() -> PromptCatalog.pins(List.of("../a@v1"))).hasMessageContaining("looks like");
    }

    // ── folder choice ───────────────────────────────────────────────────────

    @Test
    void r2_5_theCommandLineBeatsTheScriptWhichBeatsTheConvention() throws IOException {
        Files.createDirectories(dir.resolve("prompts"));
        LoomScript named = parse("prompts: \"./other\"");
        LoomScript plain = parse("agent A { model: \"m\" }");
        Path script = dir.resolve("main.loom");

        var cli = PromptFolderResolver.resolve(named, script, dir.resolve("cli"));
        var fromScript = PromptFolderResolver.resolve(named, script, null);
        var convention = PromptFolderResolver.resolve(plain, script, null);

        assertThat(cli.source()).isEqualTo(Source.COMMAND_LINE);
        assertThat(cli.dir()).isEqualTo(dir.resolve("cli"));
        assertThat(fromScript.source()).isEqualTo(Source.SCRIPT);
        assertThat(fromScript.dir()).isEqualTo(dir.resolve("other"));
        assertThat(convention.source()).isEqualTo(Source.CONVENTION);
        assertThat(convention.dir()).isEqualTo(dir.resolve("prompts"));
    }

    @Test
    void r2_4_withNoFolderAskedForAndNoPromptsBesideTheScriptThereIsNone() {
        assertThat(PromptFolderResolver.resolve(parse("agent A { model: \"m\" }"), dir.resolve("main.loom"), null)).isNull();
    }

    @Test
    void anImportedFileCannotChooseTheFolder() throws IOException {
        Files.writeString(dir.resolve("lib.loom"), "prompts: \"./elsewhere\"\nagent L { model: \"m\" }\n");
        Files.writeString(dir.resolve("main.loom"), "import \"lib.loom\"\nagent A { model: \"m\" }\n");

        assertThat(new LoomLoader().load(dir.resolve("main.loom").toString()).getPromptsDir()).isNull();

        Files.writeString(dir.resolve("main.loom"), "import \"lib.loom\"\nprompts: \"./mine\"\nagent A { model: \"m\" }\n");
        assertThat(new LoomLoader().load(dir.resolve("main.loom").toString()).getPromptsDir()).isEqualTo("./mine");
    }

    // ── what an agent runs ──────────────────────────────────────────────────

    private static final String WORKFLOW = "workflow Main() { delegate \"go\" to A -> out }";

    private String systemPromptSeen(String agent, Map<String, String> pins) {
        Harness h = new Harness(dir);
        var e = h.executor(agent + "\n" + WORKFLOW, x -> x.setPromptCatalog(catalog(pins)));
        e.initialize();
        e.executeWorkflow("Main", Map.of());
        return h.requests.get(0).getMessages().get(0).getContent();
    }

    @Test
    void r2_1_withoutAVersionTheLatestRuns() throws IOException {
        prompt("r/v1.md", "ONE");
        prompt("r/v2.md", "TWO");

        assertThat(systemPromptSeen("agent A { model: \"m\" prompt: \"r\" }", Map.of())).contains("TWO").doesNotContain("ONE");
    }

    @Test
    void r2_1_aVersionInTheScriptIsKeptWhenANewerOneExists() throws IOException {
        prompt("r/v1.md", "ONE");
        prompt("r/v2.md", "TWO");

        assertThat(systemPromptSeen("agent A { model: \"m\" prompt: \"r@v1\" }", Map.of())).contains("ONE").doesNotContain("TWO");
    }

    @Test
    void r2_5_aCommandLinePinBeatsTheScriptsVersion() throws IOException {
        prompt("r/v1.md", "ONE");
        prompt("r/v2.md", "TWO");

        assertThat(systemPromptSeen("agent A { model: \"m\" prompt: \"r@v1\" }", Map.of("r", "v2"))).contains("TWO").doesNotContain("ONE");
    }

    @Test
    void r2_3_theAgentsOwnSystemTextFollowsThePromptFile() throws IOException {
        prompt("r.md", "FROM THE FILE");

        String seen = systemPromptSeen("agent A { model: \"m\" prompt: \"r\" system: \"AND THIS\" }", Map.of());

        assertThat(seen).contains("FROM THE FILE\n\nAND THIS");
    }

    @Test
    void r2_2_systemTemplateIsAnAliasForPrompt() throws IOException {
        prompt("r.md", "FROM THE FILE");

        assertThat(systemPromptSeen("agent A { model: \"m\" system_template: \"r\" }", Map.of())).contains("FROM THE FILE");
    }

    @Test
    void r6_2_promptTextIsNotInterpretedAsLoom() throws IOException {
        prompt("r.md", "agent Evil { model: \"x\" }\nworkflow Boom() { delegate \"x\" to Evil -> y }");

        String seen = systemPromptSeen("agent A { model: \"m\" prompt: \"r\" }", Map.of());

        assertThat(seen).contains("agent Evil");
    }

    // ── checking ────────────────────────────────────────────────────────────

    private String loadError(String agent, Map<String, String> pins, boolean withCatalog) {
        Harness h = new Harness(dir);
        try {
            h.executor(agent, x -> {
                if (withCatalog) x.setPromptCatalog(catalog(pins));
            }).initialize();
            return null;
        } catch (RuntimeException e) {
            return e.getMessage();
        }
    }

    @Test
    void r3_1_aMissingPromptNamesTheNearestIdsAndWhereToPutTheFile() throws IOException {
        prompt("researcher/v1.md", "x");
        prompt("writer.md", "x");

        String message = loadError("agent A { model: \"m\" prompt: \"reseacher\" }", Map.of(), true);

        assertThat(message).contains("prompt reseacher has no file in").contains("did you mean researcher?").contains("reseacher.md");
    }

    @Test
    void r3_1_aMissingVersionListsTheOnesThatExist() throws IOException {
        prompt("r/v1.md", "x");
        prompt("r/v2.md", "x");

        String message = loadError("agent A { model: \"m\" prompt: \"r@v3\" }", Map.of(), true);

        assertThat(message).contains("prompt r has no version v3").contains("it has v1, v2");
    }

    @Test
    void r3_1_aBadCommandLinePinIsReportedAsOne() throws IOException {
        prompt("r/v1.md", "x");

        String message = loadError("agent A { model: \"m\" prompt: \"r\" }", Map.of("r", "v9"), true);

        assertThat(message).contains("no version v9").contains("pinned on the command line");
    }

    @Test
    void r3_3_usingPromptWithNoPromptFilesIsAnErrorThatSaysHowToSupplyThem() {
        String message = loadError("agent A { model: \"m\" prompt: \"r\" }", Map.of(), false);

        assertThat(message).contains("needs a prompt registry or prompt files").contains("prompts/ folder").contains("--prompts");
    }

    @Test
    void r3_1_aPromptThatExistsLoadsCleanly() throws IOException {
        prompt("r.md", "x");

        assertThat(loadError("agent A { model: \"m\" prompt: \"r\" }", Map.of(), true)).isNull();
    }

    // ── the trace ───────────────────────────────────────────────────────────

    @Test
    void r4_2_eachAgentStepSaysWhichPromptAndVersionItRan() throws IOException {
        prompt("r/v1.md", "ONE");
        prompt("r/v2.md", "TWO");
        Harness h = new Harness(dir);
        var e = h.executor("agent A { model: \"m\" prompt: \"r\" }\n" + WORKFLOW + "\nagent B { model: \"m\" system: \"plain\" }",
                x -> x.setPromptCatalog(catalog(Map.of())));
        e.initialize();
        e.executeWorkflow("Main", Map.of());

        var start = h.trace.stream().filter(t -> t.type().equals("delegate_start")).findFirst().orElseThrow();

        assertThat(start.data()).containsEntry("prompt", "r@v2");
        assertThat(start.data().get("promptHash")).asString().hasSize(12);
        assertThat(start.data().toString()).doesNotContain("TWO");
    }

    @Test
    void r4_2_anAgentWithAnInlinePromptHasNoPromptInItsTrace() {
        Harness h = new Harness(dir);
        var e = h.ready("agent A { model: \"m\" system: \"s\" }\n" + WORKFLOW);
        e.executeWorkflow("Main", Map.of());

        var start = h.trace.stream().filter(t -> t.type().equals("delegate_start")).findFirst().orElseThrow();

        assertThat(start.data()).doesNotContainKey("prompt");
    }
}
