package io.github.llm4j.loom.ast;

/**
 * AST node for a top-level scheduled task.
 */
public class ScheduleDef implements Node {
    private final String name;
    private String agentName;
    private String task;
    private String pattern; // Cron or simple duration
    private String initialDelay = "0s";

    public ScheduleDef(String name) {
        this.name = name;
    }

    public String getName() { return name; }
    public String getAgentName() { return agentName; }
    public void setAgentName(String agentName) { this.agentName = agentName; }
    public String getTask() { return task; }
    public void setTask(String task) { this.task = task; }
    public String getPattern() { return pattern; }
    public void setPattern(String pattern) { this.pattern = pattern; }
    public String getInitialDelay() { return initialDelay; }
    public void setInitialDelay(String initialDelay) { this.initialDelay = initialDelay; }
}
