package io.github.llm4j.agent.knowledge;

import io.github.llm4j.agent.knowledge.model.Entity;
import io.github.llm4j.agent.knowledge.model.Triple;

import java.util.List;
import java.util.Map;

/**
 * Interface for knowledge graph storage and querying.
 */
public interface KnowledgeGraph {

    /**
     * Adds an entity to the knowledge graph.
     *
     * @param entity the entity to add
     */
    void addEntity(Entity entity);

    /**
     * Retrieves an entity by its ID.
     *
     * @param id the entity ID
     * @return the entity, or null if not found
     */
    Entity getEntity(String id);

    /**
     * Finds entities by type and optional property filters.
     *
     * @param type    the entity type
     * @param filters optional property filters
     * @return list of matching entities
     */
    List<Entity> findEntities(String type, Map<String, Object> filters);

    /**
     * Adds a triple (relationship) to the knowledge graph.
     *
     * @param triple the triple to add
     */
    void addTriple(Triple triple);

    /**
     * Gets all triples where the given entity is the subject.
     *
     * @param subjectId the subject entity ID
     * @return list of triples
     */
    List<Triple> getTriples(String subjectId);

    /**
     * Finds triples matching the given pattern.
     * Use null for wildcards.
     *
     * @param subjectId     subject entity ID (null for any)
     * @param predicateType predicate type (null for any)
     * @param objectId      object entity ID (null for any)
     * @return list of matching triples
     */
    List<Triple> findTriples(String subjectId, String predicateType, String objectId);

    /**
     * Returns the number of entities in the graph.
     *
     * @return entity count
     */
    int getEntityCount();

    /**
     * Returns the number of triples in the graph.
     *
     * @return triple count
     */
    int getTripleCount();

    /**
     * Clears all entities and triples from the graph.
     */
    void clear();
}
