package io.github.llm4j.loom.tools;

import io.github.llm4j.agent.Tool;
import io.github.llm4j.agent.knowledge.KnowledgeGraph;
import io.github.llm4j.agent.knowledge.store.FileGraphStore;
import io.github.llm4j.agent.knowledge.store.InMemoryGraphStore;
import io.github.llm4j.agent.knowledge.tools.GraphExtractionTool;
import io.github.llm4j.agent.knowledge.tools.GraphQueryTool;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code tool Graph { use: knowledge_graph  store: "graphs/support.json" | memory  read_only: true }}: one
 * tool, named as declared, that adds relations to a graph and queries it. Declarations naming the same
 * store file share one graph.
 */
final class GraphKind implements ToolKind {

    private final Map<String, KnowledgeGraph> graphs = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return "knowledge_graph";
    }

    @Override
    public Set<String> required() {
        return Set.of("store");
    }

    @Override
    public Set<String> optional() {
        return Set.of("read_only");
    }

    @Override
    public String check(Map<String, String> options, Path baseDir) {
        String readOnly = options.get("read_only");
        if (readOnly != null && !readOnly.equals("true") && !readOnly.equals("false")) {
            return "read_only must be true or false";
        }
        String store = options.get("store");
        if ("memory".equals(store)) return null;
        Path file = baseDir.resolve(store);
        if (java.nio.file.Files.isDirectory(file)) return "store " + store + " is a directory; give a file, e.g. graphs/support.json";
        if (!java.nio.file.Files.exists(file)) return null; // created on the first change
        try {
            new FileGraphStore(file);
            return null;
        } catch (IllegalStateException e) {
            return "store " + store + " can't be read: " + e.getMessage();
        }
    }

    @Override
    public Tool create(String name, Map<String, String> options, Path baseDir) {
        String store = options.get("store");
        KnowledgeGraph graph = "memory".equals(store)
                ? graphs.computeIfAbsent("memory:" + name, k -> new InMemoryGraphStore())
                : graphs.computeIfAbsent(baseDir.resolve(store).toAbsolutePath().normalize().toString(),
                        k -> new FileGraphStore(Path.of(k)));
        return new GraphTool(name, graph, Boolean.parseBoolean(options.get("read_only")));
    }

    /** Dispatches {@code action: add | query} to the library's graph tools. */
    static final class GraphTool implements Tool {
        private final String name;
        private final boolean readOnly;
        private final GraphExtractionTool add;
        private final GraphQueryTool query;

        GraphTool(String name, KnowledgeGraph graph, boolean readOnly) {
            this.name = name;
            this.readOnly = readOnly;
            this.add = new GraphExtractionTool(graph);
            this.query = new GraphQueryTool(graph);
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return "A knowledge graph of entities and their relations"
                    + (readOnly ? " (read-only). " : ". ")
                    + "Arguments: action (\"query\"" + (readOnly ? "" : " or \"add\"") + "), then\n"
                    + "- query: {\"entityId\": id} for one entity, {\"entityType\": type} to list entities of a type, "
                    + "{\"subjectId\": id} for its relations, optionally with {\"predicateType\": type}.\n"
                    + (readOnly ? "" : "- add: {\"subject\": {\"id\", \"type\", \"properties\"}, \"predicate\": \"RELATION\", "
                    + "\"object\": {\"id\", \"type\", \"properties\"}}.");
        }

        @Override
        public String execute(Map<String, Object> args) throws Exception {
            Map<String, Object> rest = new HashMap<>(args == null ? Map.of() : args);
            Object action = rest.remove("action");
            if ("add".equals(action)) {
                if (readOnly) return "Error: " + name + " is read-only; only action \"query\" is allowed.";
                return add.execute(rest);
            }
            if ("query".equals(action)) return query.execute(rest);
            return "Error: action must be \"query\"" + (readOnly ? "" : " or \"add\"") + ", got " + action;
        }
    }
}
