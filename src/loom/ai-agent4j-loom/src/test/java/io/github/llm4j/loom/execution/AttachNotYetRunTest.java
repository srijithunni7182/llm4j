package io.github.llm4j.loom.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.LLMClient;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * A step that attaches files must never run without them (docs/specs/31-multimodal-input R18, "fail
 * closed"): until the runtime sends attachments, it refuses before any model call.
 */
class AttachNotYetRunTest {

    @Test
    void aStepWithAttachmentsRefusesToRunAndCallsNoModel() {
        AtomicInteger calls = new AtomicInteger();
        LLMClientFactory factory =
                model ->
                        new LLMClient() {
                            @Override
                            public LLMResponse chat(LLMRequest request) {
                                calls.incrementAndGet();
                                return LLMResponse.builder().content("ok").model(model).build();
                            }

                            @Override
                            public Stream<LLMResponse> chatStream(LLMRequest request) {
                                return Stream.empty();
                            }
                        };
        LoomScript script =
                new LoomParser(
                                new Lexer(
                                                """
                                                agent Reader { model: "test" system: "x" }
                                                workflow Main(x) {
                                                    delegate "total?" to Reader attach image "a.png" -> out
                                                }
                                                """)
                                        .tokenize())
                        .parseScript();
        HarnessExecutor executor = new HarnessExecutor(script, new ToolRegistry(), factory);
        executor.initialize();

        assertThatThrownBy(() -> executor.executeWorkflow("Main", Map.of("x", "1")))
                .hasStackTraceContaining("does not send attachments yet");
        assertThat(calls).hasValue(0);
    }
}
