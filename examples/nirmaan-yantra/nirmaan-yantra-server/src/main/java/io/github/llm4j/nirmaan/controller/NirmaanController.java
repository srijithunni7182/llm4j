package io.github.llm4j.nirmaan.controller;

import io.github.llm4j.nirmaan.model.ProjectContext;
import io.github.llm4j.nirmaan.service.NirmaanOrchestrator;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

@RestController
@RequestMapping("/api/project")
public class NirmaanController {

    private final NirmaanOrchestrator orchestrator;

    public NirmaanController(NirmaanOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @PostMapping("/start")
    public ResponseEntity<ProjectContext> startProject(@RequestBody String userIdea) {
        ProjectContext context = orchestrator.startProject(userIdea);
        // Call Async method externally to ensure it runs in background
        orchestrator.runWorkflow(context.getProjectId());
        return ResponseEntity.ok(context);
    }

    @GetMapping(path = "/{projectId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamUpdates(@PathVariable String projectId) {
        return orchestrator.subscribe(projectId);
    }

    @GetMapping(path = "/{projectId}/download", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<org.springframework.core.io.Resource> downloadProject(@PathVariable String projectId)
            throws IOException {
        ProjectContext context = orchestrator.getProjectContext(projectId);
        if (context == null) {
            return ResponseEntity.notFound().build();
        }

        java.nio.file.Path sandboxPath = context.getSandboxPath();
        java.nio.file.Path zipPath = sandboxPath.getParent().resolve(projectId + ".zip");

        io.github.llm4j.nirmaan.util.ZipUtil.zipDirectory(sandboxPath, zipPath);

        org.springframework.core.io.Resource resource = new org.springframework.core.io.UrlResource(zipPath.toUri());

        return ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + resource.getFilename() + "\"")
                .body(resource);
    }

    @GetMapping("/{projectId}/artifacts")
    public ResponseEntity<java.util.List<String>> listArtifacts(@PathVariable String projectId) {
        ProjectContext context = orchestrator.getProjectContext(projectId);
        if (context == null) {
            return ResponseEntity.notFound().build();
        }

        try (java.util.stream.Stream<java.nio.file.Path> stream = java.nio.file.Files.walk(context.getSandboxPath())) {
            java.util.List<String> files = stream
                    .filter(java.nio.file.Files::isRegularFile)
                    .map(path -> context.getSandboxPath().relativize(path).toString())
                    .filter(path -> !path.startsWith("target/") &&
                            !path.startsWith("build/") &&
                            !path.startsWith(".git/") &&
                            !path.startsWith("node_modules/") &&
                            !path.startsWith(".idea/"))
                    .collect(java.util.stream.Collectors.toList());

            return ResponseEntity.ok(files);
        } catch (IOException e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/{projectId}/artifacts/**")
    public ResponseEntity<org.springframework.core.io.Resource> getArtifact(@PathVariable String projectId,
            jakarta.servlet.http.HttpServletRequest request) {
        ProjectContext context = orchestrator.getProjectContext(projectId);
        if (context == null) {
            return ResponseEntity.notFound().build();
        }

        String pattern = (String) request
                .getAttribute(org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String filename = new org.springframework.util.AntPathMatcher().extractPathWithinPattern(pattern,
                request.getRequestURI());

        // Remove the prefix if it persists (depends on how extract works with multiple
        // wildcards)
        // Actually for artifacts/** mapping, extractPathWithinPattern should give the
        // remainder.

        // Safety check to prevent traversal
        if (filename.contains("..")) {
            return ResponseEntity.badRequest().build();
        }

        java.nio.file.Path filePath = context.getSandboxPath().resolve(filename);
        if (!java.nio.file.Files.exists(filePath) || !java.nio.file.Files.isRegularFile(filePath)) {
            return ResponseEntity.notFound().build();
        }

        try {
            org.springframework.core.io.Resource resource = new org.springframework.core.io.UrlResource(
                    filePath.toUri());
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_PLAIN) // Default to text for viewing
                    .body(resource);
        } catch (java.net.MalformedURLException e) {
            return ResponseEntity.internalServerError().build();
        }
    }
}
