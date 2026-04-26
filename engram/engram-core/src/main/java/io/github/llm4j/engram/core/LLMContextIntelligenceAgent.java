package io.github.llm4j.engram.core;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.LLMClient;
import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.engram.core.models.ContextBriefing;
import io.github.llm4j.engram.core.models.MemoryEvent;
import io.github.llm4j.engram.core.models.ScoredMemory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LLMContextIntelligenceAgent implements ContextIntelligenceAgent {

    private final LLMClient llmClient;
    private final ObjectMapper mapper;
    private static final Pattern JSON_PATTERN = Pattern.compile("```(?:json)?\\s*(\\[.*\\]|\\{.*\\})\\s*```", Pattern.DOTALL);

    private static final String EXTRACTION_PROMPT = """
        You are a Memory Extraction tool. 
        Your task is to find key facts, constraints, or decisions from the provided text.
        
        Rules:
        1. List each fact on a new line starting with [FACT].
        2. Keep facts concise.
        3. Only extract information that is useful for future tasks.
        
        Output format:
        [FACT] The board is 3x3.
        [FACT] Player X moves first.
        """;

    private static final String SYNTHESIS_PROMPT = """
        You are a Context Summarizer.
        You are provided with several historical memories.
        Summarize them into a short, cohesive "Context Briefing" for a developer.
        
        Focus on:
        - Core data structures
        - Important logic constraints
        - Past decisions
        
        Keep it under 200 words. Do not use JSON.
        """;

    public LLMContextIntelligenceAgent(LLMClient llmClient) {
        this.llmClient = llmClient;
        this.mapper = new ObjectMapper();
        this.mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Override
    public List<MemoryEvent> extractMemories(AgentDef agentDef, String taskIntent, AgentResult result) {
        if (result == null || result.getFinalAnswer() == null) {
            return new ArrayList<>();
        }

        String userMessage = String.format(
            "Agent: %s\nTask: %s\nOutput: %s\n\nExtract memories starting each with [FACT].",
            agentDef.getName(), taskIntent, result.getFinalAnswer()
        );

        LLMRequest request = LLMRequest.builder()
            .addSystemMessage(EXTRACTION_PROMPT)
            .addUserMessage(userMessage)
            .temperature(0.1)
            .build();

        List<MemoryEvent> events = new ArrayList<>();
        try {
            LLMResponse response = llmClient.chat(request);
            String content = response.getContent();
            String[] lines = content.split("\n");
            for (String line : lines) {
                if (line.trim().startsWith("[FACT]")) {
                    String fact = line.replace("[FACT]", "").trim();
                    if (!fact.isEmpty()) {
                        events.add(new MemoryEvent(fact, "SEMANTIC", 0.7, null, null));
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to extract memories: " + e.getMessage());
        }

        return events;
    }

    @Override
    public ContextBriefing synthesizeBriefing(String taskIntent, List<ScoredMemory> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return new ContextBriefing("No historical context available.", false);
        }

        StringBuilder memoryBlocks = new StringBuilder();
        for (int i = 0; i < candidates.size(); i++) {
            memoryBlocks.append("- ").append(candidates.get(i).memory().getContent()).append("\n");
        }

        String userMessage = String.format(
            "Task: %s\n\nMemories:\n%s\n\nSummarize the briefing.",
            taskIntent, memoryBlocks.toString()
        );

        LLMRequest request = LLMRequest.builder()
            .addSystemMessage(SYNTHESIS_PROMPT)
            .addUserMessage(userMessage)
            .temperature(0.3)
            .build();

        try {
            LLMResponse response = llmClient.chat(request);
            return new ContextBriefing(response.getContent(), false);
        } catch (Exception e) {
            return new ContextBriefing("Fallback context: " + memoryBlocks.toString(), false);
        }
    }

    private static final String INTROSPECTION_PROMPT = """
        You are an Introspector.
        Look at the result of a task and identify any serious errors or critical strategies.
        List each insight on a new line starting with [FACT].
        If everything is fine, output nothing.
        """;

    @Override
    public io.github.llm4j.engram.core.models.IntrospectionResult introspect(AgentDef agentDef, String taskIntent, ContextBriefing briefing, AgentResult result) {
        if (result == null || result.getFinalAnswer() == null) {
            return new io.github.llm4j.engram.core.models.IntrospectionResult(new ArrayList<>(), new ArrayList<>());
        }

        String userMessage = String.format(
            "Task: %s\nBriefing: %s\nResult: %s\n\nIdentify errors or strategies with [FACT].",
            taskIntent, briefing.briefingText(), result.getFinalAnswer()
        );

        LLMRequest request = LLMRequest.builder()
            .addSystemMessage(INTROSPECTION_PROMPT)
            .addUserMessage(userMessage)
            .temperature(0.1)
            .build();

        List<MemoryEvent> insights = new ArrayList<>();
        try {
            LLMResponse response = llmClient.chat(request);
            String content = response.getContent();
            String[] lines = content.split("\n");
            for (String line : lines) {
                if (line.trim().startsWith("[FACT]")) {
                    String fact = line.replace("[FACT]", "").trim();
                    if (!fact.isEmpty()) {
                        insights.add(new MemoryEvent(fact, "STRATEGY_ADJUSTMENT", 0.8, null, null));
                    }
                }
            }
        } catch (Exception e) {
            // Ignore
        }

        return new io.github.llm4j.engram.core.models.IntrospectionResult(insights, new ArrayList<>());
    }
    
    private String extractJson(String text) {
        if (text == null) return null;
        Matcher matcher = JSON_PATTERN.matcher(text);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        // If no markdown block is found, try to find the first '{' or '[' and last '}' or ']'
        int startObj = text.indexOf('{');
        int endObj = text.lastIndexOf('}');
        int startArr = text.indexOf('[');
        int endArr = text.lastIndexOf(']');
        
        if (startObj >= 0 && endObj > startObj && (startArr == -1 || startObj < startArr)) {
            return text.substring(startObj, endObj + 1);
        } else if (startArr >= 0 && endArr > startArr) {
            return text.substring(startArr, endArr + 1);
        }
        return null;
    }
}
