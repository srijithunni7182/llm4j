package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.guide.Guide;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Chapter 16 (cost and model choice) and the harness skill's cost reference travel with the jar,
 * are linked from where they are needed, and their Loom checks.
 */
class GuideChapter16Test {

    static final Path GUIDE = Path.of("../../../docs/guide");
    static final Path SKILLS = Path.of("../../../.claude/skills");

    private static String chapter(String file) throws Exception {
        return Files.readString(GUIDE.resolve(file));
    }

    @Test
    void chapter16IsListedOnceAndTravelsInsideTheJar() throws Exception {
        assertThat(Guide.find("16")).isPresent();
        assertThat(Guide.find("cost-and-model-choice")).isPresent();
        assertThat(Guide.pages().stream().filter(p -> p.name().equals("16")).count()).isEqualTo(1);
        assertThat(Guide.read("guide/16-cost-and-model-choice.md"))
                .contains(chapter("16-cost-and-model-choice.md"));
    }

    @Test
    void chapter16SaysHowToSuggestAModelAndHowToCostOne() throws Exception {
        String c = chapter("16-cost-and-model-choice.md");
        assertThat(c)
                .contains("Do not quote a price from memory")
                .contains("ClassifierComparison")
                .contains("flow.prices")
                .contains("within 2 points")
                .contains("budget")
                .contains("samples: 3")
                .contains("shared_prompt")
                .contains("intentclassify")
                .contains("## Gate");
        // the measured examples it quotes are the ones in the study, not invented
        assertThat(c).contains("$0.042").contains("$0.10").contains("$4 / $20");
    }

    @Test
    void theOtherChaptersAndTheSkillsPointToIt() throws Exception {
        assertThat(chapter("README.md")).contains("16-cost-and-model-choice.md");
        assertThat(chapter("09-go-live.md")).contains("16-cost-and-model-choice.md");
        assertThat(chapter("10-best-practices.md")).contains("16-cost-and-model-choice.md");
        assertThat(Files.readString(SKILLS.resolve("llm4j-workflow-guide/SKILL.md")))
                .contains("docs/guide/16-cost-and-model-choice.md")
                .contains("never quote a price from memory");
    }

    @Test
    void theHarnessSkillHasACostReferenceThatTravelsWithIt() throws Exception {
        assertThat(Guide.HARNESS_REFERENCES).contains("cost-and-models.md");
        Path ref = SKILLS.resolve("llm4j-harness-design/references/cost-and-models.md");
        String text = Files.readString(ref);
        assertThat(text)
                .contains("Do not quote a price from memory")
                .contains("The procedure")
                .contains("For the design record");
        // text only, and standalone: it points at no repository path
        assertThat(text).doesNotContain("docs/guide").doesNotContain("](../");
        assertThat(Files.readString(SKILLS.resolve("llm4j-harness-design/SKILL.md")))
                .contains("references/cost-and-models.md");
    }

    private static final Pattern CHECKED =
            Pattern.compile("<!-- weave check -->\\s*```loom\\n(.*?)```", Pattern.DOTALL);

    @Test
    void everyMarkedLoomBlockInChapter16PassesWeaveCheck(@TempDir Path dir) throws Exception {
        List<String> failures = new ArrayList<>();
        int seen = 0;
        Matcher m = CHECKED.matcher(chapter("16-cost-and-model-choice.md"));
        while (m.find()) {
            seen++;
            Path script = dir.resolve("block" + seen + ".loom");
            Files.writeString(script, m.group(1));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PrintStream old = System.out;
            System.setOut(new PrintStream(out, true));
            int code;
            try {
                code = WeaveCLI.commandLine().execute("check", script.toString(), "--no-env");
            } finally {
                System.setOut(old);
            }
            if (code != 0) failures.add("block " + seen + ":\n" + out);
        }
        assertThat(seen).isEqualTo(1);
        assertThat(failures).isEmpty();
    }
}
