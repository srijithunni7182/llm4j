package io.github.llm4j.getviral.media;

import java.io.ByteArrayInputStream;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import javax.imageio.ImageIO;

/**
 * Free, keyless AI image generation from Pollinations.ai's public REST API:
 * {@code GET https://image.pollinations.ai/prompt/{prompt}?width=…&height=…&seed=…&nologo=true}.
 */
public class PollinationsImageGenerator implements ImageGenerator {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Override
    public String name() {
        return "Pollinations.ai (free public API)";
    }

    @Override
    public boolean ai() {
        return true;
    }

    @Override
    public Optional<BufferedImage> generate(ImageRequest request) {
        String url = "https://image.pollinations.ai/prompt/" + URLEncoder.encode(request.prompt(), StandardCharsets.UTF_8)
                .replace("+", "%20")
                + "?width=" + request.width() + "&height=" + request.height()
                + "&seed=" + Math.abs(request.prompt().hashCode() % 100000) + "&nologo=true";
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(60))
                    .header("User-Agent", "GetViral/5.0 (llm4j showcase)")
                    .GET()
                    .build();
            HttpResponse<byte[]> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
            String type = response.headers().firstValue("content-type").orElse("");
            if (response.statusCode() / 100 != 2 || !type.startsWith("image/")) return Optional.empty();
            return Optional.ofNullable(ImageIO.read(new ByteArrayInputStream(response.body())));
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }
}
