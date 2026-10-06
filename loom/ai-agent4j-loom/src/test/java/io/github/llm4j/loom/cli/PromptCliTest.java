package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.LLMClient;
import io.github.llm4j.loom.prompt.PromptSettings;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** Prompt files from the command line: {@code --prompts}, {@code --prompt}, check, audit, run. R2.5, R3, R4 of loom-prompt-files. */
class PromptCliTest {

    @TempDir Path dir;

    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    final List<String> systemPrompts = new ArrayList<>();

    static final String SCRIPT = """
            agent A { model: "test/m" prompt: "researcher" }
            workflow Main() { delegate "go" to A -> out }
            """;

    Path script(String source) throws Exception {
        return Files.writeString(dir.resolve("main.loom"), source);
    }

    void prompt(String relative, String text) throws Exception {
        Path f = dir.resolve("prompts").resolve(relative);
        Files.createDirectories(f.getParent());
        Files.writeString(f, text);
    }

    WeaveEnv env() {
        io.github.llm4j.loom.execution.LLMClientFactory models = model -> new LLMClient() {
            @Override public LLMResponse chat(LLMRequest request) {
                systemPrompts.add(request.getMessages().get(0).getContent());
                return LLMResponse.builder().content("```json\n{\"thought\":\"t\",\"final_answer\":\"ok\"}\n```").model(model).tokenUsage(1, 1, 2).build();
            }
            @Override public Stream<LLMResponse> chatStream(LLMRequest request) { return Stream.of(chat(request)); }
        };
        PrintStream p = new PrintStream(out, true);
        return new WeaveEnv(models, m -> "yes", p, p, Clock.systemUTC(), d -> { },
                c -> new io.github.llm4j.loom.trigger.system.CommandRunner.Result(0, "", ""), List.of("weave"), k -> "key");
    }

    // ── the options ─────────────────────────────────────────────────────────

    @Test
    void theOptionsGiveAFolderAndRepeatablePins() {
        PromptOptions o = new PromptOptions();
        new CommandLine(o).parseArgs("--prompts", "p", "--prompt", "a@v2", "--prompt", "b@v1");

        PromptSettings s = o.settings();

        assertThat(s.dir()).isEqualTo(Path.of("p"));
        assertThat(s.pins()).containsExactlyInAnyOrderEntriesOf(Map.of("a", "v2", "b", "v1"));
    }

    @Test
    void aBadPinIsRefusedWithAMessageAndExitTwoMaterial() {
        PromptOptions o = new PromptOptions();
        new CommandLine(o).parseArgs("--prompt", "researcher");

        assertThat(o.apply(env())).isNull();
        assertThat(out.toString()).contains("--prompt needs a version: researcher@v1");
    }

    // ── check ───────────────────────────────────────────────────────────────

    @Test
    void checkPassesWhenThePromptFileIsBesideTheScript() throws Exception {
        prompt("researcher.md", "You research.");

        assertThat(WeaveCLI.check(script(SCRIPT).toFile(), null, false, env())).isZero();
        assertThat(out.toString()).contains("ready to run");
    }

    @Test
    void checkSaysWhatIsMissingAndWhereToPutIt() throws Exception {
        prompt("reseacher.md", "typo");

        assertThat(WeaveCLI.check(script(SCRIPT).toFile(), null, false, env())).isEqualTo(2);
        assertThat(out.toString()).contains("prompt researcher has no file").contains("did you mean reseacher?");
    }

    @Test
    void checkWithNoFolderSaysHowToSupplyOne() throws Exception {
        assertThat(WeaveCLI.check(script(SCRIPT).toFile(), null, false, env())).isEqualTo(2);
        assertThat(out.toString()).contains("needs a prompt registry or prompt files").contains("--prompts");
    }

    @Test
    void aFolderFromTheCommandLineIsUsedInsteadOfTheConvention() throws Exception {
        Path other = Files.createDirectories(dir.resolve("elsewhere"));
        Files.writeString(other.resolve("researcher.md"), "from elsewhere");

        assertThat(WeaveCLI.check(script(SCRIPT).toFile(), null, false, env().withPrompts(new PromptSettings(other, Map.of())))).isZero();
    }

    @Test
    void aFolderThatDoesNotExistIsNamed() throws Exception {
        assertThat(WeaveCLI.check(script(SCRIPT).toFile(), null, false, env().withPrompts(new PromptSettings(dir.resolve("nope"), Map.of())))).isEqualTo(2);
        assertThat(out.toString()).contains("nope does not exist");
    }

