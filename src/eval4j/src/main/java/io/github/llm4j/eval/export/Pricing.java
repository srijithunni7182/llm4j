package io.github.llm4j.eval.export;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Prices judge tokens so a run can show spend and enforce a budget. The file named by {@code
 * eval4j.pricing} is a properties file with one line per judge id or model:
 *
 * <pre>
 * gemini-2.5-pro = 1.25, 10.00
 * </pre>
 *
 * meaning USD per million input tokens, then per million output tokens. Without a file there is no
 * cost: the report shows token counts instead and says pricing is missing.
 */
public final class Pricing {

    private record Rate(double in, double out) {}

    private final Map<String, Rate> rates = new HashMap<>();

    public static Pricing fromSystem() {
        String file = System.getProperty("eval4j.pricing");
        return file == null || file.isBlank() ? new Pricing() : fromFile(Path.of(file));
    }

    /**
     * Reads a prices file in the format described above; a missing or unreadable file gives no
     * prices.
     */
    public static Pricing fromFile(Path file) {
        Pricing p = new Pricing();
        if (file != null) {
            try (Reader r = Files.newBufferedReader(file)) {
                Properties props = new Properties();
                props.load(r);
                for (String name : props.stringPropertyNames()) {
                    String[] parts = props.getProperty(name).split(",");
                    if (parts.length == 2) {
                        p.rates.put(
                                name.trim().toLowerCase(java.util.Locale.ROOT),
                                new Rate(
                                        Double.parseDouble(parts[0].trim()),
                                        Double.parseDouble(parts[1].trim())));
                    }
                }
            } catch (IOException | NumberFormatException e) {
                System.err.println(
                        "eval4j: cannot read pricing file " + file + ": " + e.getMessage());
            }
        }
        return p;
    }

    public boolean isEmpty() {
        return rates.isEmpty();
    }

    /** Cost in USD, or null when the judge has no price. */
    public Double cost(String judge, String model, int tokensIn, int tokensOut) {
        Rate r = null;
        for (String k : new String[] {judge, model}) {
            if (k != null && r == null) {
                r = rates.get(k.toLowerCase(java.util.Locale.ROOT));
            }
        }
        return r == null ? null : (tokensIn * r.in + tokensOut * r.out) / 1_000_000.0;
    }
}
