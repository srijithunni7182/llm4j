package io.github.llm4j.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verification plan, Requirement 3 (V3.2, V3.3). */
class PriceTableTest {

    @TempDir
    Path dir;

    @Test
    void v3_2_loadsTheDevelopersOwnPrices() throws Exception {
        Path file = dir.resolve("prices-test.properties");
        Files.writeString(file, "# test prices\ntest/model = 1.00 / 2.00\nollama/llama3.1:8b = 0.10 / 0.10  # listed\n");
        PriceTable prices = PriceTable.load(file);
        assertThat(prices.price("test/model")).hasValueSatisfying(p -> {
            assertThat(p.inputPerMillion()).isEqualByComparingTo("1.00");
            assertThat(p.outputPerMillion()).isEqualByComparingTo("2.00");
        });
        assertThat(prices.price("unknown/x")).isEmpty();
        assertThat(prices.price("ollama/llama3.1:8b")).hasValueSatisfying(p ->
                assertThat(p.inputPerMillion()).isEqualByComparingTo("0.10"));
        assertThat(prices.price("test/model").orElseThrow().cost(100, 50)).isEqualByComparingTo("0.0002");
    }

    @Test
    void v3_2b_noPricesShipWithTheLibrary() {
        ClassLoader cl = getClass().getClassLoader();
        for (String name : new String[] {"prices.properties", "llm4j/prices.properties", "io/github/llm4j/budget/prices.properties"}) {
            assertThat(cl.getResource(name)).as(name).isNull();
        }
    }

    @Test
    void v3_3_localOllamaModelsAreFreeUnlessListed() {
        PriceTable empty = PriceTable.of(java.util.Map.of());
        assertThat(empty.price("ollama/llama3.1:8b")).hasValueSatisfying(p ->
                assertThat(p.cost(1_000_000, 1_000_000)).isEqualByComparingTo(BigDecimal.ZERO));
    }

    @Test
    void aProviderPrefixIsOptionalInThePriceFile() {
        PriceTable prices = PriceTable.of(java.util.Map.of("gemini-2.5-flash",
                new PriceTable.Price(new BigDecimal("0.30"), new BigDecimal("2.50"))));
        assertThat(prices.price("gemini/gemini-2.5-flash")).isPresent();
    }

    @Test
    void aMalformedLineNamesItsLine() throws Exception {
        Path file = dir.resolve("bad.properties");
        Files.writeString(file, "ok/model = 1 / 2\nbroken line\n");
        assertThatThrownBy(() -> PriceTable.load(file)).hasMessageContaining(":2:");
    }
}
