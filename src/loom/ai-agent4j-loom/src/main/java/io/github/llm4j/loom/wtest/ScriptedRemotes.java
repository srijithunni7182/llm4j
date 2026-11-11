package io.github.llm4j.loom.wtest;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.llm4j.a2a.A2AException;
import io.github.llm4j.a2a.A2AMessage;
import io.github.llm4j.a2a.A2ATransport;
import io.github.llm4j.a2a.AgentCardView;
import io.github.llm4j.a2a.Artifact;
import io.github.llm4j.a2a.Part;
import io.github.llm4j.a2a.RemoteTask;
import io.github.llm4j.a2a.SendOptions;
import io.github.llm4j.a2a.SendResult;
import io.github.llm4j.a2a.TaskState;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.RemoteDef;
import io.github.llm4j.secret.SecretRef;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The remote agents of a test case (docs/specs/32-a2a-integration, R33): a {@code remote} is answered
 * from the case, one step for each time the run looks at the task, so a workflow that calls an agent
 * someone else runs is tested with no network. A step is {@code {state: working}}, {@code {state:
 * input_required, question: "…"}}, {@code {state: auth_required, explanation: "…"}}, {@code {state:
 * completed, text, data, files}}, {@code {state: failed|rejected|canceled, message}}, or {@code
 * {reply: "…"}} (a plain message and no task). The last step repeats. A remote the case did not
 * script fails the case (strict), as a classifier does.
 */
final class ScriptedRemotes implements A2ATransport {

    static final Set<String> STATES = Set.of("working", "input_required", "auth_required", "completed", "failed", "rejected", "canceled");

    private static final class Task {
        final String id;
        final String remote;
        final List<JsonNode> steps;
        final List<A2AMessage> history = new ArrayList<>();
        int index;
        boolean canceled;

        Task(String id, String remote, List<JsonNode> steps) {
            this.id = id;
            this.remote = remote;
            this.steps = steps;
        }

        JsonNode step() {
            return steps.get(Math.min(index, steps.size() - 1));
        }

        TaskState state() {
            return canceled ? TaskState.CANCELED : stateOf(step());
        }
    }

    private final Map<String, JsonNode> scripts;
    private final Map<String, String> byCard = new HashMap<>();
    private final Map<String, String> byEndpoint = new HashMap<>();
    private final Recorder recorder;
    private final Map<String, Task> tasks = new LinkedHashMap<>();
    private boolean progressed;
    private int counter;

    ScriptedRemotes(LoomScript script, Map<String, JsonNode> scripts, Recorder recorder) {
        this.scripts = scripts;
        this.recorder = recorder;
        for (RemoteDef r : script.getRemotes()) {
            String card = r.get("card", "");
            byCard.put(card, r.getName());
            String host = URI.create(card).getHost();
            byEndpoint.put("https://" + host + "/a2a", r.getName());
        }
    }

    /** Whether any task moved to its next step since the last call; clears the flag. */
    boolean takeProgress() {
        boolean was = progressed;
        progressed = false;
        return was;
    }

    @Override
    public AgentCardView fetchCard(String address) {
        String name = byCard.get(address);
        if (name == null) throw new A2AException("the test has no remote with the card " + address);
        String url = byEndpoint.entrySet().stream().filter(e -> e.getValue().equals(name)).map(Map.Entry::getKey).findFirst().orElseThrow();
        return AgentCardView.builder(name, "scripted by the test")
                .endpoints(List.of(new AgentCardView.Endpoint(url, "JSONRPC", "1.0", null)))
                .build();
    }

    private List<JsonNode> script(String remote) {
        JsonNode node = scripts.get(remote);
        if (node == null || !node.isArray() || node.isEmpty()) {
            String message = "remote " + remote + " has no scripted behaviour in this case (add remotes: " + remote + ": [ { state: completed, text: \"…\" } ])";
            recorder.violation(message);
            throw new IllegalStateException(message);
        }
        List<JsonNode> out = new ArrayList<>();
        node.forEach(out::add);
        return out;
    }

