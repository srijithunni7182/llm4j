package io.github.llm4j.loom.inspect.state;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Prices per model, as a server keeps them: {@code model = input / output} per million tokens, the same lines Loom's own price file has
 * ({@code #} starts a comment). It lets a server cost a step whose process reported tokens but no cost, because that process has no
 * price file. A model with no price has no cost; {@code ollama/*} is free, as in Loom.
 */
public final class PriceBook {

    /** One line that could not be read. */
    public record Problem(int line, String message) {
        @Override
        public String toString() {
            return "line " + line + ": " + message;
        }
    }

    /** The result of reading a price file: the book, and what was wrong with it (the book is only usable when there is nothing wrong). */
    public record Parsed(PriceBook book, List<Problem> problems) {
        public boolean ok() {
            return problems.isEmpty();
        }
    }

    public static final PriceBook EMPTY = new PriceBook(Map.of());

    private final Map<String, BigDecimal[]> prices;

    private PriceBook(Map<String, BigDecimal[]> prices) {
        this.prices = prices;
    }

    public static Parsed parse(String text) {
        Map<String, BigDecimal[]> out = new LinkedHashMap<>();
        List<Problem> problems = new ArrayList<>();
        String[] lines = (text == null ? "" : text).split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].replaceFirst("#.*$", "").strip();
            if (line.isEmpty()) continue;
            int eq = line.indexOf('=');
            String[] parts = eq < 0 ? new String[0] : line.substring(eq + 1).split("/");
            if (eq < 0 || parts.length != 2 || line.substring(0, eq).isBlank()) {
                problems.add(new Problem(i + 1, "expected 'model = input / output', got: " + lines[i].strip()));
                continue;
            }
            try {
                BigDecimal in = new BigDecimal(parts[0].strip());
                BigDecimal outp = new BigDecimal(parts[1].strip());
                if (in.signum() < 0 || outp.signum() < 0) {
                    problems.add(new Problem(i + 1, "a price cannot be negative"));
                    continue;
                }
                if (out.put(line.substring(0, eq).strip(), new BigDecimal[] {in, outp}) != null) {
                    problems.add(new Problem(i + 1, "'" + line.substring(0, eq).strip() + "' is priced twice"));
                }
            } catch (NumberFormatException e) {
                problems.add(new Problem(i + 1, "the prices must be numbers, got: " + lines[i].strip()));
            }
        }
        return new Parsed(new PriceBook(out), problems);
    }

    /** One priced model: dollars per million input and output tokens. */
    public record Entry(String model, BigDecimal input, BigDecimal output) { }

    public List<Entry> entries() {
        List<Entry> out = new ArrayList<>();
        prices.forEach((m, p) -> out.add(new Entry(m, p[0], p[1])));
        out.sort(java.util.Comparator.comparing(Entry::model));
        return out;
    }

    /** The file for these entries, as {@link #parse} reads it back. */
    public static String write(List<Entry> entries) {
        StringBuilder out = new StringBuilder("# model = input / output, dollars per million tokens\n");
        for (Entry e : entries.stream().sorted(java.util.Comparator.comparing(Entry::model)).toList()) {
            out.append(e.model()).append(" = ").append(e.input().stripTrailingZeros().toPlainString()).append(" / ").append(e.output().stripTrailingZeros().toPlainString()).append('\n');
        }
        return out.toString();
    }

    public int size() {
        return prices.size();
    }

    public boolean isEmpty() {
        return prices.isEmpty();
    }

    /** The model's cost for these tokens, or null when it has no price: exact id, then without its provider prefix; {@code ollama/*} is free. */
    public BigDecimal cost(String model, long promptTokens, long completionTokens) {
        if (model == null) return null;
        BigDecimal[] p = prices.get(model);
        if (p == null && model.contains("/")) p = prices.get(model.substring(model.indexOf('/') + 1));
        if (p == null && model.startsWith("ollama/")) return BigDecimal.ZERO;
        if (p == null) return null;
        return p[0].multiply(BigDecimal.valueOf(promptTokens)).add(p[1].multiply(BigDecimal.valueOf(completionTokens))).movePointLeft(6);
    }
}
