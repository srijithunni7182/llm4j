package io.github.llm4j.loom.execution;

import io.github.llm4j.agent.ReActAgent;
import io.github.llm4j.audit.AuditEvent;
import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.loom.ast.DelegateStmt;
import io.github.llm4j.loom.ast.Statement;
import io.github.llm4j.loom.memory.MemoryEngine;
import io.github.llm4j.loom.runtime.RateLimitFailure;
import io.github.llm4j.loom.runtime.RunJournal;
import io.github.llm4j.loom.runtime.VariableContext;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * Runs one {@code delegate} step: replays it from the journal on a resume, assembles what the agent is told (memory, knowledge, guards, schema), runs it
 * with retries, backoff, a timeout, budgets and rate limits, and records the result or hands a failure to {@code on_failure}. {@link HarnessExecutor} owns one
 * and keeps the overridable hooks ({@code agentForDelegate}, {@code beforeDelegateExecution}, ...) that embedders subclass; they reach this class through
 * {@link Host}.
 */
final class DelegateRunner {
    private static final Logger log = Logger.getLogger(HarnessExecutor.class.getName());

    /** What a delegate step needs from the run. */
    interface Host {
        /** Counts a delegate started on this thread, so a retried delegate's tool calls are numbered afresh. */
        void beginAttempt();

        /** A step may name its agent or variable by a {@code {path}} into the run's data. */
        String resolveName(String name);

        /** The agent the script defines under this name; throws when there is none. */
        AgentDef agentDef(String name);

        /** The built agent, or null. */
        ReActAgent activeAgent(String name);

        /** The id of the step the calling thread is executing. */
        String stepId();

        RunJournal journal();

        VariableContext context();

        /** Variables as the current block sees them. */
        VariableContext view();

        String resolvePayload(String raw);

        Path baseDir();

        AgentVoice voice(String agent);

        AgentMemory memory(String agent);

        AgentGuard guard(String agent);

        io.github.llm4j.loom.knowledge.Retriever retriever(String agent);

        /** Which memory session each running step belongs to. */
        Map<String, String> sessions();

        MemoryEngine memoryEngine();

        io.github.llm4j.audit.AuditLogger auditLogger();

        String sessionId();

        java.util.Optional<io.github.llm4j.loom.prompt.PromptUse> promptUsed(AgentDef agent);

        /** The budgets that enclose the step this thread is running. */
        ThreadLocal<List<io.github.llm4j.budget.Budget>> scopes();

        void trace(String type, String agent, String text, Map<String, Object> data);

        void runFailureHandler(List<Statement> onFailure, String message);

        void traceFailure(String kind, String name, String message, Throwable cause, io.github.llm4j.telemetry.ErrorCategory given, int attempts, boolean handled);

        ThreadScope.Snapshot captureScope();

        java.util.concurrent.ExecutorService branches();

        // The hooks an embedder overrides on the executor.

        ReActAgent agentForDelegate(DelegateStmt stmt, AgentDef agentDef, ReActAgent agent);

        String beforeDelegateExecution(DelegateStmt stmt, AgentDef agentDef, String resolvedPayload, String assembledContext);

        void afterDelegateExecution(DelegateStmt stmt, AgentDef agentDef, String resolvedPayload, String effectiveContext,
                io.github.llm4j.agent.AgentResult result, Object finalValue, HarnessExecutor.DelegateSuccessHandler successHandler);

        void onDelegateError(DelegateStmt stmt, AgentDef agentDef, String resolvedPayload, String effectiveContext, Exception error, int attemptNumber, int maxAttempts);

        void onDelegateReplayed(DelegateStmt stmt, AgentDef agentDef, Object value);
    }

    private final BudgetCoordinator budgets;
    private final LimitPolicy limits;
    private final InterventionCoordinator interventions;
    private final Host host;

    DelegateRunner(BudgetCoordinator budgets, LimitPolicy limits, InterventionCoordinator interventions, Host host) {
        this.budgets = budgets;
        this.limits = limits;
        this.interventions = interventions;
        this.host = host;
    }

