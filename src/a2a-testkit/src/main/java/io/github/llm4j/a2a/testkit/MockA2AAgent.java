package io.github.llm4j.a2a.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;

/**
 * A server-side A2A 1.0 agent for tests: it runs on loopback only, never talks to a live service,
 * enforces the rules of {@link SpecRules}, advances its scripted tasks by request count (never by the
 * clock), and checks every message it receives and every message it sends against the official
 * definition ({@link OfficialDefinition}). A message it sends that is invalid is recorded in
 * {@link #selfCheckFailures()} so a test can fail on it. It speaks the JSON-RPC binding; streaming,
 * push notification and the extended card are declared unsupported on its card and answered with the
 * specification's errors.
 *
 * <p>Its card advertises {@code publicBase} (an https address that does not exist) so a client's
 * https guard runs for real; the test's transport routes that host to {@link #loopbackBase()}.
 */
public final class MockA2AAgent implements AutoCloseable {

    /** How the agent misbehaves, for hostile-input tests. */
    public enum Mode {
        /** Behaves. */
        NONE,
        /** The card points at another host. */
        CARD_ELSEWHERE,
        /** Every RPC response is far larger than a sane client accepts. */
        OVERSIZED_RESPONSE,
        /** Every RPC response is not JSON. */
        MALFORMED_JSON,
        /** Tasks report a state that does not exist. */
        UNKNOWN_STATE,
        /** The request is processed, then the connection is dropped before any response. */
        DROP_AFTER_RECEIVE
    }

    /** One scripted step of a task. */
    public record Step(String state, String statusText, String artifactsJson) {
        /** A task still working. */
        public static Step working() {
            return new Step("TASK_STATE_WORKING", null, null);
        }

        /** A question for the caller. */
        public static Step asks(String question) {
            return new Step("TASK_STATE_INPUT_REQUIRED", question, null);
        }

        /** A request for authorization, with its explanation. */
        public static Step authRequired(String explanation) {
            return new Step("TASK_STATE_AUTH_REQUIRED", explanation, null);
        }

        /** Done, with artifacts given as a JSON array of Artifact objects. */
        public static Step completed(String artifactsJson) {
            return new Step("TASK_STATE_COMPLETED", null, artifactsJson);
        }

        /** Done with one text artifact. */
        public static Step completedWithText(String text) {
            return completed("[{\"artifactId\":\"a-1\",\"name\":\"Result\",\"parts\":[{\"text\":\"" + text + "\"}]}]");
        }

        /** Failed. */
        public static Step failed(String message) {
            return new Step("TASK_STATE_FAILED", message, null);
        }

        /** Declined. */
        public static Step rejected(String message) {
            return new Step("TASK_STATE_REJECTED", message, null);
        }
    }

    /** What happens to each new task, or a plain-message answer with no task. */
    public static final class Script {
        private final List<Step> steps;
        private final String plainMessage;

        private Script(List<Step> steps, String plainMessage) {
            this.steps = steps;
            this.plainMessage = plainMessage;
        }

        /** A task that moves through these steps: one per request that can advance it. */
        public static Script of(Step... steps) {
            return new Script(List.of(steps), null);
        }

        /** A task that is done at once with one text artifact. */
        public static Script completesAtOnce(String text) {
            return of(Step.completedWithText(text));
        }

        /** An answer that is a plain message and no task. */
        public static Script plainMessage(String text) {
            return new Script(List.of(), text);
        }
    }

    /** One request the agent received. */
    public record Received(String method, JsonNode params, Map<String, String> headers, String scope) {
        /** The id of the message in a send, or null. */
        public String messageId() {
            JsonNode id = params.path("message").path("messageId");
            return id.isMissingNode() ? null : id.asText();
        }
    }

    private static final class MockTask {
        final String id;
        final String contextId;
        final String scope;
        final Script script;
        final List<JsonNode> history = new ArrayList<>();
        int step;
        boolean canceled;
        Instant updated;

        MockTask(String id, String contextId, String scope, Script script) {
            this.id = id;
            this.contextId = contextId;
            this.scope = scope;
            this.script = script;
        }

