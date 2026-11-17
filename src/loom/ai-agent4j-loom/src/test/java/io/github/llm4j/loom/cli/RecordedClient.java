package io.github.llm4j.loom.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.llm4j.LLMClient;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.model.ToolCall;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * A model client for the Guide's scenario tests: in record mode it passes each request to a real
 * client and keeps the answer; in replay mode it hands back the kept answers in order and calls no
 * model. Only the answer's text, tool calls and token counts are kept, never a request (which holds
 * the project summary) or a key.
 */
final class RecordedClient implements LLMClient {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final LLMClient real;
    private final List<JsonNode> answers;
    private int next;
    long promptTokens;
    long completionTokens;

    private RecordedClient(LLMClient real, List<JsonNode> answers) {
        this.real = real;
        this.answers = answers;
    }

    static RecordedClient recording(LLMClient real) {
        return new RecordedClient(real, new ArrayList<>());
    }

    static RecordedClient replaying(JsonNode recorded) {
        List<JsonNode> list = new ArrayList<>();
        recorded.forEach(list::add);
        return new RecordedClient(null, list);
    }

    @Override
    public LLMResponse chat(LLMRequest request) {
        if (real != null) {
            LLMResponse r = real.chat(request);
            answers.add(keep(r));
            if (r.getTokenUsage() != null) {
                promptTokens += r.getTokenUsage().getPromptTokens();
                completionTokens += r.getTokenUsage().getCompletionTokens();
            }
            return r;
        }
        if (next >= answers.size())
            throw new IllegalStateException("The recording has no answer number " + (next + 1) + ": the Guide behaved differently; re-record with -Dguide.record=true");
        return restore(answers.get(next++));
    }

    @Override
    public Stream<LLMResponse> chatStream(LLMRequest request) {
        return Stream.of(chat(request));
    }

    @Override
    public boolean supportsToolCalling() {
        return true;
    }

    /** What was recorded, for the file. */
    ArrayNode recorded() {
        ArrayNode a = JSON.createArrayNode();
        answers.forEach(a::add);
        return a;
    }

    boolean allReplayed() {
        return real != null || next == answers.size();
    }

    private static JsonNode keep(LLMResponse r) {
        ObjectNode n = JSON.createObjectNode();
        n.put("content", r.getContent() == null ? "" : r.getContent());
        if (r.getFinishReason() != null) n.put("finish", r.getFinishReason().getValue());
        ArrayNode calls = n.putArray("toolCalls");
        if (r.getToolCalls() != null)
            for (ToolCall c : r.getToolCalls()) {
                ObjectNode t = calls.addObject();
                t.put("id", c.id());
                t.put("name", c.name());
                t.set("arguments", JSON.valueToTree(c.arguments() == null ? Map.of() : c.arguments()));
            }
        return n;
    }

    private static LLMResponse restore(JsonNode n) {
        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode t : n.path("toolCalls")) {
            Map<String, Object> args = JSON.convertValue(t.path("arguments"), LinkedHashMap.class);
            calls.add(new ToolCall(t.path("id").asText(), t.path("name").asText(), args));
        }
        LLMResponse.Builder b = LLMResponse.builder().content(n.path("content").asText("")).tokenUsage(0, 0, 0);
        if (!calls.isEmpty()) b.toolCalls(calls);
        if (n.has("finish")) b.finishReason(LLMResponse.FinishReason.fromValue(n.path("finish").asText()));
        return b.build();
    }
}