    void execute(DelegateStmt del) {
        host.beginAttempt();
        String agentName = host.resolveName(del.getTargetAgent());
        String variableName = host.resolveName(del.getVariableName());
        AgentDef agentDef = host.agentDef(agentName);
        ReActAgent agent = host.activeAgent(agentName);
        if (agent == null) throw new IllegalStateException("Agent not found: " + agentName);

        // A resumed run: this step already happened — reuse its recorded result, don't call the model.
        String stepId = host.stepId();
        var recorded = host.journal().get(stepId).filter(e -> !"retry".equals(e.kind())); // "retry": an operator asked for a failed step to be tried again
        if (recorded.isPresent()) {
            RunJournal.Entry entry = recorded.get();
            if ("failed".equals(entry.kind())) {
                handleExhausted(del, agentName, String.valueOf(entry.value()), null);
            } else {
                host.context().setVariable(variableName, entry.value());
                host.journal().get(stepId + "#audio").ifPresent(audio -> host.context().setVariable(variableName + "_audio", audio.value()));
                host.onDelegateReplayed(del, agentDef, entry.value());
                host.trace(TraceEvent.DELEGATE_REPLAYED, agentName, "reused the recorded result", null);
            }
            return;
        }

        String payload = host.resolvePayload(del.getPayload());
        AgentVoice voice = host.voice(agentName);
        Path heard = voice == null ? null : voice.audioTask(payload);
        if (heard != null) {
            try {
                payload = voice.transcribe(heard);
                host.trace(TraceEvent.VOICE, agentName, "heard " + host.baseDir().toAbsolutePath().normalize().relativize(heard.toAbsolutePath().normalize())
                        + ": " + payload, null);
            } catch (RuntimeException e) {
                failStep(del, stepId, agentName, "voice: couldn't transcribe " + heard.getFileName() + ": " + e.getMessage(), e);
                return;
            }
        }
        final String resolvedPayload = payload;

        String contextBriefing = host.memoryEngine().assembleContext(agentDef, resolvedPayload, host.view());
        io.github.llm4j.loom.knowledge.Retriever retriever = host.retriever(agentName);
        if (retriever != null) {
            String found = io.github.llm4j.loom.knowledge.Retriever.format(retriever.search(resolvedPayload, retriever.topK()));
            if (!found.isEmpty()) contextBriefing = found + "\n" + contextBriefing;
        }
        AgentMemory agentMemory = host.memory(agentName);
        final String session = agentMemory == null ? null : agentMemory.session(host::resolvePayload);
        if (agentMemory != null) {
            host.sessions().put(stepId, session);
            AgentMemory.Recall recall = agentMemory.recall(session, resolvedPayload);
            Map<String, Object> data = new java.util.LinkedHashMap<>();
            data.put("agent", agentName);
            data.put("session", session);
            data.put("messages", String.valueOf(recall.messages().size()));
            data.put("facts", String.valueOf(recall.facts().size()));
            host.auditLogger().logConversationEvent(host.sessionId(), null, "memory_recalled", data);
            host.trace(TraceEvent.MEMORY, agentName, "recalled " + recall.messages().size() + " messages and "
                    + recall.facts().size() + " facts for " + session, data);
            if (!recall.isEmpty()) contextBriefing = recall.format(session) + contextBriefing;
        }
        contextBriefing = host.beforeDelegateExecution(del, agentDef, resolvedPayload, contextBriefing);
        AgentGuard guard = host.guard(agentName);
        if (guard != null) {
            try {
                guard.checkTask(contextBriefing);
            } catch (StepFailure blocked) {
                host.sessions().remove(stepId);
                failStep(del, stepId, agentName, blocked.getMessage(), blocked);
                return;
            }
        }
        Map<String, Object> startData = new java.util.LinkedHashMap<>();
        startData.put("variable", variableName);
        host.promptUsed(agentDef).ifPresent(u -> {
            startData.put("prompt", u.label());
            startData.put("promptHash", u.hash());
        });
        host.trace(TraceEvent.DELEGATE_START, agentName, resolvedPayload, startData);
        
        // A step's own `expecting { ... }` schema wins over the agent's output_schema.
        io.github.llm4j.loom.ast.SchemaDef schema = del.getExpecting() != null ? del.getExpecting() : agentDef.getOutputSchema();
        if (schema != null) {
            contextBriefing += "\n\nCRITICAL: You MUST respond in valid JSON format only, following this schema: "
                            + PayloadText.stringify(schema);
        }

        int attempts = 0;
        int maxAttempts = del.getRetryCount() + 1;
        int[] limitWaits = {0};
        int[] asks = {0};
        Exception lastError = null;
        io.github.llm4j.budget.BudgetExceeded refused = null;
        io.github.llm4j.budget.BudgetExceeded partial = null;
        List<io.github.llm4j.budget.Budget> outerScopes = host.scopes().get();
        if (del.getBudget() != null && budgets.budgeting()) {
            host.scopes().set(BudgetCoordinator.plus(outerScopes, budgets.newBudget("step " + stepId, del.getBudget())));
        }
        try {
        while (attempts < maxAttempts) {
            try {
                if (attempts > 0 && del.getBackoffMillis() > 0) {
                    Thread.sleep(del.getBackoffMillis() << Math.min(attempts - 1, 10));
                }
                log.info("Delegating to " + agentName + " (Attempt " + (attempts + 1) + ")");
                ReActAgent runAgent = host.agentForDelegate(del, agentDef, agent);
                // The agent runs under a handle that is registered while it runs, so a timeout (or anyone else) can stop it.
                io.github.llm4j.agent.RunHandle handle = interventions.open(stepId, agentName);
                io.github.llm4j.agent.AgentResult result;
                try {
                    result = del.getTimeoutMillis() > 0
                            ? runWithTimeout(runAgent, contextBriefing, del.getTimeoutMillis(), handle)
                            : runAgent.run(contextBriefing, handle);
                } finally {
                    interventions.close(stepId);
                }
                if (result.isCancelled()) {
                    // Never a success: not journaled as the step's value, and not retried (retrying would undo what the person asked for).
                    String why = interventions.reasonFor(stepId);
                    throw new StepFailure("the agent run was cancelled" + (why == null || why.isBlank() ? "" : ": " + why), null);
                }
                if (result.budgetExhausted()) {
                    io.github.llm4j.budget.BudgetExceeded be = result.getBudgetExceeded();
                    if (budgets.refills(be)) throw io.github.llm4j.ratelimit.RateLimited.of(be); // pause until it refills
                    if (budgets.askForMore(be, asks)) continue; // a person allowed more: run the step again
                    budgets.markExhausted();
                    // Refused before its first call: nothing to keep, the step failed.
                    if (result.getUsage().getLlmCalls() == 0) throw result.getBudgetExceeded();
                    partial = result.getBudgetExceeded(); // ran out part-way: keep its best answer
                }

                String answer = result.getFinalAnswer();
                if (guard != null) {
                    answer = guard.checkAnswer(answer);
                    if (!answer.equals(result.getFinalAnswer())) result = result.toBuilder().finalAnswer(answer).build();
                }
                Object finalValue = answer;
                if (schema != null && partial == null) {
                    finalValue = PayloadText.parseJson(answer);
                }
                String spoken = null;
                if (voice != null && voice.speaks() && partial == null) {
                    try {
                        spoken = voice.speak(answer, variableName.replaceAll("[^A-Za-z0-9_-]", "_") + "-" + shortHash(stepId));
                    } catch (Exception e) {
                        throw new StepFailure("voice: couldn't speak the answer: " + e.getMessage(), e);
                    }
                    host.trace(TraceEvent.VOICE, agentName, "spoke the answer to " + spoken, null);
                }
                final String audio = spoken;
                final String remembered = answer;
                HarnessExecutor.DelegateSuccessHandler successHandler = (agentResult, value) -> {
                    host.context().setVariable(variableName, value);
                    host.journal().put(stepId, new RunJournal.Entry("delegate", value));
                    if (audio != null) {
                        host.context().setVariable(variableName + "_audio", audio);
                        host.journal().put(stepId + "#audio", new RunJournal.Entry("audio", audio));
                    }
                    if (agentMemory != null) {
                        agentMemory.remember(session, guard != null ? guard.forStorage(resolvedPayload) : resolvedPayload, remembered);
                    }
                    host.trace(TraceEvent.DELEGATE_END, agentName, remembered, usageData(agentResult));
                    host.memoryEngine().storeOutcome(agentDef, resolvedPayload, agentResult, host.context());
                    host.auditLogger().logAgentDecision(AuditEvent.builder()
                            .sessionId(host.sessionId())
                            .agentResult(agentResult)
                            .addMetadata("agent", agentName)
                            .addMetadata("statement", "delegate")
                            .timestamp(Instant.now())
                            .build());
                };
                host.afterDelegateExecution(del, agentDef, resolvedPayload, contextBriefing, result, finalValue, successHandler);
                break; // Success (possibly a partial answer)
            } catch (io.github.llm4j.budget.BudgetExceeded exceeded) {
                budgets.markExhausted();
                refused = exceeded; // never retried: a retry would only spend more
                break;
            } catch (io.github.llm4j.ratelimit.RateLimited limited) {
                try {
                    limits.onLimit(limited, limitWaits); // waited it out: run the step again (not a retry)
                    continue;
                } catch (RateLimitFailure f) {
                    lastError = f; // not worth waiting for: the step failed, without retries
                    attempts = maxAttempts;
                    break;
                }
            } catch (StepFailure failure) {
                lastError = failure; // a guard or voice failure: retrying wouldn't change it
                attempts = maxAttempts;
                break;
            } catch (io.github.llm4j.agent.AgentInterrupt interrupt) {
                throw interrupt; // waiting for a human is not a failure — never retried
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while delegating to " + agentName, e);
            } catch (Exception e) {
                log.warning("Delegation failed: " + e.getMessage());
                host.onDelegateError(del, agentDef, resolvedPayload, contextBriefing, e, attempts + 1, maxAttempts);
                lastError = e;
                attempts++;
            }
        }
        } finally {
            host.scopes().set(outerScopes);
            host.sessions().remove(stepId);
        }

        if (refused != null) {
            // Refused by a budget: on_failure handles it (outside the exhausted step budget), else it propagates.
            if (del.getOnFailure().isEmpty()) throw refused;
            handleExhausted(del, agentName, refused.getMessage(), refused);
            return;
        }
        if (partial != null) {
            if (!del.getOnFailure().isEmpty()) handleExhausted(del, agentName, partial.getMessage(), partial);
            return;
        }
        if (attempts < maxAttempts) return; // succeeded

        // Exhausted retries
        log.severe("Exhausted retries for delegate to " + agentName);
        String message = lastError != null && lastError.getMessage() != null ? lastError.getMessage() : "Unknown error";
        // Recorded only when on_failure lets the run continue past it; a step that fails the run is
        // retried when the run is resumed.
        if (!del.getOnFailure().isEmpty()) host.journal().put(stepId, new RunJournal.Entry("failed", message));
        handleExhausted(del, agentName, message, lastError);
    }

