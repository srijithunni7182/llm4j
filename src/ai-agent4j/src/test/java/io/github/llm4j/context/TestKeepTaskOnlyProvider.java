package io.github.llm4j.context;

import io.github.llm4j.model.Message;
import java.util.List;
import java.util.Map;

/** A strategy "from another jar": listed in META-INF/services under src/test/resources. */
public final class TestKeepTaskOnlyProvider implements ContextCompactorProvider {
    @Override
    public String name() {
        return "test_keep_task_only";
    }

    @Override
    public ContextCompactor create(Map<String, String> options, ProviderContext context) {
        return new ContextCompactor() {
            @Override
            public CompactionResult compact(List<Message> messages, CompactionRequest request) {
                return CompactionResult.of(
                        name(),
                        messages,
                        messages.subList(0, Math.min(2, messages.size())),
                        request);
            }

            @Override
            public String name() {
                return "test_keep_task_only";
            }
        };
    }
}
