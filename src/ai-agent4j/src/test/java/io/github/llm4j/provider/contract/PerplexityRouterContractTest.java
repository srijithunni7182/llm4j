package io.github.llm4j.provider.contract;

import io.github.llm4j.provider.openai.Dialect;

/**
 * The contract for the Perplexity Orchestrator API (private preview): wire formats from the
 * documentation; no live fixtures exist.
 */
class PerplexityRouterContractTest extends ChatCompletionsContract {

    @Override
    protected Dialect dialect() {
        return Dialect.PERPLEXITY_ROUTER;
    }

    @Override
    protected String model() {
        return "perplexity/kimi-k3";
    }
}
