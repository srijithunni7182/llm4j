package io.github.llm4j.tantrik.console.controller;

import io.github.llm4j.tantrik.console.model.GenerateLoomRequest;
import io.github.llm4j.tantrik.console.model.GenerateLoomResponse;
import io.github.llm4j.tantrik.console.service.LoomGenerateService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for Loom DSL script generation.
 *
 * <p>Accepts a natural-language prompt and delegates to {@link LoomGenerateService}
 * to produce a Loom script, either via a deterministic mock template or a live LLM call.
 *
 * <p>Requirements: 7.1
 */
@RestController
@RequestMapping("/api/generate")
public class LoomGenerateController {

    private final LoomGenerateService loomGenerateService;

    public LoomGenerateController(LoomGenerateService loomGenerateService) {
        this.loomGenerateService = loomGenerateService;
    }

    /**
     * POST /api/generate/loom
     * Generates a Loom DSL script from the supplied natural-language prompt.
     *
     * @param request JSON body containing {@code prompt} and {@code mockMode} flag
     * @return a {@link GenerateLoomResponse} with the generated script and workflow name
     */
    @PostMapping("/loom")
    public ResponseEntity<GenerateLoomResponse> generateLoom(
            @RequestBody GenerateLoomRequest request) {
        GenerateLoomResponse response = loomGenerateService.generate(request);
        return ResponseEntity.ok(response);
    }
}
