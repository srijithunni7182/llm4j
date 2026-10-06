package io.github.llm4j.loom.graph;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes a {@link GraphResult} as JSON (schema version 1, {@code graph-result.schema.json}). Keys that are not set are
 * left out, never written as {@code null}, and key order is fixed so the same script always gives the same text.
 */
public final class GraphJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DefaultPrettyPrinter PRINTER = new DefaultPrettyPrinter()
            .withSeparators(Separators.createDefaultInstance().withObjectFieldValueSpacing(Separators.Spacing.AFTER))
            .withObjectIndenter(new DefaultIndenter("  ", "\n"))
            .withArrayIndenter(new DefaultIndenter("  ", "\n"));

    private GraphJson() {
    }

    public static String write(GraphResult result) {
        try {
            return MAPPER.writer(PRINTER).writeValueAsString(tree(result));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("The graph could not be written as JSON", e);
        }
    }

    static Map<String, Object> tree(GraphResult result) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", result.version());
        out.put("entry", result.entry());
        out.put("files", result.files().stream().map(GraphJson::file).toList());
        out.put("workflows", result.workflows().stream().map(GraphJson::workflow).toList());
        out.put("agents", result.agents().stream().map(GraphJson::agent).toList());
        if (!result.runBudget().isEmpty()) {
            out.put("runBudget", result.runBudget());
        }
        out.put("diagnostics", result.diagnostics().stream().map(GraphJson::diagnostic).toList());
        return out;
    }

    private static Map<String, Object> file(ImportFile file) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", file.path());
        out.put("imports", file.imports());
        return out;
    }

    private static Map<String, Object> workflow(WorkflowGraph graph) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", graph.name());
        out.put("file", graph.file());
        if (graph.line() > 0) {
            out.put("line", graph.line());
        }
        out.put("params", graph.params());
        out.put("nodes", graph.nodes().stream().map(GraphJson::node).toList());
        out.put("edges", graph.edges().stream().map(GraphJson::edge).toList());
        return out;
    }

    private static Map<String, Object> node(GraphNode node) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", node.id());
        out.put("kind", node.kind());
        out.put("label", node.label());
        put(out, "agent", node.agent());
        put(out, "bound", node.bound());
        if (node.source() != null) {
            out.put("source", source(node.source()));
        }
        if (node.call() != null) {
            Map<String, Object> call = new LinkedHashMap<>();
            call.put("workflow", node.call().workflow());
            put(call, "file", node.call().file());
            out.put("call", call);
        }
        if (node.unresolved()) {
            out.put("unresolved", true);
        }
        put(out, "parent", node.parent());
        put(out, "branch", node.branch());
        if (!node.attrs().isEmpty()) {
            out.put("attrs", node.attrs());
        }
        return out;
    }

    private static Map<String, Object> edge(GraphEdge edge) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", edge.from());
        out.put("to", edge.to());
        put(out, "label", edge.label());
        return out;
    }

    private static Map<String, Object> agent(AgentInfo agent) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", agent.name());
        put(out, "model", agent.model());
        put(out, "temperature", agent.temperature());
        put(out, "persona", agent.persona());
        putList(out, "tools", agent.tools());
        putList(out, "mcp", agent.mcp());
        putList(out, "skills", agent.skills());
        putList(out, "knowledge", agent.knowledge());
        putList(out, "approve", agent.approve());
        if (agent.approveAll()) {
            out.put("approveAll", true);
        }
        if (!agent.budget().isEmpty()) {
            out.put("budget", agent.budget());
        }
        put(out, "maxIterations", agent.maxIterations());
        if (agent.source() != null) {
            out.put("source", source(agent.source()));
        }
        return out;
    }

    private static Map<String, Object> diagnostic(Diagnostic diagnostic) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("severity", diagnostic.severity().word());
        out.put("file", diagnostic.file());
        out.put("line", diagnostic.line());
        out.put("message", diagnostic.message());
        return out;
    }

    private static Map<String, Object> source(SourceRef source) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("file", source.file());
        out.put("line", source.line());
        return out;
    }

    private static void put(Map<String, Object> out, String key, Object value) {
        if (value != null) {
            out.put(key, value);
        }
    }

    private static void putList(Map<String, Object> out, String key, List<String> value) {
        if (!value.isEmpty()) {
            out.put(key, new ArrayList<>(value));
        }
    }
}
