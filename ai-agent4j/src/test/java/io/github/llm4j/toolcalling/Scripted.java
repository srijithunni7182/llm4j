package io.github.llm4j.toolcalling;

import io.github.llm4j.LLMClient;
import io.github.llm4j.agent.Tool;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.model.ToolCall;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** A model that plays back a script and records what it was sent. */
final class Scripted implements LLMClient {

    final List<LLMRequest> requests = new ArrayList<>();
    private final Deque<LLMResponse> script = new ArrayDeque<>();
    private final boolean nativeCalling;

    Scripted(boolean nativeCalling, LLMResponse... responses) {
        this.nativeCalling = nativeCalling;
        for (LLMResponse r : responses) script.add(r);
    }

    static Scripted nativeModel(LLMResponse... responses) {
        return new Scripted(true, responses);
    }

    static Scripted textModel(String... replies) {
        LLMResponse[] r = new LLMResponse[replies.length];
        for (int i = 0; i < replies.length; i++) r[i] = text(replies[i]);
        return new Scripted(false, r);
    }

    static LLMResponse text(String content) {
        return LLMResponse.builder().content(content).model("m").tokenUsage(10, 5, 15).finishReason("stop").build();
    }

    static LLMResponse calls(String note, ToolCall... calls) {
        return LLMResponse.builder().content(note).model("m").tokenUsage(10, 5, 15).finishReason("tool_calls").toolCalls(List.of(calls)).build();
    }

    static ToolCall call(String id, String name, Map<String, Object> args) {
        return new ToolCall(id, name, args);
    }

    @Override
    public LLMResponse chat(LLMRequest request) {
        requests.add(request);
        if (script.isEmpty()) throw new IllegalStateException("the script ran out after " + requests.size() + " requests");
        return script.poll();
    }

    @Override
    public Stream<LLMResponse> chatStream(LLMRequest request) {
        return Stream.of(chat(request));
    }

    @Override
    public boolean supportsToolCalling() {
        return nativeCalling;
    }

    LLMRequest last() {
        return requests.get(requests.size() - 1);
    }

    /** A tool that records its calls. */
    static class Recorder implements Tool {
        final String name;
        final String reply;
        final List<Map<String, Object>> seen = new ArrayList<>();
        Map<String, Object> schema = io.github.llm4j.model.ToolSchema.permissive();
        boolean needsApproval;

        Recorder(String name, String reply) {
            this.name = name;
            this.reply = reply;
        }

        @Override public String getName() { return name; }
        @Override public String getDescription() { return "test tool " + name; }
        @Override public Map<String, Object> getParametersSchema() { return schema; }
        @Override public boolean requiresApproval(Map<String, Object> args) { return needsApproval; }

        @Override
        public String execute(Map<String, Object> args) {
            seen.add(args);
            return reply;
        }
    }
}
