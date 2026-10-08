package io.github.llm4j.getviral.app.media;

import io.github.llm4j.getviral.app.ApiErrors;
import io.github.llm4j.getviral.app.account.CurrentUser;
import io.github.llm4j.getviral.app.runs.RunRepository;
import io.github.llm4j.getviral.config.GetViralConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves a creator's own generated media (only theirs), with HTTP Range support for video seeking.
 * {@code ?download=name.mp4} sends it as a file download under that name instead of opening it inline.
 */
@RestController
public class MediaController {

    private final CurrentUser currentUser;
    private final RunRepository runs;
    private final MediaStore store;
    private final GetViralConfig config;

    public MediaController(CurrentUser currentUser, RunRepository runs, MediaStore store, GetViralConfig config) {
        this.currentUser = currentUser;
        this.runs = runs;
        this.store = store;
        this.config = config;
    }

    @GetMapping("/media/{runId}/{fileName:.+}")
    void media(@PathVariable String runId, @PathVariable String fileName,
               @RequestParam(name = "download", required = false) String download, HttpServletRequest request,
               HttpServletResponse response) throws IOException {
        var user = currentUser.require();
        runs.findByIdAndUserId(runId, user.getId()).orElseThrow(ApiErrors::notFound);
        if (!fileName.matches("[A-Za-z0-9._-]+") || fileName.contains("..")) throw ApiErrors.notFound();
        Path root = config.dataDir().resolve("media").toAbsolutePath().normalize();
        Path file = root.resolve(runId).resolve(fileName).normalize();
        if (!file.startsWith(root) || !store.fetch(runId, fileName, file)) throw ApiErrors.notFound();

        long size = Files.size(file);
        long start = 0, end = size - 1;
        response.setContentType(GcsMediaStore.contentType(fileName));
        response.setHeader("Accept-Ranges", "bytes");
        response.setHeader("Cache-Control", "private, max-age=3600");
        if (download != null) {
            String name = download.replaceAll("[^A-Za-z0-9._-]", "");
            if (name.isBlank() || !name.contains(".")) name = fileName;
            response.setHeader("Content-Disposition", "attachment; filename=\"" + name + "\"");
        }
        String range = request.getHeader("Range");
        if (range != null && range.startsWith("bytes=")) {
            String[] bounds = range.substring(6).split("-", 2);
            try {
                if (!bounds[0].isBlank()) start = Long.parseLong(bounds[0].trim());
                if (bounds.length > 1 && !bounds[1].isBlank()) {
                    long value = Long.parseLong(bounds[1].trim());
                    if (bounds[0].isBlank()) start = Math.max(0, size - value); else end = Math.min(end, value);
                }
            } catch (NumberFormatException e) {
                start = 0;
                end = size - 1;
            }
            if (start > end) {
                response.setStatus(416);
                response.setHeader("Content-Range", "bytes */" + size);
                return;
            }
            response.setStatus(206);
            response.setHeader("Content-Range", "bytes " + start + "-" + end + "/" + size);
        }
        long length = end - start + 1;
        response.setContentLengthLong(length);
        try (SeekableByteChannel channel = Files.newByteChannel(file); OutputStream out = response.getOutputStream()) {
            channel.position(start);
            ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
            long remaining = length;
            while (remaining > 0) {
                buffer.clear();
                if (remaining < buffer.capacity()) buffer.limit((int) remaining);
                int read = channel.read(buffer);
                if (read < 0) break;
                out.write(buffer.array(), 0, read);
                remaining -= read;
            }
        } catch (IOException e) {
            // client stopped reading — normal while seeking video
        }
    }
}
