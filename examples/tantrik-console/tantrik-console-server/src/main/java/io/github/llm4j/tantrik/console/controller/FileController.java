package io.github.llm4j.tantrik.console.controller;

import io.github.llm4j.tantrik.console.model.FileDescriptor;
import io.github.llm4j.tantrik.console.service.FileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller for Loom script file management.
 *
 * <p>File paths are passed as a {@code ?path=} query parameter rather than
 * a path variable. This avoids the Spring MVC / Tomcat restriction on encoded
 * slashes (%2F) in path segments, which causes 400 errors for paths like
 * {@code examples/research-summarizer.loom}.
 *
 * <p>Requirements: 2.1, 2.3, 2.4
 */
@RestController
@RequestMapping("/api/files")
public class FileController {

    private static final Logger log = LoggerFactory.getLogger(FileController.class);

    private final FileService fileService;

    public FileController(FileService fileService) {
        this.fileService = fileService;
    }

    /**
     * GET /api/files
     * Returns a JSON array of all {@code .loom} file descriptors found under
     * the configured root directory.
     */
    @GetMapping
    public ResponseEntity<List<FileDescriptor>> listFiles() {
        List<FileDescriptor> files = fileService.listFiles();
        log.debug("listFiles() → {} files", files.size());
        return ResponseEntity.ok(files);
    }

    /**
     * GET /api/files/content?path=examples/research-summarizer.loom
     * Returns the raw text content of the requested Loom script file.
     *
     * @param path relative path from the loom-scripts root (plain, not encoded)
     */
    @GetMapping(value = "/content", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> readFile(@RequestParam("path") String path) {
        log.debug("readFile(path={})", path);
        String content = fileService.readFile(path);
        return ResponseEntity.ok(content);
    }

    /**
     * PUT /api/files/content?path=examples/research-summarizer.loom
     * Persists the supplied plain-text content to the specified file.
     *
     * @param path    relative path from the loom-scripts root
     * @param content new file content (plain text body)
     */
    @PutMapping(value = "/content", consumes = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<Void> writeFile(
            @RequestParam("path") String path,
            @RequestBody String content) {
        log.debug("writeFile(path={}, {} chars)", path, content.length());
        fileService.writeFile(path, content);
        return ResponseEntity.ok().build();
    }
}
