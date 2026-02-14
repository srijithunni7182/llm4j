package io.github.llm4j.agent.knowledge.model;

import java.util.Objects;

/**
 * Represents a triple (subject-predicate-object) in a knowledge graph.
 */
public class Triple {

    private final Entity subject;
    private final Relation predicate;
    private final Entity object;

    public Triple(Entity subject, Relation predicate, Entity object) {
        this.subject = Objects.requireNonNull(subject, "subject cannot be null");
        this.predicate = Objects.requireNonNull(predicate, "predicate cannot be null");
        this.object = Objects.requireNonNull(object, "object cannot be null");
    }

    public Entity getSubject() {
        return subject;
    }

    public Relation getPredicate() {
        return predicate;
    }

    public Entity getObject() {
        return object;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        Triple triple = (Triple) o;
        return Objects.equals(subject, triple.subject) &&
                Objects.equals(predicate.getType(), triple.predicate.getType()) &&
                Objects.equals(object, triple.object);
    }

    @Override
    public int hashCode() {
        return Objects.hash(subject, predicate.getType(), object);
    }

    @Override
    public String toString() {
        return String.format("(%s)-[%s]->(%s)",
                subject.getId(),
                predicate.getType(),
                object.getId());
    }
}
