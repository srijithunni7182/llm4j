package io.github.llm4j.tantrik;

import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.loom.ast.DelegateStmt;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.execution.DefaultLLMClientFactory;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.LLMClientFactory;
import io.github.llm4j.loom.execution.ToolRegistry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TantrikExecutor extends HarnessExecutor {
    private static final String TRACE_KEY = "_tantrik_trace";
    private final TantrikMemoryBridge memoryBridge;
    private final ContextSqueezer contextSqueezer;
    private final List<TantrikTrace> traces = new ArrayList<>();

    public TantrikExecutor(LoomScript script, ToolRegistry toolRegistry, LLMClientFactory llmClientFactory) {
        super(script, toolRegistry, new HybridLLMClientFactory(llmClientFactory, new HybridModelRegistry()));
        this.memoryBridge = new TantrikMemoryBridge(readMemoryEngine());
        this.contextSqueezer = new ContextSqueezer(resolveTokenBudget());
    }

    public TantrikExecutor(LoomScript script, ToolRegistry toolRegistry) {
        this(script, toolRegistry, new DefaultLLMClientFactory());
    }

    @Override
    protected String beforeDelegateExecution(DelegateStmt stmt, AgentDef agentDef, String resolvedPayload, String assembledContext) {
        String injectedContext = memoryBridge.recallAndInject(agentDef, resolvedPayload, getContext(), assembledContext);
        ContextSqueezer.SqueezeResult squeezeResult = contextSqueezer.squeeze(injectedContext);
        TantrikTrace trace = TantrikTrace.preTurn(stmt.getTargetAgent(), squeezeResult.inputTokensEstimate(), squeezeResult.squeezed(), squeezeResult.compressionRatio());
        traces.add(trace);
        getContext().setVariable(TRACE_KEY, trace.toMap());
        return squeezeResult.context();
    }

    @Override
    protected void afterDelegateExecution(
            DelegateStmt stmt,
            AgentDef agentDef,
            String resolvedPayload,
            String effectiveContext,
            AgentResult result,
            Object finalValue,
            DelegateSuccessHandler successHandler
    ) {
        successHandler.onSuccess(result, finalValue);
        memoryBridge.consolidate(agentDef, resolvedPayload, result, getContext());
        TantrikTrace trace = TantrikTrace.postTurn(stmt.getTargetAgent(), "SUCCESS");
        traces.add(trace);
        getContext().setVariable(TRACE_KEY, trace.toMap());
    }

    @Override
    protected void onDelegateError(
            DelegateStmt stmt,
            AgentDef agentDef,
            String resolvedPayload,
            String effectiveContext,
            Exception error,
            int attemptNumber,
            int maxAttempts
    ) {
        TantrikTrace trace = TantrikTrace.error(stmt.getTargetAgent(), error.getMessage(), attemptNumber, maxAttempts);
        traces.add(trace);
        getContext().setVariable(TRACE_KEY, trace.toMap());
        getContext().setVariable("_tantrik_last_error", error.getMessage());
    }

    public List<TantrikTrace> getTraces() {
        return Collections.unmodifiableList(traces);
    }

    private int resolveTokenBudget() {
        String configured = System.getProperty("tantrik.max.tokens", System.getenv().getOrDefault("TANTRIK_MAX_TOKENS", "4096"));
        try {
            return Integer.parseInt(configured);
        } catch (NumberFormatException ignored) {
            return 4096;
        }
    }

    private io.github.llm4j.loom.memory.MemoryEngine readMemoryEngine() {
        try {
            java.lang.reflect.Field field = HarnessExecutor.class.getDeclaredField("memoryEngine");
            field.setAccessible(true);
            return (io.github.llm4j.loom.memory.MemoryEngine) field.get(this);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to access Loom memory engine", e);
        }
    }

    public record TantrikTrace(
            String phase,
            String agent,
            String status,
            int inputTokensEstimate,
            boolean squeezed,
            double compressionRatio,
            String error,
            int attempt,
            int maxAttempts,
            Instant timestamp
    ) {
        static TantrikTrace preTurn(String agent, int estimate, boolean squeezed, double compressionRatio) {
            return new TantrikTrace("PRE_TURN", agent, "OK", estimate, squeezed, compressionRatio, null, 0, 0, Instant.now());
        }

        static TantrikTrace postTurn(String agent, String status) {
            return new TantrikTrace("POST_TURN", agent, status, 0, false, 1.0d, null, 0, 0, Instant.now());
        }

        static TantrikTrace error(String agent, String error, int attempt, int maxAttempts) {
            return new TantrikTrace("ERROR", agent, "FAILURE", 0, false, 1.0d, error, attempt, maxAttempts, Instant.now());
        }

        java.util.Map<String, Object> toMap() {
            java.util.Map<String, Object> data = new java.util.HashMap<>();
            data.put("phase", phase);
            data.put("agent", agent);
            data.put("status", status);
            data.put("inputTokensEstimate", inputTokensEstimate);
            data.put("squeezed", squeezed);
            data.put("compressionRatio", compressionRatio);
            data.put("error", error);
            data.put("attempt", attempt);
            data.put("maxAttempts", maxAttempts);
            data.put("timestamp", timestamp.toString());
            return data;
        }
    }
}
