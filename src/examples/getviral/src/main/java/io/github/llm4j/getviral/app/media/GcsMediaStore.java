package io.github.llm4j.getviral.app.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Google Cloud Storage via its JSON API, authenticated with the Cloud Run service account's token
 * from the metadata server — no SDK needed. Objects live at {@code media/<runId>/<file>} and are
 * only ever served through the app's ownership-checked /media endpoint (the bucket stays private).
 */
public class GcsMediaStore implements MediaStore {

    private static final Logger log = LoggerFactory.getLogger(GcsMediaStore.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TOKEN_URL =
            "http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/token";

    private final String bucket;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private volatile String token;
    private volatile Instant tokenExpiry = Instant.EPOCH;

    public GcsMediaStore(String bucket) {
        this.bucket = bucket;
    }

    @Override
    public void put(String runId, Path file) {
        String name = objectName(runId, file.getFileName().toString());
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://storage.googleapis.com/upload/storage/v1/b/"
                            + bucket + "/o?uploadType=media&name=" + enc(name)))
                    .header("Authorization", "Bearer " + token())
                    .header("Content-Type", contentType(file.getFileName().toString()))
                    .timeout(Duration.ofMinutes(2))
                    .POST(HttpRequest.BodyPublishers.ofFile(file))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                log.warn("GCS upload of {} failed: HTTP {} {}", name, response.statusCode(), response.body());
            }
        } catch (IOException e) {
            log.warn("GCS upload of {} failed", name, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean fetch(String runId, String fileName, Path local) {
        if (Files.isRegularFile(local)) return true;
        try {
            Files.createDirectories(local.getParent());
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://storage.googleapis.com/storage/v1/b/"
                            + bucket + "/o/" + enc(objectName(runId, fileName)) + "?alt=media"))
                    .header("Authorization", "Bearer " + token())
                    .timeout(Duration.ofMinutes(2))
                    .GET().build();
            Path tmp = Files.createTempFile(local.getParent(), "dl", ".part");
            HttpResponse<Path> response = http.send(request, HttpResponse.BodyHandlers.ofFile(tmp));
            if (response.statusCode() / 100 != 2) {
                Files.deleteIfExists(tmp);
                return false;
            }
            Files.move(tmp, local, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException e) {
            log.warn("GCS download of {}/{} failed", runId, fileName, e);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public void deleteRun(String runId) {
        try {
            HttpResponse<String> list = http.send(HttpRequest.newBuilder(URI.create("https://storage.googleapis.com/storage/v1/b/"
                    + bucket + "/o?prefix=" + enc("media/" + runId + "/")))
                    .header("Authorization", "Bearer " + token()).GET().build(), HttpResponse.BodyHandlers.ofString());
            for (JsonNode item : JSON.readTree(list.body()).path("items")) {
                http.send(HttpRequest.newBuilder(URI.create("https://storage.googleapis.com/storage/v1/b/" + bucket
                        + "/o/" + enc(item.path("name").asText())))
                        .header("Authorization", "Bearer " + token()).DELETE().build(), HttpResponse.BodyHandlers.discarding());
            }
        } catch (IOException e) {
            log.warn("GCS delete of run {} failed", runId, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String token() throws IOException, InterruptedException {
        if (token != null && Instant.now().isBefore(tokenExpiry)) return token;
        synchronized (this) {
            if (token != null && Instant.now().isBefore(tokenExpiry)) return token;
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(TOKEN_URL))
                    .header("Metadata-Flavor", "Google").GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("No service-account token from the metadata server (HTTP " + response.statusCode()
                        + ") — GCS media storage only works on Google Cloud.");
            }
            JsonNode json = JSON.readTree(response.body());
            token = json.path("access_token").asText();
            tokenExpiry = Instant.now().plusSeconds(Math.max(60, json.path("expires_in").asLong(300) - 60));
            return token;
        }
    }

    private static String objectName(String runId, String fileName) {
        return "media/" + runId + "/" + fileName;
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    static String contentType(String name) {
        String lower = name.toLowerCase();
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".webm")) return "video/webm";
        return "application/octet-stream";
    }
}
