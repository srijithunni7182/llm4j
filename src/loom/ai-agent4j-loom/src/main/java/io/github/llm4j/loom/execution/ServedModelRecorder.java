package io.github.llm4j.loom.execution;

import io.github.llm4j.LLMClient;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** Passes every call through and tells a listener which model the provider says answered it. */
final class ServedModelRecorder implements LLMClient {

    private final LLMClient delegate;
    private final Consumer<String> served;

    ServedModelRecorder(LLMClient delegate, Consumer<String> served) {
        this.delegate = delegate;
        this.served = served;
    }

    private void note(LLMResponse response) {
        if (response != null && response.getModel() != null && !response.getModel().isBlank())
            served.accept(response.getModel());
    }

    @Override
    public LLMResponse chat(LLMRequest request) {
        LLMResponse response = delegate.chat(request);
        note(response);
        return response;
    }

    @Override
    public Stream<LLMResponse> chatStream(LLMRequest request) {
        return delegate.chatStream(request).peek(this::note);
    }

    @Override
    public boolean supportsToolCalling() {
        return delegate.supportsToolCalling();
    }
}
