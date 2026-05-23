package io.github.llm4j.tantrik.console.service;

import io.github.llm4j.LLMClient;
import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.execution.DefaultLLMClientFactory;
import io.github.llm4j.loom.execution.LLMClientFactory;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.tantrik.TantrikExecutor;
import io.github.llm4j.tantrik.console.model.RunEvent;
import io.github.llm4j.tantrik.console.model.RunRequest;
import io.github.llm4j.tantrik.console.model.RunStatus;
import io.github.llm4j.tantrik.console.model.RunSummary;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

@Service
public class TantrikRunService {
    /**
     * Ring buffer capped at 50 entries. Access-order LinkedHashMap evicts the
     * least-recently-accessed entry once the map exceeds 50 entries.
     * Wrapped with Collections.synchronizedMap for thread safety.
     */
    private final Map<String, RunSummary> runs = Collections.synchronizedMap(
            new LinkedHashMap<>(51, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, RunSummary> eldest) {
                    return size() > 50;
                }
            }
    );

    /** Per-run cancellation flags. */
    private final Map<String, AtomicBoolean> cancellationTokens = new ConcurrentHashMap<>();

    private final Map<String, List<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> runAgentTiers = new ConcurrentHashMap<>();

    public RunSummary createRun(RunRequest request) {
        String runId = UUID.randomUUID().toString();
        RunSummary summary = new RunSummary();
        summary.setRunId(runId);
        summary.setStatus(RunStatus.PENDING);
        summary.setStartedAt(Instant.now());
        summary.setWorkflowName(request.getWorkflowName());
        runs.put(runId, summary);
        cancellationTokens.put(runId, new AtomicBoolean(false));
        emitters.put(runId, new CopyOnWriteArrayList<>());
        emitEvent(runId, new RunEvent("RUN_ACCEPTED", "Run accepted by Tantrik console.", "SYSTEM", Instant.now(), Map.of()));
        executeRunAsync(runId, request);
        return summary;
    }

    public RunSummary getRun(String runId) {
        return runs.get(runId);
    }

    public List<RunSummary> listRuns() {
        synchronized (runs) {
            return new ArrayList<>(runs.values());
        }
    }

    /**
     * Cancels a run by setting its cancellation flag, marking the RunSummary as
     * CANCELLED, and completing all SSE emitters for that run.
     *
     * @param runId the run to cancel
     * @return {@code true} if the run was found and cancelled, {@code false} if not found
     */
    public boolean cancelRun(String runId) {
        RunSummary summary = runs.get(runId);
        if (summary == null) {
            return false;
        }
        AtomicBoolean token = cancellationTokens.get(runId);
        if (token != null) {
            token.set(true);
        }
        summary.setStatus(RunStatus.CANCELLED);
        summary.setCompletedAt(Instant.now());
        emitEvent(runId, new RunEvent("RUN_CANCELLED", "Run was cancelled.", "SYSTEM", Instant.now(), Map.of()));
        List<SseEmitter> runEmitters = emitters.getOrDefault(runId, List.of());
        runEmitters.forEach(SseEmitter::complete);
        return true;
    }

    public SseEmitter subscribe(String runId) {
        SseEmitter emitter = new SseEmitter(3_600_000L);
        List<SseEmitter> runEmitters = emitters.computeIfAbsent(runId, ignored -> new CopyOnWriteArrayList<>());
        runEmitters.add(emitter);
        RunSummary summary = runs.get(runId);
        if (summary != null) {
            for (RunEvent event : summary.getEvents()) {
                sendSafely(emitter, event);
            }
        }
        emitter.onCompletion(() -> runEmitters.remove(emitter));
        emitter.onTimeout(() -> runEmitters.remove(emitter));
        return emitter;
    }

    @Async
    public void executeRunAsync(String runId, RunRequest request) {
        RunSummary summary = runs.get(runId);
        if (summary == null) {
            return;
        }

        summary.setStatus(RunStatus.RUNNING);
        emitEvent(runId, new RunEvent("RUN_STARTED", "Execution started.", "SYSTEM", Instant.now(), Map.of("workflow", request.getWorkflowName())));

        try {
            if (request.getScript() == null || request.getScript().isBlank()) {
                throw new IllegalArgumentException("Request must include a non-empty Loom script.");
            }

            LoomScript script = parseScript(request.getScript());
            runAgentTiers.put(runId, buildAgentTierMap(script));
            LLMClientFactory clientFactory = request.isMockMode() ? mockFactory() : new DefaultLLMClientFactory();
            TantrikExecutor executor = new TantrikExecutor(script, new ToolRegistry(), clientFactory);

            Thread monitorThread = startTraceMonitor(runId, executor);
            executor.initialize();
            executor.executeWorkflow(request.getWorkflowName(), request.getInitialContext());
            monitorThread.join(1000L);

            summary.setStatus(RunStatus.SUCCESS);
            summary.setCompletedAt(Instant.now());
            emitEvent(runId, new RunEvent("RUN_COMPLETED", "Execution completed successfully.", "SYSTEM", Instant.now(), Map.of()));
        } catch (Exception ex) {
            summary.setStatus(RunStatus.FAILED);
            summary.setError(ex.getMessage());
            summary.setCompletedAt(Instant.now());
            emitEvent(runId, new RunEvent("RUN_FAILED", ex.getMessage(), "SYSTEM", Instant.now(), Map.of("errorClass", ex.getClass().getSimpleName())));
        } finally {
            List<SseEmitter> runEmitters = emitters.getOrDefault(runId, List.of());
            runEmitters.forEach(SseEmitter::complete);
            runAgentTiers.remove(runId);
        }
    }

    private LoomScript parseScript(String source) {
        Lexer lexer = new Lexer(source);
        LoomParser parser = new LoomParser(lexer.tokenize());
        return parser.parseScript();
    }

    private Thread startTraceMonitor(String runId, TantrikExecutor executor) {
        Thread monitor = new Thread(() -> {
            int cursor = 0;
            while (!Thread.currentThread().isInterrupted()) {
                // Check cancellation flag — interrupt the monitor thread when set
                AtomicBoolean cancelled = cancellationTokens.get(runId);
                if (cancelled != null && cancelled.get()) {
                    Thread.currentThread().interrupt();
                    return;
                }

                List<TantrikExecutor.TantrikTrace> traces = executor.getTraces();
                while (cursor < traces.size()) {
                    TantrikExecutor.TantrikTrace trace = traces.get(cursor++);
                    String tier = inferExecutionTier(trace.agent());
                    emitEvent(runId, new RunEvent(
                            "TRACE_" + trace.phase(),
                            "Agent " + trace.agent() + " phase " + trace.phase(),
                            tier,
                            trace.timestamp(),
                            Map.of(
                                    "status", trace.status(),
                                    "squeezed", trace.squeezed(),
                                    "inputTokensEstimate", trace.inputTokensEstimate(),
                                    "compressionRatio", trace.compressionRatio(),
                                    "error", trace.error() == null ? "" : trace.error()
                            )));
                }

                RunSummary run = runs.get(runId);
                if (run == null || run.getStatus() == RunStatus.SUCCESS || run.getStatus() == RunStatus.FAILED
                        || run.getStatus() == RunStatus.CANCELLED) {
                    return;
                }
                try {
                    Thread.sleep(200L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        monitor.setDaemon(true);
        monitor.start();
        return monitor;
    }

    private String inferExecutionTier(String agentName) {
        for (Map<String, String> byAgent : runAgentTiers.values()) {
            String tier = byAgent.get(agentName);
            if (tier != null) {
                return tier;
            }
        }
        return "UNKNOWN";
    }

    private Map<String, String> buildAgentTierMap(LoomScript script) {
        Map<String, String> map = new ConcurrentHashMap<>();
        for (AgentDef agentDef : script.getAgents()) {
            String model = agentDef.getModel() == null ? "" : agentDef.getModel().toLowerCase();
            String tier = (model.startsWith("ollama/") || model.contains("mistral") || model.contains("llama") || model.contains("gemma"))
                    ? "LOCAL"
                    : "CLOUD";
            map.put(agentDef.getName(), tier);
        }
        return map;
    }

    private void emitEvent(String runId, RunEvent event) {
        RunSummary summary = runs.get(runId);
        if (summary == null) {
            return;
        }
        summary.getEvents().add(event);
        for (SseEmitter emitter : emitters.getOrDefault(runId, List.of())) {
            sendSafely(emitter, event);
        }
    }

    private void sendSafely(SseEmitter emitter, RunEvent event) {
        try {
            emitter.send(SseEmitter.event().name("run-event").data(event));
        } catch (Exception ignored) {
            emitter.complete();
        }
    }

    private LLMClientFactory mockFactory() {
        return model -> new LLMClient() {
            @Override
            public LLMResponse chat(LLMRequest request) {
                String lastMessage = request.getMessages().isEmpty()
                        ? "EMPTY"
                        : request.getMessages().get(request.getMessages().size() - 1).getContent();
                return LLMResponse.builder().content("MOCK_RESPONSE: " + lastMessage).build();
            }

            @Override
            public Stream<LLMResponse> chatStream(LLMRequest request) {
                return Stream.empty();
            }
        };
    }
}
