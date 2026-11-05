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
 * # prices.properties — model = input / output [/ searches [/ cache read [/ cache write]]]
 * # (tokens per million, searches per 1000)
 * gemini/gemini-2.5-flash = 0.30 / 2.50
 * gemini/gemini-3.7-flash = 0.75 / 3.75 / 14.00
 * </pre>
 *
 * Models with the {@code ollama/} prefix run locally and cost nothing unless listed.
 */
public final class PriceTable {

    /**
     * A model's price per million input and output tokens, per thousand searches, and, where the
     * provider has a prompt cache, per million cached tokens read and written. A null cache rate
     * means "not known": those tokens are priced as ordinary input, which never under-charges a
     * write and over-charges a read.
     */
    public record Price(
            BigDecimal inputPerMillion,
            BigDecimal outputPerMillion,
            BigDecimal searchPerThousand,
            BigDecimal cacheReadPerMillion,
            BigDecimal cacheWritePerMillion)
            implements BudgetSet.Pricing {

        public static final Price FREE = new Price(BigDecimal.ZERO, BigDecimal.ZERO);

        public Price {
            if (searchPerThousand == null) searchPerThousand = BigDecimal.ZERO;
        }

        /** A price for tokens only (searches cost nothing unless a vendor reports a cost). */
        public Price(BigDecimal inputPerMillion, BigDecimal outputPerMillion) {
            this(inputPerMillion, outputPerMillion, BigDecimal.ZERO);
        }

        /** A price for tokens and searches, with no cache rates. */
        public Price(
                BigDecimal inputPerMillion,
                BigDecimal outputPerMillion,
                BigDecimal searchPerThousand) {
            this(inputPerMillion, outputPerMillion, searchPerThousand, null, null);
        }

        @Override
        public BigDecimal cost(
                long promptTokens, long completionTokens, long cachedRead, long cacheWrite) {
            // the prompt count includes both, so a provider reporting more than it holds is wrong
            long read = Math.max(0, Math.min(cachedRead, promptTokens));
            long written = Math.max(0, Math.min(cacheWrite, promptTokens - read));
            long fresh = promptTokens - read - written;
            return inputPerMillion
                    .multiply(BigDecimal.valueOf(fresh))
                    .add(rate(cacheReadPerMillion).multiply(BigDecimal.valueOf(read)))
                    .add(rate(cacheWritePerMillion).multiply(BigDecimal.valueOf(written)))
                    .add(outputPerMillion.multiply(BigDecimal.valueOf(completionTokens)))
                    .movePointLeft(6);
        }

        private BigDecimal rate(BigDecimal cacheRate) {
            return cacheRate == null ? inputPerMillion : cacheRate;
        }

        @Override
        public BigDecimal cost(long promptTokens, long completionTokens) {
            return inputPerMillion
                    .multiply(BigDecimal.valueOf(promptTokens))
                    .add(outputPerMillion.multiply(BigDecimal.valueOf(completionTokens)))
                    .movePointLeft(6);
        }

        @Override
        public BigDecimal searchCost(long searches) {
            return searchPerThousand.multiply(BigDecimal.valueOf(searches)).movePointLeft(3);
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

    /**
     * Reads {@code model = input / output [/ searches [/ cache read [/ cache write]]]} lines;
     * {@code #} starts a comment. Write {@code 0} for searches to give a cache rate without one.
     */
    public static PriceTable load(Path file) throws IOException {
        Map<String, Price> prices = new LinkedHashMap<>();
        int n = 0;
        for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            n++;
            String line = raw.replaceFirst("#.*$", "").strip();
            if (line.isEmpty()) continue;
            int eq = line.indexOf('=');
            String[] parts = eq < 0 ? new String[0] : line.substring(eq + 1).split("/");
            if (eq < 0 || parts.length < 2 || parts.length > 5) {
                throw new IllegalArgumentException(
                        file
                                + ":"
                                + n
                                + ": expected 'model = input / output [/ searches per 1000"
                                + " [/ cache read [/ cache write]]]', got: "
                                + raw);
            }
            prices.put(
                    line.substring(0, eq).strip(),
                    new Price(
                            new BigDecimal(parts[0].strip()),
                            new BigDecimal(parts[1].strip()),
                            parts.length >= 3 ? new BigDecimal(parts[2].strip()) : null,
                            parts.length >= 4 ? new BigDecimal(parts[3].strip()) : null,
                            parts.length == 5 ? new BigDecimal(parts[4].strip()) : null));
        }
        return new PriceTable(prices);
    }

    /**
     * The model's price: exact id, then the id without its provider prefix; {@code ollama/*} is
     * free.
     */
    public Optional<Price> price(String model) {
        if (model == null) return Optional.empty();
        Price p = prices.get(model);
        if (p == null && model.contains("/"))
            p = prices.get(model.substring(model.indexOf('/') + 1));
        if (p == null && model.startsWith("ollama/")) p = Price.FREE;
        return Optional.ofNullable(p);
    }
}
