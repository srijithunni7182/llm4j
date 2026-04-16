package io.github.llm4j.loom.execution;

import java.util.HashMap;
import java.util.Map;

public class HarnessContext {
    private final Map<String, String> variables = new HashMap<>();

    public void setVariable(String name, String value) {
        variables.put(name, value);
    }

    public String getVariable(String name) {
        return variables.getOrDefault(name, "");
    }

    public Map<String, String> getAll() {
        return new HashMap<>(variables);
    }
}
