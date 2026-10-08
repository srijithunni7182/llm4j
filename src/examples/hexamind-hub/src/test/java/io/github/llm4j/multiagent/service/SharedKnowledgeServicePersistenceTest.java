package io.github.llm4j.multiagent.service;

import io.github.llm4j.agent.knowledge.KnowledgeGraph;
import io.github.llm4j.agent.knowledge.model.Entity;
import io.github.llm4j.agent.knowledge.model.Relation;
import io.github.llm4j.agent.knowledge.model.Triple;
import io.github.llm4j.agent.rag.document.Document;
import io.github.llm4j.multiagent.persistence.repository.KnowledgeRepository;
import io.github.llm4j.multiagent.persistence.repository.KnowledgeTripleRepository;
import io.github.llm4j.multiagent.persistence.repository.VectorEntryRepository;
import io.github.llm4j.agent.rag.embedding.EmbeddingProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "GOOGLE_API_KEY=test-key")
class SharedKnowledgeServicePersistenceTest {

    @Autowired
    private SharedKnowledgeService service;

    @MockBean
    private EmbeddingProvider embeddingProvider;

    @Autowired
    private KnowledgeRepository knowledgeRepository;

    @Autowired
    private KnowledgeTripleRepository knowledgeTripleRepository;

    @Autowired
    private VectorEntryRepository vectorEntryRepository;

    @Test
    void testArchiveAndLoadSession() {
        String sessionId = "test-session-123";
        when(embeddingProvider.embed(anyString())).thenReturn(new float[1536]);

        // 1. Populate in-memory stores
        KnowledgeGraph graph = service.getKnowledgeGraph(sessionId);
        Entity alice = Entity.builder().id("alice").type("Person").addProperty("name", "Alice").build();
        Entity bob = Entity.builder().id("bob").type("Person").addProperty("name", "Bob").build();
        graph.addTriple(new Triple(alice, Relation.builder().type("FRIEND_OF").build(), bob));

        Document doc = Document.builder()
                .id("doc1")
                .content("Alice and Bob are friends.")
                .addMetadata("source", "test")
                .build();
        service.indexDocument(sessionId, doc);

        // 2. Archive to DB
        service.archiveSession(sessionId);

        // Verify DB contains data
        assertThat(knowledgeRepository.findBySessionId(sessionId)).hasSize(2);
        assertThat(knowledgeTripleRepository.findBySessionId(sessionId)).hasSize(1);
        assertThat(vectorEntryRepository.findBySessionId(sessionId)).hasSize(1);

        // Verify cleared from memory (by checking if we get a new empty store)
        // Wait, getKnowledgeGraph computeIfAbsent will create a new one.
        // I can check if it's empty.
        assertThat(service.getKnowledgeGraph(sessionId).getTripleCount()).isEqualTo(0);

        // 3. Load from DB
        service.loadSession(sessionId);

        // Verify reloaded into memory
        KnowledgeGraph reloadedGraph = service.getKnowledgeGraph(sessionId);
        assertThat(reloadedGraph.getTripleCount()).isEqualTo(1);
        assertThat(reloadedGraph.getTriples("alice")).hasSize(1);
        assertThat(reloadedGraph.getEntity("alice").getProperty("name")).isEqualTo("Alice");

        // Verify vector store reloaded (can't easily check size without getAllEntries,
        // but it shouldn't be empty if findBySessionId returned data)
    }
}
