package io.github.llm4j.gmail;

import io.github.llm4j.DefaultLLMClient;
import io.github.llm4j.LLMClient;
import io.github.llm4j.agent.AgentEventListener;
import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.agent.ReActAgent;
import io.github.llm4j.agent.memory.ConversationHistory;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.mcp.McpClient;
import io.github.llm4j.mcp.McpToolAdapter;
import io.github.llm4j.mcp.StdioMcpTransport;
import io.github.llm4j.provider.google.GoogleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Collections;

@Service
public class GmailAgentService {
    private static final Logger logger = LoggerFactory.getLogger(GmailAgentService.class);

    private List<McpClient> mcpClients = Collections.synchronizedList(new ArrayList<>());
    private ReActAgent agent;
    private final ConversationHistory conversationHistory = new ConversationHistory(10);
    private List<Map<String, Object>> tools = new ArrayList<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    @Value("${GMAIL_USER}")
    private String gmailUser;

    @Value("${GMAIL_APP_PASSWORD}")
    private String gmailAppPassword;

    @Value("${GOOGLE_API_KEY}")
    private String googleApiKey;

    @PostConstruct
    public void init() throws Exception {
        logger.info("Initializing MCP Agents (Gmail + Filesystem)...");

        // 1. Setup Gmail MCP Transport
        Map<String, String> gmailEnv = Map.of(
                "GMAIL_USER", gmailUser,
                "GMAIL_APP_PASSWORD", gmailAppPassword);

        try {
            StdioMcpTransport gmailTransport = new StdioMcpTransport(
                    List.of("npx", "-y", "@aot-tech/gmail-mcp-server"),
                    gmailEnv);
            McpClient gmailClient = new McpClient(gmailTransport);
            mcpClients.add(gmailClient);
        } catch (Exception e) {
            logger.error("Failed to setup Gmail MCP", e);
        }

        // 2. Setup Filesystem MCP Transport
        try {
            // Mount the Projects directory for broader access
            String mountDir = "/home/srijith/Projects";
            logger.info("Mounting Filesystem MCP at: {}", mountDir);
            StdioMcpTransport fsTransport = new StdioMcpTransport(
                    List.of("npx", "-y", "@modelcontextprotocol/server-filesystem", mountDir),
                    Map.of());
            McpClient fsClient = new McpClient(fsTransport);
            mcpClients.add(fsClient);
        } catch (Exception e) {
            logger.error("Failed to setup Filesystem MCP", e);
        }

        // 3. Setup Gemini Client with Model Discovery
        LLMConfig tempConfig = LLMConfig.builder()
                .apiKey(googleApiKey)
                .build();

        GoogleProvider tempProvider = new GoogleProvider(tempConfig);
        String latestModel = "gemini-3.5-flash"; // Fallback
        try {
            String discovered = tempProvider.getFirstAvailableModel();
            if (discovered != null && !discovered.isEmpty()) {
                latestModel = discovered;
                logger.info("Auto-discovered latest model: {}", latestModel);
            }
        } catch (Exception e) {
            logger.warn("Model discovery failed, falling back to: " + latestModel, e);
        }

        LLMConfig config = LLMConfig.builder()
                .apiKey(googleApiKey)
                .defaultModel(latestModel)
                .build();

        LLMClient llmClient = new DefaultLLMClient(new GoogleProvider(config));

        // 4. Initialize Clients and Build Agent
        var agentBuilder = ReActAgent.builder()
                .llmClient(llmClient)
                .conversationHistory(conversationHistory)
                .maxIterations(10);

        for (McpClient client : mcpClients) {
            try {
                client.initialize();
                var clientTools = client.listTools();
                this.tools.addAll(clientTools);
                logger.info("Discovered {} tools from client", clientTools.size());

                for (var toolDef : clientTools) {
                    agentBuilder.addTool(new McpToolAdapter(client, toolDef));
                    logger.info("Added tool: {}", toolDef.get("name"));
                }
            } catch (Exception e) {
                logger.error("Failed to initialize an MCP client", e);
            }
        }

        this.agent = agentBuilder.build();
        logger.info("Agent ready with {} tools!", tools.size());
    }

    public SseEmitter chat(String message) {
        SseEmitter emitter = new SseEmitter(180000L); // 3 mins timeout

        executor.submit(() -> {
            try {
                if (agent == null) {
                    emitter.send(SseEmitter.event().name("error").data("Agent not initialized"));
                    emitter.complete();
                    return;
                }

                // Temporary listener for this request
                AgentEventListener listener = new AgentEventListener() {
                    @Override
                    public void onThought(String thought) {
                        sendEvent(emitter, "thought", thought);
                    }

                    @Override
                    public void onAction(String toolName, String toolInput) {
                        sendEvent(emitter, "action", Map.of("tool", toolName, "input", toolInput));
                    }

                    @Override
                    public void onObservation(String observation) {
                        sendEvent(emitter, "observation", observation);
                    }
                };

                // Re-build agent with new listener (not ideal for concurrent users but fine for
                // single-user local app)
                // A better approach would be request-scoped listeners supported by the agent,
                // but for now we'll rebuild or add/remove listeners if the builder supports it.
                // Since our ReActAgent is immutable, we need to rebuild it or handle listeners
                // differently.
                // Given the current architecture, we'll create a per-request agent instance
                // sharing the same tools/history/client.

                // Correction: To support per-request listeners without rebuilding everything,
                // we should probably have passed the listener to the run method, but the API
                // doesn't support it yet.
                // Strategies:
                // 1. Rebuild agent for every request (cheap object creation).
                // 2. Add listener, run, remove listener (thread-safety issues).
                // Let's go with 1: Rebuild agent. It's safe given we have the builder.

                ReActAgent requestAgent = agent.toBuilder()
                        .addListener(listener)
                        .build();

                AgentResult result = requestAgent.run(message);

                sendEvent(emitter, "answer", result.getFinalAnswer());
                emitter.complete();

            } catch (Exception e) {
                logger.error("Error during chat streaming", e);
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    private void sendEvent(SseEmitter emitter, String type, Object data) {
        try {
            // Wrap simple strings in a proper JSON structure to avoid newline issues in SSE
            Object payload = data;
            if (data instanceof String) {
                payload = Map.of("text", data);
            }
            emitter.send(SseEmitter.event().name(type).data(payload));
        } catch (IOException e) {
            logger.error("Error sending SSE event", e);
        }
    }

    public List<Map<String, Object>> getTools() {
        return tools;
    }

    @PreDestroy
    public void shutdown() {
        for (McpClient client : mcpClients) {
            try {
                client.close();
            } catch (Exception e) {
                logger.error("Error closing MCP client", e);
            }
        }
    }
}
