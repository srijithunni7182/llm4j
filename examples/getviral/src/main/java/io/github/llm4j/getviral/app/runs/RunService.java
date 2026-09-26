package io.github.llm4j.getviral.app.runs;

import io.github.llm4j.getviral.app.AppProperties;
import io.github.llm4j.getviral.app.account.UserAccount;
import io.github.llm4j.getviral.app.memory.MemorySync;
import io.github.llm4j.getviral.app.memory.VoiceSamples;
import io.github.llm4j.getviral.engine.GetViralEngine;
import io.github.llm4j.getviral.engine.StudioHumanInterface;
import io.github.llm4j.loom.runtime.JdbcRunJournal;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
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
    private final DataSource dataSource;
    private final JdbcTemplate jdbc;

    /** Resumes after a server went away before a run is given up on. */
    static final int MAX_RESUMES = 2;

    public RunService(GetViralEngine engine, RunRepository runs, RunStore store, MemorySync memory,
                      VoiceSamples voices, AppProperties props, io.github.llm4j.getviral.app.connect.ConnectService connect,
                      DataSource dataSource) {
        this.connect = connect;
        this.dataSource = dataSource;
        this.jdbc = new JdbcTemplate(dataSource);
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
            workers.execute(() -> execute(run.getId()));
        } catch (RejectedExecutionException e) {
            run.finished(RunStatus.FAILED, null, "GetViral is at capacity right now — please try again in a minute.");
            runs.save(run);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "GetViral is at capacity right now — try again shortly.");
        }
        return run;
    }

    /**
     * Runs a pack — or resumes it: the run's journal replays every step already done, so a resumed
     * run picks up exactly where it stopped, on whichever instance runs this.
     */
    private void execute(String runId) {
        RunRecord record = runs.findById(runId).orElse(null);
        if (record == null || record.getStatus().terminal()) return;
        record.started();
        runs.save(record);
        String userId = record.getUserId();
        Map<String, Object> saved = store.read(record.getBriefJson());
        GetViralEngine.Brief brief = new GetViralEngine.Brief(str(saved.get("idea")), str(saved.get("handle")),
                str(saved.get("niche")), str(saved.get("tone")), str(saved.get("region")), voices.all(userId), userId);
        PersistentStudioRun run = new PersistentStudioRun(runId, brief.toMap(), store, runs);
        try {
            memory.pull(userId);
            run.status(StudioRun.Status.RUNNING);
            // Publish only to this creator's own connected account — never to a server-wide one.
            GetViralEngine.Publishing publishing = connect.instagramCredentials(userId)
                    .map(c -> new GetViralEngine.Publishing(c[0], c[1], c[2]))
                    .orElse(GetViralEngine.Publishing.DRY_RUN);
            GetViralEngine.Outcome outcome = engine.run(run, brief, publishing, new JdbcRunJournal(dataSource, runId));
            if (outcome.status() == StudioRun.Status.WAITING_FOR_HUMAN) {
                // Already WAITING_FOR_HUMAN in the database (set when the question was asked). Don't save
                // this copy over it: a quick answer may already have resumed the run elsewhere.
                record = null;
            } else {
                record.finished(RunStatus.valueOf(outcome.status().name()),
                        outcome.pack().isEmpty() ? null : store.write(outcome.pack()), null);
            }
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
            if (record != null) runs.save(record);
        }
    }

    /**
     * Records the creator's answer in the run's journal and resumes the run. Any instance can do this:
     * the answer and every step before it live in the database.
     */
    public void answer(RunRecord run, String questionId, String answer) {
        RunStore.Question question = store.openQuestion(run.getId(), questionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "That question is no longer open."));
        String recorded = StudioHumanInterface.normalize(question.kind(), answer, question.options());
        if (!store.submitAnswer(run.getId(), questionId, recorded)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That question is no longer open.");
        }
        new JdbcRunJournal(dataSource, run.getId()).answer(question.stepId(), recorded);
        store.append(run.getId(), store.lastSeq(run.getId()) + 1, "human_answer",
                Map.of("kind", question.kind(), "answer", recorded, "by", "creator"), 0);
        // Only one resume per answer, even if two instances race.
        if (jdbc.update("update runs set status = ? where id = ? and status = ?",
                RunStatus.QUEUED.name(), run.getId(), RunStatus.WAITING_FOR_HUMAN.name()) == 1) {
            submit(run.getId());
        }
    }

    private void submit(String runId) {
        try {
            workers.execute(() -> execute(runId));
        } catch (RejectedExecutionException e) {
            log.warn("Workers are full; run {} will be resumed by the sweeper", runId);
        }
    }

    /**
     * A run whose instance went away (deploy, crash, scale-in) stops producing events. Instead of
     * failing it, resume it here from its journal; give up only after {@link #MAX_RESUMES} resumes.
     * Instance-agnostic: runs are claimed with a conditional update, so only one instance resumes each.
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void sweepStaleRuns() {
        Instant cutoff = Instant.now().minus(props.quota().staleAfter());
        for (RunRecord stale : runs.findStale(RunStatus.ACTIVE, cutoff)) {
            Integer resumes = jdbc.queryForObject("select resumes from runs where id = ?", Integer.class, stale.getId());
            int next = store.lastSeq(stale.getId()) + 1;
            if (resumes != null && resumes >= MAX_RESUMES) {
                stale.finished(RunStatus.FAILED, null, "This run stopped responding and was cancelled.");
                runs.save(stale);
                store.append(stale.getId(), next, "error", Map.of("message", "This run stopped responding. Please start it again."), 0);
                store.append(stale.getId(), next + 1, "status", Map.of("status", "FAILED"), 0);
                continue;
            }
            int claimed = jdbc.update("update runs set status = ?, resumes = resumes + 1, last_event_at = ? "
                            + "where id = ? and status in (?, ?) and last_event_at < ?",
                    RunStatus.QUEUED.name(), java.sql.Timestamp.from(Instant.now()), stale.getId(),
                    RunStatus.QUEUED.name(), RunStatus.RUNNING.name(), java.sql.Timestamp.from(cutoff));
            if (claimed == 1) {
                store.append(stale.getId(), next, "note", Map.of("text", "Picking this pack back up where it left off…"), 0);
                submit(stale.getId());
            }
        }
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
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
