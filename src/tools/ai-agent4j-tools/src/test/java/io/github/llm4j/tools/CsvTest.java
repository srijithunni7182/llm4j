package io.github.llm4j.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The comma-separated values {@code compute} reads, as RFC 4180 writes them. */
class CsvTest {

    static List<List<String>> parse(String text) {
        return Csv.parse(text, 100);
    }

    @Test
    void quotedFieldsHoldCommasLineBreaksAndDoubledQuotes() {
        assertThat(parse("a,b\n\"x, y\",\"line\nbreak\"\n\"say \"\"hi\"\"\",\"\"\"\""))
                .containsExactly(
                        List.of("a", "b"),
                        List.of("x, y", "line\nbreak"),
                        List.of("say \"hi\"", "\""));
    }

    @Test
    void aQuoteInsideAFieldIsKeptAsItIs() {
        assertThat(parse("ab\"c,d")).containsExactly(List.of("ab\"c", "d"));
    }

    @Test
    void linesEndWithLfOrCrlfAndTheLastNeedsNoEnd() {
        assertThat(parse("a\r\nb\r\nc")).containsExactly(List.of("a"), List.of("b"), List.of("c"));
        assertThat(parse("a\rb")).containsExactly(List.of("a"), List.of("b"));
        assertThat(parse("a\n")).containsExactly(List.of("a"));
        assertThat(parse("\"a\"\r\n")).containsExactly(List.of("a"));
        assertThat(parse("\"a\"")).containsExactly(List.of("a"));
    }

    @Test
    void blankLinesAreSkippedButEmptyFieldsAreKept() {
        assertThat(parse("a\n\n\nb\n")).containsExactly(List.of("a"), List.of("b"));
        assertThat(parse(",\n")).containsExactly(List.of("", ""));
        assertThat(parse("\"\"\n")).containsExactly(List.of(""));
        assertThat(parse("a,")).containsExactly(List.of("a", ""));
        assertThat(parse("")).isEmpty();
    }

    @Test
    void anOpenQuoteIsRefused() {
        assertThatThrownBy(() -> parse("a\n\"b")).hasMessage("csv: a quote is not closed");
        assertThatThrownBy(() -> parse("\"a\"\"")).hasMessage("csv: a quote is not closed");
    }

    @Test
    void theRowCapCountsRowsAfterTheHeader() {
        assertThat(Csv.parse("h\n1\n2", 2)).hasSize(3);
        assertThatThrownBy(() -> Csv.parse("h\n1\n2\n3", 2))
                .hasMessage("csv: the text has more than 2 rows (max_rows)");
        assertThatThrownBy(() -> Csv.parse("h\n1\n2\n3\n", 2))
                .hasMessageContaining("more than 2 rows");
    }
}
