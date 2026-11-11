package io.github.llm4j.loom.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.a2a.A2AException;
import io.github.llm4j.a2a.A2AMessage;
import io.github.llm4j.a2a.AgentChannel;
import io.github.llm4j.a2a.Artifact;
import io.github.llm4j.a2a.ChannelContext;
import io.github.llm4j.a2a.ChannelMessage;
import io.github.llm4j.a2a.ChannelResponse;
import io.github.llm4j.a2a.Part;
import io.github.llm4j.a2a.ReceivedFile;
import io.github.llm4j.a2a.RemoteAgent;
import io.github.llm4j.a2a.RemoteTask;
import io.github.llm4j.a2a.SendResult;
import io.github.llm4j.a2a.TaskState;
import io.github.llm4j.loom.ast.DelegateStmt;
import io.github.llm4j.loom.ast.RemoteDef;
import io.github.llm4j.loom.runtime.RunJournal;
import io.github.llm4j.loom.runtime.RunSuspended;
import io.github.llm4j.model.Media;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Runs a {@code delegate} whose target is a {@code remote} agent (docs/specs/32-a2a-integration, R15
 * to R21). The send is an effect: its message id and time are journaled before anything leaves, so a
 * crash never makes a second remote task; the run holds no thread while the remote works (it is
 * suspended and the host resumes it); every question, authorization request and file goes through
 * the declared channel inside the runtime's bounds; and what comes back is untrusted text.
 */
final class RemoteRunner {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration DEFAULT_TIMEOUT = Duration.ofHours(1);

    private final HarnessExecutor executor;
    private final Exchanges exchanges;

    RemoteRunner(HarnessExecutor executor) {
        this.executor = executor;
        this.exchanges = new Exchanges(executor);
    }

    /** Thrown when an earlier send may or may not have arrived and cannot be found: nothing more is sent. */
    static final class OutcomeUnknown extends RuntimeException {
        OutcomeUnknown(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** A failure the remote reported or that was refused before anything left; the step's own retry may apply. */
    private static final class DefiniteFailure extends RuntimeException {
        final boolean retryable;

        DefiniteFailure(String message, boolean retryable, Throwable cause) {
            super(message, cause);
            this.retryable = retryable;
        }
    }

    void run(DelegateStmt stmt) {
        String stepId = executor.currentStep();
        RunJournal journal = executor.journal();
        RemoteDef def = executor.remoteDef(stmt.getTargetAgent());
        Optional<RunJournal.Entry> done = journal.get(stepId).filter(e -> e.kind().equals("remote") || e.kind().equals("remote_failed"));
        if (done.isPresent()) {
            replay(stmt, done.get());
            return;
        }
        int attempts = 0;
        int maxAttempts = stmt.getRetryCount() + 1;
        DefiniteFailure last = null;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            attempts++;
            try {
                if (attempt > 0 && stmt.getBackoffMillis() > 0) {
                    executor.sleeper().sleep(Duration.ofMillis(stmt.getBackoffMillis() << Math.min(attempt - 1, 10)));
                }
                Map<String, Object> result = attempt(stmt, def, stepId + "#r" + attempt, stepId);
                finish(stmt, stepId, result);
                return;
            } catch (DefiniteFailure failure) {
                last = failure;
                if (!failure.retryable) break;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while calling " + def.getName(), e);
            }
        }
        fail(stmt, stepId, last == null ? "unknown error" : last.getMessage(), last, attempts);
    }

    // ---- one attempt

    private Map<String, Object> attempt(DelegateStmt stmt, RemoteDef def, String base, String stepId) {
        RunJournal journal = executor.journal();
        RemoteAgent agent = agentFor(def);
        Optional<RunJournal.Entry> sent = journal.get(base + "/send");
        Optional<RunJournal.Entry> taskEntry = journal.get(base + "/task");
        Instant sentAt = sent.map(e -> Instant.parse(String.valueOf(map(e.value()).get("at")))).orElse(null);
        RemoteTask task;
        if (taskEntry.isPresent()) {
            task = read(agent, String.valueOf(map(taskEntry.get().value()).get("task")), def);
        } else if (sent.isPresent()) {
            task = findEarlierSend(agent, def, String.valueOf(map(sent.get().value()).get("messageId")), sentAt, base);
        } else {
            SendResult result = send(stmt, def, agent, base);
            sentAt = Instant.parse(String.valueOf(map(journal.get(base + "/send").orElseThrow().value()).get("at")));
            if (result instanceof SendResult.MessageResult answer) {
                return collect(stmt, def, List.of(), answer.message(), List.of(answer.message()));
            }
            task = ((SendResult.TaskResult) result).task();
            journal.put(base + "/task", new RunJournal.Entry("remote_task", Map.of("task", task.id(), "remote", def.getName())));
        }
        return drive(stmt, def, agent, base, stepId, task, sentAt);
    }

