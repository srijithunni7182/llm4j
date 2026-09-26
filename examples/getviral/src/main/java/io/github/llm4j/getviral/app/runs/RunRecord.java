package io.github.llm4j.getviral.app.runs;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** One pack a creator asked for — its brief, lifecycle and (once finished) the whole pack as JSON. */
@Entity
@Table(name = "runs")
public class RunRecord {

    @Id
    private String id;
    @Column(nullable = false)
    private String userId;
    @Column(nullable = false)
    private String idea;
    @Column(nullable = false)
    private String briefJson;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RunStatus status;
    @Column(nullable = false)
    private Instant createdAt;
    private Instant startedAt;
    private Instant finishedAt;
    private Instant lastEventAt;
    private String packJson;
    private String error;

    protected RunRecord() { }

    public static RunRecord queued(String userId, String idea, String briefJson) {
        RunRecord run = new RunRecord();
        run.id = UUID.randomUUID().toString();
        run.userId = userId;
        run.idea = idea;
        run.briefJson = briefJson;
        run.status = RunStatus.QUEUED;
        run.createdAt = Instant.now();
        run.lastEventAt = run.createdAt;
        return run;
    }

    public String getId() { return id; }
    public String getUserId() { return userId; }
    public String getIdea() { return idea; }
    public String getBriefJson() { return briefJson; }
    public RunStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public String getPackJson() { return packJson; }
    public String getError() { return error; }

    public void started() {
        status = RunStatus.RUNNING;
        startedAt = Instant.now();
    }

    public void finished(RunStatus status, String packJson, String error) {
        this.status = status;
        this.packJson = packJson;
        this.error = error;
        this.finishedAt = Instant.now();
    }
}
