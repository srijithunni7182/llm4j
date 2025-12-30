package io.github.llm4j.agent.knowledge.tools;

import io.github.llm4j.agent.Tool;
import io.github.llm4j.agent.knowledge.KnowledgeGraph;
import io.github.llm4j.agent.knowledge.model.Entity;
import io.github.llm4j.agent.knowledge.model.Relation;
import io.github.llm4j.agent.knowledge.model.Triple;

import java.util.Map;
import java.util.Objects;

/**
 * Tool for extracting and adding information to a knowledge graph.
 */
public class GraphExtractionTool implements Tool {

    private final KnowledgeGraph graph;

    public GraphExtractionTool(KnowledgeGraph graph) {
        this.graph = Objects.requireNonNull(graph, "graph cannot be null");
    }

    @Override
    public String getName() {
        return "ExtractKnowledge";
    }

    @Override
    public String getDescription() {
        return "Add entities and relationships to the knowledge graph. " +
                "Input should be a JSON object with: " +
                "{'subject': {'id': 'id', 'type': 'type', 'properties': {...}}, " +
                "'predicate': 'relationship_type', " +
                "'object': {'id': 'id', 'type': 'type', 'properties': {...}}}";
    }

    @Override
    public String execute(Map<String, Object> args) throws Exception {
        Objects.requireNonNull(args, "args cannot be null");

        if (!args.containsKey("subject") || !args.containsKey("predicate") || !args.containsKey("object")) {
            return "Error: Missing required fields 'subject', 'predicate', or 'object'";
        }

        Entity subject = parseEntity((Map<String, Object>) args.get("subject"));
        Entity object = parseEntity((Map<String, Object>) args.get("object"));
        String predicateType = (String) args.get("predicate");

        Triple triple = new Triple(
                subject,
                Relation.builder().type(predicateType).build(),
                object);

        graph.addTriple(triple);

        return String.format("Successfully added relationship: %s -[%s]-> %s",
                subject.getId(), predicateType, object.getId());
    }

    private Entity parseEntity(Map<String, Object> data) {
        String id = (String) data.get("id");
        String type = (String) data.get("type");
        Map<String, Object> properties = (Map<String, Object>) data.get("properties");

        Entity.Builder builder = Entity.builder()
                .id(id)
                .type(type);

        if (properties != null) {
            for (Map.Entry<String, Object> entry : properties.entrySet()) {
                builder.addProperty(entry.getKey(), entry.getValue());
            }
        }

        return builder.build();
    }
}