    private RemoteTask read(RemoteAgent agent, String taskId, RemoteDef def) {
        try {
            return agent.get(taskId);
        } catch (A2AException e) {
            throw new DefiniteFailure("could not read the task of " + def.getName() + ": " + Exchanges.shorten(e.getMessage()), false, e);
        }
    }

    private SendResult send(DelegateStmt stmt, RemoteDef def, RemoteAgent agent, String base) {
        RunJournal journal = executor.journal();
        HarnessExecutor.Tracked<String> payload = executor.tracked(() -> executor.resolve(stmt.getPayload()));
        List<Part> parts = new ArrayList<>();
        parts.add(new Part.Text(payload.value()));
        for (Media media : executor.attachmentsOf(stmt.getAttachments())) {
            parts.add(Part.FileOffered.inline(media.bytes(), media.name(), media.mediaType()));
        }
        // what a remote is told leaves the workflow: the policy boundary decides whether this may go
        executor.guardSink("delegate " + def.getName(), payload.value(), payload);
        String messageId = "m-" + UUID.randomUUID();
        A2AMessage message = A2AMessage.user(messageId, parts);
        // the effect is recorded before it happens: after a crash the send is looked for, never repeated
        Instant at = executor.clock().instant();
        Map<String, Object> intent = new LinkedHashMap<>();
        intent.put("messageId", messageId);
        intent.put("at", at.toString());
        intent.put("remote", def.getName());
        journal.put(base + "/send", new RunJournal.Entry("remote_send", intent));
        trace("send", def, Map.of("message_id", messageId));
        try {
            return agent.send(message);
        } catch (A2AException.Refused refused) {
            // nothing left the process, and asking again cannot change the answer
            journal.put(base + "/send", new RunJournal.Entry("remote_send", Map.of("messageId", messageId, "at", at.toString(), "remote", def.getName(), "refused", true)));
            throw new DefiniteFailure(Exchanges.shorten(refused.getMessage()), false, refused);
        } catch (A2AException e) {
            throw new OutcomeUnknown("the send to " + def.getName() + " failed (" + Exchanges.shorten(e.getMessage()) + ") and it is not known whether it arrived; nothing was sent again. Resume the run: it looks for the task by its message id first.", e);
        }
    }

    private RemoteTask findEarlierSend(RemoteAgent agent, RemoteDef def, String messageId, Instant sentAt, String base) {
        Map<?, ?> intent = map(executor.journal().get(base + "/send").orElseThrow().value());
        if (Boolean.TRUE.equals(intent.get("refused")))
            throw new DefiniteFailure("the earlier send to " + def.getName() + " was refused before anything left", false, null);
        try {
            Optional<RemoteTask> found = agent.findBySentMessage(messageId, sentAt);
            if (found.isPresent()) {
                executor.journal().put(base + "/task", new RunJournal.Entry("remote_task", Map.of("task", found.get().id(), "remote", def.getName())));
                trace("found", def, Map.of("message_id", messageId, "task_id", found.get().id()));
                return found.get();
            }
        } catch (A2AException e) {
            throw new OutcomeUnknown("an earlier send to " + def.getName() + " may not have arrived, and the remote would not list its tasks (" + Exchanges.shorten(e.getMessage()) + "); nothing was sent again", e);
        }
        throw new OutcomeUnknown("an earlier send to " + def.getName() + " may not have arrived, and no recent task there holds its message id; nothing was sent again. Check the remote, then start a new run if it is safe to send once more.", null);
    }

    // ---- following the task

