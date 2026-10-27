package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/**
 * The reference for the commands is written from the commands, so these tests only have to keep it
 * complete: every command has a summary and a place, the file in the repository is the one the jar
 * writes, and the examples written by hand name options the command has.
 */
class ManPageTest {

    private static final Path ROOT = Path.of("../../..");

    private static String run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        CommandLine cli = WeaveCLI.commandLine();
        ManCommand man = (ManCommand) cli.getSubcommands().get("man").getCommand();
        man.out = new PrintStream(out, true, StandardCharsets.UTF_8);
        man.err = new PrintStream(err, true, StandardCharsets.UTF_8);
        int code = cli.execute(args);
        return code
                + "\n"
                + out.toString(StandardCharsets.UTF_8)
                + err.toString(StandardCharsets.UTF_8);
    }

    @Test
    void everyCommandHasASummaryAndAPlaceInTheList() {
        CommandLine cli = WeaveCLI.commandLine();
        var placed = new ArrayList<String>();
        ManPage.groups().values().forEach(placed::addAll);
        for (var e : cli.getSubcommands().entrySet()) {
            if (!e.getKey().equals(e.getValue().getCommandName())) continue;
            assertThat(ManPage.page(e.getValue(), "weave " + e.getKey()).summary())
                    .as("the summary of weave " + e.getKey())
                    .isNotBlank();
            assertThat(placed)
                    .as("weave " + e.getKey() + " is in man/groups.txt")
                    .contains(e.getKey());
        }
        for (String name : placed)
            assertThat(cli.getSubcommands())
                    .as("man/groups.txt names weave " + name)
                    .containsKey(name);
    }

    @Test
    void aPageHasTheSectionsOfAManPage() {
        String page = run("man", "generate");
        assertThat(page).startsWith("0\n");
        assertThat(page)
                .contains(
                        "NAME\n",
                        "SYNOPSIS\n",
                        "DESCRIPTION\n",
                        "OPTIONS\n",
                        "EXAMPLES\n",
                        "EXIT STATUS\n",
                        "SEE ALSO\n")
                .contains("--plan-model=<planModel>")
                .contains("weave generate [OPTIONS] [<description>]");
    }

    @Test
    void aCommandUnderACommandHasItsOwnPage() {
        assertThat(run("man", "observe", "flush"))
                .contains("weave observe flush")
                .startsWith("0\n");
    }

    @Test
    void anUnknownCommandIsRefusedWithTheNearestName() {
        String out = run("man", "genrate");
        assertThat(out).startsWith("2\n").contains("Did you mean 'generate'?");
    }

    @Test
    void theListNamesEveryCommandUnderTheWayItIsMet() {
        String list = run("man");
        assertThat(list)
                .contains(
                        "Start here",
                        "Describe it and build it",
                        "generate",
                        "doctor",
                        "capture",
                        "deploy");
        assertThat(list).doesNotContain("\nOther\n");
    }

    @Test
    void theReferenceInTheRepositoryIsTheOneTheJarWrites() throws Exception {
        String written = ManCommand.reference(WeaveCLI.commandLine());
        Path file = ROOT.resolve("docs/COMMAND_REFERENCE.md");
        assertThat(file).as("run scripts/make-command-reference.sh").exists();
        assertThat(Files.readString(file))
                .as(
                        "docs/COMMAND_REFERENCE.md is out of date: run scripts/make-command-reference.sh")
                .isEqualTo(written);
    }

    @Test
    void theOptionsInTheExamplesAreOptionsTheCommandHas() throws Exception {
        CommandLine cli = WeaveCLI.commandLine();
        Pattern option = Pattern.compile("(?<![\\w-])(--[a-z][a-z-]*)");
        int examples = 0;
        try (var files = Files.list(Path.of("src/main/resources/man/pages"))) {
            for (Path f : files.sorted().toList()) {
                String name = f.getFileName().toString().replace(".txt", "");
                List<String> parts = new ArrayList<>(List.of(name.split("-")));
                parts.remove(0); // "weave"
                CommandLine at = cli;
                for (String part : parts) at = at.getSubcommands().get(part);
                assertThat(at).as("a command for " + f).isNotNull();
                var page = ManPage.extras(name.replace('-', ' '));
                String text =
                        String.join(
                                "\n",
                                page.getOrDefault("EXAMPLES", ""),
                                page.getOrDefault("DESCRIPTION", ""));
                for (String line : text.split("\\R")) {
                    if (!line.startsWith("weave ")) continue;
                    examples++;
                    Matcher m = option.matcher(line);
                    while (m.find()) {
                        String used = m.group(1);
                        boolean known = false;
                        for (var o : at.getCommandSpec().options())
                            for (String n : o.names()) if (n.equals(used)) known = true;
                        // an option of the command a line runs, which may be another one
                        if (!known) known = anyCommandHas(cli, used);
                        assertThat(known)
                                .as(f.getFileName() + ": " + line + " uses " + used)
                                .isTrue();
                    }
                }
            }
        }
        assertThat(examples).isGreaterThan(30);
    }

    private static boolean anyCommandHas(CommandLine cli, String option) {
        for (CommandLine sub : cli.getSubcommands().values()) {
            for (var o : sub.getCommandSpec().options())
                for (String n : o.names()) if (n.equals(option)) return true;
            if (anyCommandHas(sub, option)) return true;
        }
        return false;
    }
}
