package io.github.llm4j.tantrik.console.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class RunSummary {
    private String runId;
    private RunStatus status;
    private Instant startedAt;
    private Instant completedAt;
    private String workflowName;
    private String error;
    private List<RunEvent> events = new ArrayList<>();

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public RunStatus getStatus() {
        return status;
    }

    public void setStatus(RunStatus status) {
        this.status = status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public String getWorkflowName() {
        return workflowName;
    }

    public void setWorkflowName(String workflowName) {
        this.workflowName = workflowName;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public List<RunEvent> getEvents() {
        return events;
    }

    public void setEvents(List<RunEvent> events) {
        this.events = events;
    }
}
