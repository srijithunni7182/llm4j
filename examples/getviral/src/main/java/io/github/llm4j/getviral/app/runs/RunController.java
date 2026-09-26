package io.github.llm4j.getviral.app.runs;

import io.github.llm4j.getviral.app.ApiErrors;
import io.github.llm4j.getviral.app.account.CurrentUser;
import io.github.llm4j.getviral.app.account.UserAccount;
import jakarta.annotation.PreDestroy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Start runs, stream them live (from the shared event log), answer questions, list and export. */
@RestController
@RequestMapping("/api/runs")
public class RunController {

    private final RunService service;
    private final RunRepository runs;
    private final RunStore store;
    private final CurrentUser currentUser;
    private final ScheduledExecutorService tails = Executors.newScheduledThreadPool(4, r -> {
        Thread t = new Thread(r, "getviral-sse");
        t.setDaemon(true);
        return t;
    });

    public RunController(RunService service, RunRepository runs, RunStore store, CurrentUser currentUser) {
        this.service = service;
        this.runs = runs;
        this.store = store;
        this.currentUser = currentUser;
    }

    public record StartRun(@NotBlank @Size(max = 500) String idea, @Size(max = 80) String niche,
                           @Size(max = 80) String tone, @Size(max = 2) String region) { }

    @PostMapping
    ResponseEntity<Map<String, Object>> start(@Valid @RequestBody StartRun body) {
        UserAccount user = currentUser.require();
        RunRecord run = service.start(user, body.idea(), body.niche(), body.tone(), body.region());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", run.getId()));
    }

    @GetMapping
    List<Map<String, Object>> list(@RequestParam(defaultValue = "50") int limit) {
        UserAccount user = currentUser.require();
        return runs.findByUserIdOrderByCreatedAtDesc(user.getId(), PageRequest.of(0, Math.min(Math.max(limit, 1), 200)))
                .stream().map(this::summary).toList();
    }

    @GetMapping("/{id}")
    Map<String, Object> get(@PathVariable String id) {
        RunRecord run = owned(id);
        Map<String, Object> detail = summary(run);
        detail.put("brief", store.read(run.getBriefJson()));
        detail.put("pack", run.getPackJson() == null ? null : store.read(run.getPackJson()));
        detail.put("openQuestions", store.openQuestions(run.getId()));
        detail.put("error", run.getError());
        return detail;
    }

    public record Answer(@NotBlank String id, @Size(max = 2000) String answer) { }

    @PostMapping("/{id}/answer")
    Map<String, Object> answer(@PathVariable String id, @Valid @RequestBody Answer body) {
        RunRecord run = owned(id);
        if (!store.submitAnswer(run.getId(), body.id(), body.answer() == null ? "" : body.answer())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That question is no longer open.");
        }
        return Map.of("ok", true);
    }

    /** Server-Sent Events: replays the run's log, then tails it until the run ends. Resumable via Last-Event-ID. */
    @GetMapping(path = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter events(@PathVariable String id, @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        RunRecord run = owned(id);
        SseEmitter emitter = new SseEmitter(TimeUnit.MINUTES.toMillis(60));
        AtomicInteger cursor = new AtomicInteger(parse(lastEventId));
        AtomicLong lastSend = new AtomicLong(System.currentTimeMillis());
        AtomicReference<ScheduledFuture<?>> task = new AtomicReference<>();
        Runnable stop = () -> {
            ScheduledFuture<?> f = task.get();
            if (f != null) f.cancel(false);
        };
        emitter.onCompletion(stop);
        emitter.onTimeout(stop);
        emitter.onError(e -> stop.run());
        task.set(tails.scheduleWithFixedDelay(() -> {
            try {
                boolean finished = false;
                for (Map<String, Object> event : store.eventsAfter(run.getId(), cursor.get(), 200)) {
                    emitter.send(SseEmitter.event().id(String.valueOf(event.get("seq"))).data(event, MediaType.APPLICATION_JSON));
                    cursor.set((Integer) event.get("seq"));
                    lastSend.set(System.currentTimeMillis());
                    if ("status".equals(event.get("type"))) {
                        Object status = ((Map<?, ?>) event.get("data")).get("status");
                        finished = "DONE".equals(status) || "BLOCKED".equals(status) || "FAILED".equals(status);
                    }
                }
                if (finished) {
                    stop.run();
                    emitter.complete();
                } else if (System.currentTimeMillis() - lastSend.get() > 15_000) {
                    emitter.send(SseEmitter.event().comment("keep-alive"));
                    lastSend.set(System.currentTimeMillis());
                }
            } catch (IOException | IllegalStateException e) {
                stop.run();
            } catch (RuntimeException e) {
                stop.run();
                emitter.completeWithError(e);
            }
        }, 0, 300, TimeUnit.MILLISECONDS));
        return emitter;
    }

    @GetMapping(path = "/{id}/export.md", produces = "text/markdown;charset=UTF-8")
    ResponseEntity<byte[]> export(@PathVariable String id) {
        RunRecord run = owned(id);
        String md = io.github.llm4j.getviral.web.PackMarkdown.render(run.getIdea(),
                run.getPackJson() == null ? Map.of() : store.read(run.getPackJson()));
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"getviral-pack.md\"")
                .body(md.getBytes(StandardCharsets.UTF_8));
    }

    private RunRecord owned(String id) {
        UserAccount user = currentUser.require();
        return runs.findByIdAndUserId(id, user.getId()).orElseThrow(ApiErrors::notFound);
    }

    private Map<String, Object> summary(RunRecord run) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("id", run.getId());
        s.put("idea", run.getIdea());
        s.put("status", run.getStatus().name());
        s.put("createdAt", run.getCreatedAt().toString());
        s.put("finishedAt", run.getFinishedAt() == null ? null : run.getFinishedAt().toString());
        if (run.getPackJson() != null) {
            Map<String, Object> pack = store.read(run.getPackJson());
            s.put("hook", pack.get("hook"));
            if (pack.get("critic") instanceof Map<?, ?> critic) s.put("score", critic.get("score"));
            if (pack.get("media") instanceof List<?> media) {
                s.put("mediaCount", media.size());
                media.stream().filter(m -> m instanceof Map<?, ?> mm && "youtube_thumbnail".equals(mm.get("purpose")))
                        .findFirst().ifPresent(m -> s.put("cover", ((Map<?, ?>) m).get("url")));
            }
        }
        return s;
    }

    private static int parse(String lastEventId) {
        try {
            return lastEventId == null ? 0 : Integer.parseInt(lastEventId.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @PreDestroy
    void shutdown() {
        tails.shutdownNow();
    }
}
