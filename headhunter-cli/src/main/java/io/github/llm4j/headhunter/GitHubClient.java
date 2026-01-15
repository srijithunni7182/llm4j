package io.github.llm4j.headhunter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class GitHubClient {
    private static final Logger logger = LoggerFactory.getLogger(GitHubClient.class);
    private final OkHttpClient client;
    private final ObjectMapper mapper;
    private final String apiToken;

    public GitHubClient(String apiToken) {
        this.client = new OkHttpClient();
        this.mapper = new ObjectMapper();
        this.apiToken = apiToken;
    }

    public List<Map<String, Object>> findGoodFirstIssues(String language) {
        String url = "https://api.github.com/search/issues?q=label:good-first-issue+language:" + language
                + "+state:open&sort=updated";

        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .addHeader("Accept", "application/vnd.github.v3+json")
                .addHeader("User-Agent", "Headhunter-Agent");

        if (apiToken != null && !apiToken.isEmpty()) {
            requestBuilder.addHeader("Authorization", "Bearer " + apiToken);
        }

        try (Response response = client.newCall(requestBuilder.build()).execute()) {
            if (!response.isSuccessful()) {
                logger.error("GitHub API failed: " + response.code());
                return Collections.emptyList();
            }

            String body = response.body().string();
            Map<String, Object> result = mapper.readValue(body, new TypeReference<>() {
            });
            return (List<Map<String, Object>>) result.get("items");
        } catch (IOException e) {
            logger.error("Failed to fetch issues", e);
            return Collections.emptyList();
        }
    }

    public String createPullRequest(String owner, String repo, String title, String body, String head, String base) {
        String url = String.format("https://api.github.com/repos/%s/%s/pulls", owner, repo);

        Map<String, String> payload = Map.of(
                "title", title,
                "body", body,
                "head", head,
                "base", base);

        try {
            String json = mapper.writeValueAsString(payload);
            Request request = new Request.Builder()
                    .url(url)
                    .post(okhttp3.RequestBody.create(json, okhttp3.MediaType.parse("application/json")))
                    .addHeader("Accept", "application/vnd.github.v3+json")
                    .addHeader("User-Agent", "Headhunter-Agent")
                    .addHeader("Authorization", "Bearer " + apiToken)
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String error = response.body() != null ? response.body().string() : "Unknown error";
                    throw new RuntimeException("Failed to create PR: " + response.code() + " " + error);
                }
                String responseBody = response.body().string();
                Map<String, Object> result = mapper.readValue(responseBody, new TypeReference<>() {
                });
                return (String) result.get("html_url");
            }
        } catch (Exception e) {
            logger.error("Failed to create PR", e);
            throw new RuntimeException(e);
        }
    }
}
