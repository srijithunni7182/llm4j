package io.github.llm4j.loom.host;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.llm4j.a2a.A2AException;
import io.github.llm4j.a2a.A2AMessage;
import io.github.llm4j.a2a.AgentChannel;
import io.github.llm4j.a2a.Artifact;
import io.github.llm4j.a2a.ChannelMessage;
import io.github.llm4j.a2a.ChannelResponse;
import io.github.llm4j.a2a.Part;
import io.github.llm4j.a2a.RemoteTask;
import io.github.llm4j.a2a.TaskRunner;
import io.github.llm4j.a2a.TaskState;
import io.github.llm4j.a2a.TaskStore;
import io.github.llm4j.loom.ast.AgentChannelDef;
import io.github.llm4j.loom.ast.ExposeDef;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.WorkflowDef;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.LoomRun;
import io.github.llm4j.loom.execution.RunControl;
import io.github.llm4j.loom.execution.TraceEvent;
import io.github.llm4j.loom.execution.WorkflowOutput;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Serves one {@code expose} block as A2A tasks (docs/specs/32-a2a-integration, R22 to R24): the
 * handler's {@link TaskRunner} over a {@link LoomHost}, which is not changed. A task is a run of the
 * exposed workflow; its state follows the run, and a crash resumes the run through the host.
 *
 * <p>What comes in is bound to the workflow's parameters by {@link #start} and nothing else: text
 * only to the parameter named by {@code text_input}, a JSON object by name, no files, nothing over
 * {@code max_input}. A pending {@code human_prompt} or {@code approve} is internal and the task shows
 * as {@code WORKING}; only an {@code ask} to a channel whose audience is {@code caller} pauses it as
 * {@code INPUT_REQUIRED}. What the caller sees of a failure is a fixed sentence, never the workflow's
 * own error text.
 *
 * <p>Built in two steps because the host needs {@link #wiring()} and the runner needs the host:
 * create the runner, pass {@code wiring()} to {@code LoomHost.Builder.customizer}, then {@link
 * #attach}. Tasks are kept in the {@link TaskStore} the consumer supplies; a task that must survive a
 * restart needs a durable store.
 */
public final class LoomTaskRunner implements TaskRunner {

    static final long DEFAULT_MAX_INPUT_BYTES = 64 * 1024;
    private static final Pattern SIZE = Pattern.compile("(\\d+)(k|kb|m|mb)?", Pattern.CASE_INSENSITIVE);
    private static final String FAILED_TEXT = "The workflow did not finish.";
    private static final String ORIGIN = "a2a";
    private static final int RELEASE_ATTEMPTS = 200;
    private static final long RELEASE_PAUSE_MILLIS = 10;

    private final ExposeDef expose;
    private final LoomScript script;
    private final TaskStore store;
    private final Clock clock;
    private final WorkflowDef workflow;
    private final long maxInputBytes;
    private final String textInput;
    private final Set<String> callerChannels = new LinkedHashSet<>();

    private final Map<String, CompletableFuture<OwnedTask>> settled = new ConcurrentHashMap<>();
    private final Map<String, String> pendingQuestions = new ConcurrentHashMap<>();
    private final Map<String, String> answers = new ConcurrentHashMap<>();
    private final Map<String, LoomRun> live = new ConcurrentHashMap<>();
    private final Map<String, String> scopes = new ConcurrentHashMap<>();
    private final java.util.List<java.util.function.Consumer<ServedEvent>> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile LoomHost host;

    /**
     * What happened to a served task, for the consumer's own observability. It carries the task id,
     * the kind of event and the state, never the caller's scope or any text from either side.
     *
     * @param kind {@code received}, {@code state}, {@code answered} or {@code cancelled}
     * @param taskId the task
     * @param state the task's state after the event
     */
    public record ServedEvent(String kind, String taskId, String state) {}

    /**
     * Calls the listener for every event of a served task.
     *
     * @param listener what to call; it must not block
     */
    public void onEvent(java.util.function.Consumer<ServedEvent> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    private void emit(String kind, String taskId, TaskState state) {
        ServedEvent event = new ServedEvent(kind, taskId, state.name());
        for (var listener : listeners) listener.accept(event);
    }

    /**
     * Creates the runner for one {@code expose} block.
     *
     * @param expose the block this runner serves
     * @param script the script that holds it
     * @param store where tasks are kept between requests
     * @param clock the time of a status change
     */
    public LoomTaskRunner(ExposeDef expose, LoomScript script, TaskStore store, Clock clock) {
        this.expose = Objects.requireNonNull(expose, "expose");
        this.script = Objects.requireNonNull(script, "script");
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
        String name = expose.get("workflow");
        this.workflow =
                script.getWorkflows().stream()
                        .filter(w -> w.getName().equals(name))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "expose "
                                                        + expose.getName()
                                                        + " names a workflow that does not exist: "
                                                        + name
                                                        + ". Run weave check."));
        this.textInput = expose.get("text_input");
        this.maxInputBytes = sizeOf(expose.get("max_input"));
        for (AgentChannelDef channel : script.getAgentChannels())
            if ("caller".equals(channel.get("audience"))) callerChannels.add(channel.getName());
    }

    private static long sizeOf(String text) {
        if (text == null) return DEFAULT_MAX_INPUT_BYTES;
        Matcher m = SIZE.matcher(text.trim());
        if (!m.matches())
            throw new IllegalArgumentException("max_input is a size such as 64k or 1mb, not '" + text + "'");
        long number = Long.parseLong(m.group(1));
        String unit = m.group(2) == null ? "" : m.group(2).toLowerCase(Locale.ROOT);
        return switch (unit) {
            case "k", "kb" -> number * 1024;
            case "m", "mb" -> number * 1024 * 1024;
            default -> number;
        };
    }

    /**
     * What gives every run of the host the channels this runner answers (those whose audience is
     * {@code caller}) and lets it see a run end. Pass it to {@code LoomHost.Builder.customizer}, or
     * combine it with another customizer using {@code andThen}.
     *
     * @return the customizer
     */
    public java.util.function.Consumer<HarnessExecutor> wiring() {
        return executor -> {
            String runId = executor.getRunId();
            if (runId == null) return;
            for (String channel : callerChannels) executor.setAgentChannel(channel, callerChannel(runId));
            executor.addTraceListener(event -> onTrace(runId, executor, event));
        };
    }

    /**
     * Connects the runner to the host built with {@link #wiring()}.
     *
     * @param loomHost the host that runs the exposed workflow
     * @return this runner
     */
    public LoomTaskRunner attach(LoomHost loomHost) {
        this.host = Objects.requireNonNull(loomHost, "host");
        return this;
    }

    private AgentChannel callerChannel(String runId) {
        return (context, message) -> {
            String answer = answers.remove(runId);
            if (answer != null) return new ChannelResponse.Reply(answer, null);
            String question = message instanceof ChannelMessage.Question q ? q.text() : "The workflow needs an answer.";
            pendingQuestions.put(runId, question);
            return new ChannelResponse.Defer(runId + "-question");
        };
    }

    // ---- what a message becomes (R23)

    private Map<String, String> bind(A2AMessage message) {
        List<String> texts = new ArrayList<>();
        Map<String, String> inputs = new LinkedHashMap<>();
        long size = 0;
        for (Part part : message.parts()) {
            if (part instanceof Part.Text t) {
                texts.add(t.text());
                size += t.text().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            } else if (part instanceof Part.Data d) {
                size += d.value().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            } else {
                throw new A2AException.ContentTypeNotSupported(
                        "this agent accepts text and JSON data only, not files");
            }
        }
        if (size > maxInputBytes)
            throw new A2AException.InvalidParams(
                    "the message is larger than this agent accepts (max_input " + maxInputBytes + " bytes)");
        for (Part part : message.parts()) {
            if (!(part instanceof Part.Data data)) continue;
            JsonNode value = data.value();
            if (!value.isObject())
                throw new A2AException.InvalidParams("a data part must be a JSON object whose keys are the workflow's parameters");
            for (String parameter : workflow.getParameters()) {
                JsonNode field = value.get(parameter);
                if (field != null && !field.isNull())
                    inputs.put(parameter, field.isTextual() ? field.asText() : field.toString());
            }
        }
        if (!texts.isEmpty()) {
            if (textInput == null)
                throw new A2AException.InvalidParams(
                        "this agent takes its input as JSON data, not text: it has no text_input parameter");
            if (inputs.containsKey(textInput))
                throw new A2AException.InvalidParams("the parameter " + textInput + " was given twice, as text and as data");
            inputs.put(textInput, String.join("\n\n", texts));
        }
        List<String> missing = new ArrayList<>();
        for (String parameter : workflow.getParameters()) if (!inputs.containsKey(parameter)) missing.add(parameter);
        if (!missing.isEmpty())
            throw new A2AException.InvalidParams(
                    "missing "
                            + String.join(", ", missing)
                            + "; the workflow takes: "
                            + String.join(", ", workflow.getParameters())
                            + (textInput == null ? "" : " (text goes to " + textInput + ")"));
        return inputs;
    }

    // ---- TaskRunner

    @Override
    public OwnedTask start(String scope, A2AMessage message, String contextId) {
        LoomHost running = requireHost();
        Map<String, String> inputs = bind(message);
        String runId = "a2a-" + UUID.randomUUID();
        String context = contextId == null || contextId.isBlank() ? "ctx-" + UUID.randomUUID() : contextId;
        save(scope, RemoteTask.of(runId, context, TaskState.WORKING));
        emit("received", runId, TaskState.WORKING);
        settled.put(runId, new CompletableFuture<>());
        try {
            live.put(runId, running.start(RunRequest.of(workflow.getName(), inputs).withRunId(runId).from(ORIGIN + ":" + scope)));
        } catch (RuntimeException notStarted) {
            finish(runId, TaskState.FAILED, null, FAILED_TEXT);
        }
        return store.find(scope, runId).orElseThrow();
    }

    @Override
    public Optional<OwnedTask> get(String scope, String taskId) {
        return store.find(scope, taskId);
    }

    @Override
    public List<OwnedTask> list(String scope) {
        return store.list(scope);
    }

    @Override
    public OwnedTask answer(String scope, String taskId, A2AMessage message) {
        OwnedTask owned = store.find(scope, taskId).orElseThrow(() -> new A2AException.TaskNotFound(taskId));
        if (owned.task().state() != TaskState.INPUT_REQUIRED || !pendingQuestions.containsKey(taskId))
            throw new A2AException.UnsupportedOperation("the task is not waiting for an answer");
        String text = message.text();
        if (text == null || text.isBlank()) throw new A2AException.InvalidParams("an answer needs text");
        if (text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > maxInputBytes)
            throw new A2AException.InvalidParams("the answer is larger than this agent accepts (max_input " + maxInputBytes + " bytes)");
        answers.put(taskId, text);
        pendingQuestions.remove(taskId);
        OwnedTask working = save(scope, RemoteTask.of(taskId, owned.task().contextId(), TaskState.WORKING));
        settled.put(taskId, new CompletableFuture<>());
        emit("answered", taskId, TaskState.WORKING);
        try {
            live.put(taskId, resumeWhenReleased(taskId));
        } catch (A2AException notResumed) {
            finish(taskId, TaskState.FAILED, null, FAILED_TEXT);
            throw notResumed;
        }
        return working;
    }

    /**
     * The task shows as waiting a moment before the host lets go of the run that paused it, so an
     * answer that arrives at once can find the run still claimed. The wait is short and bounded, and
     * it is the only one in this class.
     */
    private LoomRun resumeWhenReleased(String taskId) {
        IllegalStateException last = null;
        for (int attempt = 0; attempt < RELEASE_ATTEMPTS; attempt++) {
            try {
                return requireHost().resumeAsync(taskId, null);
            } catch (IllegalStateException stillClaimed) {
                last = stillClaimed;
                try {
                    Thread.sleep(RELEASE_PAUSE_MILLIS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw new A2AException("the run could not be resumed with the answer: " + (last == null ? "interrupted" : last.getMessage()), last);
    }

    @Override
    public OwnedTask cancel(String scope, String taskId) {
        OwnedTask owned = store.find(scope, taskId).orElseThrow(() -> new A2AException.TaskNotFound(taskId));
        if (owned.task().isTerminal()) throw new A2AException.TaskNotCancelable(taskId);
        LoomRun run = live.get(taskId);
        if (run != null) run.executor().control().request(RunControl.Action.STOP, ORIGIN);
        pendingQuestions.remove(taskId);
        answers.remove(taskId);
        finish(taskId, TaskState.CANCELED, null, null);
        emit("cancelled", taskId, TaskState.CANCELED);
        return store.find(scope, taskId).orElseThrow();
    }

    @Override
    public CompletionStage<OwnedTask> settled(String scope, String taskId) {
        OwnedTask owned = store.find(scope, taskId).orElseThrow(() -> new A2AException.TaskNotFound(taskId));
        TaskState state = owned.task().state();
        if (state.isTerminal() || state == TaskState.INPUT_REQUIRED) return CompletableFuture.completedFuture(owned);
        return settled.computeIfAbsent(taskId, k -> new CompletableFuture<>());
    }

    // ---- what a run's end does to its task

    private void onTrace(String runId, HarnessExecutor executor, TraceEvent event) {
        if (!TraceEvent.RUN_END.equals(event.type())) return;
        if (!workflow.getName().equals(String.valueOf(event.data().get("workflow")))) return;
        String status = String.valueOf(event.data().get("status"));
        switch (status) {
            case "completed" -> {
                WorkflowOutput out = executor.output();
                String text = out.source() == WorkflowOutput.Source.RETURN ? out.text() : null;
                finish(runId, TaskState.COMPLETED, text, null);
            }
            case "stopped" -> finish(runId, TaskState.CANCELED, null, null);
            case "suspended" -> {
                String question = pendingQuestions.get(runId);
                if (question != null) pause(runId, question);
            }
            default -> finish(runId, TaskState.FAILED, null, FAILED_TEXT);
        }
    }

    private OwnedTask save(String scope, RemoteTask task) {
        OwnedTask owned = new OwnedTask(scope, task, clock.instant());
        scopes.put(task.id(), scope);
        store.save(owned);
        return owned;
    }

    private Optional<OwnedTask> current(String taskId) {
        // the store is keyed by scope in its API, but a task id is unique, so ask the one scope that holds it
        return knownScope(taskId).flatMap(scope -> store.find(scope, taskId));
    }

    private Optional<String> knownScope(String taskId) {
        String cached = scopes.get(taskId);
        if (cached != null) return Optional.of(cached);
        // after a restart the host's record of the run still says who started it
        LoomHost attached = host;
        if (attached == null) return Optional.empty();
        return attached.run(taskId)
                .map(RunRecord::origin)
                .filter(origin -> origin.startsWith(ORIGIN + ":"))
                .map(origin -> origin.substring(ORIGIN.length() + 1));
    }

    private void pause(String taskId, String question) {
        current(taskId)
                .filter(t -> !t.task().isTerminal())
                .ifPresent(
                        owned -> {
                            RemoteTask task = owned.task();
                            A2AMessage status = A2AMessage.agent("q-" + UUID.randomUUID(), List.of(new Part.Text(question)));
                            OwnedTask updated =
                                    save(owned.scope(), new RemoteTask(task.id(), task.contextId(), TaskState.INPUT_REQUIRED, status, task.artifacts(), List.of()));
                            emit("state", taskId, TaskState.INPUT_REQUIRED);
                            complete(taskId, updated);
                        });
    }

    private void finish(String taskId, TaskState state, String resultText, String failureText) {
        current(taskId)
                .filter(t -> !t.task().isTerminal())
                .ifPresent(
                        owned -> {
                            RemoteTask task = owned.task();
                            List<Artifact> artifacts =
                                    resultText == null ? List.of() : List.of(new Artifact("result", "result", List.of(new Part.Text(resultText))));
                            A2AMessage status =
                                    failureText == null
                                            ? null
                                            : A2AMessage.agent("f-" + UUID.randomUUID(), List.of(new Part.Text(failureText)));
                            OwnedTask updated = save(owned.scope(), new RemoteTask(task.id(), task.contextId(), state, status, artifacts, List.of()));
                            live.remove(taskId);
                            emit("state", taskId, state);
                            complete(taskId, updated);
                        });
    }

    private void complete(String taskId, OwnedTask task) {
        CompletableFuture<OwnedTask> waiting = settled.get(taskId);
        if (waiting != null) waiting.complete(task);
    }

    private LoomHost requireHost() {
        LoomHost attached = host;
        if (attached == null)
            throw new IllegalStateException("the runner has no host: call attach(host) with the host built from wiring()");
        return attached;
    }
}