        Step current() {
            return canceled ? new Step("TASK_STATE_CANCELED", null, null) : script.steps.get(step);
        }

        boolean terminal() {
            String s = current().state();
            return s.equals("TASK_STATE_COMPLETED") || s.equals("TASK_STATE_FAILED")
                    || s.equals("TASK_STATE_CANCELED") || s.equals("TASK_STATE_REJECTED");
        }

        boolean working() {
            return current().state().equals("TASK_STATE_WORKING");
        }

        boolean waiting() {
            String s = current().state();
            return s.equals("TASK_STATE_INPUT_REQUIRED") || s.equals("TASK_STATE_AUTH_REQUIRED");
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ERROR_DOMAIN = "a2a-protocol.org";
    private static final int OVERSIZE_BYTES = 6 * 1024 * 1024;

    private final MockWebServer server = new MockWebServer();
    private final String publicBase;
    private final Map<String, MockTask> tasks = new LinkedHashMap<>();
    private final List<Received> received = Collections.synchronizedList(new ArrayList<>());
    private final List<String> selfCheckFailures = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, String> dedupe = new LinkedHashMap<>();
    private final List<String[]> interfaces = new ArrayList<>();
    private Script script = Script.completesAtOnce("done");
    private boolean acceptsProposedContext = true;
    private boolean dedupeOnMessageId;
    private boolean listingRefused;
    private boolean requireAuth;
    private String requiredExtension;
    private int historyLimit = Integer.MAX_VALUE;
    private Mode mode = Mode.NONE;
    private int taskCounter;
    private int contextCounter;
    private Instant lastUpdate = Instant.EPOCH;

    private MockA2AAgent(String publicBase) {
        this.publicBase = publicBase;
    }

    /**
     * Starts an agent on loopback. Its card advertises {@code publicBase}.
     *
     * @param publicBase an https base address, such as {@code https://agent.example.test}
     */
    public static MockA2AAgent start(String publicBase) {
        MockA2AAgent agent = new MockA2AAgent(publicBase);
        agent.interfaces.add(new String[] {publicBase + "/rpc", "JSONRPC", "1.0", null});
        agent.server.setDispatcher(
                new Dispatcher() {
                    @Override
                    public MockResponse dispatch(RecordedRequest request) {
                        return agent.dispatch(request);
                    }
                });
        try {
            agent.server.start(java.net.InetAddress.getLoopbackAddress(), 0);
        } catch (IOException e) {
            throw new IllegalStateException("could not start the mock agent on loopback", e);
        }
        return agent;
    }

    /** The loopback base address (http, a port on 127.0.0.1) the tests' transport routes to. */
    public String loopbackBase() {
        return "http://127.0.0.1:" + server.getPort();
    }

    /** The https base address the card advertises. */
    public String publicBase() {
        return publicBase;
    }

    /** The address the script would declare for the card. */
    public String cardAddress() {
        return publicBase + "/.well-known/agent-card.json";
    }

    /** What each new task does. */
    public MockA2AAgent script(Script value) {
        this.script = value;
        return this;
    }

    /** Whether a client-proposed context id is accepted (otherwise it is rejected, never replaced). */
    public MockA2AAgent acceptsProposedContext(boolean value) {
        this.acceptsProposedContext = value;
        return this;
    }

    /** Whether a repeated message id returns the earlier task instead of making a new one. */
    public MockA2AAgent dedupeOnMessageId(boolean value) {
        this.dedupeOnMessageId = value;
        return this;
    }

    /** Whether ListTasks is refused as unsupported. */
    public MockA2AAgent listingRefused(boolean value) {
        this.listingRefused = value;
        return this;
    }

    /** Requires an Authorization header; its value is the caller's scope. */
    public MockA2AAgent requireAuth(boolean value) {
        this.requireAuth = value;
        return this;
    }

    /** Marks an extension required on the card; requests must then declare it. */
    public MockA2AAgent requiredExtension(String uri) {
        this.requiredExtension = uri;
        return this;
    }

    /** Cuts the history returned for a task to its latest {@code n} messages, whatever is asked. */
    public MockA2AAgent historyLimit(int n) {
        this.historyLimit = n;
        return this;
    }

    /** Replaces the card's interfaces: each entry is url, binding, version and tenant (or null). */
    public MockA2AAgent interfaces(List<String[]> value) {
        this.interfaces.clear();
        this.interfaces.addAll(value);
        return this;
    }

    /** Makes the agent misbehave. */
    public MockA2AAgent mode(Mode value) {
        this.mode = value;
        return this;
    }

    /** Every request received, in order. */
    public List<Received> received() {
        synchronized (received) {
            return List.copyOf(received);
        }
    }

    /** The message ids of every send received, in order. */
    public List<String> receivedMessageIds() {
        return received().stream().filter(r -> r.method().equals("SendMessage")).map(Received::messageId).toList();
    }

    /** Messages this agent sent that the official definition rejects; must be empty. */
    public List<String> selfCheckFailures() {
        synchronized (selfCheckFailures) {
            return List.copyOf(selfCheckFailures);
        }
    }

    /** The number of tasks made. */
    public synchronized int taskCount() {
        return tasks.size();
    }

    @Override
    public void close() {
        try {
            server.shutdown();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- dispatch ----

    private synchronized MockResponse dispatch(RecordedRequest request) {
        String path = request.getPath() == null ? "" : request.getPath();
        if (path.equals("/.well-known/agent-card.json") && "GET".equals(request.getMethod())) {
            return card();
        }
        if (path.equals("/rpc") && "POST".equals(request.getMethod())) {
            return rpc(request);
        }
        return new MockResponse().setResponseCode(404);
    }

    private MockResponse card() {
        ObjectNode card = JSON.createObjectNode();
        card.put("name", "Mock Supplier");
        card.put("description", "A mock A2A 1.0 agent for tests");
        ArrayNode list = card.putArray("supportedInterfaces");
        for (String[] i : interfaces) {
            ObjectNode node = list.addObject();
            node.put("url", mode == Mode.CARD_ELSEWHERE ? "https://evil.example.net/rpc" : i[0]);
            node.put("protocolBinding", i[1]);
            if (i[3] != null) {
                node.put("tenant", i[3]);
            }
            node.put("protocolVersion", i[2]);
        }
        card.put("version", "1.0.0");
        ObjectNode capabilities = card.putObject("capabilities");
        capabilities.put("streaming", false);
        capabilities.put("pushNotifications", false);
        capabilities.put("extendedAgentCard", false);
        if (requiredExtension != null) {
            ObjectNode ext = capabilities.putArray("extensions").addObject();
            ext.put("uri", requiredExtension);
            ext.put("required", true);
        }
        card.putArray("defaultInputModes").add("text/plain").add("application/json");
        card.putArray("defaultOutputModes").add("text/plain").add("application/json");
        ObjectNode skill = card.putArray("skills").addObject();
        skill.put("id", "quote");
        skill.put("name", "Quote");
        skill.put("description", "Quotes a price");
        skill.putArray("tags").add("buy");
        check("AgentCard", card);
        return new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setHeader("Cache-Control", "max-age=60")
                .setHeader("ETag", "\"" + Integer.toHexString(card.toString().hashCode()) + "\"")
                .setBody(card.toString());
    }

    private MockResponse rpc(RecordedRequest request) {
        String body = request.getBody().readUtf8();
        String scope = request.getHeader("Authorization") == null ? "" : request.getHeader("Authorization");
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (IOException e) {
            return json(error(null, -32700, "Invalid JSON payload", "JSON_PARSE", Map.of()));
        }
        JsonNode id = root.path("id");
        String method = root.path("method").asText("");
        JsonNode params = root.path("params");
        Map<String, String> headers = new LinkedHashMap<>();
        request.getHeaders().names().forEach(n -> headers.put(n.toLowerCase(), request.getHeader(n)));
        received.add(new Received(method, params, headers, scope));
        if (requireAuth && scope.isEmpty()) {
            return new MockResponse().setResponseCode(401);
        }
        ObjectNode response = handle(id, method, params, headers, scope);
        MockResponse out = json(response);
        if (mode == Mode.DROP_AFTER_RECEIVE) {
            out.setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST);
        }
        if (mode == Mode.MALFORMED_JSON) {
            out.setBody("{ this is not json");
        }
        if (mode == Mode.OVERSIZED_RESPONSE) {
            out.setBody("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"pad\":\"" + "x".repeat(OVERSIZE_BYTES) + "\"}}");
        }
        return out;
    }

    private static MockResponse json(ObjectNode body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body.toString());
    }

    private ObjectNode handle(JsonNode id, String method, JsonNode params, Map<String, String> headers, String scope) {
        String version = headers.getOrDefault("a2a-version", "");
        // the version is matched on major and minor (spec 3.6.2): 1.0.1 is the same version as 1.0
        if (!(version.equals("1.0") || version.startsWith("1.0."))) {
            return error(id, -32009, "Unsupported A2A version '" + version + "'; this agent speaks 1.0", "VERSION_NOT_SUPPORTED", Map.of());
        }
        if (requiredExtension != null
                && !List.of(headers.getOrDefault("a2a-extensions", "").split(",")).contains(requiredExtension)) {
            return error(id, -32008, "The extension " + requiredExtension + " is required", "EXTENSION_SUPPORT_REQUIRED", Map.of());
        }
        String type = switch (method) {
            case "SendMessage" -> "SendMessageRequest";
            case "GetTask" -> "GetTaskRequest";
            case "ListTasks" -> "ListTasksRequest";
            case "CancelTask" -> "CancelTaskRequest";
            default -> null;
        };
        if (type == null) {
            return switch (method) {
                case "SendStreamingMessage", "SubscribeToTask" ->
                        error(id, -32004, "Streaming is not supported", "UNSUPPORTED_OPERATION", Map.of());
                case "CreateTaskPushNotificationConfig", "GetTaskPushNotificationConfig",
                        "ListTaskPushNotificationConfigs", "DeleteTaskPushNotificationConfig" ->
                        error(id, -32003, "Push notifications are not supported", "PUSH_NOTIFICATION_NOT_SUPPORTED", Map.of());
                case "GetExtendedAgentCard" ->
                        error(id, -32004, "An extended agent card is not offered", "UNSUPPORTED_OPERATION", Map.of());
                default -> error(id, -32601, "Method not found", "METHOD_NOT_FOUND", Map.of());
            };
        }
        List<String> problems = OfficialDefinition.problems(type, params.toString());
        if (!problems.isEmpty()) {
            return error(id, -32602, "Invalid parameters: " + problems, "INVALID_PARAMS", Map.of());
        }
        return switch (method) {
            case "SendMessage" -> send(id, params, headers, scope);
            case "GetTask" -> get(id, params, scope);
            case "CancelTask" -> cancel(id, params, scope);
            default -> list(id, params, scope);
        };
    }

    // ---- operations ----

    private ObjectNode send(JsonNode id, JsonNode params, Map<String, String> headers, String scope) {
        JsonNode message = params.get("message");
        String messageId = message.get("messageId").asText();
        String taskId = message.path("taskId").asText("");
        String contextId = message.path("contextId").asText("");
        boolean blocking = !params.path("configuration").path("returnImmediately").asBoolean(false);
        if (dedupeOnMessageId && dedupe.containsKey(scope + "|" + messageId)) {
            MockTask earlier = tasks.get(dedupe.get(scope + "|" + messageId));
            return ok(id, "SendMessageResponse", taskPayload(earlier, 0));
        }
        MockTask task;
        if (!taskId.isEmpty()) {
            task = tasks.get(taskId);
            if (task == null || !task.scope.equals(scope)) {
                return error(id, -32001, "Task not found", "TASK_NOT_FOUND", Map.of("taskId", taskId));
            }
            if (!contextId.isEmpty() && !contextId.equals(task.contextId)) {
                return error(id, -32602, "contextId does not match the task's context", "INVALID_PARAMS", Map.of());
            }
            if (task.terminal()) {
                return error(id, -32004, "The task is in a terminal state and takes no more messages", "UNSUPPORTED_OPERATION", Map.of("taskId", taskId));
            }
            task.history.add(message);
            if (task.waiting() && task.step < task.script.steps.size() - 1) {
                advance(task);
            }
        } else {
            if (!contextId.isEmpty() && !acceptsProposedContext) {
                return error(id, -32602, "This agent cannot accept a client-provided contextId", "INVALID_PARAMS", Map.of());
            }
            if (script.plainMessage != null) {
                ObjectNode reply = JSON.createObjectNode();
                reply.put("messageId", "reply-" + (++taskCounter));
                reply.put("contextId", contextId.isEmpty() ? "ctx-" + (++contextCounter) : contextId);
                reply.put("role", "ROLE_AGENT");
                reply.putArray("parts").addObject().put("text", script.plainMessage);
                ObjectNode payload = JSON.createObjectNode();
                payload.set("message", reply);
                return ok(id, "SendMessageResponse", payload);
            }
            task = new MockTask("task-" + (++taskCounter), contextId.isEmpty() ? "ctx-" + (++contextCounter) : contextId, scope, script);
            task.history.add(message);
            touch(task);
            tasks.put(task.id, task);
            dedupe.put(scope + "|" + messageId, task.id);
        }
        while (blocking && task.working() && task.step < task.script.steps.size() - 1) {
            advance(task);
        }
        return ok(id, "SendMessageResponse", taskPayload(task, Integer.MAX_VALUE));
    }

    private ObjectNode get(JsonNode id, JsonNode params, String scope) {
        MockTask task = tasks.get(params.get("id").asText());
        if (task == null || !task.scope.equals(scope)) {
            return error(id, -32001, "Task not found", "TASK_NOT_FOUND", Map.of("taskId", params.get("id").asText()));
        }
        if (task.working() && task.step < task.script.steps.size() - 1) {
            advance(task);
        }
        int history = params.has("historyLength") ? params.get("historyLength").asInt() : 0;
        return ok(id, "Task", taskJson(task, history, true));
    }

    private ObjectNode cancel(JsonNode id, JsonNode params, String scope) {
        MockTask task = tasks.get(params.get("id").asText());
        if (task == null || !task.scope.equals(scope)) {
            return error(id, -32001, "Task not found", "TASK_NOT_FOUND", Map.of("taskId", params.get("id").asText()));
        }
        if (task.canceled) {
            return ok(id, "Task", taskJson(task, 0, true));
        }
        if (task.terminal()) {
            return error(id, -32002, "The task is already finished", "TASK_NOT_CANCELABLE", Map.of("taskId", task.id));
        }
        task.canceled = true;
        touch(task);
        return ok(id, "Task", taskJson(task, 0, true));
    }

    private ObjectNode list(JsonNode id, JsonNode params, String scope) {
        if (listingRefused) {
            return error(id, -32004, "Listing is not supported", "UNSUPPORTED_OPERATION", Map.of());
        }
        int pageSize = params.has("pageSize") ? params.get("pageSize").asInt() : 50;
        if (pageSize < 1 || pageSize > 100) {
            return error(id, -32602, "pageSize must be between 1 and 100", "INVALID_PARAMS", Map.of());
        }
        int offset = params.path("pageToken").asText("").isEmpty() ? 0 : Integer.parseInt(params.get("pageToken").asText());
        Instant after = params.has("statusTimestampAfter") ? Instant.parse(params.get("statusTimestampAfter").asText()) : null;
        List<MockTask> matching = new ArrayList<>();
        for (MockTask t : tasks.values()) {
            boolean mine = t.scope.equals(scope);
            boolean context = params.path("contextId").asText("").isEmpty() || params.get("contextId").asText().equals(t.contextId);
            boolean recent = after == null || !t.updated.isBefore(after);
            if (mine && context && recent) {
                matching.add(t);
            }
        }
        matching.sort(Comparator.comparing((MockTask t) -> t.updated).reversed());
        int end = Math.min(matching.size(), offset + pageSize);
        ObjectNode result = JSON.createObjectNode();
        ArrayNode out = result.putArray("tasks");
        int history = params.has("historyLength") ? params.get("historyLength").asInt() : 0;
        boolean artifacts = params.path("includeArtifacts").asBoolean(false);
        for (MockTask t : matching.subList(Math.min(offset, end), end)) {
            out.add(taskJson(t, history, artifacts));
        }
        result.put("nextPageToken", end < matching.size() ? String.valueOf(end) : "");
        result.put("pageSize", pageSize);
        result.put("totalSize", matching.size());
        return ok(id, "ListTasksResponse", result);
    }

    // ---- building ----

    private void advance(MockTask task) {
        task.step++;
        touch(task);
        String text = task.current().statusText();
        if (text != null) {
            ObjectNode status = JSON.createObjectNode();
            status.put("messageId", "status-" + task.id + "-" + task.step);
            status.put("role", "ROLE_AGENT");
            status.put("contextId", task.contextId);
            status.put("taskId", task.id);
            status.putArray("parts").addObject().put("text", text);
            task.history.add(status);
        }
    }

    private void touch(MockTask task) {
        Instant now = Instant.now();
        lastUpdate = now.isAfter(lastUpdate) ? now : lastUpdate.plusMillis(1);
        task.updated = lastUpdate;
    }

    private ObjectNode taskPayload(MockTask task, int history) {
        ObjectNode payload = JSON.createObjectNode();
        payload.set("task", taskJson(task, history, true));
        return payload;
    }

    private ObjectNode taskJson(MockTask task, int historyLength, boolean withArtifacts) {
        ObjectNode node = JSON.createObjectNode();
        node.put("id", task.id);
        node.put("contextId", task.contextId);
        Step step = task.current();
        ObjectNode status = node.putObject("status");
        status.put("state", mode == Mode.UNKNOWN_STATE ? "TASK_STATE_QUANTUM" : step.state());
        if (step.statusText() != null) {
            ObjectNode message = status.putObject("message");
            message.put("messageId", "status-" + task.id + "-" + task.step);
            message.put("role", "ROLE_AGENT");
            message.put("contextId", task.contextId);
            message.put("taskId", task.id);
            message.putArray("parts").addObject().put("text", step.statusText());
        }
        status.put("timestamp", task.updated.toString());
        if (withArtifacts && step.artifactsJson() != null) {
            try {
                node.set("artifacts", JSON.readTree(step.artifactsJson()));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
        int shown = Math.min(Math.min(historyLength, historyLimit), task.history.size());
        if (shown > 0) {
            ArrayNode history = node.putArray("history");
            task.history.subList(task.history.size() - shown, task.history.size()).forEach(history::add);
        }
        return node;
    }

    private ObjectNode ok(JsonNode id, String resultType, ObjectNode result) {
        if (mode != Mode.UNKNOWN_STATE) {
            check(resultType, result);
        }
        ObjectNode response = JSON.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id);
        response.set("result", result);
        return response;
    }

    private ObjectNode error(JsonNode id, int code, String message, String reason, Map<String, String> metadata) {
        ObjectNode response = JSON.createObjectNode();
        response.put("jsonrpc", "2.0");
        if (id == null) {
            response.putNull("id");
        } else {
            response.set("id", id);
        }
        ObjectNode error = response.putObject("error");
        error.put("code", code);
        error.put("message", message);
        ObjectNode info = error.putArray("data").addObject();
        info.put("@type", "type.googleapis.com/google.rpc.ErrorInfo");
        info.put("reason", reason);
        info.put("domain", ERROR_DOMAIN);
        if (!metadata.isEmpty()) {
            ObjectNode meta = info.putObject("metadata");
            metadata.forEach(meta::put);
        }
        return response;
    }

    private void check(String type, ObjectNode node) {
        List<String> problems = OfficialDefinition.problems(type, node.toString());
        if (!problems.isEmpty()) {
            selfCheckFailures.add(type + ": " + problems);
        }
    }
}
