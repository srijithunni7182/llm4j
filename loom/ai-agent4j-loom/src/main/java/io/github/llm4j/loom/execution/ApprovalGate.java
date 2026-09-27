package io.github.llm4j.loom.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.llm4j.loom.runtime.RunJournal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Asks a person before an approved-only tool call runs ({@code approve: [..]} on an agent). The answer is
 * journaled under the step, the tool and a hash of its exact arguments, so a resumed run never asks twice
 * for the same call — and never lets an answer approve a call nobody saw. When the human interface pauses
 * the run ({@link io.github.llm4j.loom.runtime.RunSuspended}), no thread waits for the answer.
 */
final class ApprovalGate {

    private static final ObjectMapper CANONICAL = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private final HarnessExecutor executor;

    ApprovalGate(HarnessExecutor executor) {
        this.executor = executor;
    }

    boolean approve(String agent, String tool, Map<String, Object> args, String thought) {
        String key = key(executor.currentStep(), tool, args);
        RunJournal journal = executor.getJournal();
        String masked = executor.maskPii(String.valueOf(args));
        audit("approval_requested", agent, tool, masked);
        String answer = journal.get(key).map(e -> String.valueOf(e.value())).orElse(null);
        if (answer == null) {
            String question = "Agent " + agent + " wants to call " + tool + " with " + args
                    + (thought == null || thought.isBlank() ? "" : ". Reason: " + thought.strip()) + ". Approve? yes/no";
            answer = executor.humanInterface().promptHuman(key, question); // may pause the run
            journal.put(key, new RunJournal.Entry("human", answer));
        }
        boolean yes = yes(answer);
        audit(yes ? "approval_granted" : "approval_rejected", agent, tool, masked);
        return yes;
    }

    static boolean yes(String answer) {
        String a = answer == null ? "" : answer.trim().toLowerCase(Locale.ROOT);
        return a.equals("yes") || a.equals("y") || a.equals("ok") || a.equals("approve") || a.equals("true");
    }

    /** {@code <step>#approve:<tool>:<12 hex of sha-256(tool + canonical json(args))>}. */
    static String key(String step, String tool, Map<String, Object> args) {
        try {
            String json = CANONICAL.writeValueAsString(sorted(args == null ? Map.of() : args));
            byte[] digest = MessageDigest.getInstance("SHA-256").digest((tool + json).getBytes(StandardCharsets.UTF_8));
            return step + "#approve:" + tool + ":" + HexFormat.of().formatHex(digest).substring(0, 12);
        } catch (Exception e) {
            throw new IllegalStateException("Could not key the approval of " + tool, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object sorted(Object value) {
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> out = new java.util.TreeMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), sorted(v)));
            return out;
        }
        if (value instanceof java.util.List<?> l) return l.stream().map(ApprovalGate::sorted).toList();
        return value;
    }

    private void audit(String event, String agent, String tool, String maskedArgs) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("agent", agent);
        data.put("tool", tool);
        data.put("step", executor.currentStep());
        data.put("args", maskedArgs);
        executor.audit(event, data);
    }
}
