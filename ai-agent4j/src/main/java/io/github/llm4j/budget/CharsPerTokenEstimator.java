package io.github.llm4j.budget;

import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.Message;

/**
 * The default estimate: about 4 characters per token, plus a 10% safety margin so budgets err on the
 * side of refusing early rather than overspending.
 */
public final class CharsPerTokenEstimator implements TokenEstimator {

    public static final CharsPerTokenEstimator INSTANCE = new CharsPerTokenEstimator();

    @Override
    public long prompt(LLMRequest request) {
        long chars = 0;
        for (Message m : request.getMessages()) {
            chars += m.getContent() == null ? 0 : m.getContent().length();
            for (io.github.llm4j.model.ToolCall c : m.getToolCalls()) chars += c.name().length() + String.valueOf(c.arguments()).length();
        }
        // tool definitions are part of the prompt the model reads
        for (io.github.llm4j.model.ToolSpec t : request.getTools()) {
            chars += t.name().length() + t.description().length() + String.valueOf(t.parameters()).length();
        }
        return tokens(chars);
    }

    @Override
    public long completion(String content) {
        return tokens(content == null ? 0 : content.length());
    }

    /** ceil(chars / 4 × 1.1), in integers so no floating-point error adds a token. */
    static long tokens(long chars) {
        return (chars * 11 + 39) / 40;
    }
}