    private Map<String, Object> drive(DelegateStmt stmt, RemoteDef def, RemoteAgent agent, String base, String stepId, RemoteTask first, Instant sentAt) {
        RemoteTask task = first;
        RunJournal journal = executor.journal();
        for (int guard = 0; guard < 1000; guard++) {
            executor.control().checkpoint(stepId); // an operator's stop or pause takes effect between the remote's states
            trace("state", def, Map.of("task_id", task.id(), "task_state", task.state().name()));
            switch (task.state()) {
                case COMPLETED -> {
                    return collect(stmt, def, task.artifacts(), task.statusMessage(), task.history());
                }
                case FAILED, REJECTED, CANCELED -> throw new DefiniteFailure(
                        "the remote agent " + def.getName() + " reported " + task.state() + (task.statusMessage() == null ? "" : ": " + Exchanges.shorten(task.statusMessage().text())), true, null);
                case INPUT_REQUIRED, AUTH_REQUIRED -> task = exchange(def, agent, base, stepId, task);
                default -> {
                    Instant deadline = sentAt.plus(Durations.parse(def.get("timeout"), DEFAULT_TIMEOUT));
                    if (!executor.clock().instant().isBefore(deadline)) {
                        cancelBestEffort(agent, def, task);
                        throw new DefiniteFailure("the remote agent " + def.getName() + " did not finish in time (" + (task.state() == TaskState.UNKNOWN ? "its state stayed unknown" : "still " + task.state()) + ")", true, null);
                    }
                    throw RunSuspended.remote(stepId, deadline, "waiting for " + def.getName() + " (task " + task.id() + ")");
                }
            }
        }
        throw new DefiniteFailure("the exchange with " + def.getName() + " did not settle", false, null);
    }

    private RemoteTask exchange(RemoteDef def, RemoteAgent agent, String base, String stepId, RemoteTask task) {
        RunJournal journal = executor.journal();
        String channelName = def.get("agent_channel");
        int n = 0;
        while (journal.get(base + "/ex" + n).filter(e -> "sent".equals(e.kind())).isPresent()) n++;
        String key = base + "/ex" + n;
        Optional<RunJournal.Entry> recorded = journal.get(key);
        String text = task.statusMessage() == null ? "" : task.statusMessage().text();
        boolean authorization = task.state() == TaskState.AUTH_REQUIRED;
        String reply;
        if (recorded.isPresent() && "reply".equals(recorded.get().kind())) {
            reply = String.valueOf(recorded.get().value()); // answered before a crash: do not ask again
        } else {
            AgentChannel channel;
            try {
                channel = exchanges.channel(channelName);
            } catch (StepFailure noChannel) {
                throw new DefiniteFailure(noChannel.getMessage(), false, noChannel);
            }
            ChannelMessage message = authorization ? new ChannelMessage.AuthorizationNeeded(text) : new ChannelMessage.Question(text, null, null, 1, null);
            io.github.llm4j.a2a.ExchangeGuard guard = exchanges.guard(stepId, channelName);
            ChannelContext context = exchanges.context(stepId, ChannelContext.Direction.INBOUND_QUESTION, def.getName(), 0);
            exchangeTrace(authorization ? "authorization" : "question", channelName, "INBOUND_QUESTION", text, key);
            ChannelResponse response;
            try {
                response = guard.exchange(channel, context, message, r -> Optional.empty());
            } catch (A2AException.ChannelFailure failure) {
                throw new DefiniteFailure("the channel " + channelName + " could not answer " + def.getName() + ": " + Exchanges.shorten(failure.getMessage()), false, failure);
            }
            if (response instanceof ChannelResponse.Refuse refuse)
                throw new DefiniteFailure("the channel " + channelName + " refused to answer " + def.getName() + ": " + Exchanges.shorten(refuse.reason()), true, null);
            if (response instanceof ChannelResponse.Defer defer) {
                journal.put(key, new RunJournal.Entry("deferred", defer.token()));
                throw RunSuspended.remote(stepId, executor.clock().instant().plus(Durations.parse(def.get("timeout"), DEFAULT_TIMEOUT)), "waiting for the answer to " + def.getName() + " (token " + defer.token() + ")");
            }
            ChannelResponse.Reply answered = (ChannelResponse.Reply) response;
            reply = answered.text().isEmpty() && answered.data() != null ? answered.data().toString() : answered.text();
            journal.put(key, new RunJournal.Entry("reply", reply));
        }
        exchangeTrace("reply", channelName, "INBOUND_QUESTION", reply, key);
        RemoteTask next = agent.reply(task.id(), A2AMessage.user("m-" + UUID.randomUUID(), List.of(new Part.Text(reply))));
        journal.put(key, new RunJournal.Entry("sent", Exchanges.digest(reply)));
        return next;
    }

