package io.github.llm4j.context;

import io.github.llm4j.LLMClient;
import io.github.llm4j.agent.Tool;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.model.ToolCall;
import io.github.llm4j.model.ToolSchema;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.stream.Stream;

/** A model that follows a script (or a function of the call number), records every request, and can report usage or fail on cue. */
final class ScriptedModel implements LLMClient {

    final List<LLMRequest> requests = Collections.synchronizedList(new ArrayList<>());
    private final boolean nativeCalling;
    private final IntFunction<Object> script; // an LLMResponse, or a RuntimeException to throw
    private final boolean reportUsage;

    private ScriptedModel(boolean nativeCalling, boolean reportUsage, IntFunction<Object> script) {
        this.nativeCalling = nativeCalling;
        this.reportUsage = reportUsage;
        this.script = script;
    }

    /** Plays the items in order: an {@link LLMResponse} is returned, a {@link RuntimeException} is thrown. */
    static ScriptedModel of(boolean nativeCalling, boolean reportUsage, Object... items) {
        return new ScriptedModel(nativeCalling, reportUsage, n -> {
            if (n >= items.length) throw new IllegalStateException("the script ran out after " + n + " requests");
            return items[n];
        });
    }

    static ScriptedModel by(boolean nativeCalling, boolean reportUsage, IntFunction<Object> script) {
        return new ScriptedModel(nativeCalling, reportUsage, script);
    }

    @Override
    public LLMResponse chat(LLMRequest request) {
        int n;
        synchronized (requests) {
            n = requests.size();
            requests.add(request);
        }
        Object item = script.apply(n);
        if (item instanceof RuntimeException e) throw e;
        LLMResponse r = (LLMResponse) item;
        if (!reportUsage) return r;
        long prompt = io.github.llm4j.budget.CharsPerTokenEstimator.INSTANCE.prompt(request);
        return LLMResponse.builder().content(r.getContent()).model(r.getModel()).finishReason(r.getFinishReason())
                .toolCalls(r.getToolCalls()).providerData(r.getProviderData())
                .tokenUsage((int) prompt, 20, (int) prompt + 20).build();
    }

    @Override
    public Stream<LLMResponse> chatStream(LLMRequest request) {
        return Stream.of(chat(request));
    }

    @Override
    public boolean supportsToolCalling() {
        return nativeCalling;
    }

    // ── responses ──────────────────────────────────────────────────────────────────

    static LLMResponse answer(String text) {
        return LLMResponse.builder().content(text).model("m").tokenUsage(10, 5, 15).finishReason("stop").build();
    }

    static LLMResponse calls(String note, ToolCall... calls) {
        return LLMResponse.builder().content(note).model("m").tokenUsage(10, 5, 15).finishReason("tool_calls").toolCalls(List.of(calls)).build();
    }

    static ToolCall call(String id, String tool, Map<String, Object> args) {
        return new ToolCall(id, tool, args);
    }

    /** What the text protocol expects of a tool step and of a final answer. */
    static LLMResponse textStep(String thought, String tool, String inputJson) {
        return answer("Thought: " + thought + "\nAction: " + tool + "\nAction Input: " + inputJson + "\n");
    }

    static LLMResponse textAnswer(String text) {
        return answer("Thought: I have it.\nFinal Answer: " + text);
    }

    // ── a tool ─────────────────────────────────────────────────────────────────────

    /** A tool that returns a fixed reply, or one of a given size, and records how often it ran. */
    static final class FixedTool implements Tool {
        private final String name;
        private final java.util.function.IntFunction<String> reply;
        final List<Map<String, Object>> seen = Collections.synchronizedList(new ArrayList<>());

        FixedTool(String name, String reply) {
            this(name, n -> reply);
        }

        FixedTool(String name, java.util.function.IntFunction<String> reply) {
            this.name = name;
            this.reply = reply;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return "test tool " + name;
        }

        @Override
        public Map<String, Object> getParametersSchema() {
            return ToolSchema.permissive();
        }

        @Override
        public String execute(Map<String, Object> args) {
            seen.add(args);
            return reply.apply(seen.size());
        }
    }
}
