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
        return "Returns exactly what you input. Input should be a JSON object with a 'text' field.";
    }

    @Override
    public String execute(java.util.Map<String, Object> args) {
        String input = (String) args.get("text");
        if (input == null) {
            input = (String) args.get("input");
        }
        return input != null ? input : "";
    }
}
