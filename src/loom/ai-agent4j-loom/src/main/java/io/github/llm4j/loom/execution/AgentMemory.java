package io.github.llm4j.loom.execution;

import io.github.llm4j.agent.memory.ConversationStore;
import io.github.llm4j.agent.memory.FileConversationStore;
import io.github.llm4j.agent.memory.FileMemoryVectorStore;
import io.github.llm4j.agent.memory.InMemoryConversationStore;
import io.github.llm4j.agent.memory.InMemoryVectorStore;
import io.github.llm4j.agent.memory.SemanticMemoryService;
import io.github.llm4j.agent.memory.VectorStore;
import io.github.llm4j.agent.rag.embedding.EmbeddingProvider;
import io.github.llm4j.agent.tool.MemoryManagementTool;
import io.github.llm4j.agent.Tool;
import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.model.Message;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * One agent's own memory ({@code memory { … }}): its conversations, kept per session, and the long-term
 * facts it chose to save. The executor recalls both before a delegate and records the exchange after
 * it; replayed steps touch neither.
 */
final class AgentMemory {

    static final String MEMORY = "memory";

    /** What was recalled for a task, ready to put in front of it. */
    record Recall(List<Message> messages, List<String> facts) {
        boolean isEmpty() {
            return messages.isEmpty() && facts.isEmpty();
        }

        String format(String session) {
            StringBuilder sb = new StringBuilder();
            if (!messages.isEmpty()) {
                sb.append("Previous conversation (").append(session).append("):\n");
                for (Message m : messages) sb.append(m.getRole().getValue()).append(": ").append(m.getContent()).append('\n');
                sb.append('\n');
            }
            if (!facts.isEmpty()) {
                sb.append("Relevant context from user's long-term memory:\n");
                for (String f : facts) sb.append("- ").append(f).append('\n');
                sb.append('\n');
            }
            return sb.toString();
        }
    }

    private final String agent;
    private final AgentDef.MemoryConfig config;
    private final ConversationStore conversations;
    private final EmbeddingProvider embedding;
    private final VectorStore facts;
    private final Map<String, SemanticMemoryService> services = new ConcurrentHashMap<>();

    AgentMemory(String agent, AgentDef.MemoryConfig config, Path baseDir, EmbeddingProvider embedding) {
        this.agent = agent;
        this.config = config;
        String conversation = config.getConversation();
        this.conversations = conversation == null ? null
                : MEMORY.equals(conversation) ? new InMemoryConversationStore()
                : new FileConversationStore(baseDir.resolve(conversation));
        String factsAt = config.getFacts();
        this.embedding = factsAt == null ? null : embedding;
        this.facts = factsAt == null ? null
                : MEMORY.equals(factsAt) ? new InMemoryVectorStore()
                : new FileMemoryVectorStore(baseDir.resolve(factsAt));
    }

    boolean keepsFacts() {
        return facts != null;
    }

    /** The session a delegate belongs to: the resolved {@code session:}, else the agent's name. */
    String session(java.util.function.UnaryOperator<String> resolve) {
        String s = config.getSession();
        if (s == null) return agent;
        String resolved = resolve.apply(s);
        return resolved == null || resolved.isBlank() ? agent : resolved;
    }

    Recall recall(String session, String task) {
        List<Message> messages = conversations == null ? List.of() : conversations.loadHistory(key(session), config.getLimit());
        List<String> found = facts == null ? List.of()
                : service(session).recallRelevantFacts(task, config.getRecall(), (float) config.getMinSimilarity());
        return new Recall(messages, found);
    }

    /** Adds a finished exchange to the session's conversation. */
    void remember(String session, String task, String answer) {
        if (conversations == null) return;
        conversations.saveMessage(key(session), Message.user(task));
        conversations.saveMessage(key(session), Message.assistant(answer == null ? "" : answer));
    }

    /**
     * {@code save_memory_fact}, writing to whichever session the calling step belongs to (the same agent
     * may serve several users in parallel branches).
     */
    Tool factTool(Supplier<String> currentSession) {
        MemoryManagementTool described = new MemoryManagementTool(service(agent));
        return new Tool() {
            @Override
            public String getName() {
                return described.getName();
            }

            @Override
            public String getDescription() {
                return described.getDescription();
            }

            @Override
            public String execute(Map<String, Object> args) throws Exception {
                return new MemoryManagementTool(service(currentSession.get())).execute(args);
            }
        };
    }

    private SemanticMemoryService service(String session) {
        return services.computeIfAbsent(session, s -> new SemanticMemoryService(embedding, facts, s));
    }

    /**
     * A conversation id that is unique per (agent, session) and safe as a file name: readable prefix,
     * plus a hash so distinct sessions never share a file after sanitising.
     */
    String key(String session) {
        String readable = session.replaceAll("[^A-Za-z0-9._-]", "_");
        if (readable.length() > 40) readable = readable.substring(0, 40);
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest((agent + "\u0000" + session).getBytes(StandardCharsets.UTF_8));
            return agent + "--" + readable + "-" + HexFormat.of().formatHex(hash, 0, 4);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