    /**
     * A run was stopped: asks the remote to cancel every task of this run that has not finished, as a
     * best effort. A cancel that fails is recorded and never blocks the stop.
     */
    void cancelInFlight() {
        RunJournal journal = executor.journal();
        for (Map.Entry<String, RunJournal.Entry> entry : new java.util.TreeMap<>(journal.all()).entrySet()) {
            String key = entry.getKey();
            if (!key.endsWith("/task") || !"remote_task".equals(entry.getValue().kind())) continue;
            String base = key.substring(0, key.length() - "/task".length());
            String stepId = base.contains("#r") ? base.substring(0, base.lastIndexOf("#r")) : base;
            boolean finished = journal.get(stepId).filter(e -> e.kind().equals("remote") || e.kind().equals("remote_failed")).isPresent();
            if (finished || journal.get(base + "/cancelled").isPresent()) continue;
            Map<?, ?> value = map(entry.getValue().value());
            RemoteDef def = executor.remoteDef(String.valueOf(value.get("remote")));
            if (def == null) continue;
            String taskId = String.valueOf(value.get("task"));
            try {
                agentFor(def).cancel(taskId);
                trace("cancel", def, Map.of("task_id", taskId));
                journal.put(base + "/cancelled", new RunJournal.Entry("remote_cancel", "ok"));
            } catch (RuntimeException e) {
                trace("cancel_failed", def, Map.of("task_id", taskId, "error", e.getClass().getSimpleName()));
                journal.put(base + "/cancelled", new RunJournal.Entry("remote_cancel", "failed"));
            }
        }
    }

    private void cancelBestEffort(RemoteAgent agent, RemoteDef def, RemoteTask task) {
        try {
            agent.cancel(task.id());
            trace("cancel", def, Map.of("task_id", task.id()));
        } catch (RuntimeException e) {
            trace("cancel_failed", def, Map.of("task_id", task.id(), "error", e.getClass().getSimpleName()));
        }
    }

    // ---- the result

