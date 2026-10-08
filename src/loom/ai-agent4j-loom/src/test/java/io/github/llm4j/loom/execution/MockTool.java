package io.github.llm4j.loom.execution;

import io.github.llm4j.agent.Tool;

public class MockTool implements Tool {
    
    public MockTool() {
    }

    @Override
    public String getName() {
        return "MockTool";
    }

    @Override
    public String getDescription() {
        return "A mock tool for testing .loot files";
    }

    @Override
    public String execute(java.util.Map<String, Object> input) {
        return "MockOutput:" + input.get("test");
    }
}
