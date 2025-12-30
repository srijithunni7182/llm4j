package io.github.llm4j.multiagent.service;

import io.github.llm4j.agent.knowledge.KnowledgeGraph;
import io.github.llm4j.agent.knowledge.model.Relation;
import io.github.llm4j.agent.knowledge.model.Triple;
import io.github.llm4j.agent.knowledge.model.Triple;
import io.github.llm4j.agent.knowledge.store.InMemoryGraphStore;
import io.github.llm4j.agent.rag.embedding.EmbeddingProvider;
import io.github.llm4j.agent.rag.store.InMemoryVectorStore;
import io.github.llm4j.agent.rag.store.VectorStore;
import io.github.llm4j.multiagent.persistence.entity.KnowledgeEntity;
import io.github.llm4j.multiagent.persistence.entity.KnowledgeTripleEntity;
import io.github.llm4j.multiagent.persistence.entity.VectorEntryEntity;
import io.github.llm4j.multiagent.persistence.repository.KnowledgeRepository;
import io.github.llm4j.multiagent.persistence.repository.KnowledgeTripleRepository;
import io.github.llm4j.multiagent.persistence.repository.VectorEntryRepository;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SharedKnowledgeService {

    private final KnowledgeRepository knowledgeRepository;
    private final KnowledgeTripleRepository knowledgeTripleRepository;
    private final VectorEntryRepository vectorEntryRepository;
    private final EmbeddingProvider embeddingProvider;

    // In-memory stores for active sessions
    private final Map<String, VectorStore> activeVectorStores = new ConcurrentHashMap<>();
    private final Map<String, KnowledgeGraph> activeGraphs = new ConcurrentHashMap<>();

    public VectorStore getVectorStore(String sessionId) {
        return activeVectorStores.computeIfAbsent(sessionId, k -> new InMemoryVectorStore());
    }

    public KnowledgeGraph getKnowledgeGraph(String sessionId) {
        return activeGraphs.computeIfAbsent(sessionId, k -> new InMemoryGraphStore());
    }

    public EmbeddingProvider getEmbeddingProvider() {
        return embeddingProvider;
    }

    public void indexDocument(String sessionId, io.github.llm4j.agent.rag.document.Document document) {
        VectorStore store = getVectorStore(sessionId);
        float[] embedding = embeddingProvider.embed(document.getContent());
        store.add(document.getId(), embedding, document.getMetadata());
    }

    /**
     * Archive session knowledge from memory to H2 database.
     */
    @Transactional
    public void archiveSession(String sessionId) {
        log.info("Archiving knowledge for session: {}", sessionId);

        VectorStore vectorStore = activeVectorStores.get(sessionId);
        if (vectorStore instanceof InMemoryVectorStore inMemoryVectorStore) {
            archiveVectorStore(sessionId, inMemoryVectorStore);
        }

        KnowledgeGraph graph = activeGraphs.get(sessionId);
        if (graph instanceof InMemoryGraphStore inMemoryGraphStore) {
            archiveGraph(sessionId, inMemoryGraphStore);
        }

        // Clear from memory
        activeVectorStores.remove(sessionId);
        activeGraphs.remove(sessionId);
    }

    private void archiveVectorStore(String sessionId, InMemoryVectorStore store) {
        vectorEntryRepository.deleteBySessionId(sessionId); // Clear previous if any

        for (VectorStore.VectorEntry entry : store.getAllEntries()) {
            Map<String, String> stringMetadata = entry.getMetadata().entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, e -> String.valueOf(e.getValue())));

            VectorEntryEntity entity = VectorEntryEntity.builder()
                    .sessionId(sessionId)
                    .entryId(entry.getId())
                    .embedding(entry.getEmbedding())
                    .metadata(stringMetadata)
                    .build();
            vectorEntryRepository.save(entity);
        }
    }

    private void archiveGraph(String sessionId, InMemoryGraphStore graphStore) {
        knowledgeTripleRepository.deleteBySessionId(sessionId);
        knowledgeRepository.deleteBySessionId(sessionId);

        // Keep track of mapped entities to avoid duplicates
        Map<String, KnowledgeEntity> entityMap = new HashMap<>();

        for (Triple triple : graphStore.getAllTriples()) {
            KnowledgeEntity subject = getOrCreateEntity(sessionId, triple.getSubject(), entityMap);
            KnowledgeEntity object = getOrCreateEntity(sessionId, triple.getObject(), entityMap);

            KnowledgeTripleEntity tripleEntity = KnowledgeTripleEntity.builder()
                    .sessionId(sessionId)
                    .subject(subject)
                    .predicate(triple.getPredicate().getType())
                    .object(object)
                    .build();
            knowledgeTripleRepository.save(tripleEntity);
        }
    }

    private KnowledgeEntity getOrCreateEntity(String sessionId, io.github.llm4j.agent.knowledge.model.Entity libEntity,
            Map<String, KnowledgeEntity> entityMap) {
        return entityMap.computeIfAbsent(libEntity.getId(), id -> {
            Map<String, String> stringProps = libEntity.getProperties().entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, e -> String.valueOf(e.getValue())));

            return KnowledgeEntity.builder()
                    .sessionId(sessionId)
                    .entityId(libEntity.getId())
                    .type(libEntity.getType())
                    .properties(stringProps)
                    .build();
        });
    }

    /**
     * Load session knowledge from H2 database into memory.
     */
    @Transactional(readOnly = true)
    public void loadSession(String sessionId) {
        if (activeVectorStores.containsKey(sessionId)) {
            return; // Already in memory
        }

        log.info("Loading knowledge for session: {}", sessionId);

        loadVectorStore(sessionId);
        loadGraph(sessionId);
    }

    private void loadVectorStore(String sessionId) {
        List<VectorEntryEntity> entities = vectorEntryRepository.findBySessionId(sessionId);
        if (entities.isEmpty())
            return;

        VectorStore store = getVectorStore(sessionId);
        for (VectorEntryEntity entity : entities) {
            Map<String, Object> metadata = new HashMap<>(entity.getMetadata());
            store.add(entity.getEntryId(), entity.getEmbedding(), metadata);
        }
    }

    private void loadGraph(String sessionId) {
        List<KnowledgeTripleEntity> triples = knowledgeTripleRepository.findBySessionId(sessionId);
        if (triples.isEmpty())
            return;

        KnowledgeGraph graph = getKnowledgeGraph(sessionId);
        for (KnowledgeTripleEntity tripleEntity : triples) {
            graph.addTriple(new Triple(
                    mapToLibraryEntity(tripleEntity.getSubject()),
                    Relation.builder().type(tripleEntity.getPredicate()).build(),
                    mapToLibraryEntity(tripleEntity.getObject())));
        }
    }

    private io.github.llm4j.agent.knowledge.model.Entity mapToLibraryEntity(KnowledgeEntity entity) {
        io.github.llm4j.agent.knowledge.model.Entity.Builder builder = io.github.llm4j.agent.knowledge.model.Entity
                .builder()
                .id(entity.getEntityId())
                .type(entity.getType());

        entity.getProperties().forEach(builder::addProperty);
        return builder.build();
    }

    public KnowledgeStats getKnowledgeStats(String sessionId) {
        int vectorCount = 0;
        VectorStore vectorStore = activeVectorStores.get(sessionId);
        if (vectorStore instanceof InMemoryVectorStore inMemoryVectorStore) {
            vectorCount = inMemoryVectorStore.getAllEntries().size();
        } else {
            // Check persistence if not in memory (optional, but good for completeness)
            vectorCount = vectorEntryRepository.findBySessionId(sessionId).size();
        }

        int tripleCount = 0;
        KnowledgeGraph graph = activeGraphs.get(sessionId);
        if (graph instanceof InMemoryGraphStore inMemoryGraphStore) {
            tripleCount = inMemoryGraphStore.getAllTriples().size();
        } else {
            tripleCount = knowledgeTripleRepository.findBySessionId(sessionId).size();
        }

        return new KnowledgeStats(vectorCount, tripleCount);
    }

    public static class KnowledgeStats {
        private final int vectorCount;
        private final int tripleCount;

        public KnowledgeStats(int vectorCount, int tripleCount) {
            this.vectorCount = vectorCount;
            this.tripleCount = tripleCount;
        }

        public int getVectorCount() {
            return vectorCount;
        }

        public int getTripleCount() {
            return tripleCount;
        }
    }
}