    private Map<String, Object> collect(DelegateStmt stmt, RemoteDef def, List<Artifact> artifacts, A2AMessage message, List<A2AMessage> history) {
        List<String> texts = new ArrayList<>();
        List<Object> data = new ArrayList<>();
        List<Map<String, Object>> files = new ArrayList<>();
        List<Part> all = new ArrayList<>();
        if (message != null && message.role() == A2AMessage.Role.AGENT) all.addAll(message.parts());
        for (Artifact artifact : artifacts) all.addAll(artifact.parts());
        String channelName = def.get("agent_channel");
        int index = 0;
        for (Part part : all) {
            if (part instanceof Part.Text t) {
                texts.add(t.text());
            } else if (part instanceof Part.Data d) {
                data.add(plain(d.value()));
            } else if (part instanceof Part.FileOffered f) {
                files.addAll(offerFile(def, channelName, f, index++));
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("text", String.join("\n\n", texts));
        result.put("data", data);
        result.put("files", files);
        if (stmt.getExpecting() != null) {
            Object object = structured(data, texts);
            String problem = ExpectedShape.problem(stmt.getExpecting(), object);
            if (problem != null)
                throw new DefiniteFailure("the result from " + def.getName() + " does not match the expected shape: " + problem, true, null);
            result.put("object", object);
        }
        return result;
    }

    private static Object structured(List<Object> data, List<String> texts) {
        if (data.size() == 1) return data.get(0);
        if (data.size() > 1) return null; // several data parts are ambiguous: the shape check names it
        if (texts.size() == 1) {
            Object parsed = PayloadText.parseJson(texts.get(0));
            if (!(parsed instanceof String)) return parsed;
        }
        return null;
    }

    private List<Map<String, Object>> offerFile(RemoteDef def, String channelName, Part.FileOffered file, int index) {
        ChannelMessage message =
                file.isLink()
                        ? new ChannelMessage.FileOffered.Link(file.url(), file.label(), file.claimedMediaType())
                        : new ChannelMessage.FileOffered.Inline(file.bytes(), file.label(), file.claimedMediaType());
        String stepId = executor.currentStep();
        AgentChannel channel;
        try {
            channel = exchanges.channel(channelName);
        } catch (StepFailure noChannel) {
            throw new DefiniteFailure(noChannel.getMessage(), false, noChannel);
        }
        ChannelContext context = exchanges.context(stepId, ChannelContext.Direction.INBOUND_QUESTION, def.getName(), 0);
        exchangeTrace("file", channelName, "INBOUND_QUESTION", file.label(), stepId + "#f" + index);
        ChannelResponse response;
        try {
            response = exchanges.guard(stepId, channelName).exchange(channel, context, message, r -> Optional.empty());
        } catch (A2AException.ChannelFailure failure) {
            throw new DefiniteFailure("the channel " + channelName + " could not handle a file from " + def.getName() + ": " + Exchanges.shorten(failure.getMessage()), false, failure);
        }
        if (response instanceof ChannelResponse.Received received) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (ReceivedFile f : received.files()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("path", f.path().toString());
                entry.put("kind", f.kind().name());
                entry.put("mediaType", f.mediaType());
                entry.put("size", f.size());
                entry.put("sha256", f.sha256());
                out.add(entry);
            }
            return out;
        }
        if (response instanceof ChannelResponse.Ignore) return List.of();
        throw new DefiniteFailure("the channel " + channelName + " did not accept or ignore a file from " + def.getName() + ", so the step stops", true, null);
    }

    private static Object plain(JsonNode value) {
        return JSON.convertValue(value, Object.class);
    }

    // ---- outcomes

    private void finish(DelegateStmt stmt, String stepId, Map<String, Object> result) {
        executor.journal().put(stepId, new RunJournal.Entry("remote", result));
        bindResult(stmt, result);
        trace("done", executor.remoteDef(stmt.getTargetAgent()), Map.of());
    }

    private void replay(DelegateStmt stmt, RunJournal.Entry entry) {
        if (entry.kind().equals("remote_failed")) {
            fail(stmt, executor.currentStep(), String.valueOf(entry.value()), null, 0, false);
            return;
        }
        bindResult(stmt, map(entry.value()));
    }

    @SuppressWarnings("unchecked")
    private void bindResult(DelegateStmt stmt, Map<?, ?> result) {
        String name = executor.resolveResultName(stmt.getVariableName());
        // what a remote agent wrote is untrusted: nothing here has been judged
        Object value = result.containsKey("object") ? result.get("object") : result.get("text");
        executor.bind(name, value, Provenance.UNCHECKED);
        executor.bind(name + "_data", result.get("data"), Provenance.UNCHECKED);
        executor.bind(name + "_files", result.get("files"), Provenance.UNCHECKED);
    }

    private void fail(DelegateStmt stmt, String stepId, String message, Throwable cause, int attempts) {
        fail(stmt, stepId, message, cause, attempts, true);
    }

    private void fail(DelegateStmt stmt, String stepId, String message, Throwable cause, int attempts, boolean record) {
        executor.traceFailure("delegate", stmt.getTargetAgent(), message, cause, null, attempts, !stmt.getOnFailure().isEmpty());
        if (stmt.getOnFailure().isEmpty())
            throw new RuntimeException("delegate " + stmt.getTargetAgent() + " failed: " + message, cause);
        if (record) executor.journal().put(stepId, new RunJournal.Entry("remote_failed", message));
        executor.runFailureHandler(stmt.getOnFailure(), message);
    }

    // ---- helpers

    private RemoteAgent agentFor(RemoteDef def) {
        if (executor.a2aTransport() == null)
            throw new StepFailure("this run has no A2A transport, so remote agent " + def.getName() + " cannot be called; the host supplies one (setA2ATransport). Nothing was sent.", null);
        String channelName = def.get("agent_channel");
        if (executor.agentChannel(channelName) == null)
            throw new StepFailure("the host has not supplied an implementation for agent_channel " + channelName + "; register one under that name (setAgentChannel). Nothing was sent.", null);
        RemoteAgent.Builder builder = RemoteAgent.builder(def.get("card"), executor.a2aTransport(), executor.agentChannel(channelName));
        io.github.llm4j.secret.SecretRef credential = executor.credentialRef(def.getValues().get("auth"));
        if (credential != null) builder.credential(credential);
        if ("true".equals(def.get("require_signed_card"))) builder.requireSignedCard(true);
        try {
            return builder.build();
        } catch (A2AException.Refused refused) {
            throw new DefiniteFailure(Exchanges.shorten(refused.getMessage()), false, refused);
        }
    }

    private static Map<?, ?> map(Object value) {
        return value instanceof Map<?, ?> m ? m : Map.of();
    }

    private void trace(String kind, RemoteDef def, Map<String, Object> extra) {
        Map<String, Object> data = new LinkedHashMap<>(extra);
        data.put("kind", kind);
        data.put("remote", def == null ? "" : def.getName());
        executor.trace(TraceEvent.REMOTE, null, kind + " " + (def == null ? "" : def.getName()), data);
    }

    private void exchangeTrace(String kind, String channel, String direction, String text, String key) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("kind", kind);
        data.put("channel", channel);
        data.put("direction", direction);
        data.put("digest", Exchanges.digest(text == null ? "" : text));
        data.put("key", key);
        executor.trace(TraceEvent.EXCHANGE, null, kind + " via " + channel, data);
    }
}
