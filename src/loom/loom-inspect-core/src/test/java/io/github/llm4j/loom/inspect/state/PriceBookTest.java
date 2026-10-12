package io.github.llm4j.loom.inspect.state;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** The server's price table: the same lines as Loom's price file, and what a model costs by it. */
class PriceBookTest {

    @Test
    void readsModelEqualsInputSlashOutputPerMillion_withCommentsAndBlankLines() {
        PriceBook.Parsed p = PriceBook.parse("# prices\n\ngemini-2.5-flash = 0.30 / 2.50   # per million\nclaude-sonnet = 3 / 15\n");
        assertThat(p.ok()).isTrue();
        assertThat(p.book().size()).isEqualTo(2);
        // 1,000,000 in at 0.30 + 1,000,000 out at 2.50
        assertThat(p.book().cost("gemini-2.5-flash", 1_000_000, 1_000_000)).isEqualByComparingTo(new BigDecimal("2.80"));
        assertThat(p.book().cost("claude-sonnet", 1000, 500)).isEqualByComparingTo(new BigDecimal("0.0105"));
    }

    @Test
    void aModelIsFoundWithOrWithoutItsProviderPrefix_ollamaIsFree_andAnUnpricedModelHasNoCost() {
        PriceBook b = PriceBook.parse("gemini-2.5-flash = 1 / 2").book();
        assertThat(b.cost("gemini/gemini-2.5-flash", 1_000_000, 0)).isEqualByComparingTo("1");
        assertThat(b.cost("ollama/llama3", 5_000, 5_000)).isEqualByComparingTo("0");
        assertThat(b.cost("unknown-model", 1000, 1000)).isNull();
        assertThat(b.cost(null, 1, 1)).isNull();
    }

    @Test
    void everyBadLineIsReportedWithItsNumber_andNothingIsUsable() {
        PriceBook.Parsed p = PriceBook.parse("ok = 1 / 2\nno equals sign\nm = 1\nn = a / b\nneg = -1 / 2\nok = 3 / 4\n");
        assertThat(p.ok()).isFalse();
        assertThat(p.problems()).extracting(PriceBook.Problem::line).containsExactly(2, 3, 4, 5, 6);
    }
}
