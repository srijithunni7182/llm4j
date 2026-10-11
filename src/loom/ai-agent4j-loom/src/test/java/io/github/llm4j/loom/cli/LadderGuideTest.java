package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.execution.ScriptValidator;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** The guide's "Ladders" section is checked against the code: every example loads with its imports and passes the checks, every command and option exists. */
@Tag("L8")
class LadderGuideTest {

    static final String HEADING = "### Ladders: Earned Autonomy as a Policy You Write";

    @TempDir
    Path dir;

    static String section() throws Exception {
        String guide = Files.readString(Path.of("LOOM_GUIDE.md"), StandardCharsets.UTF_8);
        int from = guide.indexOf(HEADING);
        assertThat(from).as("the guide has the section").isGreaterThanOrEqualTo(0);
        return guide.substring(from, guide.indexOf("\n### ", from + 10));
    }

    @Test
    void everyLoomBlockInTheSectionLoadsWithItsImportsAndPassesTheChecks() throws Exception {
        List<String> blocks = new ArrayList<>();
        Matcher m = Pattern.compile("```loom\\n(.*?)```", Pattern.DOTALL).matcher(section());
        while (m.find()) blocks.add(m.group(1));
        assertThat(blocks).hasSizeGreaterThanOrEqualTo(2);
        int n = 0;
        for (String block : blocks) {
            Path file = dir.resolve("example" + (++n) + ".loom");
            Files.writeString(file, block);
            LoomScript script = new LoomLoader().load(file.toString());
            List<ScriptValidator.Problem> errors = new ScriptValidator().validate(script, new ScriptValidator.Context().registeredTools(Set.of())).stream()
                    .filter(p -> p.severity() == ScriptValidator.Severity.ERROR).toList();
            assertThat(errors).as("this guide example does not pass the checks:\n" + block).isEmpty();
            assertThat(script.getLadders()).isNotEmpty();
        }
    }

    @Test
    void everyCommandAndOptionTheGuideShowsExists() throws Exception {
        CommandLine cli = WeaveCLI.commandLine();
        String text = section().replace("\\\n", " ");
        Matcher commands = Pattern.compile("(?m)^weave (autonomy|ladder) (\\w+) (.*)$").matcher(text);
        int seen = 0;
        while (commands.find()) {
            seen++;
            CommandLine group = cli.getSubcommands().get(commands.group(1));
            CommandLine sub = group.getSubcommands().get(commands.group(2));
            assertThat(sub).as("weave " + commands.group(1) + " " + commands.group(2)).isNotNull();
            Matcher options = Pattern.compile("(--[a-z-]+)").matcher(commands.group(3));
            while (options.find()) assertThat(sub.getCommandSpec().findOption(options.group(1))).as(commands.group(0) + " has " + options.group(1)).isNotNull();
        }
        assertThat(seen).isGreaterThanOrEqualTo(6);
    }

    @Test
    void theGuideStatesWhatItDoesNotDoAndTheGuaranteesPlainly() throws Exception {
        String text = section();
        assertThat(text).contains("**What it does not do.**").contains("It does not learn").contains("It does not certify compliance")
                .contains("**What it guarantees.**").contains("missing data never lets an agent climb");
        assertThat(text).contains("20 of 20 is not 100% evidence");
    }

    @Test
    void theThreeModesAndTheStandardMeasuresAreNamedAsTheCodeNamesThem() throws Exception {
        String text = section();
        for (var mode : io.github.llm4j.loom.ast.RungDef.Mode.values()) assertThat(text).contains("`" + mode.word() + "`");
        var standard = io.github.llm4j.loom.execution.LibraryImports.load("loom/autonomy");
        for (var measure : standard.getMeasures()) {
            if (!measure.getName().equals("untouched")) assertThat(text).as("the guide names the standard measure " + measure.getName()).contains(measure.getName());
        }
    }

    @Test
    void theEditorAndTheRelatedDocumentsMentionLadders() throws Exception {
        assertThat(Files.readString(Path.of("LOOM_PROMPT.md"))).contains("ladder").contains("proposes:");
        assertThat(Files.readString(Path.of("README.md"))).contains("`ladder` / `rung` / `measure`");
        assertThat(Files.readString(Path.of("../../../llms.txt"))).contains("**Ladders:**");
        assertThat(Files.readString(Path.of("../vscode-loom/src/lsp/server.ts"))).contains("**ladder**").contains("**measure**").contains("**climb when**");
        assertThat(Files.readString(Path.of("../vscode-loom/syntaxes/loom.tmLanguage.json"))).contains("ladder|rung|measure");
    }
}
