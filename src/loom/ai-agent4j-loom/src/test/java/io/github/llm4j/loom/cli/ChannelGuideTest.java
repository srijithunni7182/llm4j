package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.generic.support.ScriptedRun;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** The guide's "Answering from Your Phone" section is checked against the code (spec loom-remote-answers R7). */
class ChannelGuideTest {

    static final String HEADING = "### Answering from Your Phone";

    @TempDir
    Path dir;

    static String section() throws Exception {
        String guide = Files.readString(Path.of("LOOM_GUIDE.md"), StandardCharsets.UTF_8);
        int from = guide.indexOf(HEADING);
        assertThat(from).as("the guide has the section").isGreaterThanOrEqualTo(0);
        return guide.substring(from, guide.indexOf("\n### ", from + 10));
    }

    @Test
    @Tag("RA-V7.1")
    void everyLoomBlockLoadsEveryCommandAndOptionExistsAndTheChannelFileParses() throws Exception {
        List<String> blocks = new ArrayList<>();
        Matcher m = Pattern.compile("```loom\\n(.*?)```", Pattern.DOTALL).matcher(section());
        while (m.find()) blocks.add(m.group(1));
        assertThat(blocks).isNotEmpty();
        for (String block : blocks) {
            try {
                new ScriptedRun(dir).executor(block).initialize();
            } catch (RuntimeException e) {
                throw new AssertionError("this guide example doesn't load:\n" + block + "\n→ " + e.getMessage(), e);
            }
        }

        CommandLine cli = WeaveCLI.commandLine();
        Matcher commands = Pattern.compile("(?m)^weave (\\w+) (.*)$").matcher(section());
        int seen = 0;
        while (commands.find()) {
            seen++;
            CommandLine sub = cli.getSubcommands().get(commands.group(1));
            assertThat(sub).as("weave " + commands.group(1)).isNotNull();
            Matcher options = Pattern.compile("(--[a-z-]+)").matcher(commands.group(2));
            while (options.find()) assertThat(sub.getCommandSpec().findOption(options.group(1))).as(commands.group(0) + " has " + options.group(1)).isNotNull();
        }
        assertThat(seen).isGreaterThanOrEqualTo(6);

        Matcher json = Pattern.compile("```json\\n(.*?)```", Pattern.DOTALL).matcher(section());
        assertThat(json.find()).isTrue();
        Path store = dir.resolve("store");
        Files.createDirectories(store);
        Files.writeString(store.resolve("channel.json"), json.group(1));
        var config = io.github.llm4j.loom.channel.ChannelConfig.load(store, null, Map.of("TELEGRAM_BOT_TOKEN", "t")::get).orElseThrow();
        assertThat(config.chatsFor("support-lead")).containsExactly(123456L, 777888L);
        assertThat(config.remindAtMost()).isEqualTo(3);
        assertThat(config.expire()).hasDays(3);
    }

    @Test
    @Tag("RA-V7.2")
    void theAutonomySectionMentionsTheChatAndTheHelpListsTheCommands() throws Exception {
        String guide = Files.readString(Path.of("LOOM_GUIDE.md"));
        int from = guide.indexOf("### Earned Autonomy");
        assertThat(guide.substring(from, guide.indexOf("\n### ", from + 10))).contains("From your phone").contains("answering-from-your-phone");
        CommandLine cli = WeaveCLI.commandLine();
        for (String name : List.of("answer", "questions")) assertThat(cli.getSubcommands()).containsKey(name);
        for (String name : List.of("run", "resume", "tick", "daemon")) assertThat(cli.getSubcommands().get(name).getCommandSpec().findOption("--ask-via")).as(name).isNotNull();
    }

    @Test
    @Tag("RA-V7.3")
    void noSampleDirectoryWasAdded() throws Exception {
        try (var samples = Files.list(Path.of("samples"))) {
            assertThat(samples.map(p -> p.getFileName().toString()).sorted().toList()).containsExactly("boardroom", "content_factory", "digest");
        }
    }
}
