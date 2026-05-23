package io.github.llm4j.tantrik.console.controller;

import io.github.llm4j.tantrik.console.model.RunRequest;
import io.github.llm4j.tantrik.console.model.RunSummary;
import io.github.llm4j.tantrik.console.service.TantrikRunService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequestMapping("/api/runs")
public class TantrikConsoleController {
    private final TantrikRunService runService;

    public TantrikConsoleController(TantrikRunService runService) {
        this.runService = runService;
    }

    @PostMapping
    public ResponseEntity<RunSummary> createRun(@RequestBody RunRequest request) {
        return ResponseEntity.ok(runService.createRun(request));
    }

    @GetMapping
    public ResponseEntity<List<RunSummary>> listRuns() {
        return ResponseEntity.ok(runService.listRuns());
    }

    @GetMapping("/{runId}")
    public ResponseEntity<RunSummary> getRun(@PathVariable String runId) {
        RunSummary summary = runService.getRun(runId);
        if (summary == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(summary);
    }

    @GetMapping(path = "/{runId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamRun(@PathVariable String runId) {
        return runService.subscribe(runId);
    }

    @DeleteMapping("/{runId}")
    public ResponseEntity<Void> cancelRun(@PathVariable String runId) {
        boolean cancelled = runService.cancelRun(runId);
        if (!cancelled) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.noContent().build();
    }
}
