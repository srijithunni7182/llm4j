package io.github.llm4j.loom.fabric;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.loom.inspect.control.ServerConfig;
import io.github.llm4j.loom.inspect.rules.RulesFile;
import io.github.llm4j.loom.inspect.state.InspectRules;
import io.github.llm4j.loom.inspect.state.PriceBook;
import io.github.llm4j.loom.inspect.state.RunRegistry;
import io.github.llm4j.loom.inspect.state.Thresholds;
import io.github.llm4j.loom.inspect.state.WindowBook;
import java.io.IOException;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What an administrator keeps at the server: the inspect rules and the price table, each a plain
 * text file under {@code <data>/config/} in the format a process keeps beside its script ({@code
 * rules.inspect}, {@code prices.properties}). A save is checked first and kept whole or not at all;
 * the files are read again at start-up. The rules are laid over each run's own, rule by rule; the
 * prices cost the steps of a process that has no price file.
 */
final class FabricConfig implements ServerConfig {

    static final String RULES = "rules";
    static final String PRICES = "prices";
    static final String WINDOWS = "windows";
    private static final int MAX_CHARS = 200_000;

    private final Path dir;
    private final RunRegistry registry;
    private final PrintStream out;
    private static final ObjectMapper JSON = new ObjectMapper();
    private String rulesText = "";
    private String pricesText = "";
    private RulesFile.Parsed rules = RulesFile.parse("");
    private PriceBook.Parsed prices = PriceBook.parse("");
    private String windowsText = "";
    private WindowBook.Parsed windows = WindowBook.parse("");

    FabricConfig(Path data, RunRegistry registry, PrintStream out) {
        this.dir = data.resolve("config");
        this.registry = registry;
        this.out = out;
    }