    @Test
    void checkWarnsAboutAFileNoAgentUsesAndAboutThingsItIgnores() throws Exception {
        prompt("researcher.md", "x");
        prompt("spare/v1.md", "x");
        prompt("notes.txt", "x");

        assertThat(WeaveCLI.check(script(SCRIPT).toFile(), null, false, env())).isZero();

        assertThat(out.toString()).contains("⚠").contains("prompt spare is not used by any agent (spare").contains("notes.txt: ignored").contains("2 warnings");
    }

    @Test
    void checkFailsOnAPromptFileItRefuses() throws Exception {
        prompt("researcher.md", "x".repeat(70_000));

        assertThat(WeaveCLI.check(script(SCRIPT).toFile(), null, false, env())).isEqualTo(2);
        assertThat(out.toString()).contains("researcher.md: refused: 70000 bytes is over the 65536 byte limit");
    }

    // ── run ─────────────────────────────────────────────────────────────────

    private int run(WeaveEnv env, Path journal) throws Exception {
        return WeaveCLI.run(dir.resolve("main.loom").toFile(), null, "Main", Map.of(), null, null, null, null, journal, null, false, env);
    }

    @Test
    void runUsesThePromptFileAndAPinPicksAnotherVersion() throws Exception {
        prompt("researcher/v1.md", "FIRST WORDING");
        prompt("researcher/v2.md", "SECOND WORDING");
        script(SCRIPT);

        assertThat(run(env(), null)).isZero();
        assertThat(run(env().withPrompts(new PromptSettings(null, Map.of("researcher", "v1"))), null)).isZero();

        assertThat(systemPrompts.get(0)).contains("SECOND WORDING");
        assertThat(systemPrompts.get(1)).contains("FIRST WORDING").doesNotContain("SECOND WORDING");
    }

    @Test
    void aJournaledRunKeepsItsPinsSoAResumeRunsTheSameWording() throws Exception {
        prompt("researcher/v1.md", "FIRST WORDING");
        prompt("researcher/v2.md", "SECOND WORDING");
        script(SCRIPT);
        Path journal = dir.resolve("run-1");

        assertThat(run(env().withPrompts(new PromptSettings(null, Map.of("researcher", "v1"))), journal)).isZero();

        RunSpec saved = RunSpec.read(journal);
        assertThat(saved.promptPins()).isEqualTo(Map.of("researcher", "v1"));
        assertThat(saved.prompts().pins()).isEqualTo(Map.of("researcher", "v1"));
    }

    @Test
    void aJournaledRunRecordsWhichPromptEachAgentRanButNotItsText() throws Exception {
        prompt("researcher/v1.md", "PRIVATE WORDING");
        script(SCRIPT);
        Path journal = dir.resolve("run-3");

        run(env(), journal);

        String manifest = Files.readString(journal.resolve("prompts.json"));
        assertThat(manifest).contains("\"agent\" : \"A\"").contains("\"version\" : \"v1\"").contains("\"hash\"").doesNotContain("PRIVATE WORDING");
    }

    @Test
    void aRunWithNoPromptSettingsSavesNone() throws Exception {
        prompt("researcher.md", "x");
        script(SCRIPT);
        Path journal = dir.resolve("run-2");

        run(env(), journal);

        assertThat(Files.readString(journal.resolve("run.json"))).doesNotContain("promptPins").doesNotContain("promptsDir");
    }

    // ── audit ───────────────────────────────────────────────────────────────

    @Test
    void auditListsWhichPromptEachAgentRunsWithAHashAndNeverTheText() throws Exception {
        prompt("researcher/v1.md", "SECRET SAUCE ONE");
        prompt("researcher/v2.md", "SECRET SAUCE TWO");
        AuditCommand c = new AuditCommand();
        c.script = script(SCRIPT).toFile();
        c.format = "md";
        c.failOn = "none";

        AuditCommand.audit(c, env());

        assertThat(out.toString()).contains("## Prompts").contains("| A (line 1) | researcher | v2 |").doesNotContain("SECRET SAUCE");
        out.reset();
        c.format = "json";
        AuditCommand.audit(c, env());
        assertThat(out.toString()).contains("\"prompts\"").contains("\"version\" : \"v2\"").contains("\"hash\"").doesNotContain("SECRET SAUCE");
    }

    @Test
    void auditOfAScriptWithoutPromptFilesHasNoPromptsSection() throws Exception {
        AuditCommand c = new AuditCommand();
        c.script = script("agent A { model: \"m\" system: \"s\" }\nworkflow Main() { delegate \"x\" to A -> r }").toFile();
        c.format = "md";
        c.failOn = "none";

        AuditCommand.audit(c, env());

        assertThat(out.toString()).doesNotContain("## Prompts");
    }
}
