package io.github.llm4j.agent.knowledge.tools;

import io.github.llm4j.agent.Tool;
import io.github.llm4j.agent.knowledge.KnowledgeGraph;
import io.github.llm4j.agent.knowledge.model.Entity;
import io.github.llm4j.agent.knowledge.model.Triple;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Tool for querying a knowledge graph.
 */
public class GraphQueryTool implements Tool {

    private final KnowledgeGraph graph;

    public GraphQueryTool(KnowledgeGraph graph) {
        this.graph = Objects.requireNonNull(graph, "graph cannot be null");
    }

    @Override
    public String getName() {
        return "QueryKnowledgeGraph";
    }

    @Override
    public String getDescription() {
        return "Query the knowledge graph to find entities and relationships. " +
                "Input should be a JSON object with one of: " +
                "{'entityId': 'id'} to get entity details, " +
                "{'entityType': 'type'} to find entities by type, " +
                "{'subjectId': 'id'} to get all relationships from an entity, " +
                "{'subjectId': 'id', 'predicateType': 'type'} to find specific relationships.";
    }

    @Override
    public String execute(Map<String, Object> args) throws Exception {
        Objects.requireNonNull(args, "args cannot be null");

        // Query by entity ID
        if (args.containsKey("entityId")) {
            String entityId = (String) args.get("entityId");
            Entity entity = graph.getEntity(entityId);

            if (entity == null) {
                return String.format("Entity with ID '%s' not found", entityId);
            }

            return formatEntity(entity);
        }

        // Query by entity type
        if (args.containsKey("entityType")) {
            String entityType = (String) args.get("entityType");
            List<Entity> entities = graph.findEntities(entityType, null);

            if (entities.isEmpty()) {
                return String.format("No entities found with type '%s'", entityType);
            }

            return formatEntities(entities);
        }

        // Query relationships
        if (args.containsKey("subjectId")) {
            String subjectId = (String) args.get("subjectId");
            String predicateType = (String) args.get("predicateType");

            List<Triple> triples = graph.findTriples(subjectId, predicateType, null);

            if (triples.isEmpty()) {
                return String.format("No relationships found for entity '%s'", subjectId);
            }

            return formatTriples(triples);
        }

        return "Invalid query. Please provide 'entityId', 'entityType', or 'subjectId'.";
    }

    private String formatEntity(Entity entity) {
        StringBuilder result = new StringBuilder();
        result.append(String.format("Entity: %s (Type: %s)\n", entity.getId(), entity.getType()));
        result.append("Properties:\n");

        for (Map.Entry<String, Object> prop : entity.getProperties().entrySet()) {
            result.append(String.format("  - %s: %s\n", prop.getKey(), prop.getValue()));
        }

        return result.toString();
    }

    private String formatEntities(List<Entity> entities) {
        StringBuilder result = new StringBuilder();
        result.append(String.format("Found %d entities:\n", entities.size()));

        for (Entity entity : entities) {
            result.append(String.format("- %s (Type: %s)\n", entity.getId(), entity.getType()));
        }

        return result.toString();
    }

    private String formatTriples(List<Triple> triples) {
        StringBuilder result = new StringBuilder();
        result.append(String.format("Found %d relationships:\n", triples.size()));

        for (Triple triple : triples) {
            result.append(String.format("- %s -[%s]-> %s\n",
                    triple.getSubject().getId(),
                    triple.getPredicate().getType(),
                    triple.getObject().getId()));
        }

        return result.toString();
    }
}