    /**
     * Reads what was kept and applies it. A file that no longer reads is reported and left unused.
     */
    synchronized void load() {
        rulesText = read("rules.inspect");
        RulesFile.Parsed r = RulesFile.parse(rulesText);
        if (r.ok()) {
            rules = r;
            registry.setCentralRules(r.rules());
        } else {
            out.println(
                    "fabric: config/rules.inspect has errors and is not used: "
                            + r.problems().get(0));
        }
        pricesText = read("prices.properties");
        PriceBook.Parsed p = PriceBook.parse(pricesText);
        if (p.ok()) {
            prices = p;
            registry.setPrices(p.book());
        } else {
            out.println(
                    "fabric: config/prices.properties has errors and is not used: "
                            + p.problems().get(0));
        }
        windowsText = read("windows.properties");
        WindowBook.Parsed w = WindowBook.parse(windowsText);
        if (w.ok()) {
            windows = w;
            registry.setWindows(w.book());
        } else {
            out.println(
                    "fabric: config/windows.properties has errors and is not used: "
                            + w.problems().get(0));
            windowsText = "";
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
        r.put(
                "summary",
                rules.rules().isEmpty()
                        ? "no rules: every run is watched with its own rules, or the defaults"
                        : rules.rules().size() + " block(s), laid over each run's own rules");
        r.put("warnings", warnings(rules));
        r.put("structured", structuredRules(rules.rules()));
        r.put("defaults", defaults());
        out.put(RULES, r);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("text", pricesText);
        p.put(
                "summary",
                prices.book().isEmpty()
                        ? "no prices: only a process with its own price file reports cost"
                        : prices.book().size() + " model(s) priced");
        p.put("warnings", List.of());
        List<Map<String, Object>> entries = new ArrayList<>();
        for (PriceBook.Entry e : prices.book().entries())
            entries.add(
                    Map.of(
                            "model",
                            e.model(),
                            "input",
                            e.input().stripTrailingZeros().toPlainString(),
                            "output",
                            e.output().stripTrailingZeros().toPlainString()));
        p.put("structured", entries);
        out.put(PRICES, p);
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("text", windowsText);
        w.put(
                "summary",
                windows.book().isEmpty()
                        ? "no windows: an agent shows its context fill only when its process was told the model's window"
                        : windows.book().size() + " model(s) with a window");
        w.put("warnings", List.of());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (WindowBook.Entry e : windows.book().entries())
            rows.add(Map.of("model", e.model(), "window", e.usable()));
        w.put("structured", rows);
        out.put(WINDOWS, w);
        return out;
    }

    @Override
    public Map<String, Object> catalog() {
        return registry.catalog();
    }

    private static Map<String, Object> defaults() {
        Thresholds t = Thresholds.defaults();
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("same_action", t.sameAction());
        d.put("same_output", t.sameOutput());
        d.put("tool_failures", t.toolFailures());
        d.put("ungrounded_figures", Map.of("warn", t.ungroundedWarn(), "high", t.ungroundedHigh()));
        d.put("paused_longer_than", t.pausedLongerThanSeconds());
        d.put(
                "context_nearly_full",
                Map.of(
                        "warn",
                        (int) Math.round(t.contextWarn() * 100),
                        "high",
                        (int) Math.round(t.contextHigh() * 100)));
        d.put("truncated_answers", t.truncatedAnswers());
        d.put(
                "compaction_thrash",
                Map.of("count", t.thrashCompactions(), "of_calls", t.thrashOfCalls()));
        d.put(
                "tool_success_rate",
                Map.of(
                        "below",
                        (int) Math.round(t.toolRate() * 100),
                        "over_calls",
                        t.toolRateWindow()));
        d.put("latency_spike", t.spikeFactor());
        d.put(
                "access_denied",
                Map.of("count", t.accessDeniedCount(), "seconds", (int) t.accessDeniedSeconds()));
        d.put("injection_scan", t.injectionScan());
        return d;
    }

    private static Map<String, Object> structuredRules(Map<String, InspectRules> rules) {
        Map<String, Object> out = new LinkedHashMap<>();
        rules.forEach(
                (agent, r) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    if (r.sameAction() != null) m.put("same_action", r.sameAction());
                    if (r.sameOutput() != null) m.put("same_output", r.sameOutput());
                    if (r.toolFailures() != null) m.put("tool_failures", r.toolFailures());
                    if (r.ungroundedWarn() != null || r.ungroundedHigh() != null) {
                        Map<String, Object> u = new LinkedHashMap<>();
                        if (r.ungroundedWarn() != null) u.put("warn", r.ungroundedWarn());
                        if (r.ungroundedHigh() != null) u.put("high", r.ungroundedHigh());
                        m.put("ungrounded_figures", u);
                    }
                    if (r.pausedLongerThanSeconds() != null)
                        m.put("paused_longer_than", r.pausedLongerThanSeconds());
                    if (r.noProgressRounds() != null)
                        m.put(
                                "no_progress",
                                Map.of(
                                        "rounds",
                                        r.noProgressRounds(),
                                        "variable",
                                        String.valueOf(r.noProgressVariable())));
                    InspectRules.Telemetry t = r.telemetry();
                    if (t != null) {
                        if (t.contextWarn() != null || t.contextHigh() != null) {
                            Map<String, Object> c = new LinkedHashMap<>();
                            if (t.contextWarn() != null)
                                c.put("warn", (int) Math.round(t.contextWarn() * 100));
                            if (t.contextHigh() != null)
                                c.put("high", (int) Math.round(t.contextHigh() * 100));
                            m.put("context_nearly_full", c);
                        }
                        if (t.truncatedAnswers() != null)
                            m.put("truncated_answers", t.truncatedAnswers());
                        if (t.thrashCompactions() != null && t.thrashOfCalls() != null)
                            m.put(
                                    "compaction_thrash",
                                    Map.of(
                                            "count",
                                            t.thrashCompactions(),
                                            "of_calls",
                                            t.thrashOfCalls()));
                        if (t.toolRate() != null && t.toolRateWindow() != null)
                            m.put(
                                    "tool_success_rate",
                                    Map.of(
                                            "below",
                                            (int) Math.round(t.toolRate() * 100),
                                            "over_calls",
                                            t.toolRateWindow()));
                        if (t.spikeFactor() != null) m.put("latency_spike", t.spikeFactor());
                    }
                    InspectRules.Security sec = r.security();
                    if (sec != null) {
                        if (sec.accessDeniedCount() != null && sec.accessDeniedSeconds() != null)
                            m.put(
                                    "access_denied",
                                    Map.of(
                                            "count",
                                            sec.accessDeniedCount(),
                                            "seconds",
                                            sec.accessDeniedSeconds().intValue()));
                        if (sec.injectionScan() != null)
                            m.put("injection_scan", sec.injectionScan());
                        if (sec.trifectaAccepted() != null)
                            m.put("trifecta_accepted", sec.trifectaAccepted());
                    }
                    out.put(agent, m);
                });
        return out;
    }

