package io.github.llm4j.agent.knowledge.store;

import io.github.llm4j.agent.knowledge.KnowledgeGraph;
import io.github.llm4j.agent.knowledge.model.Entity;
import io.github.llm4j.agent.knowledge.model.Triple;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory implementation of a knowledge graph.
 * Suitable for small to medium-sized graphs.
 */
public class InMemoryGraphStore implements KnowledgeGraph {

    private final Map<String, Entity> entities;
    private final List<Triple> triples;

    public InMemoryGraphStore() {
        this.entities = new ConcurrentHashMap<>();
        this.triples = Collections.synchronizedList(new ArrayList<>());
    }

    @Override
    public void addEntity(Entity entity) {
        Objects.requireNonNull(entity, "entity cannot be null");
        entities.put(entity.getId(), entity);
    }

    @Override
    public Entity getEntity(String id) {
        return entities.get(id);
    }

    @Override
    public List<Entity> findEntities(String type, Map<String, Object> filters) {
        return entities.values().stream()
                .filter(entity -> type == null || entity.getType().equals(type))
                .filter(entity -> matchesFilters(entity, filters))
                .collect(Collectors.toList());
    }

    @Override
    public void addTriple(Triple triple) {
        Objects.requireNonNull(triple, "triple cannot be null");

        // Ensure entities exist in the graph
        addEntity(triple.getSubject());
        addEntity(triple.getObject());

        triples.add(triple);
    }

    @Override
    public List<Triple> getTriples(String subjectId) {
        return findTriples(subjectId, null, null);
    }

    @Override
    public List<Triple> findTriples(String subjectId, String predicateType, String objectId) {
        return triples.stream()
                .filter(triple -> subjectId == null || triple.getSubject().getId().equals(subjectId))
                .filter(triple -> predicateType == null || triple.getPredicate().getType().equals(predicateType))
                .filter(triple -> objectId == null || triple.getObject().getId().equals(objectId))
                .collect(Collectors.toList());
    }

    @Override
    public int getEntityCount() {
        return entities.size();
    }

    @Override
    public int getTripleCount() {
        return triples.size();
    }

    @Override
    public void clear() {
        entities.clear();
        triples.clear();
    }

    /**
     * Checks if an entity matches the given property filters.
     *
     * @param entity  the entity to check
     * @param filters the filters to apply
     * @return true if entity matches all filters
     */
    private boolean matchesFilters(Entity entity, Map<String, Object> filters) {
        if (filters == null || filters.isEmpty()) {
            return true;
        }

        for (Map.Entry<String, Object> filter : filters.entrySet()) {
            Object propertyValue = entity.getProperty(filter.getKey());
            Object filterValue = filter.getValue();

            if (propertyValue == null || !propertyValue.equals(filterValue)) {
                return false;
            }
        }

        return true;
    }
}
