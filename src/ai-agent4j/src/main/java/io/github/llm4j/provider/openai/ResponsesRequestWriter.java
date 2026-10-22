package io.github.llm4j.provider.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.Message;
import io.github.llm4j.model.ToolCall;
import io.github.llm4j.model.ToolSpec;
import java.io.IOException;
import java.util.List;
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
