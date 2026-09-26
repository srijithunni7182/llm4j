package io.github.llm4j.getviral.app.runs;

import io.github.llm4j.getviral.app.AppProperties;
import io.github.llm4j.getviral.app.account.UserAccount;
import io.github.llm4j.getviral.app.memory.MemorySync;
import io.github.llm4j.getviral.app.memory.VoiceSamples;
import io.github.llm4j.getviral.engine.GetViralEngine;
import io.github.llm4j.getviral.studio.StudioRun;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Starts runs for creators with fair-use limits: one active run per creator, a monthly pack quota,
 * and a bounded worker pool so a burst of users queues instead of exhausting the instance.
 */
@Service
public class RunService {

    private static final Logger log = LoggerFactory.getLogger(RunService.class);

    private final GetViralEngine engine;
    private final RunRepository runs;
    private final RunStore store;
    private final MemorySync memory;
    private final VoiceSamples voices;
    private final AppProperties props;
    private final ThreadPoolExecutor workers;
    private final io.github.llm4j.getviral.app.connect.ConnectService connect;

    public RunService(GetViralEngine engine, RunRepository runs, RunStore store, MemorySync memory,
                      VoiceSamples voices, AppProperties props, io.github.llm4j.getviral.app.connect.ConnectService connect) {
        this.connect = connect;
        this.engine = engine;
        this.runs = runs;
        this.store = store;
        this.memory = memory;
        this.voices = voices;
        this.props = props;
        int size = Math.max(1, props.quota().workers());
        AtomicInteger n = new AtomicInteger();
        this.workers = new ThreadPoolExecutor(size, size, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(200),
                r -> {
                    Thread t = new Thread(r, "getviral-run-" + n.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                });
    }

    public record Quota(long used, int limit, long active) { }

    public Quota quota(String userId) {
        Instant monthStart = YearMonth.now(ZoneOffset.UTC).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        long used = runs.countSince(userId, monthStart, EnumSet.of(RunStatus.FAILED, RunStatus.BLOCKED));
        long active = runs.countByUserIdAndStatusIn(userId, RunStatus.ACTIVE);
        return new Quota(used, props.quota().packsPerMonth(), active);
    }

    public RunRecord start(UserAccount user, String idea, String niche, String tone, String region) {
        String cleanIdea = idea == null ? "" : idea.strip();
        if (cleanIdea.isEmpty() || cleanIdea.length() > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Give GetViral an idea (up to 500 characters).");
        }
        Quota quota = quota(user.getId());
        if (quota.active() > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "You already have a pack in progress — finish it first.");
        }
        if (quota.used() >= quota.limit()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "You've used all " + quota.limit() + " packs for this month. They reset on the 1st.");
        }
        GetViralEngine.Brief brief = new GetViralEngine.Brief(cleanIdea, orDefault(user.getHandle(), "creator"),
                orDefault(niche, user.getNiche()), orDefault(tone, user.getTone()), orDefault(region, user.getRegion()),
                List.of(), user.getId());
        RunRecord run = runs.save(RunRecord.queued(user.getId(), cleanIdea, store.write(brief.toMap())));
        store.append(run.getId(), 1, "status", Map.of("status", "QUEUED", "position", workers.getQueue().size()), 0);
        try {
            workers.execute(() -> execute(run.getId(), brief));
        } catch (RejectedExecutionException e) {
            run.finished(RunStatus.FAILED, null, "GetViral is at capacity right now — please try again in a minute.");
            runs.save(run);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "GetViral is at capacity right now — try again shortly.");
        }
        return run;
    }

    private void execute(String runId, GetViralEngine.Brief queuedBrief) {
        RunRecord record = runs.findById(runId).orElse(null);
        if (record == null) return;
        record.started();
        runs.save(record);
        String userId = record.getUserId();
        GetViralEngine.Brief brief = new GetViralEngine.Brief(queuedBrief.idea(), queuedBrief.handle(), queuedBrief.niche(),
                queuedBrief.tone(), queuedBrief.region(), voices.all(userId), userId);
        PersistentStudioRun run = new PersistentStudioRun(runId, brief.toMap(), props.quota().humanTimeout(), store, runs, 1);
        try {
            memory.pull(userId);
            run.status(StudioRun.Status.RUNNING);
            // Publish only to this creator's own connected account — never to a server-wide one.
            GetViralEngine.Publishing publishing = connect.instagramCredentials(userId)
                    .map(c -> new GetViralEngine.Publishing(c[0], c[1], c[2]))
                    .orElse(GetViralEngine.Publishing.DRY_RUN);
            GetViralEngine.Outcome outcome = engine.run(run, brief, publishing);
            RunStatus status = RunStatus.valueOf(outcome.status().name());
            record.finished(status, outcome.pack().isEmpty() ? null : store.write(outcome.pack()), null);
        } catch (RuntimeException e) {
            log.warn("Run {} failed", runId, e);
            record.finished(RunStatus.FAILED, null, String.valueOf(e.getMessage()));
            run.emit("error", Map.of("message", "Something went wrong while making this pack."));
            run.status(StudioRun.Status.FAILED);
        } finally {
            try {
                memory.push(userId);
            } catch (RuntimeException e) {
                log.warn("Could not save memory for {}", userId, e);
            }
            runs.save(record);
        }
    }

    /**
     * Runs whose instance died (deploy, crash, scale-in) stop producing events; mark them failed so the
     * creator can start again. Instance-agnostic: it only looks at the shared database.
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void sweepStaleRuns() {
        Instant cutoff = Instant.now().minus(props.quota().humanTimeout()).minusSeconds(300);
        for (RunRecord stale : runs.findStale(RunStatus.ACTIVE, cutoff)) {
            stale.finished(RunStatus.FAILED, null, "This run stopped responding and was cancelled.");
            runs.save(stale);
            int next = store.eventsAfter(stale.getId(), 0, 100_000).size() + 1;
            store.append(stale.getId(), next, "error", Map.of("message", "This run stopped responding. Please start it again."), 0);
            store.append(stale.getId(), next + 1, "status", Map.of("status", "FAILED"), 0);
        }
    }

    public Map<String, Object> pack(RunRecord run) {
        return run.getPackJson() == null ? Map.of() : store.read(run.getPackJson());
    }

    @PreDestroy
    void shutdown() {
        workers.shutdown();
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }
}
