package io.github.llm4j.loom.context;

import io.github.llm4j.context.ContextWindow;
import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.RoutingPolicyDef;
import io.github.llm4j.loom.security.AuditReport;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** What {@code weave audit} says about context: each agent's strategy and window, and the strategies mapped in {@code .loct} files. Reads files, loads no class, calls no model. */
public final class ContextAudit {

    private ContextAudit() { }

    public static AuditReport add(AuditReport report, LoomScript script, Path scriptFile) {
        ContextFiles.beside(scriptFile);
        ContextFiles files = ContextFiles.current();
        WindowLayers layers = files.windowLayers();
        CompactorRegistry registry = new CompactorRegistry();
        for (Path f : files.loctFiles()) registry.load(f);
        List<AuditReport.ContextUse> uses = new ArrayList<>();
        for (AgentDef a : script.getAgents()) {
            AgentDef.ContextConfig cfg = a.getContext();
            if (cfg == null) continue;
            List<String> models = modelsOf(script, a);
            WindowLayers.Found smallest = null;
            for (String m : models) {
                Optional<WindowLayers.Found> f = layers.find(m);
                if (f.isPresent() && (smallest == null || f.get().window().usableInput() < smallest.window().usableInput())) smallest = f.get();
            }
            String window = smallest == null ? "unknown" : describe(smallest.window());
            String source = smallest == null ? "none: compacts only after a provider's \"prompt too long\" error" : smallest.source();
            if (models.size() > 1 && smallest != null) window += " (smallest of " + String.join(", ", models) + ")";
            String strategies = cfg.compactNames().isEmpty() ? "none" : String.join(" -> ", cfg.compactNames().stream()
                    .map(n -> registry.has(n) ? n + " (yours)" : n).toList());
            uses.add(new AuditReport.ContextUse(a.getName(), cfg.getLine(), strategies, window, source, cfg.getSummarizer()));
        }
        List<AuditReport.CustomStrategy> mapped = registry.entries().stream()
                .map(e -> new AuditReport.CustomStrategy(e.name(), e.className(), e.file(), e.line())).toList();
        return uses.isEmpty() && mapped.isEmpty() ? report : report.withContext(uses, mapped);
    }

    private static String describe(ContextWindow w) {
        return w.tokens() + " tokens (" + w.usableInput() + " usable)";
    }

    private static List<String> modelsOf(LoomScript script, AgentDef a) {
        List<String> models = new ArrayList<>();
        if (a.getRoutingPolicy() != null) {
            for (RoutingPolicyDef p : script.getRoutingPolicies()) {
                if (p.getName().equals(a.getRoutingPolicy())) {
                    if (p.getPrimaryModel() != null) models.add(p.getPrimaryModel());
                    models.addAll(p.getFallbackModels());
                }
            }
        }
        if (models.isEmpty() && a.getModel() != null) models.add(a.getModel());
        return models;
    }
}
