package io.github.llm4j.agent.tools;

import io.github.llm4j.agent.Tool;

/**
 * A simple echo tool that returns the input. Useful for testing.
 */
public class EchoTool implements Tool {

    @Override
    public String getName() {
        return "Echo";
    }

    @Override
    public String getDescription() {
        return "Returns exactly what you input. Useful for testing.";
    }

    @Override
    public String execute(String input) {
        return input != null ? input : "";
    }
}
