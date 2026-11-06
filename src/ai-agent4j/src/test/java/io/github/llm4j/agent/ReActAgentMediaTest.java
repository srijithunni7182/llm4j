package io.github.llm4j.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.DefaultLLMClient;
import io.github.llm4j.LLMClient;
import io.github.llm4j.exception.InvalidRequestException;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.model.Media;
import io.github.llm4j.model.MediaCapabilities;
import io.github.llm4j.model.MediaKind;
import io.github.llm4j.model.MediaPolicy;
import io.github.llm4j.model.Message;
import io.github.llm4j.model.TinyMedia;
import io.github.llm4j.model.ToolCall;
import io.github.llm4j.provider.LLMProvider;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * docs/specs/31-multimodal-input R19: {@code run(question, media)} sends the media with the first
 * user message of the run and keeps it there for every later call of the loop.
 */
class ReActAgentMediaTest {

    private static final MediaPolicy POLICY = MediaPolicy.defaults();

    private static Media photo() {
        return Media.of(MediaKind.IMAGE, TinyMedia.png(), "a.png", POLICY);
    }

    /** Calls the tool once (when it can), then answers; takes pictures; records every request. */
    private static final class ToolThenAnswer implements LLMClient {
        final List<LLMRequest> seen = new ArrayList<>();
        final AtomicInteger n = new AtomicInteger();
        final boolean tools;

        ToolThenAnswer(boolean tools) {
            this.tools = tools;
        }

        @Override
        public LLMResponse chat(LLMRequest request) {
            seen.add(request);
            int call = n.getAndIncrement();
            if (tools && call == 0) {
                return LLMResponse.builder()
                        .content("")
                        .model("m")
                        .tokenUsage(10, 5, 15)
                        .toolCalls(List.of(new ToolCall("call-1", "notebook", Map.of())))
                        .finishReason("tool_calls")
                        .build();
            }
            String content =
                    tools
                            ? "done"
                            : "```json\n{\"thought\":\"done\",\"final_answer\":\"red\"}\n```";
            return LLMResponse.builder()
                    .content(content)
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
            return tools;
        }

