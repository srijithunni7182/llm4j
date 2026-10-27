package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * A developer who works with a coding agent in an editor: {@code weave guide --install-skill --for}
 * writes the instruction file each agent reads, keeps what the person wrote in it, and says only
 * what the tool really has.
 */
class AgentInstructionsTest {

    @TempDir Path dir;

    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    final ByteArrayOutputStream err = new ByteArrayOutputStream();

    int guide(String... args) {
        GuideCommand c = new GuideCommand();
        new CommandLine(c).parseArgs(args);
        WeaveEnv env =
                new WeaveEnv(
                        m -> null,
                        q -> "yes",
                        new PrintStream(out, true, StandardCharsets.UTF_8),
                        new PrintStream(err, true, StandardCharsets.UTF_8),
                        Clock.systemUTC(),
                        d -> {},
                        c2 ->
                                new io.github.llm4j.loom.trigger.system.CommandRunner.Result(
                                        0, "", ""),
                        List.of("weave"),
                        k -> null);
        return GuideCommand.guide(c, env);
    }

    @Test
    void theDefaultIsStillTheClaudeSkillsAndNothingElse() throws Exception {
        assertThat(guide("--install-skill", dir.toString())).isZero();
        assertThat(dir.resolve(".claude/skills/llm4j-workflow-guide/SKILL.md")).exists();
        assertThat(dir.resolve("AGENTS.md")).doesNotExist();
    }

    @Test
    void agentsWritesTheCrossAgentFileWithTheRulesAndWhereToLook() throws Exception {
        assertThat(guide("--install-skill", dir.toString(), "--for", "agents")).isZero();
        String text = Files.readString(dir.resolve("AGENTS.md"));
        assertThat(text)
                .startsWith("<!-- loom:begin")
                .contains("weave man", "weave guide loom", "Never write a key", "weave next")
                .endsWith("<!-- loom:end -->\n");
        assertThat(dir.resolve(".claude")).doesNotExist();
    }

    @Test
    void whatThePersonWroteInTheFileStaysAndIsNotReplacedBehindTheirBack() throws Exception {
        Path file = dir.resolve("AGENTS.md");
        Files.writeString(file, "# Our rules\n\nUse tabs.\n");
        assertThat(guide("--install-skill", dir.toString(), "--for", "agents")).isZero();
        String first = Files.readString(file);
        assertThat(first).startsWith("# Our rules\n\nUse tabs.\n\n<!-- loom:begin");

        // again: nothing to do without --force
        assertThat(guide("--install-skill", dir.toString(), "--for", "agents")).isEqualTo(2);
        assertThat(Files.readString(file)).isEqualTo(first);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("already in").contains("--force");

        // text after the block, and a block that was edited, with --force: only the block changes
        Files.writeString(
                file,
                first.replace("Never write a key", "Write keys freely") + "\n## After\nkeep me\n");
        assertThat(guide("--install-skill", dir.toString(), "--for", "agents", "--force")).isZero();
        String after = Files.readString(file);
        assertThat(after)
                .contains("Use tabs.", "keep me", "Never write a key")
                .doesNotContain("Write keys freely");
        assertThat(after.split("<!-- loom:begin", -1)).hasSize(2);
    }

    @Test
    void copilotAndCursorGetTheFileTheyRead() throws Exception {
        assertThat(guide("--install-skill", dir.toString(), "--for", "copilot,cursor")).isZero();
        assertThat(Files.readString(dir.resolve(".github/copilot-instructions.md")))
                .contains("weave man");
        String cursor = Files.readString(dir.resolve(".cursor/rules/loom.mdc"));
        assertThat(cursor).startsWith("---\ndescription:").contains("globs:", "alwaysApply: false");
    }

    @Test
    void allIsTheSkillsAndEveryFile() throws Exception {
        assertThat(guide("--install-skill", dir.toString(), "--for", "all")).isZero();
        assertThat(dir.resolve(".claude/skills/llm4j-loom-deployer/SKILL.md")).exists();
        assertThat(dir.resolve("AGENTS.md")).exists();
        assertThat(dir.resolve(".github/copilot-instructions.md")).exists();
        assertThat(dir.resolve(".cursor/rules/loom.mdc")).exists();
    }

    @Test
    void anAgentThatDoesNotExistIsRefused() {
        assertThat(guide("--install-skill", dir.toString(), "--for", "vim")).isEqualTo(2);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("--for is claude, agents");
        assertThat(dir.toFile().list()).isEmpty();
    }

    // ---- what the instructions say about weave is true

    private static final Pattern COMMAND = Pattern.compile("(?<![\\w/.-])weave ([a-z][a-z-]*)");
    private static final Pattern OPTION = Pattern.compile("(?<![\\w-])(--[a-z][a-z-]*)");

    private static void options(CommandLine cli, Set<String> out) {
        cli.getCommandSpec().options().forEach(o -> out.addAll(List.of(o.names())));
        cli.getSubcommands().values().forEach(s -> options(s, out));
    }

    @Test
    void everyCommandAndOptionTheInstructionsAndTheSkillsNameExists() throws Exception {
        CommandLine cli = WeaveCLI.commandLine();
        Set<String> commands = new HashSet<>(cli.getSubcommands().keySet());
        Set<String> options = new HashSet<>();
        options(cli, options);
        Path root = Path.of("../../..");
        List<Path> files =
                List.of(
                        root.resolve("docs/guide/AGENT_INSTRUCTIONS.md"),
                        root.resolve(".claude/skills/llm4j-workflow-guide/SKILL.md"),
                        root.resolve(".claude/skills/llm4j-loom-deployer/SKILL.md"),
                        root.resolve(".claude/skills/llm4j-harness-design/SKILL.md"));
        int seen = 0;
        for (Path f : files) {
            for (String line : Files.readString(f).split("\\R")) {
                Matcher m = COMMAND.matcher(line);
                boolean weave = false;
                while (m.find()) {
                    String word = m.group(1);
                    // prose such as "weave a workflow" is not a command; only code spans are
                    // checked
                    if (!line.contains("`weave " + word)
                            && !line.startsWith("weave " + word)
                            && !line.contains("$ weave " + word)) continue;
                    weave = true;
                    seen++;
                    assertThat(commands).as(f.getFileName() + ": " + line.strip()).contains(word);
                }
                if (!weave) continue;
                Matcher o = OPTION.matcher(line);
                while (o.find())
                    assertThat(options)
                            .as(f.getFileName() + ": " + line.strip() + " uses " + o.group(1))
                            .contains(o.group(1));
            }
        }
        assertThat(seen).isGreaterThan(20);
    }
}
