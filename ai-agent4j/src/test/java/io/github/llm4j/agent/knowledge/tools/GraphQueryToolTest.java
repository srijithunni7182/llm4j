package io.github.llm4j.agent.knowledge.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import io.github.llm4j.agent.knowledge.KnowledgeGraph;
import io.github.llm4j.agent.knowledge.model.Entity;
import io.github.llm4j.agent.knowledge.model.Relation;
import io.github.llm4j.agent.knowledge.model.Triple;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GraphQueryToolTest {

    @Mock private KnowledgeGraph mockGraph;

    private GraphQueryTool tool;

    @BeforeEach
    void setUp() {
        tool = new GraphQueryTool(mockGraph);
    }

    @Test
    void execute_shouldReturnEntity_whenEntityIdProvided() throws Exception {
        Entity entity =
                Entity.builder().id("e1").type("Person").addProperty("name", "John").build();
        when(mockGraph.getEntity("e1")).thenReturn(entity);

        String result = tool.execute(Map.of("entityId", "e1"));

        assertThat(result).contains("Entity: e1 (Type: Person)");
        assertThat(result).contains("name: John");
    }

    @Test
    void execute_shouldReturnNotFound_whenEntityMissing() throws Exception {
        when(mockGraph.getEntity("e1")).thenReturn(null);

        String result = tool.execute(Map.of("entityId", "e1"));

        assertThat(result).contains("Entity with ID 'e1' not found");
    }

    @Test
    void execute_shouldReturnEntities_whenEntityTypeProvided() throws Exception {
        Entity entity =
                Entity.builder().id("e1").type("Person").addProperty("name", "John").build();
        when(mockGraph.findEntities("Person", null)).thenReturn(List.of(entity));

        String result = tool.execute(Map.of("entityType", "Person"));

        assertThat(result).contains("Found 1 entities");
        assertThat(result).contains("- e1 (Type: Person)");
    }

    @Test
    void execute_shouldReturnRelationships_whenSubjectIdProvided() throws Exception {
        Entity subject = Entity.builder().id("e1").type("Person").build();
        Entity object = Entity.builder().id("e2").type("City").build();
        Relation relation = Relation.builder().type("LIVES_IN").build();
        Triple triple = new Triple(subject, relation, object);

        when(mockGraph.findTriples("e1", "LIVES_IN", null)).thenReturn(List.of(triple));

        String result = tool.execute(Map.of("subjectId", "e1", "predicateType", "LIVES_IN"));

        assertThat(result).contains("Found 1 relationships");
        assertThat(result).contains("- e1 -[LIVES_IN]-> e2");
    }
}
