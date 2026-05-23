package io.github.llm4j.tantrik.console.model;

import java.util.HashMap;
import java.util.Map;

public class RunRequest {
    private String script;
    private String workflowName = "Main";
    private Map<String, String> initialContext = new HashMap<>();
    private boolean mockMode = true;

    public String getScript() {
        return script;
    }

    public void setScript(String script) {
        this.script = script;
    }

    public String getWorkflowName() {
        return workflowName;
    }

    public void setWorkflowName(String workflowName) {
        this.workflowName = workflowName;
    }

    public Map<String, String> getInitialContext() {
        return initialContext;
    }

    public void setInitialContext(Map<String, String> initialContext) {
        this.initialContext = initialContext;
    }

    public boolean isMockMode() {
        return mockMode;
    }

    public void setMockMode(boolean mockMode) {
        this.mockMode = mockMode;
    }
}