    /** A step that failed before its agent ran: recorded (when on_failure lets the run go on), then handled. */
    private void failStep(DelegateStmt del, String stepId, String agentName, String message, Exception cause) {
        log.warning("Step " + stepId + " failed: " + message);
        if (!del.getOnFailure().isEmpty()) host.journal().put(stepId, new RunJournal.Entry("failed", message));
        handleExhausted(del, agentName, message, cause);
    }

    private static String shortHash(String text) {
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(h, 0, 4);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static Map<String, Object> usageData(io.github.llm4j.agent.AgentResult r) {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        if (r == null || r.getUsage() == null) return data;
        data.put("calls", r.getUsage().getLlmCalls());
        data.put("tokens", r.getUsage().getTotalTokens());
        data.put("promptTokens", r.getUsage().getPromptTokens()); // the split lets a server with a price table cost the step
        data.put("completionTokens", r.getUsage().getCompletionTokens());
        if (r.getUsage().getCost() != null) data.put("cost", r.getUsage().getCost().toPlainString());
        return data;
    }

    /** Runs the on_failure block with {@code _error} in scope, or fails the workflow. */
    private void handleExhausted(DelegateStmt del, String agentName, String message, Exception cause) {
        host.traceFailure("delegate", agentName, message, cause, null, del.getRetryCount() + 1, !del.getOnFailure().isEmpty());
        if (!del.getOnFailure().isEmpty()) {
            host.runFailureHandler(del.getOnFailure(), message);
        } else {
            throw new RuntimeException("Delegate to " + agentName + " failed after " + del.getRetryCount()
                    + " retries: " + message, cause);
        }
    }

    private io.github.llm4j.agent.AgentResult runWithTimeout(ReActAgent agent, String task, long timeoutMillis,
                                                              io.github.llm4j.agent.RunHandle handle) throws Exception {
        ThreadScope.Snapshot scope = host.captureScope();
        CompletableFuture<io.github.llm4j.agent.AgentResult> future = CompletableFuture.supplyAsync(() -> {
            try (ThreadScope.Installed ignored = scope.install()) {
                return agent.run(task, handle);
            }
        }, host.branches());
        try {
            return future.get(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            // A CompletableFuture does not interrupt its thread when cancelled, so the agent is told to stop through its handle: without this it
            // goes on calling (and paying for) the model after the step has failed.
            handle.cancel();
            future.cancel(true);
            throw new java.util.concurrent.TimeoutException("Step timed out after " + timeoutMillis + " ms");
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof RuntimeException re) throw re;
            throw e;
        }
    }
}
