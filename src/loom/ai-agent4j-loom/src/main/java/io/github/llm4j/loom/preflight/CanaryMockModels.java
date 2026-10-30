package io.github.llm4j.loom.preflight;

import io.github.llm4j.LLMClient;
import io.github.llm4j.loom.execution.LLMClientFactory;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Models for {@code weave preflight --mock}: they cost nothing and follow the one instruction a
 * preflight gives (begin the answer with a canary), so the checks run end to end without a key.
 */
public final class CanaryMockModels implements LLMClientFactory {
    private static final Pattern CANARY = Pattern.compile("CANARY-[0-9a-f]+");

    @Override
    public LLMClient createClient(String model) {
        return new LLMClient() {
            @Override
            public LLMResponse chat(LLMRequest request) {
                StringBuilder all = new StringBuilder();
                request.getMessages().forEach(m -> all.append(m.getContent()).append('\n'));
                Matcher m = CANARY.matcher(all);
                String answer = (m.find() ? m.group() + " " : "") + "[mock answer]";
                return LLMResponse.builder()
                        .content("```json\n{\"thought\": \"mock\", \"final_answer\": \"" + answer + "\"}\n```")
                        .model(model)
                        .tokenUsage(1, 1, 2)
                        .build();
            }

            @Override
            public Stream<LLMResponse> chatStream(LLMRequest request) {
                return Stream.of(chat(request));
            }
        };
    }
}
