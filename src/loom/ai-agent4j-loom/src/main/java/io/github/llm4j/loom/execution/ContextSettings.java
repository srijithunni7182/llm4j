package io.github.llm4j.loom.execution;

import io.github.llm4j.context.ContextCompactors;
import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.loom.autonomy.expr.Suggest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiConsumer;

/** Checks an agent's {@code context { … }} block: names, numbers and keys. It loads and calls nothing. */
final class ContextSettings {

    /** Options the built-in strategies read, and the strategy each belongs to. */
    static final Map<String, String> BUILT_IN_OPTIONS = Map.of("max_chars", "trim_tool_results", "max_summary_tokens", "summarize");

    private ContextSettings() { }

    /** Every strategy name a script may use: the built-ins and providers on the classpath, then the names a {@code .loct} file maps. */
    static TreeSet<String> knownNames(Set<String> custom) {
        TreeSet<String> all = new TreeSet<>(ContextCompactors.names());
        all.addAll(custom);
        return all;
    }

    static void check(ScriptValidator.Checker c, String who, AgentDef.ContextConfig cfg, Set<String> custom, BiConsumer<Integer, String> summarizerModel) {
        TreeSet<String> known = knownNames(custom);
        boolean anyCustom = false;
        if (cfg.compactNames().isEmpty() && !cfg.has("compact")) {
            c.error(cfg.getLine(), who, "context needs compact: <strategy> (one of " + String.join(", ", known) + "), e.g. context { compact: drop_oldest }");
        }
        for (String name : cfg.compactNames()) {
            if (!known.contains(name)) {
                c.error(cfg.lineOf("compact"), who, "unknown compaction strategy '" + name + "'" + Suggest.hint(name, known)
                        + "; known strategies: " + String.join(", ", known) + (custom.isEmpty() ? "" : " (those from your .loct files included)"));
            }
            if (custom.contains(name)) anyCustom = true;
        }

        for (var e : cfg.getValues().entrySet()) {
            String key = e.getKey();
            int line = cfg.lineOf(key);
            if (e.getValue().isReference()) {
                c.error(line, who, "context " + key + " is written in the script, not taken from the environment or a secret");
            } else if (key.equals("window") || key.equals("window_tokens") || key.equals("context_window")) {
                c.error(line, who, "context " + key + ": the size of a model's window is not written in the script; "
                        + "configure it per model in a windows file (--context-windows, or <script>.windows)");
            } else if (!AgentDef.ContextConfig.OWN_KEYS.contains(key) && !BUILT_IN_OPTIONS.containsKey(key) && !anyCustom) {
                List<String> allowed = new ArrayList<>(new TreeSet<>(AgentDef.ContextConfig.OWN_KEYS));
                allowed.addAll(new TreeSet<>(BUILT_IN_OPTIONS.keySet()));
                c.error(line, who, "unknown context setting " + key + "; use " + String.join(", ", allowed));
            } else if (BUILT_IN_OPTIONS.containsKey(key) && !cfg.compactNames().contains(BUILT_IN_OPTIONS.get(key)) && !anyCustom) {
                c.error(line, who, "context " + key + " is an option of " + BUILT_IN_OPTIONS.get(key) + ", which compact: does not name");
            }
        }

        percent(c, who, cfg, "trigger");
        percent(c, who, cfg, "target");
        Double trigger = cfg.getTrigger();
        Double target = cfg.getTarget();
        if (trigger != null && target != null && target >= trigger) {
            c.error(cfg.lineOf("target"), who, "context target (" + cfg.get("target") + ") must be below trigger (" + cfg.get("trigger") + "): compaction starts at the trigger and goes down to the target");
        }
        positive(c, who, cfg, "keep_recent", true);
        positive(c, who, cfg, "max_compactions", false);

        if (cfg.getSummarizer() != null) {
            summarizerModel.accept(cfg.lineOf("summarizer"), cfg.getSummarizer());
            if (!cfg.compactNames().contains("summarize") && !anyCustom) {
                c.warn(cfg.lineOf("summarizer"), who, "summarizer is set but compact: does not use summarize; it will not be called");
            }
        } else if (cfg.compactNames().contains("summarize")) {
            c.warn(cfg.lineOf("compact"), who, "compact: summarize needs a summarizer: \"<model>\" (a cheaper model that writes the notes); without one it falls back to drop_oldest");
        }
    }

    private static void percent(ScriptValidator.Checker c, String who, AgentDef.ContextConfig cfg, String key) {
        if (!cfg.has(key) || cfg.getValues().get(key).isReference()) return;
        String v = cfg.get(key);
        Double f = key.equals("trigger") ? cfg.getTrigger() : cfg.getTarget();
        if (f == null) {
            String hint = "";
            try {
                double d = Double.parseDouble(v);
                if (d > 0 && d < 1) hint = "; write " + Math.round(d * 100) + "% for " + v + " of the window";
            } catch (NumberFormatException ignored) {
                // not a number at all
            }
            c.error(cfg.lineOf(key), who, "context " + key + " is a percentage of the usable window, written 80%" + hint);
        } else if (f < 0.01 || f > 0.99) {
            c.error(cfg.lineOf(key), who, "context " + key + " must be between 1% and 99%, not " + v);
        }
    }

    private static void positive(ScriptValidator.Checker c, String who, AgentDef.ContextConfig cfg, String key, boolean zeroOk) {
        if (!cfg.has(key) || cfg.getValues().get(key).isReference()) return;
        String v = cfg.get(key);
        try {
            double d = Double.parseDouble(v);
            if (d != Math.floor(d) || d < (zeroOk ? 0 : 1)) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            c.error(cfg.lineOf(key), who, "context " + key + " must be a whole number " + (zeroOk ? "of zero or more" : "of one or more") + ", not " + v);
        }
    }

}
