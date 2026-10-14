package io.github.llm4j.loom.memory;

import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.budget.CharsPerTokenEstimator;
import io.github.llm4j.context.CompactionRequest;
import io.github.llm4j.context.CompactionResult;
import io.github.llm4j.context.ContextCompactor;
import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.loom.runtime.VariableContext;
import io.github.llm4j.model.Message;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * A basic MemoryEngine that implements a traditional transcript accumulation model.
 * It appends all tasks and responses to a growing transcript.
 *
 * <p>With {@link #limits} it also stays bounded: when the transcript is over the limit of the agent that has just stored an exchange, a compaction strategy
 * is applied to it, one entry per exchange, so a task is never separated from its answer.
 */
public class TranscriptAccumulationEngine implements MemoryEngine {

    private static final Logger log = Logger.getLogger(TranscriptAccumulationEngine.class.getName());

    /**
     * How one agent bounds the shared transcript.
     *
     * @param maxTokens the most the transcript may be, in estimated tokens, before it is compacted
     * @param compactor how to make it smaller
     * @param targetAt the share of {@code maxTokens} to compact down to
     * @param keepRecent how many of the latest exchanges are never touched
     */
    public record Limit(long maxTokens, ContextCompactor compactor, double targetAt, int keepRecent) {
        public Limit {
            if (maxTokens < 1) throw new IllegalArgumentException("maxTokens must be positive, got " + maxTokens);
            if (compactor == null) throw new IllegalArgumentException("a compactor is needed");
            if (!(targetAt > 0 && targetAt < 1)) throw new IllegalArgumentException("targetAt must be in (0, 1), got " + targetAt);
            if (keepRecent < 0) throw new IllegalArgumentException("keepRecent must not be negative");
        }
    }

    /** One exchange, or a note standing for exchanges a strategy removed. */
    private record Entry(String text) { }

    private List<Entry> entries = new ArrayList<>();
    private Function<AgentDef, Limit> limits;

    /** Bounds the transcript by what each agent asks for ({@code null} for an agent that asks for nothing). */
    public synchronized TranscriptAccumulationEngine limits(Function<AgentDef, Limit> limits) {
        this.limits = limits;
        return this;
    }

    public synchronized boolean bounded() {
        return limits != null;
    }

    @Override
    public synchronized String assembleContext(AgentDef agentDef, String taskIntent, VariableContext context) {
        if (entries.isEmpty()) {
            return taskIntent;
        }

        return "Conversation History:\n"
                + render(entries)
                + "\n\nCurrent Task:\n"
                + taskIntent;
    }

    @Override
    public synchronized void storeOutcome(AgentDef agentDef, String taskIntent, AgentResult result, VariableContext context) {
        entries.add(new Entry("Agent [" + agentDef.getName() + "] Task: " + taskIntent + "\n"
                + "Agent [" + agentDef.getName() + "] Response: " + result.getFinalAnswer() + "\n\n"));
        Limit limit = limits == null ? null : limits.apply(agentDef);
        if (limit != null) compact(limit);
    }

    private static long size(List<Entry> list) {
        return CharsPerTokenEstimator.INSTANCE.prompt(io.github.llm4j.model.LLMRequest.builder().addUserMessage(render(list)).build());
    }

    private static String render(List<Entry> list) {
        StringBuilder sb = new StringBuilder();
        for (Entry e : list) sb.append(e.text());
        return sb.toString();
    }

    private void compact(Limit limit) {
        if (size(entries) <= limit.maxTokens()) return;
        Map<String, Entry> byText = new java.util.HashMap<>();
        List<Message> messages = new ArrayList<>();
        for (Entry e : entries) {
            Message m = Message.user(e.text());
            byText.put(e.text(), e);
            messages.add(m);
        }
        long target = Math.max(1, (long) Math.floor(limit.maxTokens() * limit.targetAt()));
        try {
            CompactionResult result = limit.compactor().compact(messages, CompactionRequest.builder(target)
                    .estimator(CharsPerTokenEstimator.INSTANCE).keepRecent(limit.keepRecent()).protectTask(false).build());
            List<Entry> next = new ArrayList<>();
            for (Message m : result.messages()) {
                Entry known = byText.get(m.getContent());
                next.add(known != null ? known : new Entry("(Earlier exchanges, " + result.strategy() + "): " + m.getContent() + "\n\n"));
            }
            if (size(next) < size(entries)) entries = next;
        } catch (RuntimeException e) {
            log.warning("Compacting the transcript failed, so it is left as it was: " + e);
        }
    }

    @Override
    public synchronized Object mark() {
        return List.copyOf(entries);
    }

    @Override
    @SuppressWarnings("unchecked")
    public synchronized void restore(Object mark) {
        if (mark instanceof List<?> saved) entries = new ArrayList<>((List<Entry>) saved);
    }
}
