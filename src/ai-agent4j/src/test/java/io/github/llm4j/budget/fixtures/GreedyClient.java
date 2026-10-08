package io.github.llm4j.budget.fixtures;

/** Ignores maxTokens: every answer reports 500 completion tokens. */
public class GreedyClient extends ScriptedLLMClient {
    public GreedyClient() {
        super(100, 500);
    }
}
