package io.github.llm4j.loom.execution;

import io.github.llm4j.a2a.A2AException;
import io.github.llm4j.a2a.AgentChannel;
import io.github.llm4j.a2a.ChannelContext;
import io.github.llm4j.a2a.ChannelMessage;
import io.github.llm4j.a2a.ChannelResponse;
import io.github.llm4j.loom.ast.AskStmt;
import io.github.llm4j.loom.runtime.RunJournal;
import io.github.llm4j.loom.runtime.RunSuspended;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Runs {@code ask Channel "text" -> reply} (docs/specs/32-a2a-integration, R21): the question goes
 * through the runtime's bounds to the declared channel. Without {@code expecting} the answer is
 * text; with it, a typed object checked against the shape, and a reply that does not fit goes back
 * to the channel with the reason and counts as a question used. The answer is journaled, so a resumed
 * run never asks twice.
 */
final class AskRunner {

    private final HarnessExecutor executor;
    private final Exchanges exchanges;

    AskRunner(HarnessExecutor executor) {
        this.executor = executor;
        this.exchanges = new Exchanges(executor);
    }

    void run(AskStmt stmt) {
        String stepId = executor.currentStep();
        RunJournal journal = executor.journal();
        Optional<RunJournal.Entry> recorded = journal.get(stepId).filter(e -> e.kind().equals("ask") || e.kind().equals("ask_failed"));
        if (recorded.isPresent()) {
            if (recorded.get().kind().equals("ask_failed")) fail(stmt, stepId, String.valueOf(recorded.get().value()), null, false);
            else bind(stmt, recorded.get().value());
            return;
        }
        HarnessExecutor.Tracked<String> question = executor.tracked(() -> executor.resolve(stmt.getText()));
        int maxAttempts = stmt.getRetryCount() + 1;
        String failure = "unknown error";
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            try {
                if (attempt > 0 && stmt.getBackoffMillis() > 0)
                    executor.sleeper().sleep(Duration.ofMillis(stmt.getBackoffMillis() << Math.min(attempt - 1, 10)));
                Object answer = ask(stmt, stepId, question.value());
                journal.put(stepId, new RunJournal.Entry("ask", answer));
                bind(stmt, answer);
                return;
            } catch (AskFailure e) {
                failure = e.getMessage();
                if (!e.retryable) break;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while asking " + stmt.getChannel(), e);
            }
        }
        fail(stmt, stepId, failure, null, true);
    }

    private static final class AskFailure extends RuntimeException {
        final boolean retryable;

        AskFailure(String message, boolean retryable) {
            super(message);
            this.retryable = retryable;
        }
    }

    private Object ask(AskStmt stmt, String stepId, String text) {
        AgentChannel channel;
        try {
            channel = exchanges.channel(stmt.getChannel());
        } catch (StepFailure noChannel) {
            throw new AskFailure(noChannel.getMessage(), false);
        }
        var guard = exchanges.guard(stepId, stmt.getChannel());
        ChannelContext context = exchanges.context(stepId, ChannelContext.Direction.OUTBOUND_ASK, stmt.getChannel(), 0);
        Object[] parsed = new Object[1];
        ChannelMessage.Question message = new ChannelMessage.Question(text, null, null, 1, null);
        trace("ask", stmt.getChannel(), text, stepId);
        ChannelResponse response;
        try {
            response = guard.exchange(channel, context, message, reply -> {
                Object value = reply.data() != null ? plain(reply.data()) : reply.text();
                if (stmt.getExpecting() != null) {
                    if (value instanceof String s) value = PayloadText.parseJson(s);
                    String problem = ExpectedShape.problem(stmt.getExpecting(), value);
                    if (problem != null) return Optional.of(problem);
                }
                parsed[0] = value;
                return Optional.empty();
            });
        } catch (A2AException.ChannelFailure failure) {
            throw new AskFailure("the channel " + stmt.getChannel() + " could not answer: " + Exchanges.shorten(failure.getMessage()), false);
        }
        if (response instanceof ChannelResponse.Refuse refuse)
            throw new AskFailure("the channel " + stmt.getChannel() + " refused to answer: " + Exchanges.shorten(refuse.reason()), true);
        if (response instanceof ChannelResponse.Defer defer)
            throw RunSuspended.remote(stepId, executor.clock().instant().plus(Duration.ofHours(1)), "waiting for the answer from " + stmt.getChannel() + " (token " + defer.token() + ")");
        trace("reply", stmt.getChannel(), String.valueOf(parsed[0]), stepId);
        return parsed[0];
    }

    private static Object plain(com.fasterxml.jackson.databind.JsonNode node) {
        return new com.fasterxml.jackson.databind.ObjectMapper().convertValue(node, Object.class);
    }

    private void bind(AskStmt stmt, Object answer) {
        // a person or service answered from outside the workflow: it is not something a model has judged
        executor.bind(executor.resolveVariableName(stmt.getVariable()), answer, Provenance.UNCHECKED);
    }

    private void fail(AskStmt stmt, String stepId, String message, Throwable cause, boolean record) {
        executor.traceFailure("ask", stmt.getChannel(), message, cause, null, 0, !stmt.getOnFailure().isEmpty());
        if (stmt.getOnFailure().isEmpty()) throw new RuntimeException("ask " + stmt.getChannel() + " failed: " + message, cause);
        if (record) executor.journal().put(stepId, new RunJournal.Entry("ask_failed", message));
        executor.runFailureHandler(stmt.getOnFailure(), message);
    }

    private void trace(String kind, String channel, String text, String key) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("kind", kind);
        data.put("channel", channel);
        data.put("direction", "OUTBOUND_ASK");
        data.put("digest", Exchanges.digest(text));
        data.put("key", key);
        executor.trace(TraceEvent.EXCHANGE, null, kind + " via " + channel, data);
    }
}
