package io.github.llm4j.budget;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Prices per model, in the developer's currency per million input and output tokens. No prices ship
 * with the library because they go stale; load your own:
 *
 * <pre>
 * # prices.properties — model = input / output (per million tokens)
 * gemini/gemini-2.5-flash = 0.30 / 2.50
 * </pre>
 *
 * Models with the {@code ollama/} prefix run locally and cost nothing unless listed.
 */
public final class PriceTable {

    /** A model's price per million input and output tokens. */
    public record Price(BigDecimal inputPerMillion, BigDecimal outputPerMillion) implements BudgetSet.Pricing {

        public static final Price FREE = new Price(BigDecimal.ZERO, BigDecimal.ZERO);

        @Override
        public BigDecimal cost(long promptTokens, long completionTokens) {
            return inputPerMillion.multiply(BigDecimal.valueOf(promptTokens))
                    .add(outputPerMillion.multiply(BigDecimal.valueOf(completionTokens)))
                    .movePointLeft(6);
        }

        @Override
        public BigDecimal perOutputToken() {
            return outputPerMillion.movePointLeft(6);
        }
    }

    private final Map<String, Price> prices;

    private PriceTable(Map<String, Price> prices) {
        this.prices = Map.copyOf(prices);
    }

    public static PriceTable of(Map<String, Price> prices) {
        return new PriceTable(prices);
    }

    /** Reads {@code model = input / output} lines; {@code #} starts a comment. */
    public static PriceTable load(Path file) throws IOException {
        Map<String, Price> prices = new LinkedHashMap<>();
        int n = 0;
        for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            n++;
            String line = raw.replaceFirst("#.*$", "").strip();
            if (line.isEmpty()) continue;
            int eq = line.indexOf('=');
            String[] parts = eq < 0 ? new String[0] : line.substring(eq + 1).split("/");
            if (eq < 0 || parts.length != 2) {
                throw new IllegalArgumentException(file + ":" + n + ": expected 'model = input / output', got: " + raw);
            }
            prices.put(line.substring(0, eq).strip(),
                    new Price(new BigDecimal(parts[0].strip()), new BigDecimal(parts[1].strip())));
        }
        return new PriceTable(prices);
    }

    /** The model's price: exact id, then the id without its provider prefix; {@code ollama/*} is free. */
    public Optional<Price> price(String model) {
        if (model == null) return Optional.empty();
        Price p = prices.get(model);
        if (p == null && model.contains("/")) p = prices.get(model.substring(model.indexOf('/') + 1));
        if (p == null && model.startsWith("ollama/")) p = Price.FREE;
        return Optional.ofNullable(p);
    }
}
