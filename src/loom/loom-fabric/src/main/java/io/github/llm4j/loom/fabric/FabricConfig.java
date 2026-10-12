package io.github.llm4j.loom.fabric;

import io.github.llm4j.loom.inspect.control.ServerConfig;
import io.github.llm4j.loom.inspect.rules.RulesFile;
import io.github.llm4j.loom.inspect.state.InspectRules;
import io.github.llm4j.loom.inspect.state.PriceBook;
import io.github.llm4j.loom.inspect.state.RunRegistry;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What an administrator keeps at the server: the inspect rules and the price table, each a plain text file under {@code <data>/config/} in the
 * format a process keeps beside its script ({@code rules.inspect}, {@code prices.properties}). A save is checked first and kept whole or not at all; the
 * files are read again at start-up. The rules are laid over each run's own, rule by rule; the prices cost the steps of a process that has no price
 * file.
 */
final class FabricConfig implements ServerConfig {

    static final String RULES = "rules";
    static final String PRICES = "prices";
    private static final int MAX_CHARS = 200_000;

    private final Path dir;
    private final RunRegistry registry;
    private final PrintStream out;
    private String rulesText = "";
    private String pricesText = "";
    private RulesFile.Parsed rules = RulesFile.parse("");
    private PriceBook.Parsed prices = PriceBook.parse("");

    FabricConfig(Path data, RunRegistry registry, PrintStream out) {
        this.dir = data.resolve("config");
        this.registry = registry;
        this.out = out;
    }

    /** Reads what was kept and applies it. A file that no longer reads is reported and left unused. */
    synchronized void load() {
        rulesText = read("rules.inspect");
        RulesFile.Parsed r = RulesFile.parse(rulesText);
        if (r.ok()) {
            rules = r;
            registry.setCentralRules(r.rules());
        } else {
            out.println("fabric: config/rules.inspect has errors and is not used: " + r.problems().get(0));
        }
        pricesText = read("prices.properties");
        PriceBook.Parsed p = PriceBook.parse(pricesText);
        if (p.ok()) {
            prices = p;
            registry.setPrices(p.book());
        } else {
            out.println("fabric: config/prices.properties has errors and is not used: " + p.problems().get(0));
        }
    }

    private String read(String name) {
        try {
            Path f = dir.resolve(name);
            return Files.isRegularFile(f) ? Files.readString(f, StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            return "";
        }
    }

    @Override
    public synchronized Map<String, Map<String, Object>> view() {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("text", rulesText);
        r.put("summary", rules.rules().isEmpty() ? "no rules: every run is watched with its own rules, or the defaults" : rules.rules().size() + " block(s), laid over each run's own rules");
        r.put("warnings", warnings(rules));
        out.put(RULES, r);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("text", pricesText);
        p.put("summary", prices.book().isEmpty() ? "no prices: only a process with its own price file reports cost" : prices.book().size() + " model(s) priced");
        p.put("warnings", List.of());
        out.put(PRICES, p);
        return out;
    }

    @Override
    public synchronized Result save(String section, String text, String who) {
        if (text.length() > MAX_CHARS) return Result.refused(413, "too long", List.of());
        String normalized = text.replace("\r\n", "\n");
        switch (section) {
            case RULES -> {
                RulesFile.Parsed parsed = RulesFile.parse(normalized);
                if (!parsed.ok()) return Result.refused(422, "not saved: the rules have errors", parsed.problems().stream().map(Object::toString).toList());
                if (!write("rules.inspect", normalized)) return Result.refused(500, "could not write the file", List.of());
                rulesText = normalized;
                rules = parsed;
                registry.setCentralRules(parsed.rules());
                List<String> w = new ArrayList<>(warnings(parsed));
                return Result.saved("saved: " + parsed.rules().size() + " block(s), in force now for every run", w);
            }
            case PRICES -> {
                PriceBook.Parsed parsed = PriceBook.parse(normalized);
                if (!parsed.ok()) return Result.refused(422, "not saved: the prices have errors", parsed.problems().stream().map(Object::toString).toList());
                if (!write("prices.properties", normalized)) return Result.refused(500, "could not write the file", List.of());
                pricesText = normalized;
                prices = parsed;
                registry.setPrices(parsed.book());
                return Result.saved("saved: " + parsed.book().size() + " model(s) priced. Steps costed from now on use it; costs already counted do not change", List.of());
            }
            default -> {
                return Result.refused(404, "no such section", List.of());
            }
        }
    }

    private static List<String> warnings(RulesFile.Parsed parsed) {
        List<String> w = new ArrayList<>();
        parsed.problems().forEach(p -> w.add(p.toString()));
        boolean variable = parsed.rules().values().stream().anyMatch(r -> r.noProgressVariable() != null);
        if (variable) w.add("no_progress names a variable here, but the variable is read in the process: it only takes effect where that process's own rules name the same variable (the rounds here replace its count)");
        return w;
    }

    private boolean write(String name, String text) {
        try {
            Files.createDirectories(dir);
            Path tmp = dir.resolve(name + ".tmp");
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            Files.move(tmp, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException e) {
            out.println("fabric: cannot write config/" + name + ": " + e.getMessage());
            return false;
        }
    }
}
