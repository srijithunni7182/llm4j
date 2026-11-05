package io.github.llm4j.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.LLMClient;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.model.PromptCache;
import io.github.llm4j.model.ToolCall;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * An agent loop resends its system prompt, tools and history on every turn, so it lets the provider
 * cache them (docs/specs/31-multimodal-input, R5): the system prompt and tools always, and from the
 * second call on, the conversation so far.
 */
class ReActAgentPromptCacheTest {

    /** Calls the tool once, then answers. Remembers every request. */
    private static final class ToolThenAnswer implements LLMClient {
        final List<LLMRequest> seen = new ArrayList<>();
        final AtomicInteger n = new AtomicInteger();

        @Override
        public LLMResponse chat(LLMRequest request) {
            seen.add(request);
            if (n.getAndIncrement() == 0) {
                return LLMResponse.builder()
                        .content("")
                        .model("m")
                        .tokenUsage(10, 5, 15)
                        .toolCalls(List.of(new ToolCall("call-1", "notebook", Map.of())))
                        .finishReason("tool_calls")
                        .build();
            }
            return LLMResponse.builder()
                    .content("done")
                    .model("m")
                    .tokenUsage(10, 5, 15)
                    .finishReason("stop")
                    .build();
        }

        @Override
        public Stream<LLMResponse> chatStream(LLMRequest request) {
            return Stream.of(chat(request));
        }

        @Override
        public boolean supportsToolCalling() {
            return true;
        }
    }

    private static Tool notebook() {
        return new Tool() {
            @Override
            public String getName() {
                return "notebook";
            }

            @Override
            public String getDescription() {
                return "writes a note";
            }

            @Override
            public String execute(Map<String, Object> args) {
                return "noted";
            }
        };
    }

    private static ToolThenAnswer run(ReActAgent.Builder agent) {
        ToolThenAnswer model = new ToolThenAnswer();
        agent.llmClient(model).addTool(notebook()).build().run("write it down");
        return model;
    }

    @Test
    void cachesTheSystemPromptAndToolsOnTheFirstCall() {
        ToolThenAnswer model = run(ReActAgent.builder());

        PromptCache first = model.seen.get(0).getPromptCache();
        assertThat(first.system()).isTrue();
        assertThat(first.tools()).isTrue();
        // one question and no history yet: a message breakpoint would only pay the write premium
        assertThat(first.throughMessages()).isEmpty();
    }

    @Test
    void fromTheSecondCallAlsoCachesTheConversationSoFar() {
        ToolThenAnswer model = run(ReActAgent.builder());

        LLMRequest second = model.seen.get(1);
        PromptCache cache = second.getPromptCache();
        assertThat(cache.system()).isTrue();
        assertThat(cache.tools()).isTrue();
        // the newest message of the history is the end of the part that will not change
        assertThat(cache.throughMessages()).containsExactly(second.getMessages().size() - 1);
    }

    @Test
    void promptCachingOffAsksForNothing() {
        ToolThenAnswer model = run(ReActAgent.builder().promptCaching(false));

        assertThat(model.seen)
                .isNotEmpty()
                .allSatisfy(r -> assertThat(r.getPromptCache()).isNull());
    }
}
