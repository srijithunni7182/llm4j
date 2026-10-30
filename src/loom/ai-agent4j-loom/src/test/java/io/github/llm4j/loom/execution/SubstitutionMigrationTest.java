package io.github.llm4j.loom.execution;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Phase 22 R5.3: what would change if {@code substitution: braces} were set, and the rewrite of the
 * cases that were plainly meant as variables. A bare word inside another word is left to the
 * person.
 */
class SubstitutionMigrationTest {

    private static final String SOURCE =
            "agent A { model: \"gemini-2.5-flash\" }\n"
                    + "workflow W(topic) {\n"
                    + "    delegate \"Write about topic for a general reader\" to A -> draft\n"
                    + "    note \"length-topic-of-range checked\"\n"
                    + "    halt \"the draft was refused\"\n"
                    + "}\n";

    private static LoomScript parse(String s) {
        return new LoomParser(new Lexer(s).tokenize()).parseScript();
    }

    @Test
    void thePreviewNamesEachStringThatWouldChangeWithItsVariableAndPosition() {
        List<SubstitutionMigration.Change> changes =
                SubstitutionMigration.preview(parse(SOURCE), SOURCE);

        assertThat(changes)
                .extracting(
                        c ->
                                c.line()
                                        + ":"
                                        + c.variable()
                                        + ":"
                                        + c.position()
                                        + ":"
                                        + c.standalone())
                .contains(
                        "3:topic:delegate payload:true",
                        "4:topic:note message:false",
                        "5:draft:halt message:true");
    }

    @Test
    void aBracedNameIsNotAChange() {
        String braced = SOURCE.replace("Write about topic", "Write about {topic}");

        assertThat(SubstitutionMigration.preview(parse(braced), braced))
                .noneMatch(c -> c.line() == 3 && c.variable().equals("topic"));
    }

    @Test
    void theRewriteWrapsOnlyTheStandaloneWordsAndLeavesTheOthersAlone() {
        String rewritten =
                SubstitutionMigration.rewrite(
                        SOURCE, SubstitutionMigration.preview(parse(SOURCE), SOURCE));

        assertThat(rewritten).contains("Write about {topic} for a general reader");
        assertThat(rewritten).contains("the {draft} was refused");
        assertThat(rewritten).contains("length-topic-of-range checked");
    }

    @Test
    void theRewriteIsTheOnlyThingThatChangesAndKeepsTheLineCount() {
        String rewritten =
                SubstitutionMigration.rewrite(
                        SOURCE, SubstitutionMigration.preview(parse(SOURCE), SOURCE));

        assertThat(rewritten.lines().count()).isEqualTo(SOURCE.lines().count());
        assertThat(parse(rewritten).getWorkflows()).hasSize(1);
    }

    @Test
    void theDiffShowsEachChangedLineBeforeAndAfter() {
        String rewritten =
                SubstitutionMigration.rewrite(
                        SOURCE, SubstitutionMigration.preview(parse(SOURCE), SOURCE));
        String diff = SubstitutionMigration.diff(SOURCE, rewritten);

        assertThat(diff).contains("line 3").contains("- ").contains("+ ").contains("{topic}");
        assertThat(diff).doesNotContain("line 2");
    }

    @Test
    void aScriptWithNothingToChangeHasAnEmptyPreviewAndRewritesToItself() {
        String clean =
                "agent A { model: \"gemini-2.5-flash\" }\nworkflow W(topic) {\n delegate \"Write about {topic}\" to A -> d\n return d\n}\n";

        assertThat(SubstitutionMigration.preview(parse(clean), clean)).isEmpty();
        assertThat(SubstitutionMigration.rewrite(clean, List.of())).isEqualTo(clean);
    }
}
