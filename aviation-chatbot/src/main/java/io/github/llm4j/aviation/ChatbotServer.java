package io.github.llm4j.aviation;

import io.github.llm4j.DefaultLLMClient;
import io.github.llm4j.LLMClient;
import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.agent.ReActAgent;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.provider.google.GoogleProvider;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

public class ChatbotServer {

    private static final Logger logger = LoggerFactory.getLogger(ChatbotServer.class);
    private static final int PORT = 8080;

    public static void main(String[] args) {
        // 1. Initialize LLM Client
        String googleApiKey = System.getenv("GOOGLE_API_KEY");
        if (googleApiKey == null || googleApiKey.isEmpty()) {
            logger.error("GOOGLE_API_KEY environment variable is not set.");
            System.exit(1);
        }

        String aviationStackApiKey = System.getenv("AVIATION_STACK_API_KEY");
        if (aviationStackApiKey == null || aviationStackApiKey.isEmpty()) {
            logger.error("AVIATION_STACK_API_KEY environment variable is not set.");
            System.exit(1);
        }

        LLMConfig config = LLMConfig.builder()
                .apiKey(googleApiKey)
                .defaultModel("gemini-2.0-flash") // Use a fast model
                .build();

        LLMClient llmClient = new DefaultLLMClient(new GoogleProvider(config));

        // 2. Initialize ReAct Agent with OpenAPI-based AviationStack tool
        // Note: Using default system prompt which includes proper ReAct format
        // instructions
        io.github.llm4j.agent.tools.openapi.OpenAPITool aviationTool = io.github.llm4j.agent.tools.openapi.OpenAPITool
                .builder()
                .name("AviationStack")
                .specLocation("aviation-chatbot/src/main/resources/aviationstack-openapi.json")
                .apiKeyAuth("access_key", aviationStackApiKey)
                .build();

        ReActAgent agent = ReActAgent.builder()
                .llmClient(llmClient)
                .addTool(aviationTool)
                .addTool(new io.github.llm4j.agent.tools.CurrentTimeTool())
                .maxIterations(10)
                .temperature(0.7)
                .build();

        // 3. Start Web Server
        Javalin app = Javalin.create(javalinConfig -> {
            javalinConfig.bundledPlugins.enableCors(cors -> {
                cors.addRule(it -> {
                    it.allowHost("http://localhost:4200"); // Allow Angular dev server
                    it.allowHost("http://localhost:8080"); // Allow self
                });
            });
        }).start(PORT);

        logger.info("Chatbot server started on port {}", PORT);

        // 4. Define Endpoints
        app.post("/api/chat", ctx -> handleChat(ctx, agent));
    }

    private static void handleChat(Context ctx, ReActAgent agent) {
        try {
            Map<String, String> body = ctx.bodyAsClass(Map.class);
            String message = body.get("message");

            if (message == null || message.trim().isEmpty()) {
                ctx.status(400).json(Map.of("error", "Message cannot be empty"));
                return;
            }

            logger.info("Received message: {}", message);
            AgentResult result = agent.run(message);

            Map<String, Object> response = new HashMap<>();
            response.put("response", result.getFinalAnswer());
            response.put("steps", result.getSteps()); // Optional: return steps for debugging/visualization

            ctx.json(response);

        } catch (Exception e) {
            logger.error("Error handling chat request", e);
            ctx.status(500).json(Map.of("error", e.getMessage()));
        }
    }
}
