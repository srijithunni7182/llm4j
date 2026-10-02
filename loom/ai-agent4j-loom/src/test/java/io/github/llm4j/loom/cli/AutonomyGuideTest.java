package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

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

/** The guide's "Earned Autonomy" section is checked against the code, and the prompt, READMEs and editor mention the feature (spec loom-earned-autonomy R9). */
class AutonomyGuideTest {

    static final String HEADING = "### Earned Autonomy";

    @TempDir
    Path dir;

    static String section() throws Exception {
        String guide = Files.readString(Path.of("LOOM_GUIDE.md"), StandardCharsets.UTF_8);
        int from = guide.indexOf(HEADING);
        assertThat(from).as("the guide has the section").isGreaterThanOrEqualTo(0);
        return guide.substring(from, guide.indexOf("\n### ", from + 10));
    }

    @Test
    @Tag("EA-V9.1")
    void everyLoomBlockInTheSectionLoadsAndPassesTheChecks() throws Exception {
        List<String> blocks = new ArrayList<>();
        Matcher m = Pattern.compile("```loom\\n(.*?)```", Pattern.DOTALL).matcher(section());
        while (m.find()) blocks.add(m.group(1));
        assertThat(blocks).isNotEmpty();
        for (String block : blocks) {
            var executor = new ScriptedRun(dir).executor(block);
            try {
                executor.initialize();
            } catch (RuntimeException e) {
                throw new AssertionError("this guide example doesn't load:\n" + block + "\n→ " + e.getMessage(), e);
            }
        }
    }

    @Test
    @Tag("EA-V9.3")
    void everyCommandAndOptionTheGuideShowsExists() throws Exception {
        CommandLine cli = new CommandLine(new WeaveCLI()).addSubcommand(new AutonomyCommands()).addSubcommand(new ReplayCommand())
                .addSubcommand(new TravelCommands.Fork());
        CommandLine autonomy = cli.getSubcommands().get("autonomy");
        String text = section().replace("\\\n", " ");
        Matcher commands = Pattern.compile("(?m)^weave (autonomy|replay) ?(\\w+)? (.*)$").matcher(text);
        int seen = 0;
        while (commands.find()) {
            seen++;
            CommandLine sub = commands.group(1).equals("replay") ? cli.getSubcommands().get("replay") : autonomy.getSubcommands().get(commands.group(2));
            assertThat(sub).as("weave " + commands.group(1) + " " + commands.group(2)).isNotNull();
            Matcher options = Pattern.compile("(--[a-z-]+)").matcher(commands.group(3));
            while (options.find()) assertThat(sub.getCommandSpec().findOption(options.group(1))).as(commands.group(0) + " has " + options.group(1)).isNotNull();
        }
        assertThat(seen).isGreaterThanOrEqualTo(9);
        for (String name : List.of("status", "history", "promote", "demote", "approve", "reject", "freeze", "unfreeze", "outcome")) {
            assertThat(autonomy.getSubcommands()).containsKey(name);
        }
        for (String option : List.of("--until", "--script", "--at", "--effects")) {
            assertThat(cli.getSubcommands().get("fork").getCommandSpec().findOption(option)).as("weave fork has " + option).isNotNull();
        }
    }

    @Test
    @Tag("EA-V9.3")
    void theGuideStatesWhatItDoesNotDoAndTheRulesPlainly() throws Exception {
        String text = section();
        assertThat(text).contains("**What it does not do.**").contains("It does not learn").contains("It does not certify compliance");
        assertThat(text).contains("the lower end of the 95% Wilson interval").contains("`never go above suggest` is the default");
        for (String level : List.of("watch", "suggest", "act")) assertThat(text).contains("`" + level + "`");
    }

    @Test
    @Tag("EA-V9.2")
    void thePromptTheReadmesAndTheEditorMentionDecisionDecideAndAutonomy() throws Exception {
        for (String file : List.of("LOOM_PROMPT.md", "README.md", "../README.md", "../vscode-loom/src/lsp/server.ts")) {
            Path p = Path.of(file);
            if (!Files.exists(p)) continue;
            String text = Files.readString(p);
            assertThat(text).as(file).contains("decision").contains("decide").contains("autonomy");
        }
        assertThat(Files.readString(Path.of("../vscode-loom/syntaxes/loom.tmLanguage.json"))).contains("decision").contains("decide");
    }

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Test
    @Tag("EA-V9.4")
    void noSampleWorkflowWasAddedForTheFeature() throws Exception {
        try (var samples = Files.list(Path.of("samples"))) {
            assertThat(samples.map(p -> p.getFileName().toString()).sorted().toList()).containsExactly("boardroom", "content_factory", "digest");
        }
        try (var files = Files.walk(Path.of("samples"))) {
            assertThat(files.filter(Files::isRegularFile).map(p -> read(p)).noneMatch(t -> t.contains("decision "))).isTrue();
        }
    }
}