    /**
     * From the page's editors: the structure is turned into the same text a person would write,
     * then checked and kept exactly as a text save is.
     */
    @Override
    public synchronized Result saveStructured(String section, String json, String who) {
        try {
            JsonNode n = JSON.readTree(json);
            return switch (section) {
                case RULES -> save(RULES, RulesFile.write(rulesOf(n)), who);
                case PRICES -> save(PRICES, PriceBook.write(pricesOf(n)), who);
                case WINDOWS -> save(WINDOWS, WindowBook.write(windowsOf(n)), who);
                default -> Result.refused(404, "no such section", List.of());
            };
        } catch (IOException | IllegalArgumentException e) {
            return Result.refused(
                    400, "could not read what was sent: " + e.getMessage(), List.of());
        }
    }

    private static Map<String, InspectRules> rulesOf(JsonNode n) {
        Map<String, InspectRules> out = new LinkedHashMap<>();
        if (!n.isObject())
            throw new IllegalArgumentException("rules are an object of agent to rules");
        n.fields()
                .forEachRemaining(
                        e -> {
                            JsonNode r = e.getValue();
                            JsonNode u = r.path("ungrounded_figures");
                            JsonNode np = r.path("no_progress");
                            JsonNode cx = r.path("context_nearly_full");
                            JsonNode th = r.path("compaction_thrash");
                            JsonNode tr = r.path("tool_success_rate");
                            InspectRules.Telemetry telemetry =
                                    new InspectRules.Telemetry(
                                            percent(cx, "warn"),
                                            percent(cx, "high"),
                                            whole(r, "truncated_answers"),
                                            whole(th, "count"),
                                            whole(th, "of_calls"),
                                            percent(tr, "below"),
                                            whole(tr, "over_calls"),
                                            r.hasNonNull("latency_spike")
                                                    ? r.get("latency_spike").asDouble()
                                                    : null);
                            JsonNode ad = r.path("access_denied");
                            String accepted =
                                    r.hasNonNull("trifecta_accepted")
                                            ? r.get("trifecta_accepted").asText()
                                            : null;
                            if (accepted != null && accepted.isBlank())
                                throw new IllegalArgumentException(
                                        e.getKey() + ": an accepted trifecta needs a reason");
                            InspectRules.Security security =
                                    new InspectRules.Security(
                                            whole(ad, "count"),
                                            ad.hasNonNull("seconds")
                                                    ? ad.get("seconds").asLong()
                                                    : null,
                                            r.hasNonNull("injection_scan")
                                                    ? r.get("injection_scan").asBoolean()
                                                    : null,
                                            accepted == null ? null : accepted.strip());
                            out.put(
                                    e.getKey(),
                                    new InspectRules(
                                            whole(r, "same_action"),
                                            whole(r, "same_output"),
                                            whole(r, "tool_failures"),
                                            whole(u, "warn"),
                                            whole(u, "high"),
                                            r.hasNonNull("paused_longer_than")
                                                    ? r.get("paused_longer_than").asLong()
                                                    : null,
                                            whole(np, "rounds"),
                                            np.hasNonNull("variable")
                                                            && !np.get("variable")
                                                                    .asText()
                                                                    .isBlank()
                                                    ? np.get("variable").asText().strip()
                                                    : null,
                                            telemetry.isEmpty() ? null : telemetry,
                                            security.isEmpty() ? null : security));
                        });
        return out;
    }

    private static Double percent(JsonNode n, String key) {
        return n.hasNonNull(key) ? n.get(key).asDouble() / 100.0 : null;
    }

    private static Integer whole(JsonNode n, String key) {
        return n.hasNonNull(key) ? n.get(key).asInt() : null;
    }

