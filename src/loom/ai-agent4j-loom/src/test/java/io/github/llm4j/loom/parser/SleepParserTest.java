package io.github.llm4j.loom.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.LoopStmt;
import io.github.llm4j.loom.ast.SleepStmt;
import io.github.llm4j.loom.ast.Statement;
import io.github.llm4j.loom.lexer.Lexer;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@code sleep <duration>}: how it is read, and that the word stays usable as a name. */
class SleepParserTest {

    private static LoomScript parse(String source) {
        return new LoomParser(new Lexer(source).tokenize()).parseScript();
    }

    private static List<Statement> body(String statements) {
        return parse("agent A { model: \"m\" }\nworkflow W() {\n" + statements + "\n}").getWorkflows().get(0).getStatements();
    }

    private static SleepStmt only(String statements) {
        List<Statement> all = body(statements);
        assertThat(all).hasSize(1);
        return (SleepStmt) all.get(0);
    }

    @Test
    void readsSecondsMinutesHoursAndDays() {
        assertThat(only("sleep 90s").getDuration()).isEqualTo(Duration.ofSeconds(90));
        assertThat(only("sleep 5m").getDuration()).isEqualTo(Duration.ofMinutes(5));
        assertThat(only("sleep 2h").getDuration()).isEqualTo(Duration.ofHours(2));
        assertThat(only("sleep 1d").getDuration()).isEqualTo(Duration.ofDays(1));
    }

    @Test
    void keepsTheDurationAsWrittenAndAcceptsAString() {
        assertThat(only("sleep 90s").getText()).isEqualTo("90s");
        SleepStmt quoted = only("sleep \"15m\"");
        assertThat(quoted.getDuration()).isEqualTo(Duration.ofMinutes(15));
        assertThat(quoted.getText()).isEqualTo("15m");
    }

    @Test
    void recordsItsLineAndWorksBetweenOtherSteps() {
        List<Statement> all = body("note \"a\"\nsleep 10s\nnote \"b\"");
        assertThat(all).hasSize(3);
        assertThat(all.get(1)).isInstanceOf(SleepStmt.class);
        assertThat(all.get(1).getLine()).isEqualTo(4);
    }

    @Test
    void canBeInsideABlock() {
        List<Statement> all = body("loop until (r == \"x\") max 2 {\n sleep 1m\n}");
        LoopStmt loop = (LoopStmt) all.get(0);
        assertThat(loop.getBody()).singleElement().isInstanceOf(SleepStmt.class);
    }

    @Test
    void refusesAZeroOrUnknownUnitOrMissingDuration() {
        assertThatThrownBy(() -> body("sleep 0s")).hasMessageContaining("must be positive");
        assertThatThrownBy(() -> body("sleep 5x")).hasMessageContaining("Unknown duration unit");
        assertThatThrownBy(() -> body("sleep")).hasMessageContaining("Expected statement");
        assertThatThrownBy(() -> body("sleep 1.5h")).hasMessageContaining("whole number");
    }

    @Test
    void sleepStaysUsableAsAVariableName() {
        List<Statement> all = body("delegate \"x\" to A -> sleep\nnote \"slept {sleep}\"");
        assertThat(all).noneMatch(s -> s instanceof SleepStmt);
        assertThat(all).hasSize(2);
    }
}
