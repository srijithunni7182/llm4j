package io.github.llm4j.a2a;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The one place in llm4j that knows a wire name: it translates A2A 1.0 over JSON-RPC to and from
 * the version-free model. Pure functions, no network. A consumer's transport that speaks this
 * binding calls it and only moves the bytes; a consumer who speaks anything else writes their own
 * mapping and uses the rest of the package unchanged.
 *
 * <p>It writes only the 1.0 form. On input it accepts both spellings of a task state that the 1.0
 * text and its examples use, ignores fields it does not know, and treats a state it does not
 * recognise as {@link TaskState#UNKNOWN}. Everything in a response is untrusted.
 */
public final class A2AJsonRpc10 {

    /** The protocol version this helper speaks, as {@code Major.Minor}. */
    public static final String VERSION = "1.0";

    private static final String BINDING = "JSONRPC";
    private static final ObjectMapper JSON = new ObjectMapper();

    private A2AJsonRpc10() {}

    /**
     * A JSON-RPC request ready to be sent.
     *
     * @param body the JSON text to post
     * @param headers the service parameters to send as HTTP headers
     */
    public record Request(String body, Map<String, String> headers) {
        /** Copies the headers. */
        public Request {
            Objects.requireNonNull(body, "body");
            headers = Map.copyOf(headers);
        }
    }

    // ---- requests ----

    /** The headers every request needs: the protocol version, and the extensions in use if any. */
    public static Map<String, String> serviceParameters(List<String> extensionUris) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("A2A-Version", VERSION);
        if (!extensionUris.isEmpty()) {
            headers.put("A2A-Extensions", String.join(",", extensionUris));
        }
        return headers;
    }

    /**
     * Builds a send request. It always sets the flag that asks the server not to hold the call,
     * because llm4j never waits.
     *
     * @param id the JSON-RPC id
     * @param message the message
     * @param options how to send
     * @param tenant a tenant to pass through, or null
     */
    public static Request sendMessage(
            long id, A2AMessage message, SendOptions options, String tenant) {
        ObjectNode params = JSON.createObjectNode();
        putTenant(params, tenant);
        params.set("message", writeMessage(message));
        ObjectNode configuration = params.putObject("configuration");
        if (!options.acceptedOutputMediaTypes().isEmpty()) {
            ArrayNode modes = configuration.putArray("acceptedOutputModes");
            options.acceptedOutputMediaTypes().forEach(modes::add);
        }
        if (options.historyLength() != null) {
            configuration.put("historyLength", options.historyLength());
        }
        configuration.put("returnImmediately", options.returnImmediately());
        return request(id, "SendMessage", params);
    }

    /** Builds a request to read a task. */
    public static Request getTask(long id, String taskId, Integer historyLength, String tenant) {
        ObjectNode params = JSON.createObjectNode();
        putTenant(params, tenant);
        params.put("id", taskId);
        if (historyLength != null) {
            params.put("historyLength", historyLength);
        }
        return request(id, "GetTask", params);
    }

    /** Builds a request to cancel a task. */
    public static Request cancelTask(long id, String taskId, String tenant) {
        ObjectNode params = JSON.createObjectNode();
        putTenant(params, tenant);
        params.put("id", taskId);
        return request(id, "CancelTask", params);
    }

    /**
     * Builds a request to list tasks updated since a time, with history.
     *
     * @param since only tasks whose status changed at or after this
     * @param historyLength how many history messages per task
     * @param pageSize the page size
     */
    public static Request listTasks(
            long id, Instant since, int historyLength, int pageSize, String tenant) {
        ObjectNode params = JSON.createObjectNode();
        putTenant(params, tenant);
        params.put("pageSize", pageSize);
        params.put("historyLength", historyLength);
        params.put("statusTimestampAfter", since.toString());
        return request(id, "ListTasks", params);
    }

    /** Whether this helper can speak to the endpoint: the JSON-RPC binding at version 1.0. */
    public static boolean supports(AgentCardView.Endpoint endpoint) {
        return BINDING.equalsIgnoreCase(endpoint.binding())
                && VERSION.equals(endpoint.protocolVersion());
    }

    private static Request request(long id, String method, ObjectNode params) {
        ObjectNode root = JSON.createObjectNode();
        root.put("jsonrpc", "2.0");
        root.put("id", id);
        root.put("method", method);
        root.set("params", params);
        return new Request(root.toString(), serviceParameters(List.of()));
    }

    private static void putTenant(ObjectNode params, String tenant) {
        if (tenant != null && !tenant.isEmpty()) {
            params.put("tenant", tenant);
        }
    }

    private static ObjectNode writeMessage(A2AMessage message) {
        ObjectNode node = JSON.createObjectNode();
        node.put("messageId", message.messageId());
        if (message.contextId() != null) {
            node.put("contextId", message.contextId());
        }
        if (message.taskId() != null) {
            node.put("taskId", message.taskId());
        }
        node.put("role", message.role() == A2AMessage.Role.USER ? "ROLE_USER" : "ROLE_AGENT");
        ArrayNode parts = node.putArray("parts");
        message.parts().forEach(p -> parts.add(writePart(p)));
        if (!message.referenceTaskIds().isEmpty()) {
            ArrayNode refs = node.putArray("referenceTaskIds");
            message.referenceTaskIds().forEach(refs::add);
        }
        return node;
    }

    private static ObjectNode writePart(Part part) {
        ObjectNode node = JSON.createObjectNode();
        if (part instanceof Part.Text t) {
            node.put("text", t.text());
        } else if (part instanceof Part.Data d) {
            node.set("data", d.value());
        } else if (part instanceof Part.FileOffered f) {
            if (f.isLink()) {
                node.put("url", f.url());
            } else {
                node.put("raw", Base64.getEncoder().encodeToString(f.bytes()));
            }
            if (!f.label().isEmpty()) {
                node.put("filename", f.label());
            }
            if (!f.claimedMediaType().isEmpty()) {
                node.put("mediaType", f.claimedMediaType());
            }
        }
        return node;
    }

    // ---- responses ----

    /** Parses the response to a send: a task, or a plain message. */
    public static SendResult parseSendResponse(String json) {
        JsonNode result = result(json);
        if (result.has("task")) {
            return new SendResult.TaskResult(readTask(result.get("task")));
        }
        if (result.has("message")) {
            return new SendResult.MessageResult(readMessage(result.get("message")));
        }
        if (result.has("id") && result.has("status")) {
            return new SendResult.TaskResult(readTask(result));
        }
        throw new A2AException.Malformed(
                "the response to a send holds neither a task nor a message", null);
    }

    /** Parses the response to a get or a cancel. */
    public static RemoteTask parseGetTaskResponse(String json) {
        return readTask(result(json));
    }

    /** Parses the response to a list. */
    public static List<RemoteTask> parseListTasksResponse(String json) {
        JsonNode tasks = result(json).get("tasks");
        if (tasks == null || !tasks.isArray()) {
            throw new A2AException.Malformed("the list response has no tasks array", null);
        }
        List<RemoteTask> out = new ArrayList<>();
        tasks.forEach(t -> out.add(readTask(t)));
        return out;
    }

    /**
     * Parses an agent card. The signature status is {@code NOT_SIGNED} when the card carries no
     * signature and {@code NOT_CHECKED} when it does: llm4j does no cryptography, so a transport
     * that verifies replaces the status with the result.
     */
    public static AgentCardView parseCard(String json) {
        JsonNode card = parse(json);
        if (!card.isObject() || text(card, "name").isEmpty()) {
            throw new A2AException.Malformed("the agent card has no name", null);
        }
        List<AgentCardView.Endpoint> endpoints = new ArrayList<>();
        for (JsonNode i : array(card, "supportedInterfaces")) {
            if (!text(i, "url").isEmpty()) {
                endpoints.add(
                        new AgentCardView.Endpoint(
                                text(i, "url"),
                                text(i, "protocolBinding"),
                                text(i, "protocolVersion"),
                                nullable(i, "tenant")));
            }
        }
        if (endpoints.isEmpty() && !text(card, "url").isEmpty()) {
            // an older card has one address and states its own version; offer it as it is so the
            // refusal can name the version
            String binding =
                    text(card, "preferredTransport").isEmpty()
                            ? BINDING
                            : text(card, "preferredTransport");
            endpoints.add(
                    new AgentCardView.Endpoint(
                            text(card, "url"), binding, text(card, "protocolVersion"), null));
        }
        List<AgentCardView.Skill> skills = new ArrayList<>();
        for (JsonNode s : array(card, "skills")) {
            skills.add(
                    new AgentCardView.Skill(
                            text(s, "id"),
                            text(s, "name"),
                            text(s, "description"),
                            strings(s, "tags"),
                            strings(s, "examples"),
                            strings(s, "inputModes"),
                            strings(s, "outputModes")));
        }
        List<AgentCardView.Extension> extensions = new ArrayList<>();
        for (JsonNode e : array(card.path("capabilities"), "extensions")) {
            extensions.add(
                    new AgentCardView.Extension(
                            text(e, "uri"), e.path("required").asBoolean(false)));
        }
        List<String> schemes = new ArrayList<>();
        JsonNode securitySchemes = card.path("securitySchemes");
        if (securitySchemes.isObject()) {
            securitySchemes.fieldNames().forEachRemaining(schemes::add);
        }
        boolean signed = array(card, "signatures").iterator().hasNext();
        try {
            return new AgentCardView(
                    text(card, "name"),
                    text(card, "description"),
                    skills,
                    endpoints,
                    strings(card, "defaultInputModes"),
                    extensions,
                    signed
                            ? AgentCardView.SignatureStatus.NOT_CHECKED
                            : AgentCardView.SignatureStatus.NOT_SIGNED,
                    schemes);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new A2AException.Malformed("the agent card is not valid: " + e.getMessage(), e);
        }
    }

    // ---- reading ----

    private static JsonNode parse(String json) {
        if (json == null || json.isBlank()) {
            throw new A2AException.Malformed("the response is empty", null);
        }
        try {
            return JSON.readTree(json);
        } catch (JsonProcessingException e) {
            throw new A2AException.Malformed("the response is not JSON", e);
        }
    }

    private static JsonNode result(String json) {
        JsonNode root = parse(json);
        if (!root.isObject()) {
            throw new A2AException.Malformed("the response is not a JSON object", null);
        }
        JsonNode error = root.get("error");
        if (error != null && !error.isNull()) {
            throw error(error);
        }
        JsonNode result = root.get("result");
        if (result == null || !result.isObject()) {
            throw new A2AException.Malformed("the response has no result", null);
        }
        return result;
    }

    private static A2AException error(JsonNode error) {
        int code = error.path("code").asInt(0);
        String message = error.path("message").asText("");
        return switch (code) {
            case -32001 -> new A2AException.TaskNotFound(detail(error, "task"));
            case -32002 -> new A2AException.TaskNotCancelable(detail(error, "task"));
            case -32004 -> new A2AException.UnsupportedOperation(message);
            case -32005 -> new A2AException.ContentTypeNotSupported(message);
            case -32008 -> new A2AException.ExtensionSupportRequired(message);
            case -32009 -> new A2AException.VersionNotSupported(message);
            default -> new A2AException("the remote agent reported an error: " + message);
        };
    }

    private static String detail(JsonNode error, String fallback) {
        for (JsonNode item : array(error, "data")) {
            String taskId = item.path("metadata").path("taskId").asText("");
            if (!taskId.isEmpty()) {
                return taskId;
            }
        }
        return fallback;
    }

    private static RemoteTask readTask(JsonNode task) {
        try {
            JsonNode status = task.path("status");
            A2AMessage question = status.has("message") ? readMessage(status.get("message")) : null;
            List<Artifact> artifacts = new ArrayList<>();
            for (JsonNode a : array(task, "artifacts")) {
                artifacts.add(new Artifact(text(a, "artifactId"), text(a, "name"), readParts(a)));
            }
            List<A2AMessage> history = new ArrayList<>();
            for (JsonNode m : array(task, "history")) {
                history.add(readMessage(m));
            }
            return new RemoteTask(
                    text(task, "id"),
                    text(task, "contextId"),
                    state(status.path("state").asText(null)),
                    question,
                    artifacts,
                    history);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new A2AException.Malformed("the task is not valid: " + e.getMessage(), e);
        }
    }

    private static A2AMessage readMessage(JsonNode message) {
        try {
            String role = message.path("role").asText("").toLowerCase(Locale.ROOT);
            A2AMessage.Role parsed =
                    role.contains("user") ? A2AMessage.Role.USER : A2AMessage.Role.AGENT;
            return new A2AMessage(
                    text(message, "messageId"),
                    parsed,
                    readParts(message),
                    nullable(message, "contextId"),
                    nullable(message, "taskId"),
                    strings(message, "referenceTaskIds"));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new A2AException.Malformed("the message is not valid: " + e.getMessage(), e);
        }
    }

    private static List<Part> readParts(JsonNode owner) {
        List<Part> parts = new ArrayList<>();
        for (JsonNode p : array(owner, "parts")) {
            String label = text(p, "filename");
            String type = text(p, "mediaType");
            if (p.has("text")) {
                parts.add(new Part.Text(p.get("text").asText()));
            } else if (p.has("data")) {
                parts.add(new Part.Data(p.get("data")));
            } else if (p.has("url")) {
                parts.add(Part.FileOffered.link(p.get("url").asText(), label, type));
            } else if (p.has("raw")) {
                try {
                    parts.add(
                            Part.FileOffered.inline(
                                    Base64.getDecoder().decode(p.get("raw").asText()),
                                    label,
                                    type));
                } catch (IllegalArgumentException e) {
                    throw new A2AException.Malformed("a raw part is not valid base64", e);
                }
            } else {
                throw new A2AException.Malformed(
                        "a part holds none of text, data, url or raw", null);
            }
        }
        return parts;
    }

    private static TaskState state(String wire) {
        if (wire == null) {
            return TaskState.UNKNOWN;
        }
        String key = wire.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        if (key.startsWith("TASK_STATE_")) {
            key = key.substring("TASK_STATE_".length());
        }
        return switch (key) {
            case "SUBMITTED", "WORKING" -> TaskState.WORKING;
            case "INPUT_REQUIRED" -> TaskState.INPUT_REQUIRED;
            case "AUTH_REQUIRED" -> TaskState.AUTH_REQUIRED;
            case "COMPLETED" -> TaskState.COMPLETED;
            case "FAILED" -> TaskState.FAILED;
            case "CANCELED", "CANCELLED" -> TaskState.CANCELED;
            case "REJECTED" -> TaskState.REJECTED;
            default -> TaskState.UNKNOWN;
        };
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText("");
    }

    private static String nullable(JsonNode node, String field) {
        String value = text(node, field);
        return value.isEmpty() ? null : value;
    }

    private static Iterable<JsonNode> array(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isArray() ? value : List.of();
    }

    private static List<String> strings(JsonNode node, String field) {
        List<String> out = new ArrayList<>();
        array(node, field).forEach(v -> out.add(v.asText()));
        return out;
    }
}
