package io.github.llm4j.engram.core;

import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.engram.core.models.ContextBriefing;
import io.github.llm4j.engram.core.models.MemoryEvent;
import io.github.llm4j.engram.core.models.MemoryObject;
import io.github.llm4j.engram.core.models.MemoryTier;
import io.github.llm4j.engram.core.models.ScoredMemory;
import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.loom.memory.MemoryEngine;
import io.github.llm4j.engram.core.models.IntrospectionResult;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class EngramEngine implements MemoryEngine {

    private final VectorStore store;
    private final ContextIntelligenceAgent cia;
    private static final int MAX_CANDIDATES = 50;
    private static final double MIN_SCORE = 0.10;

    // Cache to hold the recent briefing for the Introspection Loop
    private final ConcurrentHashMap<String, ContextBriefing> recentBriefings = new ConcurrentHashMap<>();

    /**
     * Creates an EngramEngine with the default in-memory store and template CIA (no LLM).
     */
    public EngramEngine() {
        this.store = new InMemoryStore();
        this.cia = new TemplateContextIntelligenceAgent();
    }

    /**
     * Creates an EngramEngine with JSON-file-backed persistence and template CIA.
     */
    public EngramEngine(String storagePath) {
        this.store = new InMemoryStore(storagePath);
        this.cia = new TemplateContextIntelligenceAgent();
    }

    /**
     * Creates an EngramEngine with JSON-file-backed persistence and a custom CIA.
     */
    public EngramEngine(String storagePath, ContextIntelligenceAgent cia) {
        this.store = new InMemoryStore(storagePath);
        this.cia = cia;
    }

    /**
     * Creates an EngramEngine with a fully custom VectorStore and CIA.
     * This is the production constructor — inject PGVectorStore, PineconeStore, etc.
     * Pass {@code null} as the store to fall back to an in-memory store (useful for benchmarks).
     */
    public EngramEngine(VectorStore store, ContextIntelligenceAgent cia) {
        this.store = store != null ? store : new InMemoryStore();
        this.cia = cia;
    }

    @Override
    public String assembleContext(AgentDef agentDef, String taskIntent, VariableContext context) {
        List<ScoredMemory> candidates = store.scoreCandidates(taskIntent, MAX_CANDIDATES, MIN_SCORE);

        ContextBriefing briefing = cia.synthesizeBriefing(taskIntent, candidates);
        recentBriefings.put(taskIntent, briefing);

        // Reinforce retrieved candidates for decay calculation
        for (ScoredMemory sm : candidates) {
            sm.memory().markAccessed();
            sm.memory().reinforce();
        }

        return briefing.briefingText();
    }

    @Override
    public void storeOutcome(AgentDef agentDef, String taskIntent, AgentResult result, VariableContext context) {
        List<MemoryEvent> extractedEvents = cia.extractMemories(agentDef, taskIntent, result);

        // Phase 2.5: Introspection Loop — review execution and apply corrections
        ContextBriefing briefing = recentBriefings.remove(taskIntent);
        if (briefing == null) {
            briefing = new ContextBriefing("", false);
        }

        IntrospectionResult evaluation = cia.introspect(agentDef, taskIntent, briefing, result);
        
        // Process extractions from introspection
        if (evaluation.extractedMemories() != null && !evaluation.extractedMemories().isEmpty()) {
            System.out.println("[Engram] Introspector identified " + evaluation.extractedMemories().size() + " strategic insights.");
            extractedEvents.addAll(evaluation.extractedMemories());
        }

        // Process deletions (Interference Shadowing)
        if (evaluation.memoriesToDelete() != null && !evaluation.memoriesToDelete().isEmpty()) {
            System.out.println("[Engram] Introspector shadowing " + evaluation.memoriesToDelete().size() + " superseded memories.");
            for (String content : evaluation.memoriesToDelete()) {
                store.removeByContent(content);
            }
        }

        for (MemoryEvent event : extractedEvents) {
            float[] embedding = store.embed(event.content());

            MemoryTier tier = MemoryTier.WORKING;
            if ("EPISODIC".equals(event.type()) || "OUTCOME".equals(event.type())) {
                tier = MemoryTier.EPISODIC;
            } else if ("ARCH_FACT".equals(event.type()) || "CONSTRAINT".equals(event.type())
                    || "ERROR_PATTERN".equals(event.type()) || "PERFORMANCE_FEEDBACK".equals(event.type())) {
                tier = MemoryTier.SEMANTIC;
            }

            MemoryObject obj = new MemoryObject(
                    event.content(),
                    embedding,
                    tier,
                    event.importance() > 0 ? event.importance() : 0.5,
                    event.topicKey()
            );

            store.add(obj);
        }

        if (!extractedEvents.isEmpty() ||
                (evaluation.memoriesToDelete() != null && !evaluation.memoriesToDelete().isEmpty())) {
            store.save();
        }
    }

    /** For tests — returns the underlying store. */
    public VectorStore getStore() {
        return store;
    }
}
