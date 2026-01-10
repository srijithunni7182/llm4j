package io.github.llm4j.nirmaan.agent;

import io.github.llm4j.DefaultLLMClient;
import io.github.llm4j.LLMClient;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.provider.google.GoogleProvider;
import io.github.llm4j.nirmaan.model.ProjectContext;

import jakarta.annotation.PostConstruct;

public abstract class BaseNirmaanAgent implements NirmaanAgent {

    protected LLMClient llmClient;

    @PostConstruct
    public void init() {
        String apiKey = System.getenv("GOOGLE_API_KEY");
        if (apiKey == null) {
            System.err.println("GOOGLE_API_KEY environment variable is missing!");
            throw new RuntimeException("GOOGLE_API_KEY not found in environment");
        }

        this.llmClient = createLLMClient(apiKey);
    }

    private LLMClient createLLMClient(String apiKey) {
        String model = null;
        try {
            LLMConfig tempConfig = LLMConfig.builder().apiKey(apiKey).build();
            GoogleProvider tempProvider = new GoogleProvider(tempConfig);
            String discoveredModel = tempProvider.getFirstAvailableModel();

            if (discoveredModel != null) {
                model = discoveredModel;
                System.out.println(String.format("%s (%s) found model: %s", getName(), getRole(), model));
            } else {
                model = "gemini-1.5-flash";
                System.out.println(String.format("%s could not discover models, using fallback: %s", getName(), model));
            }
        } catch (Exception e) {
            model = "gemini-1.5-flash";
            System.out.println(String.format("%s model discovery failed: %s. Using fallback: %s", getName(),
                    e.getMessage(), model));
        }

        LLMConfig config = LLMConfig.builder()
                .apiKey(apiKey)
                .defaultModel(model)
                .build();

        return new DefaultLLMClient(new GoogleProvider(config));

    }

    protected String chatWithTools(ProjectContext context, String prompt) {
        int maxTurns = 3;
        StringBuilder chatHistory = new StringBuilder(prompt);
        String lastResponse = "";

        for (int i = 0; i < maxTurns; i++) {
            try {
                // 1. Call LLM
                io.github.llm4j.model.LLMRequest request = io.github.llm4j.model.LLMRequest.builder()
                        .addUserMessage(chatHistory.toString())
                        .temperature(0.2) // Low temp for tool use
                        .build();

                io.github.llm4j.model.LLMResponse response = llmClient.chat(request);
                lastResponse = response.getContent();

                // 2. Check for [SEARCH: ...]
                java.util.regex.Pattern searchPattern = java.util.regex.Pattern.compile("\\[SEARCH: (.*?)\\]");
                java.util.regex.Matcher matcher = searchPattern.matcher(lastResponse);

                if (matcher.find()) {
                    String query = matcher.group(1);
                    context.log(getName(), "Searching Web: " + query);

                    // Execute Search
                    java.util.List<String> results = io.github.llm4j.nirmaan.util.SearchUtil.search(query);
                    String searchBlock = "\n\n[SEARCH_RESULTS]\n" + String.join("\n---\n", results)
                            + "\n[/SEARCH_RESULTS]\n\n";

                    // Append to history and continue loop
                    chatHistory.append("\nAssistant: ").append(lastResponse);
                    chatHistory.append("\nSystem: ").append(searchBlock);
                    chatHistory.append("Please verify the info and provide the Final Answer (or search again).");

                } else {
                    // No search needed, return final response
                    return lastResponse;
                }

            } catch (Exception e) {
                context.log(getName(), "Error in Tool Loop: " + e.getMessage());
                return lastResponse; // Return what we have
            }
        }
        return lastResponse;
    }

    protected void logThought(ProjectContext context, String thought) {
        context.log(getName(), "[Thought] " + thought);
    }
}