    @Override
    public SendResult send(AgentCardView.Endpoint endpoint, SecretRef credential, A2AMessage message, SendOptions options) {
        String remote = byEndpoint.get(endpoint.url());
        List<JsonNode> steps = script(remote);
        recorder.call(remote);
        if (message.taskId() != null) {
            Task task = tasks.get(message.taskId());
            if (task == null) throw new A2AException.TaskNotFound(message.taskId());
            task.history.add(message);
            if (task.state().isWaitingOnCaller() && task.index < task.steps.size() - 1) {
                task.index++;
                progressed = true;
            }
            return new SendResult.TaskResult(view(task));
        }
        if (steps.get(0).has("reply")) {
            return new SendResult.MessageResult(A2AMessage.agent("scripted-reply-" + (++counter), List.of(new Part.Text(steps.get(0).get("reply").asText()))));
        }
        Task task = new Task("task-" + (++counter), remote, steps);
        task.history.add(message);
        tasks.put(task.id, task);
        progressed = true; // a task was made: the remote has begun
        return new SendResult.TaskResult(view(task));
    }

    @Override
    public RemoteTask get(AgentCardView.Endpoint endpoint, SecretRef credential, String taskId) {
        Task task = tasks.get(taskId);
        if (task == null) throw new A2AException.TaskNotFound(taskId);
        if (task.state() == TaskState.WORKING && task.index < task.steps.size() - 1) {
            task.index++;
            progressed = true;
        }
        return view(task);
    }

    @Override
    public RemoteTask cancel(AgentCardView.Endpoint endpoint, SecretRef credential, String taskId) {
        Task task = tasks.get(taskId);
        if (task == null) throw new A2AException.TaskNotFound(taskId);
        task.canceled = true;
        return view(task);
    }

    @Override
    public List<RemoteTask> recentTasks(AgentCardView.Endpoint endpoint, SecretRef credential, Instant since, int historyLimit) {
        return tasks.values().stream().map(this::view).toList();
    }

    @Override
    public boolean supports(AgentCardView.Endpoint endpoint) {
        return true;
    }

    @Override
    public Set<String> supportedExtensions() {
        return Set.of();
    }

    // ---- a step as a task

    private static TaskState stateOf(JsonNode step) {
        return switch (step.path("state").asText("working")) {
            case "input_required" -> TaskState.INPUT_REQUIRED;
            case "auth_required" -> TaskState.AUTH_REQUIRED;
            case "completed" -> TaskState.COMPLETED;
            case "failed" -> TaskState.FAILED;
            case "rejected" -> TaskState.REJECTED;
            case "canceled" -> TaskState.CANCELED;
            default -> TaskState.WORKING;
        };
    }

    private RemoteTask view(Task task) {
        JsonNode step = task.step();
        TaskState state = task.state();
        A2AMessage status = null;
        String note = firstText(step, "question", "explanation", "message");
        if (note != null && !state.equals(TaskState.COMPLETED))
            status = A2AMessage.agent("scripted-status-" + task.id + "-" + task.index, List.of(new Part.Text(note)));
        List<Artifact> artifacts = new ArrayList<>();
        if (state == TaskState.COMPLETED) {
            List<Part> parts = new ArrayList<>();
            if (step.has("text")) parts.add(new Part.Text(step.get("text").asText()));
            if (step.has("data")) parts.add(new Part.Data(step.get("data")));
            if (step.has("files"))
                for (JsonNode f : step.get("files"))
                    parts.add(Part.FileOffered.link(f.path("link").asText(), f.path("name").asText(""), f.path("type").asText("")));
            if (!parts.isEmpty()) artifacts.add(new Artifact("scripted-artifact", "Result", parts));
        }
        return new RemoteTask(task.id, "scripted-context-" + task.id, state, status, artifacts, task.history);
    }

    private static String firstText(JsonNode step, String... keys) {
        for (String key : keys) if (step.has(key)) return step.get(key).asText();
        return null;
    }
}
