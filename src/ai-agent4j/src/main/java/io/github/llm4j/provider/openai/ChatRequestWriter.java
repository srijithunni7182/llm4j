package io.github.llm4j.provider.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.Message;
import io.github.llm4j.model.ToolCall;
import io.github.llm4j.model.ToolSpec;
import java.io.IOException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes a chat-completions request body for a {@link Dialect}; what a model refuses is left out,
 * as {@link ModelTraits} says.
 */
final class ChatRequestWriter {

    /**
     * Key in {@link Message#getProviderData()} holding the reasoning text that came with a tool
     * call, to be sent back with the tool results.
     */
    static final String REASONING = "chat.reasoning_content";

    private static final Logger logger = LoggerFactory.getLogger(ChatRequestWriter.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private ChatRequestWriter() {}

    static String write(Dialect dialect, LLMRequest request, String model, boolean stream)
            throws IOException {
        ModelTraits traits = dialect.traits(model);
        ObjectNode root = JSON.createObjectNode();
        root.put("model", model);
        ArrayNode messages = root.putArray("messages");
        for (Message m : request.getMessages()) messages.add(message(dialect, m));
        if (stream) {
            root.put("stream", true);
            if (dialect.streamUsage()) root.putObject("stream_options").put("include_usage", true);
        }
        if (request.getTemperature() != null) {
            if (traits.acceptsTemperature()) root.put("temperature", request.getTemperature());
            else logger.debug("{} doesn't accept a custom temperature; leaving it out", model);
        }
        if (request.getTopP() != null) {
            if (traits.acceptsTopP()) root.put("top_p", request.getTopP());
            else logger.debug("{} doesn't accept a custom top_p; leaving it out", model);
        }
        if (request.getMaxTokens() != null) {
            root.put(dialect.maxTokensField(), request.getMaxTokens());
        }
        if (request.getStopSequences() != null && !request.getStopSequences().isEmpty()) {
            if (traits.acceptsStop()) {
                ArrayNode stops = root.putArray("stop");
                request.getStopSequences().forEach(stops::add);
            } else {
                logger.debug("{} doesn't accept stop sequences; leaving them out", model);
            }
        }
        if (!request.getTools().isEmpty() && dialect.toolCalling()) {
            ArrayNode tools = root.putArray("tools");
            for (ToolSpec t : request.getTools()) {
                ObjectNode function =
                        tools.addObject()
                                .put("type", "function")
                                .putObject("function")
                                .put("name", t.name())
                                .put("description", t.description());
                function.set("parameters", JSON.valueToTree(t.parameters()));
            }
            if (request.getToolChoice() == LLMRequest.ToolChoice.NONE) {
                root.put("tool_choice", "none");
            }
        }
        // thinking is switched explicitly where the model has a switch: on when a streamed call
        // asks to see it, off otherwise
        Map<String, Object> thinking =
                stream && request.isReasoning() ? traits.reasoningOn() : traits.reasoningOff();
        thinking.forEach((k, v) -> root.set(k, JSON.valueToTree(v)));
        dialect.extraRequest().forEach((k, v) -> root.set(k, JSON.valueToTree(v)));
        // the escape hatch: anything else the caller knows the vendor accepts
        request.getAdditionalParameters().forEach((k, v) -> root.set(k, JSON.valueToTree(v)));
        return JSON.writeValueAsString(root);
    }

    private static ObjectNode message(Dialect dialect, Message m) {
        ObjectNode node = JSON.createObjectNode().put("role", m.getRole().getValue());
        if (m.getRole() == Message.Role.TOOL) {
            node.put("tool_call_id", m.getToolCallId() == null ? "" : m.getToolCallId());
            node.put("content", m.getContent());
            return node;
        }
        if (m.getRole() == Message.Role.ASSISTANT && !m.getToolCalls().isEmpty()) {
            if (m.getContent().isEmpty()) node.putNull("content");
            else node.put("content", m.getContent());
            Object reasoning = m.getProviderData().get(REASONING);
            if (reasoning != null && dialect.reasoningFields().contains("reasoning_content")) {
                node.put("reasoning_content", String.valueOf(reasoning));
            }
            ArrayNode calls = node.putArray("tool_calls");
            for (ToolCall c : m.getToolCalls()) {
                ObjectNode function =
                        calls.addObject()
                                .put("id", c.id() == null ? "" : c.id())
                                .put("type", "function")
                                .putObject("function")
                                .put("name", c.name());
                function.put("arguments", JSON.valueToTree(c.arguments()).toString());
            }
            return node;
        }
        node.put("content", m.getContent());
        return node;
    }
}
