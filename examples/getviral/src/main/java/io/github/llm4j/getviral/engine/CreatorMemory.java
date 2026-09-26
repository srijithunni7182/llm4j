package io.github.llm4j.getviral.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.engram.core.ContextIntelligenceAgent;
import io.github.llm4j.engram.core.EngramEngine;
import io.github.llm4j.engram.core.InMemoryStore;
import io.github.llm4j.engram.core.models.MemoryObject;
import io.github.llm4j.engram.core.models.MemoryTier;
import io.github.llm4j.getviral.studio.StudioEvents;
import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.loom.memory.MemoryEngine;
import io.github.llm4j.loom.runtime.VariableContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Engram as a creator's long-term memory, plugged into Loom as its {@link MemoryEngine}.
 *
 * <p>Memory is scoped on purpose: the agents that make strategic calls (the Showrunner and the
 * Strategist) receive an Engram briefing of what worked for this creator before; the agents whose
 * outcomes are worth learning from (Strategist, ViralityCritic) have them extracted into memory.
 * Everyone else gets their task untouched — no context bloat. Memories persist per creator on disk,
 * so every run makes the next one sharper.
 */
public class CreatorMemory implements MemoryEngine {

    static final Set<String> RECALLS = Set.of("Showrunner", "Strategist");
    static final Set<String> LEARNS_FROM = Set.of("Strategist", "ViralityCritic");

    private static final ObjectMapper JSON = new ObjectMapper();

    private final InMemoryStore store;
    private final EngramEngine engram;
    private final Path file;
    private final StudioEvents events;

    public CreatorMemory(Path dataDir, String creator, ContextIntelligenceAgent cia, StudioEvents events) {
        this.file = fileFor(dataDir, creator);
        try {
            Files.createDirectories(file.getParent());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create memory directory " + file.getParent(), e);
        }
        this.store = new InMemoryStore(file.toString());
        this.engram = new EngramEngine(store, cia);
        this.events = events != null ? events : StudioEvents.NONE;
    }

    public static Path fileFor(Path dataDir, String creator) {
        return dataDir.resolve("memory").resolve(creator.replaceAll("[^A-Za-z0-9_.-]", "_") + ".json");
    }

    @Override
    public String assembleContext(AgentDef agentDef, String taskIntent, VariableContext context) {
        if (!RECALLS.contains(agentDef.getName())) {
            return taskIntent;
        }
        String briefing = engram.assembleContext(agentDef, taskIntent, context);
        boolean recalled = briefing.length() > taskIntent.length() + 40;
        events.emit("memory_recall", Map.of(
                "agent", agentDef.getName(),
                "recalled", recalled,
                "briefing", recalled ? briefing : ""));
        return briefing;
    }

    @Override
    public void storeOutcome(AgentDef agentDef, String taskIntent, AgentResult result, VariableContext context) {
        if (!LEARNS_FROM.contains(agentDef.getName())) {
            return;
        }
        int before = count();
        engram.storeOutcome(agentDef, taskIntent, result, context);
        events.emit("memory_store", Map.of("agent", agentDef.getName(), "learned", Math.max(0, count() - before)));
    }

    /**
     * Records an explicit signal from the creator (hook choice, a 🔥 / 👎 rating). Signals that share
     * a {@code topic} shadow older ones — Engram's interference handling keeps only the latest view.
     */
    public void remember(String content, double importance, String topic) {
        store.add(new MemoryObject(content, store.embed(content), MemoryTier.SEMANTIC, importance, topic));
        store.save();
        events.emit("memory_store", Map.of("agent", "Creator", "learned", 1, "content", content));
    }

    private int count() {
        return list(file).size();
    }

    /** Active (non-shadowed) memories on disk for display. */
    public static List<Map<String, Object>> list(Path file) {
        List<Map<String, Object>> memories = new ArrayList<>();
        if (!Files.exists(file)) return memories;
        try {
            for (JsonNode node : JSON.readTree(file.toFile())) {
                Map<String, Object> memory = new LinkedHashMap<>();
                memory.put("content", node.path("content").asText());
                memory.put("tier", node.path("tier").asText());
                memory.put("importance", node.path("importance").asDouble());
                memory.put("shadow", node.path("shadow").asBoolean());
                memory.put("reinforced", node.path("reinforcementCount").asInt());
                memories.add(memory);
            }
        } catch (IOException e) {
            // unreadable memory file: show nothing rather than fail the studio
        }
        return memories;
    }
}
