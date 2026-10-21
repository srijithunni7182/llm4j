package io.github.llm4j.context;

/**
 * A developer's own compactor, written as a lambda, is held to the same rules as the built-ins (R1,
 * A2).
 */
class LambdaCompactorContractTest extends ContextCompactorContract {
    @Override
    protected ContextCompactor subject() {
        ContextCompactor inner = ContextCompactors.dropOldest();
        return (messages, request) -> {
            CompactionResult r = inner.compact(messages, request);
            return CompactionResult.of("my-trim", messages, r.messages(), request);
        };
    }
}
