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
    private String cron;
    private java.time.Duration every;
    private String timezone;
    private String runWorkflow;
    private java.util.Map<String, String> runArgs = new java.util.LinkedHashMap<>();
    private String misfire;
    private String overlap;
    private int line;

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

    /** A 5-field cron expression, or null. */
    public String getCron() { return cron; }
    public void setCron(String cron) { this.cron = cron; }
    /** A fixed interval ({@code every: 6h}), or null. */
    public java.time.Duration getEvery() { return every; }
    public void setEvery(java.time.Duration every) { this.every = every; }
    /** The zone cron fields are read in (default UTC). */
    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }
    /** The workflow a {@code run:} starts, or null for an agent task. */
    public String getRunWorkflow() { return runWorkflow; }
    public void setRunWorkflow(String runWorkflow) { this.runWorkflow = runWorkflow; }
    public java.util.Map<String, String> getRunArgs() { return runArgs; }
    public void setRunArgs(java.util.Map<String, String> runArgs) { this.runArgs = runArgs; }
    /** {@code run_once} or {@code skip}; null for the default. */
    public String getMisfire() { return misfire; }
    public void setMisfire(String misfire) { this.misfire = misfire; }
    /** {@code skip} or {@code queue}; null for the default. */
    public String getOverlap() { return overlap; }
    public void setOverlap(String overlap) { this.overlap = overlap; }
    /** The line the schedule starts on, for error messages. */
    public int getLine() { return line; }
    public void setLine(int line) { this.line = line; }
}