        @Override
        public MediaCapabilities mediaCapabilities(String model) {
            return MediaCapabilities.of(MediaKind.IMAGE);
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

    private static Message userMessage(LLMRequest request) {
        return request.getMessages().stream()
                .filter(m -> m.getRole() == Message.Role.USER)
                .findFirst()
                .orElseThrow();
    }

    @Test
    void nativeLoopSendsTheMediaWithTheFirstUserMessageAndKeepsItOnLaterCalls() {
        ToolThenAnswer model = new ToolThenAnswer(true);

        AgentResult result =
                ReActAgent.builder()
                        .llmClient(model)
                        .addTool(notebook())
                        .build()
                        .run("what colour is it?", List.of(photo()));

        assertThat(result.getFinalAnswer()).isEqualTo("done");
        assertThat(model.seen).hasSize(2);
        for (LLMRequest request : model.seen) {
            Message first = userMessage(request);
            assertThat(first.getContent()).contains("what colour is it?");
            assertThat(first.getMedia()).containsExactly(photo());
        }
    }

    @Test
    void textLoopSendsTheMediaWithItsSingleUserMessage() {
        ToolThenAnswer model = new ToolThenAnswer(false);

        AgentResult result =
                ReActAgent.builder()
                        .llmClient(model)
                        .addTool(notebook())
                        .build()
                        .run("what colour is it?", List.of(photo()));

        assertThat(result.getFinalAnswer()).isEqualTo("red");
        assertThat(model.seen).isNotEmpty();
        for (LLMRequest request : model.seen) {
            assertThat(userMessage(request).getMedia()).containsExactly(photo());
        }
    }

    @Test
    void theTextLoopsWrapUpCallStillSeesTheMedia() {
        ToolThenAnswer model = new ToolThenAnswer(false);
        RunHandle handle = new RunHandle();
        handle.wrapUp(); // asked to finish before the first step

        ReActAgent.builder()
                .llmClient(model)
                .addTool(notebook())
                .build()
                .run("what colour is it?", List.of(photo()), handle);

        assertThat(model.seen).hasSize(1);
        assertThat(userMessage(model.seen.get(0)).getMedia()).containsExactly(photo());
        assertThat(userMessage(model.seen.get(0)).getContent()).contains("Stop using tools now");
    }

    @Test
    void theNativeLoopsWrapUpCallStillSeesTheMedia() {
        ToolThenAnswer model = new ToolThenAnswer(true);
        RunHandle handle = new RunHandle();
        handle.wrapUp();

        ReActAgent.builder()
                .llmClient(model)
                .addTool(notebook())
                .build()
                .run("what colour is it?", List.of(photo()), handle);

        assertThat(model.seen).hasSize(1);
        assertThat(userMessage(model.seen.get(0)).getMedia()).containsExactly(photo());
    }

    @Test
    void aRunWithoutMediaIsExactlyWhatItWas() {
        ToolThenAnswer model = new ToolThenAnswer(true);

        ReActAgent.builder().llmClient(model).addTool(notebook()).build().run("hi");

        assertThat(model.seen).allSatisfy(r -> assertThat(r.mediaKinds()).isEmpty());
    }

    @Test
    void anEmptyMediaListIsTheSameAsNone() {
        ToolThenAnswer model = new ToolThenAnswer(true);

        ReActAgent.builder()
                .llmClient(model)
                .addTool(notebook())
                .build()
                .run("hi", Collections.emptyList());

        assertThat(model.seen).allSatisfy(r -> assertThat(r.mediaKinds()).isEmpty());
    }

    @Test
    void theRunHandleVariantTakesMediaToo() {
        ToolThenAnswer model = new ToolThenAnswer(true);

        ReActAgent.builder()
                .llmClient(model)
                .addTool(notebook())
                .build()
                .run("look", List.of(photo()), null);

        assertThat(userMessage(model.seen.get(0)).getMedia()).hasSize(1);
    }

    @Test
    void refusesNullMedia() {
        ReActAgent agent = ReActAgent.builder().llmClient(new ToolThenAnswer(true)).build();

        assertThatThrownBy(() -> agent.run("x", (List<Media>) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("media");
    }

    @Test
    void aModelThatCannotTakeTheMediaIsNeverCalled() {
        List<LLMRequest> sent = new ArrayList<>();
        LLMProvider blind =
                new LLMProvider() {
                    @Override
                    public LLMResponse chat(LLMRequest request) {
                        sent.add(request);
                        return LLMResponse.builder().content("x").model("m").build();
                    }

                    @Override
                    public String getProviderName() {
                        return "blind";
                    }

                    @Override
                    public void validate() {}

                    @Override
                    public boolean supportsToolCalling() {
                        return true;
                    }
                };
        ReActAgent agent =
                ReActAgent.builder()
                        .llmClient(new DefaultLLMClient(blind))
                        .addTool(notebook())
                        .build();

        assertThatThrownBy(() -> agent.run("look", List.of(photo())))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("does not accept image");
        assertThat(sent).isEmpty();
    }

    @Test
    void moreItemsThanThePolicyAllowsNeedsAMorePermissivePolicy() {
        MediaPolicy loose = MediaPolicy.builder().maxItemsPerMessage(5).build();
        List<Media> five = Collections.nCopies(5, photo());

        ToolThenAnswer strictModel = new ToolThenAnswer(true);
        assertThatThrownBy(
                        () ->
                                ReActAgent.builder()
                                        .llmClient(strictModel)
                                        .addTool(notebook())
                                        .build()
                                        .run("look", five))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("5 media items");

        ToolThenAnswer looseModel = new ToolThenAnswer(true);
        ReActAgent.builder()
                .llmClient(looseModel)
                .addTool(notebook())
                .mediaPolicy(loose)
                .build()
                .run("look", five);
        assertThat(userMessage(looseModel.seen.get(0)).getMedia()).hasSize(5);
    }
}
