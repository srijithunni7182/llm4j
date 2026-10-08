package io.github.llm4j.budget.fixtures;

import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Holds every call until {@link #release()}, so tests can keep many calls in flight at once. */
public class LatchedClient extends ScriptedLLMClient {

    private final CountDownLatch gate = new CountDownLatch(1);

    public LatchedClient(int prompt, int completion) {
        super(prompt, completion);
    }

    public void release() {
        gate.countDown();
    }

    @Override
    public LLMResponse chat(LLMRequest request) {
        try {
            if (!gate.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("latch never released");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        return super.chat(request);
    }
}
