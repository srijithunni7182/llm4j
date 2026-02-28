package io.github.llm4j.agent.skill;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Objects;

/**
 * A {@link SkillLoader} that loads skills directly from a remote HTTP URL.
 * Intended for fetching raw markdown from a direct URL (e.g. GitHub raw file link).
 */
public class RemoteSkillLoader implements SkillLoader {

    private final HttpClient httpClient;

    public RemoteSkillLoader() {
        this(HttpClient.newHttpClient());
    }

    public RemoteSkillLoader(HttpClient httpClient) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient cannot be null");
    }

    @Override
    public AgentSkill load(String source) throws IOException {
        URI uri = URI.create(source);
        HttpRequest request = HttpRequest.newBuilder(uri).GET().build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("Failed to load skill from URL " + source + ": HTTP " + response.statusCode());
            }

            String filename = extractFilename(uri.getPath());
            String name = AgentSkill.inferNameFromPath(filename);
            return AgentSkill.of(name, response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Skill fetch interrupted for URL " + source, e);
        }
    }

    private String extractFilename(String path) {
        if (path == null || path.isEmpty() || !path.contains("/")) {
            return "Remote Skill";
        }
        return path.substring(path.lastIndexOf('/') + 1);
    }
}
