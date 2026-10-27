package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/**
 * The documents about review say only what the command does: every {@code weave review} line in
 * them uses options the command has, every rule they name exists, and the guide for rule authors
 * names only commands and operations that exist.
 */
class ReviewDocsTest {

    static final Path REPO = Path.of("../../..");

    static String read(String path) throws IOException {
        return Files.readString(REPO.resolve(path));
    }

    static Set<String> reviewOptions() {
        Set<String> names = new HashSet<>();
        new CommandLine(new ReviewCommand())
                .getCommandSpec()
                .options()
                .forEach(o -> names.addAll(List.of(o.names())));
        return names;
    }

    static List<String> reviewLines(String text) {
        List<String> lines = new ArrayList<>();
        for (String l : text.split("\n")) {
            String t = l.strip();
            if (t.startsWith("weave review ") || t.startsWith("`weave review ")) lines.add(t);
        }
        return lines;
    }

    @Test
    void everyReviewCommandLineInTheDocsUsesRealOptions() throws IOException {
        Set<String> real = reviewOptions();
        Pattern flag = Pattern.compile("(?<![\\w-])(--[a-z][a-z-]*)");
        for (String doc :
                List.of(
                        "docs/review/README.md",
                        "docs/review/WRITING_RULES.md",
                        "src/loom/ai-agent4j-loom/LOOM_GUIDE.md",
                        ".claude/skills/llm4j-workflow-guide/SKILL.md")) {
            for (String line : reviewLines(read(doc))) {
                Matcher m = flag.matcher(line.replaceAll("#.*$", ""));
                while (m.find())
                    assertThat(real).as(doc + ": " + line).contains(m.group(1));
            }
        }
    }

    @Test
    void theRuleIdsTheGuidesNameExist() throws IOException {
        Set<String> ids = new HashSet<>();
        io.github.llm4j.loom.review.Reviews.catalog().all().forEach(r -> ids.add(r.id()));
        Pattern id = Pattern.compile("\\bL[A-Z]\\d{2}\\b");
        for (String doc :
                List.of("docs/review/README.md", "src/loom/ai-agent4j-loom/LOOM_GUIDE.md")) {
            Matcher m = id.matcher(read(doc));
            while (m.find()) assertThat(ids).as(doc + " names " + m.group()).contains(m.group());
        }
    }

    @Test
    void theReviewSectionIsInTheGuideAndPointsAtTheOtherPages() throws IOException {
        String guide = read("src/loom/ai-agent4j-loom/LOOM_GUIDE.md");
        assertThat(guide)
                .contains("### Reviewing a Workflow (`review`)")
                .contains("docs/review/README.md")
                .contains("docs/review/WRITING_RULES.md");
        assertThat(Files.isRegularFile(REPO.resolve("docs/review/rules/index.md"))).isTrue();
    }

    @Test
    void theAuthorGuideNamesOnlyRealCommandsAndOperations() throws IOException {
        String text = read("docs/review/WRITING_RULES.md");
        Set<String> subcommands = new HashSet<>();
        new CommandLine(new RulesCommand()).getSubcommands().keySet().forEach(subcommands::add);
        Matcher m = Pattern.compile("weave rules (\\w+)").matcher(text);
        while (m.find()) assertThat(subcommands).as(m.group()).contains(m.group(1));
        for (String op : io.github.llm4j.loom.review.rule.FixTemplate.OPERATIONS)
            assertThat(text).as("operation " + op).contains(op);
        for (String anchor : List.of("statementEnd", "statementStart", "beforeBrace", "lineEnd", "block", "fileStart"))
            assertThat(io.github.llm4j.loom.review.rule.FixTemplate.ANCHORS).contains(anchor);
        for (io.github.llm4j.loom.review.rule.Op op : io.github.llm4j.loom.review.rule.Op.values())
            if (!op.quantifier() && op != io.github.llm4j.loom.review.rule.Op.COUNT)
                assertThat(text).as("operator " + op.key()).contains(op.key());
    }
}
