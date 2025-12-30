package io.github.llm4j.agent.knowledge.tools;

import io.github.llm4j.agent.knowledge.KnowledgeGraph;
import io.github.llm4j.agent.knowledge.model.Entity;
import io.github.llm4j.agent.knowledge.model.Triple;
import io.github.llm4j.agent.knowledge.store.InMemoryGraphStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GraphExtractionToolTest {

    private KnowledgeGraph graph;
    private GraphExtractionTool tool;

    @BeforeEach
    void setUp() {
        graph = new InMemoryGraphStore();
        tool = new GraphExtractionTool(graph);
    }

    @Test
    void testExecuteSuccess() throws Exception {
        Map<String, Object> args = Map.of(
                "subject", Map.of(
                        "id", "alice",
                        "type", "Person",
                        "properties", Map.of("name", "Alice")),
                "predicate", "REPORTS_TO",
                "object", Map.of(
                        "id", "bob",
                        "type", "Person",
                        "properties", Map.of("name", "Bob")));

        String result = tool.execute(args);

        assertThat(result).contains("Successfully added relationship");
        assertThat(graph.getEntityCount()).isEqualTo(2);
        assertThat(graph.getTripleCount()).isEqualTo(1);

        Entity alice = graph.getEntity("alice");
        assertThat(alice.getProperty("name")).isEqualTo("Alice");

        Triple triple = graph.getTriples("alice").get(0);
        assertThat(triple.getPredicate().getType()).isEqualTo("REPORTS_TO");
        assertThat(triple.getObject().getId()).isEqualTo("bob");
    }

    @Test
    void testExecuteMissingFields() throws Exception {
        Map<String, Object> args = Map.of(
                "subject", Map.of("id", "alice"));

        String result = tool.execute(args);

        assertThat(result).contains("Error: Missing required fields");
    }
}