    private static List<WindowBook.Entry> windowsOf(JsonNode n) {
        if (!n.isArray()) throw new IllegalArgumentException("windows are a list of models");
        List<WindowBook.Entry> out = new ArrayList<>();
        for (JsonNode e : n) {
            String model = e.path("model").asText("").strip();
            if (model.isEmpty()) throw new IllegalArgumentException("a window needs a model");
            JsonNode w = e.path("window");
            if (!w.canConvertToLong() || w.isFloatingPointNumber())
                throw new IllegalArgumentException(
                        "the window of " + model + " must be a whole number of tokens");
            out.add(new WindowBook.Entry(model, w.asLong()));
        }
        return out;
    }

    private static List<PriceBook.Entry> pricesOf(JsonNode n) {
        if (!n.isArray()) throw new IllegalArgumentException("prices are a list of models");
        List<PriceBook.Entry> out = new ArrayList<>();
        for (JsonNode e : n) {
            String model = e.path("model").asText("").strip();
            if (model.isEmpty()) throw new IllegalArgumentException("a price needs a model");
            try {
                out.add(
                        new PriceBook.Entry(
                                model,
                                new BigDecimal(e.path("input").asText().strip()),
                                new BigDecimal(e.path("output").asText().strip())));
            } catch (NumberFormatException bad) {
                throw new IllegalArgumentException("the prices for " + model + " must be numbers");
            }
        }
        return out;
    }

    @Override
    public synchronized Result save(String section, String text, String who) {
        if (text.length() > MAX_CHARS) return Result.refused(413, "too long", List.of());
        String normalized = text.replace("\r\n", "\n");
        switch (section) {
            case RULES -> {
                RulesFile.Parsed parsed = RulesFile.parse(normalized);
                if (!parsed.ok())
                    return Result.refused(
                            422,
                            "not saved: the rules have errors",
                            parsed.problems().stream().map(Object::toString).toList());
                if (!write("rules.inspect", normalized))
                    return Result.refused(500, "could not write the file", List.of());
                rulesText = normalized;
                rules = parsed;
                registry.setCentralRules(parsed.rules());
                List<String> w = new ArrayList<>(warnings(parsed));
                return Result.saved(
                        "saved: " + parsed.rules().size() + " block(s), in force now for every run",
                        w);
            }
            case PRICES -> {
                PriceBook.Parsed parsed = PriceBook.parse(normalized);
                if (!parsed.ok())
                    return Result.refused(
                            422,
                            "not saved: the prices have errors",
                            parsed.problems().stream().map(Object::toString).toList());
                if (!write("prices.properties", normalized))
                    return Result.refused(500, "could not write the file", List.of());
                pricesText = normalized;
                prices = parsed;
                registry.setPrices(parsed.book());
                return Result.saved(
                        "saved: "
                                + parsed.book().size()
                                + " model(s) priced. Steps costed from now on use it; costs already counted do not change",
                        List.of());
            }
            case WINDOWS -> {
                WindowBook.Parsed parsed = WindowBook.parse(normalized);
                if (!parsed.ok())
                    return Result.refused(
                            422,
                            "not saved: the windows have errors",
                            parsed.problems().stream().map(Object::toString).toList());
                if (!write("windows.properties", normalized))
                    return Result.refused(500, "could not write the file", List.of());
                windowsText = normalized;
                windows = parsed;
                registry.setWindows(parsed.book());
                return Result.saved(
                        "saved: "
                                + parsed.book().size()
                                + " model(s) with a window. Runs show it from now on",
                        List.of());
            }
            default -> {
                return Result.refused(404, "no such section", List.of());
            }
        }
    }

    private static List<String> warnings(RulesFile.Parsed parsed) {
        List<String> w = new ArrayList<>();
        parsed.problems().forEach(p -> w.add(p.toString()));
        return w;
    }

    private boolean write(String name, String text) {
        try {
            Files.createDirectories(dir);
            Path tmp = dir.resolve(name + ".tmp");
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            Files.move(
                    tmp,
                    dir.resolve(name),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException e) {
            out.println("fabric: cannot write config/" + name + ": " + e.getMessage());
            return false;
        }
    }
}
