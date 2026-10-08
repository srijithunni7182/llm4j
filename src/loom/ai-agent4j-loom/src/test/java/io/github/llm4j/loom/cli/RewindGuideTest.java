package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.generic.support.ScriptedRun;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** The guide's "Checkpoints, Rewind and Fork" section is checked against the code: every example loads, every command and option exists. */
class RewindGuideTest {

    static final Path GUIDE = Path.of("LOOM_GUIDE.md");
    static final String HEADING = "### Checkpoints, Rewind and Fork";

    @TempDir
    Path dir;

    static String section() throws Exception {
        String guide = Files.readString(GUIDE, StandardCharsets.UTF_8);
        int from = guide.indexOf(HEADING);
        assertThat(from).as("the guide has the section").isGreaterThanOrEqualTo(0);
        return guide.substring(from, guide.indexOf("\n### ", from + 10));
    }

    @Test
    @Tag("RW-V9.3")
    void everyLoomBlockInTheSectionLoadsAndPassesTheChecks() throws Exception {
        List<String> blocks = new ArrayList<>();
        Matcher m = Pattern.compile("```loom\\n(.*?)```", Pattern.DOTALL).matcher(section());
        while (m.find()) blocks.add(m.group(1));
        assertThat(blocks).hasSizeGreaterThanOrEqualTo(2);
        for (String block : blocks) {
            HarnessExecutor executor = new ScriptedRun(dir).executor(block);
            try {
                executor.initialize();
            } catch (RuntimeException e) {
                throw new AssertionError("this guide example doesn't load:\n" + block + "\n→ " + e.getMessage(), e);
            }
        }
    }

    @Test
    @Tag("RW-V9.3")
    void everyCommandAndOptionTheGuideShowsExists() throws Exception {
        CommandLine cli = new CommandLine(new WeaveCLI())
                .addSubcommand(new WeaveCLI.RunCommand()).addSubcommand(new WeaveCLI.ResumeCommand())
                .addSubcommand(new TravelCommands.Timeline()).addSubcommand(new TravelCommands.Rewind())
                .addSubcommand(new TravelCommands.Reset()).addSubcommand(new TravelCommands.Fork());
        String text = section().replace("\\\n", " ");
        Matcher commands = Pattern.compile("(?m)^weave (timeline|rewind|reset|fork) (.*)$").matcher(text);
        int seen = 0;
        while (commands.find()) {
            seen++;
            CommandLine sub = cli.getSubcommands().get(commands.group(1));
            Matcher options = Pattern.compile("(--[a-z-]+)").matcher(commands.group(2));
            while (options.find()) {
                assertThat(sub.getCommandSpec().findOption(options.group(1))).as("weave " + commands.group(1) + " has " + options.group(1)).isNotNull();
            }
        }
        assertThat(seen).isGreaterThanOrEqualTo(5);
        for (String option : List.of("--stop-at", "--max-rewinds")) {
            assertThat(cli.getSubcommands().get("run").getCommandSpec().findOption(option)).as("weave run has " + option).isNotNull();
        }
        assertThat(cli.getSubcommands().get("resume").getCommandSpec().findOption("--stop-at")).isNotNull();
        for (String option : List.of("--trigger", "--force", "--reason", "--effects", "--resume")) {
            assertThat(cli.getSubcommands().get("rewind").getCommandSpec().findOption(option)).as("weave rewind has " + option).isNotNull();
        }
        assertThat(cli.getSubcommands().get("fork").getCommandSpec().findOption("--allow-drift")).isNotNull();
    }

    @Test
    @Tag("RW-V9.3")
    @Tag("RW-V4.10")
    void theGuideStatesTheRuleAndTheLimitsPlainly() throws Exception {
        String text = section();
        assertThat(text).contains("A model call is identified by where it is *and which attempt it belongs to*, so it runs again. A side effect, or a person's answer, is identified by where it is *and what it is*, so an identical one is never repeated.");
        assertThat(text).contains("memory { facts }").contains("does **not** undo");
        assertThat(text).contains("older `weave` builds, from before this feature, cannot read");
        for (String policy : List.of("ask first", "keep", "repeat")) assertThat(text).contains("`" + policy + "`");
    }

    @Test
    @Tag("RW-V9.3")
    void thePromptTheReadmesAndTheEditorMentionTheNewStatements() throws Exception {
        for (String file : List.of("LOOM_PROMPT.md", "README.md", "../README.md", "../../../README.md", "../vscode-loom/src/lsp/server.ts")) {
            Path p = Path.of(file);
            if (!Files.exists(p)) continue;
            String text = Files.readString(p);
            if (file.equals("../../../README.md")) continue; // the repository README does not list statements
            assertThat(text).as(file).contains("checkpoint").contains("rewind");
        }
    }

    @Test
    @Tag("RW-V2.4")
    void maxRewindsFromTheCommandLineCapsTheWholeRun() throws Exception {
        java.io.File script = dir.resolve("again.loom").toFile();
        Files.writeString(script.toPath(), """
                agent Writer { model: "m" system: "You are Writer." }
                workflow W() {
                    checkpoint a
                    delegate "Write" to Writer -> w
                    rewind to a at most 9 times
                }
                """);
        java.io.ByteArrayOutputStream err = new java.io.ByteArrayOutputStream();
        var env = new WeaveEnv(m -> new io.github.llm4j.LLMClient() {
            @Override public io.github.llm4j.model.LLMResponse chat(io.github.llm4j.model.LLMRequest r) {
                return io.github.llm4j.model.LLMResponse.builder().content(ScriptedRun.done("ok")).model("m").tokenUsage(1, 1, 2).build();
            }
            @Override public java.util.stream.Stream<io.github.llm4j.model.LLMResponse> chatStream(io.github.llm4j.model.LLMRequest r) {
                return java.util.stream.Stream.of(chat(r));
            }
        }, message -> "yes", new java.io.PrintStream(new java.io.ByteArrayOutputStream()), new java.io.PrintStream(err, true), java.time.Clock.systemUTC(), d -> { },
                c -> new io.github.llm4j.loom.trigger.system.CommandRunner.Result(0, "", ""), List.of("weave"), System::getenv);

        int code = WeaveCLI.run(script, null, "W", java.util.Map.of(), null, null, null, null, dir.resolve("run"), null, false, false, null, null, 2, env);

        assertThat(code).isEqualTo(1);
        assertThat(err.toString()).contains("most it may (2)");
    }
}
