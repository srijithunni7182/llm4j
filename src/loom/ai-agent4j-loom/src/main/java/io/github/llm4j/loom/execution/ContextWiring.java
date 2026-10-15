package io.github.llm4j.loom.execution;

import io.github.llm4j.LLMClient;
import io.github.llm4j.context.ContextCompactor;
import io.github.llm4j.context.ContextCompactors;
import io.github.llm4j.context.ContextPolicy;
import io.github.llm4j.context.ContextWindow;
import io.github.llm4j.context.ProviderContext;
import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.loom.context.CompactorRegistry;
import io.github.llm4j.loom.context.WindowLayers;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Turns an agent's {@code context} block into what ai-agent4j takes: a policy with the model's window, and a compaction strategy. */
final class ContextWiring {

    private ContextWiring() { }

    /** The window that governs an agent that may be answered by any of these models: the smallest one known; empty when none is known. */
    static Optional<WindowLayers.Found> governing(WindowLayers layers, List<String> models) {
        WindowLayers.Found smallest = null;
        for (String m : models) {
            Optional<WindowLayers.Found> f = layers.find(m);
            if (f.isPresent() && (smallest == null || f.get().window().usableInput() < smallest.window().usableInput())) smallest = f.get();
        }
        return Optional.ofNullable(smallest);
    }

    static ContextPolicy policy(AgentDef.ContextConfig cfg, ContextWindow window) {
        ContextPolicy.Builder b = ContextPolicy.builder();
        if (window != null) b.window(window);
        if (cfg.getTrigger() != null) b.triggerAt(cfg.getTrigger());
        if (cfg.getTarget() != null) b.targetAt(cfg.getTarget());
        if (cfg.has("keep_recent")) b.keepRecent(cfg.getInt("keep_recent", 0));
        if (cfg.has("max_compactions")) b.maxCompactions(cfg.getInt("max_compactions", 1));
        return b.build();
    }

    /**
     * The strategy named by {@code compact:}: one, or several chained. Null for {@code none}. A strategy that calls a model gets {@code summarizer}
     * (when there is none, {@code summarize} falls back to {@code drop_oldest}, as the check warned).
     */
    static ContextCompactor compactor(AgentDef.ContextConfig cfg, CompactorRegistry custom, LLMClient summarizer) {
        List<String> names = cfg.compactNames();
        if (names.isEmpty() || names.equals(List.of("none"))) return null;
        Map<String, String> extra = cfg.extraOptions();
        Map<String, String> unclaimed = new LinkedHashMap<>(extra);
        ProviderContext ctx = summarizer == null ? ProviderContext.none() : ProviderContext.of(summarizer);
        List<ContextCompactor> stages = new ArrayList<>();
        for (String name : names) {
            if (name.equals("none")) continue;
            if (name.equals("chain")) throw new IllegalArgumentException("chain needs the strategies in parentheses: chain(trim_tool_results, drop_oldest)");
            if (custom.has(name)) {
                Map<String, String> mine = new LinkedHashMap<>();
                boolean provider = custom.takesOptions(name).orElse(true);
                if (provider) {
                    for (var e : extra.entrySet()) if (!ContextSettings.BUILT_IN_OPTIONS.containsKey(e.getKey())) mine.put(e.getKey(), e.getValue());
                    unclaimed.keySet().removeAll(mine.keySet());
                }
                stages.add(custom.create(name, mine, ctx));
                continue;
            }
            Map<String, String> mine = new LinkedHashMap<>();
            for (var e : extra.entrySet()) {
                if (name.equals(ContextSettings.BUILT_IN_OPTIONS.get(e.getKey()))) {
                    mine.put(e.getKey(), e.getValue());
                    unclaimed.remove(e.getKey());
                }
            }
            if (name.equals("summarize") && summarizer == null) {
                stages.add(ContextCompactors.byName("drop_oldest", Map.of()));
            } else {
                stages.add(ContextCompactors.byName(name, mine, ctx));
            }
        }
        if (!unclaimed.isEmpty()) {
            throw new IllegalArgumentException("context option " + String.join(", ", unclaimed.keySet()) + " is not taken by any strategy in compact: " + String.join(", ", names));
        }
        if (stages.isEmpty()) return null;
        return stages.size() == 1 ? stages.get(0) : ContextCompactors.chain(stages.toArray(new ContextCompactor[0]));
    }
}
