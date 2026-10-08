package io.github.llm4j.loom.execution;

import io.github.llm4j.LLMClient;
import io.github.llm4j.fairness.BiasContext;
import io.github.llm4j.fairness.BiasEvent;
import io.github.llm4j.fairness.BiasMonitor;
import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.privacy.MaskingLLMClient;
import io.github.llm4j.privacy.PIIDetector;
import io.github.llm4j.privacy.PIIEntity;
import io.github.llm4j.privacy.PIIType;
import io.github.llm4j.privacy.RegexPIIDetector;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * An agent's {@code guard { pii: … bias: … }}. PII means personal identifiers (email, phone, SSN, card,
 * IP address); URLs are not treated as personal. Findings are audited and traced with types and counts,
 * never the values themselves.
 */
final class AgentGuard {

    enum Pii { MASK, BLOCK, WARN }

    enum Bias { WARN, BLOCK }

    static final Set<String> PII_VALUES = Set.of("mask", "block", "warn");
    static final Set<String> BIAS_VALUES = Set.of("warn", "block");

    private final String agent;
    private final Pii pii;
    private final Bias bias;
    private final BiasMonitor monitor;
    private final HarnessExecutor executor;
    private final PIIDetector detector = new RegexPIIDetector();

    AgentGuard(String agent, AgentDef.GuardConfig config, BiasMonitor monitor, HarnessExecutor executor) {
        this.agent = agent;
        this.pii = config.getPii() == null ? null : Pii.valueOf(config.getPii().toUpperCase(Locale.ROOT));
        this.bias = config.getBias() == null ? null : Bias.valueOf(config.getBias().toUpperCase(Locale.ROOT));
        this.monitor = monitor;
        this.executor = executor;
    }

    /** With {@code pii: mask}, everything the agent sends its model is masked first. */
    LLMClient wrap(LLMClient client) {
        if (pii != Pii.MASK) return client;
        return new MaskingLLMClient(client, detector, MaskingLLMClient.PERSONAL, counts -> report("pii_masked", "model input", counts));
    }

    /** Before the agent runs: {@code block} refuses a task carrying PII; {@code warn} records it. */
    void checkTask(String task) {
        if (pii != Pii.BLOCK && pii != Pii.WARN) return;
        Map<PIIType, Integer> found = find(task);
        if (found.isEmpty()) return;
        if (pii == Pii.WARN) {
            report("pii_detected", "task", found);
            return;
        }
        report("pii_blocked", "task", found);
        throw new StepFailure("guard: the task for " + agent + " contains personal data (" + types(found)
                + "); pii: block keeps it from the model", null);
    }

    /** After the agent answers: mask, block or record PII, then check for bias. Returns what may be stored. */
    String checkAnswer(String answer) {
        String out = answer == null ? "" : answer;
        if (pii != null) {
            Map<PIIType, Integer> found = find(out);
            if (!found.isEmpty()) {
                switch (pii) {
                    case MASK -> {
                        out = MaskingLLMClient.mask(out, detector, MaskingLLMClient.PERSONAL, null);
                        report("pii_masked", "answer", found);
                    }
                    case BLOCK -> {
                        report("pii_blocked", "answer", found);
                        throw new StepFailure("guard: " + agent + "'s answer contains personal data (" + types(found)
                                + "); pii: block keeps it out of the run", null);
                    }
                    case WARN -> report("pii_detected", "answer", found);
                }
            }
        }
        if (bias != null) {
            List<BiasEvent> events = monitor.detectBias(out, BiasContext.builder().taskType("delegate").build());
            for (BiasEvent e : events) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("agent", agent);
                data.put("step", executor.currentStep());
                data.put("type", e.getType().name());
                data.put("severity", e.getSeverity().name());
                data.put("explanation", e.getExplanation() == null ? "" : e.getExplanation());
                executor.audit("bias_detected", data);
                executor.trace(TraceEvent.GUARD, agent, "bias " + e.getSeverity() + " " + e.getType() + ": " + e.getExplanation(), data);
            }
            if (bias == Bias.BLOCK && monitor.shouldIntervene(events)) {
                String what = events.stream().map(e -> e.getType() + " (" + e.getSeverity() + ")").distinct().toList().toString();
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("agent", agent);
                data.put("step", executor.currentStep());
                data.put("findings", what);
                executor.audit("bias_blocked", data);
                throw new StepFailure("guard: " + agent + "'s answer was blocked for bias " + what, null);
            }
        }
        return out;
    }

    /** What is kept (e.g. in conversation memory): masked with {@code pii: mask}, else as is. */
    String forStorage(String text) {
        return pii == Pii.MASK ? MaskingLLMClient.mask(text, detector, MaskingLLMClient.PERSONAL, null) : text;
    }

    private Map<PIIType, Integer> find(String text) {
        Map<PIIType, Integer> counts = new EnumMap<>(PIIType.class);
        if (text == null) return counts;
        for (PIIEntity e : detector.detect(text).getEntities()) {
            if (MaskingLLMClient.PERSONAL.contains(e.getType())) counts.merge(e.getType(), 1, Integer::sum);
        }
        return counts;
    }

    private static String types(Map<PIIType, Integer> found) {
        return String.join(", ", new TreeSet<>(found.keySet().stream().map(Enum::name).toList()));
    }

    private void report(String event, String where, Map<PIIType, Integer> counts) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("agent", agent);
        data.put("step", executor.currentStep());
        data.put("where", where);
        Map<String, Integer> byType = new java.util.TreeMap<>();
        counts.forEach((t, n) -> byType.put(t.name(), n));
        data.put("types", byType.toString());
        executor.audit(event, data);
        executor.trace(TraceEvent.GUARD, agent, event.replace('_', ' ') + " in " + where + ": " + byType, data);
    }
}
