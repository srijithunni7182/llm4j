package io.github.llm4j.agent.knowledge;

import io.github.llm4j.agent.knowledge.model.Entity;
import io.github.llm4j.agent.knowledge.model.Relation;
import io.github.llm4j.agent.knowledge.model.Triple;
import io.github.llm4j.agent.knowledge.store.InMemoryGraphStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for InMemoryGraphStore
 */
class InMemoryGraphStoreTest {

    private InMemoryGraphStore graph;

    @BeforeEach
    void setUp() {
        graph = new InMemoryGraphStore();
    }

    @Test
    void testAddAndGetEntity() {
        Entity entity = Entity.builder()
                .id("person1")
                .type("Person")
                .addProperty("name", "Alice")
                .addProperty("age", 30)
                .build();

        graph.addEntity(entity);

        Entity retrieved = graph.getEntity("person1");
        assertThat(retrieved).isNotNull();
        assertThat(retrieved.getId()).isEqualTo("person1");
        assertThat(retrieved.getType()).isEqualTo("Person");
        assertThat(retrieved.getProperty("name")).isEqualTo("Alice");
    }

    @Test
    void testFindEntitiesByType() {
        graph.addEntity(Entity.builder().id("p1").type("Person").build());
        graph.addEntity(Entity.builder().id("p2").type("Person").build());
        graph.addEntity(Entity.builder().id("c1").type("Company").build());

        List<Entity> people = graph.findEntities("Person", null);
        assertThat(people).hasSize(2);
        assertThat(people).allMatch(e -> e.getType().equals("Person"));
    }

    @Test
    void testFindEntitiesWithFilters() {
        graph.addEntity(Entity.builder()
                .id("p1")
                .type("Person")
                .addProperty("city", "NYC")
                .build());

        graph.addEntity(Entity.builder()
                .id("p2")
                .type("Person")
                .addProperty("city", "SF")
                .build());

        List<Entity> nycPeople = graph.findEntities("Person", Map.of("city", "NYC"));
        assertThat(nycPeople).hasSize(1);
        assertThat(nycPeople.get(0).getId()).isEqualTo("p1");
    }

    @Test
    void testAddAndGetTriple() {
        Entity alice = Entity.builder().id("alice").type("Person").build();
        Entity bob = Entity.builder().id("bob").type("Person").build();
        Relation knows = Relation.builder().type("KNOWS").build();

        Triple triple = new Triple(alice, knows, bob);
        graph.addTriple(triple);

        assertThat(graph.getEntityCount()).isEqualTo(2);
        assertThat(graph.getTripleCount()).isEqualTo(1);

        List<Triple> aliceTriples = graph.getTriples("alice");
        assertThat(aliceTriples).hasSize(1);
        assertThat(aliceTriples.get(0).getObject().getId()).isEqualTo("bob");
    }

    @Test
    void testFindTriples() {
        Entity alice = Entity.builder().id("alice").type("Person").build();
        Entity bob = Entity.builder().id("bob").type("Person").build();
        Entity charlie = Entity.builder().id("charlie").type("Person").build();

        graph.addTriple(new Triple(alice, Relation.builder().type("KNOWS").build(), bob));
        graph.addTriple(new Triple(alice, Relation.builder().type("WORKS_WITH").build(), charlie));
        graph.addTriple(new Triple(bob, Relation.builder().type("KNOWS").build(), charlie));

        // Find all relationships from alice
        List<Triple> aliceTriples = graph.findTriples("alice", null, null);
        assertThat(aliceTriples).hasSize(2);

        // Find specific relationship type
        List<Triple> knowsTriples = graph.findTriples(null, "KNOWS", null);
        assertThat(knowsTriples).hasSize(2);

        // Find specific triple
        List<Triple> aliceKnowsBob = graph.findTriples("alice", "KNOWS", "bob");
        assertThat(aliceKnowsBob).hasSize(1);
    }

    @Test
    void testClear() {
        graph.addEntity(Entity.builder().id("e1").type("Test").build());
        graph.addTriple(new Triple(
                Entity.builder().id("e2").type("Test").build(),
                Relation.builder().type("REL").build(),
                Entity.builder().id("e3").type("Test").build()));

        assertThat(graph.getEntityCount()).isGreaterThan(0);
        assertThat(graph.getTripleCount()).isGreaterThan(0);

        graph.clear();

        assertThat(graph.getEntityCount()).isEqualTo(0);
        assertThat(graph.getTripleCount()).isEqualTo(0);
    }

    @Test
    void testEntityEquality() {
        Entity e1 = Entity.builder().id("same").type("Type1").build();
        Entity e2 = Entity.builder().id("same").type("Type2").build();

        assertThat(e1).isEqualTo(e2); // Same ID means equal
    }

    @Test
    void testTripleToString() {
        Triple triple = new Triple(
                Entity.builder().id("alice").type("Person").build(),
                Relation.builder().type("KNOWS").build(),
                Entity.builder().id("bob").type("Person").build());

        String str = triple.toString();
        assertThat(str).contains("alice");
        assertThat(str).contains("KNOWS");
        assertThat(str).contains("bob");
    }
}
