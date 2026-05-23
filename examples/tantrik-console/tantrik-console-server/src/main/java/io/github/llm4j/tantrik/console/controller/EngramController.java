package io.github.llm4j.tantrik.console.controller;

import io.github.llm4j.engram.core.models.MemoryObject;
import io.github.llm4j.tantrik.console.model.EngramNodeDescriptor;
import io.github.llm4j.tantrik.console.service.EngramService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller for Engram knowledge-graph node management.
 *
 * <p>Error handling (404 for unknown node ids) is delegated to
 * {@code GlobalExceptionHandler}, which catches {@link java.util.NoSuchElementException}
 * and returns HTTP 404 with an {@code {"error": "..."}} body.
 *
 * <p>Requirements: 6.1, 6.2, 6.3
 */
@RestController
@RequestMapping("/api/engram/nodes")
public class EngramController {

    private final EngramService engramService;

    public EngramController(EngramService engramService) {
        this.engramService = engramService;
    }

    /**
     * GET /api/engram/nodes
     *
     * <p>Returns a JSON array of all non-shadow nodes in the Engram index.
     * The {@code content} field of each descriptor is truncated to 200 characters
     * by {@link EngramService#listNodes()}.
     *
     * @return list of {@link EngramNodeDescriptor} with truncated content
     */
    @GetMapping
    public ResponseEntity<List<EngramNodeDescriptor>> listNodes() {
        return ResponseEntity.ok(engramService.listNodes());
    }

    /**
     * GET /api/engram/nodes/{id}
     *
     * <p>Returns the full content of a single Engram node without truncation.
     * The response uses the same {@link EngramNodeDescriptor} shape as the list
     * endpoint, but with the complete {@code content} value.
     *
     * @param id the node identifier
     * @return full {@link EngramNodeDescriptor} for the requested node
     * @throws java.util.NoSuchElementException (→ HTTP 404) if no node with the given id exists
     */
    @GetMapping("/{id}")
    public ResponseEntity<EngramNodeDescriptor> getNode(@PathVariable String id) {
        MemoryObject memory = engramService.getNode(id);
        String tier = memory.getTier() != null ? memory.getTier().name() : null;
        EngramNodeDescriptor descriptor = new EngramNodeDescriptor(
                memory.getId(),
                memory.getContent(),
                tier,
                memory.getImportance(),
                memory.getTopicKey()
        );
        return ResponseEntity.ok(descriptor);
    }

    /**
     * DELETE /api/engram/nodes/{id}
     *
     * <p>Removes the node with the given {@code id} from both the secondary index
     * and the underlying {@link io.github.llm4j.engram.core.EngramEngine} store.
     *
     * @param id the node identifier
     * @return HTTP 204 No Content on success
     * @throws java.util.NoSuchElementException (→ HTTP 404) if no node with the given id exists
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteNode(@PathVariable String id) {
        engramService.deleteNode(id);
        return ResponseEntity.noContent().build();
    }
}
