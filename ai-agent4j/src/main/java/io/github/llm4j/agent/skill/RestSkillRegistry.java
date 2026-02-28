package io.github.llm4j.agent.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * An implementation of {@link SkillRegistry} that interacts with a REST API (like SkillsMP)
 * to discover and fetch skills.
 */
public class RestSkillRegistry implements SkillRegistry {

    private final String baseUrl;
    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    private RestSkillRegistry(Builder builder) {
        this.baseUrl = Objects.requireNonNull(builder.baseUrl, "baseUrl must not be null");
        this.apiKey = builder.apiKey; // null is allowed if API doesn't require it
        this.httpClient = builder.httpClient != null ? builder.httpClient : HttpClient.newHttpClient();
        this.objectMapper = builder.objectMapper != null ? builder.objectMapper : new ObjectMapper();
    }

    @Override
    public List<SkillMetadata> searchSkills(String query) throws IOException {
        String encodedQuery = URLEncoder.encode(query == null ? "" : query, StandardCharsets.UTF_8);
        // We assume /search?q=query is the standard endpoint for keyword search
        URI uri = URI.create(baseUrl + (baseUrl.endsWith("/") ? "" : "/") + "search?q=" + encodedQuery);

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(uri).GET();
        if (apiKey != null && !apiKey.isBlank()) {
            requestBuilder.header("Authorization", "Bearer " + apiKey);
            requestBuilder.header("x-api-key", apiKey); // Add both formats just in case
        }

        try {
            HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("Failed to search skills: HTTP " + response.statusCode() + " - " + response.body());
            }

            return parseSearchResponse(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Search interrupted", e);
        }
    }

    @Override
    public AgentSkill getSkill(String skillId) throws IOException {
        String encodedId = URLEncoder.encode(skillId, StandardCharsets.UTF_8);
        URI uri = URI.create(baseUrl + (baseUrl.endsWith("/") ? "" : "/") + encodedId);

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(uri).GET();
        if (apiKey != null && !apiKey.isBlank()) {
            requestBuilder.header("Authorization", "Bearer " + apiKey);
            requestBuilder.header("x-api-key", apiKey);
        }

        try {
            HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("Failed to fetch skill: HTTP " + response.statusCode() + " - " + response.body());
            }

            return parseSkillResponse(skillId, response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Skill fetch interrupted", e);
        }
    }

    private List<SkillMetadata> parseSearchResponse(String body) throws IOException {
        JsonNode rootNode = objectMapper.readTree(body);
        List<SkillMetadata> metadataList = new ArrayList<>();

        // Handle APIs that wrap results in "data" or "items", or just return an array natively
        JsonNode itemsNode = rootNode;
        if (rootNode.isObject()) {
            if (rootNode.has("data") && rootNode.get("data").isArray()) {
                itemsNode = rootNode.get("data");
            } else if (rootNode.has("items") && rootNode.get("items").isArray()) {
                itemsNode = rootNode.get("items");
            } else if (rootNode.has("skills") && rootNode.get("skills").isArray()) {
                itemsNode = rootNode.get("skills");
            }
        }

        if (itemsNode.isArray()) {
            for (JsonNode item : itemsNode) {
                String id = item.has("id") ? item.get("id").asText() : "";
                String name = item.has("name") ? item.get("name").asText() : id;
                String description = item.has("description") ? item.get("description").asText() : "";
                String author = item.has("author") ? item.get("author").asText() : "Unknown";
                
                List<String> tags = new ArrayList<>();
                if (item.has("tags") && item.get("tags").isArray()) {
                    for (JsonNode tag : item.get("tags")) {
                        tags.add(tag.asText());
                    }
                }
                
                metadataList.add(new SkillMetadata(id, name, description, author, tags));
            }
        }
        return metadataList;
    }

    private AgentSkill parseSkillResponse(String fallbackId, String body) throws IOException {
        JsonNode rootNode;
        try {
             rootNode = objectMapper.readTree(body);
        } catch(Exception e) {
            // It might be raw markdown, not JSON!
            return AgentSkill.of(fallbackId, body);
        }

        // If it's JSON, try to extract the markdown content
        String content = "";
        String name = fallbackId;

        // Unpack "data" block if it exists
        if (rootNode.isObject() && rootNode.has("data") && rootNode.get("data").isObject()) {
            rootNode = rootNode.get("data");
        }

        if (rootNode.has("content")) {
            content = rootNode.get("content").asText();
        } else if (rootNode.has("markdown")) {
            content = rootNode.get("markdown").asText();
        } else if (rootNode.has("instructions")) {
            content = rootNode.get("instructions").asText();
        } else {
            // Fallback, treat entire thing as string if we can't find a content field
            content = body; 
        }

        if (rootNode.has("name")) {
            name = rootNode.get("name").asText();
        }

        return AgentSkill.of(name, content);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String baseUrl;
        private String apiKey;
        private HttpClient httpClient;
        private ObjectMapper objectMapper;

        private Builder() {}

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder httpClient(HttpClient httpClient) {
            this.httpClient = httpClient;
            return this;
        }

        public Builder objectMapper(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
            return this;
        }

        public RestSkillRegistry build() {
            return new RestSkillRegistry(this);
        }
    }
}
