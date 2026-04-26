package io.github.llm4j.engram.core;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import io.github.llm4j.engram.core.models.MemoryObject;
import io.github.llm4j.engram.core.models.ScoredMemory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryStore implements VectorStore {
    private final ConcurrentHashMap<String, MemoryObject> store = new ConcurrentHashMap<>();
    private final EmbeddingModel embeddingModel;
    private final String storagePath;
    private final ObjectMapper mapper;

    // Weights from spec
    private static final double W_SIMILARITY = 0.35;
    private static final double W_RECENCY = 0.15;
    private static final double W_REINFORCEMENT = 0.15;
    private static final double W_IMPORTANCE = 0.10;
    private static final double DAMPING_FACTOR = 0.3;

    public InMemoryStore() {
        this(null);
    }

    public InMemoryStore(String storagePath) {
        this.embeddingModel = new AllMiniLmL6V2EmbeddingModel();
        this.storagePath = storagePath;
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
        this.mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        load();
    }

    public float[] embed(String text) {
        return embeddingModel.embed(text).content().vector();
    }

    public void add(MemoryObject memory) {
        // Interference check: if topicKey matches an existing non-shadow memory, shadow it.
        if (memory.getTopicKey() != null && !memory.getTopicKey().isEmpty()) {
            store.values().stream()
                .filter(m -> !m.isShadow() && memory.getTopicKey().equals(m.getTopicKey()))
                .forEach(m -> m.setShadow(true));
        }
        store.put(memory.getId(), memory);
    }

    public void removeByContent(String content) {
        if (content == null || content.trim().isEmpty()) return;
        store.entrySet().removeIf(entry -> content.equals(entry.getValue().getContent()));
    }

    public List<ScoredMemory> scoreCandidates(String taskIntent, int topN, double minScore) {
        float[] taskVector = embed(taskIntent);
        List<ScoredMemory> candidates = new ArrayList<>();

        for (MemoryObject mem : store.values()) {
            if (mem.isShadow()) continue;

            double similarity = cosineSimilarity(taskVector, mem.getEmbedding());
            double recency = calculateRecencyScore(mem.getLastAccessedAt());
            double reinforcement = Math.log10(1 + mem.getReinforcementCount());
            double decay = calculateDecay(mem);

            double score = (W_SIMILARITY * similarity)
                         + (W_RECENCY * recency)
                         + (W_REINFORCEMENT * reinforcement)
                         + (W_IMPORTANCE * mem.getImportance())
                         - decay;

            if (score >= minScore) {
                candidates.add(new ScoredMemory(mem, score));
            }
        }

        candidates.sort(Comparator.comparingDouble(ScoredMemory::score).reversed());
        return candidates.subList(0, Math.min(topN, candidates.size()));
    }

    private double calculateDecay(MemoryObject memory) {
        double decayRate = switch (memory.getTier()) {
            case EPISODIC -> 0.05;
            case SEMANTIC -> 0.001;
            case WORKING -> 0.0;
        };
        
        long elapsedHours = Duration.between(memory.getLastAccessedAt(), Instant.now()).toHours();
        if (elapsedHours < 0) elapsedHours = 0;

        return decayRate 
             * Math.exp(-memory.getReinforcementCount() * DAMPING_FACTOR) 
             * elapsedHours;
    }

    private double calculateRecencyScore(Instant lastAccessedAt) {
        long elapsedMinutes = Duration.between(lastAccessedAt, Instant.now()).toMinutes();
        if (elapsedMinutes < 0) return 1.0;
        // Simple decay for recency score
        return Math.max(0.0, 1.0 - (elapsedMinutes / 1440.0)); // scales over 24h
    }

    private double cosineSimilarity(float[] vectorA, float[] vectorB) {
        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < vectorA.length; i++) {
            dotProduct += vectorA[i] * vectorB[i];
            normA += Math.pow(vectorA[i], 2);
            normB += Math.pow(vectorB[i], 2);
        }
        if (normA == 0 || normB == 0) return 0.0;
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    public void save() {
        if (storagePath == null) return;
        try {
            List<MemoryObject> objects = new ArrayList<>(store.values());
            mapper.writeValue(new File(storagePath), objects);
        } catch (IOException e) {
            System.err.println("Failed to save Engram memory to " + storagePath + ": " + e.getMessage());
        }
    }

    private void load() {
        if (storagePath == null) return;
        File file = new File(storagePath);
        if (!file.exists()) return;

        try {
            List<MemoryObject> objects = mapper.readValue(file, new TypeReference<List<MemoryObject>>() {});
            for (MemoryObject obj : objects) {
                store.put(obj.getId(), obj);
            }
        } catch (IOException e) {
            System.err.println("Failed to load Engram memory from " + storagePath + ": " + e.getMessage());
        }
    }
}
