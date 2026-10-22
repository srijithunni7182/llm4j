package io.github.llm4j.provider.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.llm4j.exception.InvalidRequestException;
import io.github.llm4j.grounding.Grounding;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.Message;
import io.github.llm4j.model.ToolCall;
import io.github.llm4j.model.ToolSpec;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Writes a Responses-schema request body for a {@link ResponsesDialect}. */
final class ResponsesRequestWriter {

    /**
     * Key in {@link Message#getProviderData()} holding the output items of a tool-call turn, to be
     * sent back verbatim.
     */
    static final String OUTPUT = "responses.output";

    private static final Logger logger = LoggerFactory.getLogger(ResponsesRequestWriter.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private ResponsesRequestWriter() {}

    static String write(ResponsesDialect dialect, LLMRequest request, String model, boolean stream)
            throws IOException {
        ModelTraits traits = dialect.traits(model);
        ObjectNode root = JSON.createObjectNode();
        root.put(dialect.modelField(model), dialect.modelValue(model));

        String instructions =
                request.getMessages().stream()
                        .filter(m -> m.getRole() == Message.Role.SYSTEM)
                        .map(Message::getContent)
                        .collect(Collectors.joining("\n\n"));
        if (!instructions.isEmpty()) root.put("instructions", instructions);

        Object previous = request.getAdditionalParameters().get("previous_response_id");
        root.set("input", input(dialect, request.getMessages(), previous != null));
        if (stream) root.put("stream", true);

        if (request.getTemperature() != null) {
            if (traits.acceptsTemperature()) root.put("temperature", request.getTemperature());
            else logger.debug("{} doesn't accept a custom temperature; leaving it out", model);
        }
        if (request.getTopP() != null) {
            if (traits.acceptsTopP()) root.put("top_p", request.getTopP());
            else logger.debug("{} doesn't accept a custom top_p; leaving it out", model);
        }
        Integer limit =
                request.getMaxTokens() != null
                        ? request.getMaxTokens()
                        : dialect.requiredMaxTokens(model);
        if (limit != null) root.put(dialect.maxTokensField(), limit);
        if (request.getStopSequences() != null && !request.getStopSequences().isEmpty()) {
            logger.debug("The Responses schema has no stop sequences; leaving them out");
        }
        if (!request.getTools().isEmpty()) {
            ArrayNode tools = root.putArray("tools");
            for (ToolSpec t : request.getTools()) {
                ObjectNode tool =
                        tools.addObject()
                                .put("type", "function")
                                .put("name", t.name())
                                .put("description", t.description());
                tool.set("parameters", JSON.valueToTree(t.parameters()));
            }
            if (request.getToolChoice() == LLMRequest.ToolChoice.NONE)
                root.put("tool_choice", "none");
        }
        if (request.getGrounding() != null) grounding(dialect, request.getGrounding(), root);
        if (stream && request.isReasoning() && dialect.reasoningSummary()) {
            ((ObjectNode) root.with("reasoning")).put("summary", "auto");
        }
        dialect.extraRequest().forEach((k, v) -> root.set(k, JSON.valueToTree(v)));
        // the escape hatch; an object that is already there ({@code reasoning}) is merged, not
        // replaced
        request.getAdditionalParameters()
                .forEach(
                        (k, v) -> {
                            JsonNode value = JSON.valueToTree(v);
                            if (root.get(k) instanceof ObjectNode existing
                                    && value instanceof ObjectNode extra) existing.setAll(extra);
                            else root.set(k, value);
                        });
        return JSON.writeValueAsString(root);
    }

    /**
     * Adds the vendor's search tool and the settings of the grounding, following the dialect's
     * {@link ResponsesGrounding}. A provider used without the client's central check still refuses
     * what it cannot do.
     */
    private static void grounding(ResponsesDialect dialect, Grounding grounding, ObjectNode root) {
        ResponsesGrounding spec = dialect.grounding();
        if (spec == null) {
            throw new InvalidRequestException(
                    "Provider " + dialect.name() + " does not support grounding");
        }
        ArrayNode tools =
                root.has("tools") ? (ArrayNode) root.get("tools") : root.putArray("tools");
        ObjectNode tool = tools.addObject().put("type", spec.toolType());
        List<String> allow = new ArrayList<>(grounding.allowDomains());
        List<String> block = new ArrayList<>(grounding.blockDomains());
        if (spec.blockPrefix() != null) {
            // blocked domains share the allowed list, spelled with a prefix
            for (String d : block) allow.add(spec.blockPrefix() + d);
            block = List.of();
        }
        if (allow.size() > spec.maxAllowDomains())
            throw tooMany(dialect, "domains", allow.size(), spec.maxAllowDomains());
        if (block.size() > spec.maxBlockDomains())
            throw tooMany(dialect, "blocked domains", block.size(), spec.maxBlockDomains());
        if (!allow.isEmpty()) set(tool, spec.allowPath(), strings(allow));
        if (!block.isEmpty()) set(tool, spec.blockPath(), strings(block));
        if (grounding.recency() != null) {
            set(
                    tool,
                    spec.recencyPath(),
                    JSON.getNodeFactory().textNode(spec.recencyValues().get(grounding.recency())));
        }
        if (grounding.country() != null) {
            set(tool, spec.countryPath(), JSON.getNodeFactory().textNode(grounding.country()));
            spec.countryExtras()
                    .forEach(
                            (path, value) ->
                                    set(tool, path, JSON.getNodeFactory().textNode(value)));
        }
        if (grounding.maxSearches() != null)
            root.put(spec.maxSearchesField(), grounding.maxSearches());
        if (!spec.include().isEmpty()) {
            Set<String> include = new LinkedHashSet<>();
            if (root.has("include")) root.get("include").forEach(n -> include.add(n.asText()));
            include.addAll(spec.include());
            root.set("include", strings(new ArrayList<>(include)));
        }
    }

    private static InvalidRequestException tooMany(
            ResponsesDialect dialect, String what, int given, int limit) {
        return new InvalidRequestException(
                dialect.name()
                        + " accepts at most "
                        + limit
                        + " "
                        + what
                        + " in a search, got "
                        + given);
    }

    private static ArrayNode strings(List<String> values) {
        ArrayNode a = JSON.createArrayNode();
        values.forEach(a::add);
        return a;
    }

    /** Sets {@code value} at a dotted path inside {@code node}, creating the objects on the way. */
    private static void set(ObjectNode node, String path, JsonNode value) {
        String[] parts = path.split("\\.");
        ObjectNode at = node;
        for (int i = 0; i < parts.length - 1; i++) {
            JsonNode next = at.get(parts[i]);
            at = next instanceof ObjectNode o ? o : at.putObject(parts[i]);
        }
        at.set(parts[parts.length - 1], value);
    }

    private static ArrayNode input(
            ResponsesDialect dialect, List<Message> messages, boolean continuing) {
        int from = 0;
        if (continuing) {
            // the vendor holds the conversation up to its last answer: send only what came after it
            for (int i = messages.size() - 1; i >= 0; i--) {
                if (messages.get(i).getRole() == Message.Role.ASSISTANT) {
                    from = i + 1;
                    break;
                }
            }
        }
        ArrayNode input = JSON.createArrayNode();
        for (int i = from; i < messages.size(); i++) {
            Message m = messages.get(i);
            switch (m.getRole()) {
                case SYSTEM -> {
                    // sent as instructions
                }
                case TOOL -> input.addObject()
                        .put("type", "function_call_output")
                        .put("call_id", m.getToolCallId() == null ? "" : m.getToolCallId())
                        .put("output", m.getContent());
                case ASSISTANT -> assistant(dialect, m, input);
                default -> input.addObject()
                        .put("type", "message")
                        .put("role", "user")
                        .put("content", m.getContent());
            }
        }
        return input;
    }

    private static void assistant(ResponsesDialect dialect, Message m, ArrayNode input) {
        if (m.getToolCalls().isEmpty()) {
            input.addObject()
                    .put("type", "message")
                    .put("role", "assistant")
                    .put("content", m.getContent());
            return;
        }
        Object raw = m.getProviderData().get(OUTPUT);
        if (raw instanceof List<?> items && !items.isEmpty()) {
            for (Object item : items) {
                JsonNode node = JSON.valueToTree(item);
                if (dialect.replayTypes().contains(node.path("type").asText())) input.add(node);
            }
            return;
        }
        if (!m.getContent().isEmpty()) {
            input.addObject()
                    .put("type", "message")
                    .put("role", "assistant")
                    .put("content", m.getContent());
        }
        for (ToolCall c : m.getToolCalls()) {
            input.addObject()
                    .put("type", "function_call")
                    .put("call_id", c.id() == null ? "" : c.id())
                    .put("name", c.name())
                    .put("arguments", JSON.valueToTree(c.arguments()).toString());
        }
    }
}
