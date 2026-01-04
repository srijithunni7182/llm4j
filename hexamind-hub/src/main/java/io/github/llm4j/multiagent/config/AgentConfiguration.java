package io.github.llm4j.multiagent.config;

import io.github.llm4j.DefaultLLMClient;
import io.github.llm4j.LLMClient;
import io.github.llm4j.agent.ReActAgent;
import io.github.llm4j.agent.persona.AgentPersona;
import io.github.llm4j.agent.persona.PersonaLibrary;
import io.github.llm4j.agent.prompt.FileSystemPromptRegistry;
import io.github.llm4j.agent.prompt.PromptRegistry;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.multiagent.model.AgentParticipant;
import io.github.llm4j.provider.google.GoogleProvider;
import io.github.llm4j.agent.rag.embedding.EmbeddingProvider;
import io.github.llm4j.agent.rag.embedding.GeminiEmbeddingProvider;
import io.github.llm4j.agent.rag.embedding.OnnxEmbeddingProvider;
import io.github.llm4j.agent.rag.embedding.DjlEmbeddingProvider;
import io.github.llm4j.multiagent.service.SharedKnowledgeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import lombok.extern.slf4j.Slf4j;

/**
 * Configuration for AI agents.
 */
@Slf4j
@Configuration
public class AgentConfiguration {

        @Autowired
        @Lazy
        private SharedKnowledgeService sharedKnowledgeService;

        @Value("${google.api.key}")
        private String apiKey;

        @Value("${google.search.cx:}") // Optional search engine ID
        private String searchCx;

        @Value("${serpapi.api.key:}")
        private String serpApiKey;

        @Value("${embedding.provider:gemini}")
        private String embeddingProviderType;

        @Value("${onnx.model.path:}")
        private String onnxModelPath;

        @Value("${onnx.tokenizer.path:}")
        private String onnxTokenizerPath;

        @Value("${djl.model.url:}")
        private String djlModelUrl;

        @Value("${djl.engine:}")
        private String djlEngine;

        @Bean
        public LLMClient llmClient() {
                LLMConfig config = LLMConfig.builder()
                                .apiKey(apiKey)
                                .defaultModel("gemini-2.0-flash")
                                .build();

                return new DefaultLLMClient(new GoogleProvider(config));
        }

        @Bean
        public EmbeddingProvider embeddingProvider(LLMClient client) {
                log.info("Initializing EmbeddingProvider type: {}", embeddingProviderType);
                try {
                        if ("onnx".equalsIgnoreCase(embeddingProviderType)) {
                                if (onnxModelPath.isEmpty() || onnxTokenizerPath.isEmpty()) {
                                        throw new IllegalArgumentException(
                                                        "ONNX paths must be provided when using onnx provider");
                                }
                                return new OnnxEmbeddingProvider(onnxModelPath, onnxTokenizerPath);
                        } else if ("djl".equalsIgnoreCase(embeddingProviderType)) {
                                if (djlModelUrl.isEmpty()) {
                                        throw new IllegalArgumentException(
                                                        "DJL model URL must be provided when using djl provider");
                                }
                                return new DjlEmbeddingProvider(djlModelUrl, djlEngine.isEmpty() ? null : djlEngine);
                        } else {
                                LLMConfig config = LLMConfig.builder()
                                                .apiKey(apiKey)
                                                .build();
                                return new GeminiEmbeddingProvider(config);
                        }
                } catch (Exception e) {
                        log.error("Failed to initialize local embedding provider, falling back to Gemini", e);
                        LLMConfig config = LLMConfig.builder()
                                        .apiKey(apiKey)
                                        .build();
                        return new GeminiEmbeddingProvider(config);
                }
        }

        @Bean
        public PromptRegistry promptRegistry() {
                // For development, point to src/main/resources/prompts.yaml
                // In production, this should be configurable
                Path promptsPath = Paths.get("src/main/resources/prompts.yaml");
                // Fallback to absolute path if needed or handle classpath if
                // FileSystemPromptRegistry supported it
                // For now assuming running from project root
                log.info("Loading PromptRegistry from: {}", promptsPath.toAbsolutePath());
                return new FileSystemPromptRegistry(promptsPath);
        }

        @Bean
        public List<AgentParticipant> agents(LLMClient client, PromptRegistry promptRegistry) {
                log.info("🚀 System Initialization: Booting up AI Agent Swarm...");
                try {
                        List<AgentParticipant> swarm = List.of(
                                        createAgent("tech", "Alex", AgentPersona.builder()
                                                        .name("Alex")
                                                        .role("Deep-Dive Technical Analyst & Systems Researcher")
                                                        .expertise("Cloud architecture, security, performance benchmarking, and emerging tech trends")
                                                        .tone("Precise, highly technical, and data-driven. Never settle for superficial analysis.")
                                                        .addConstraint("PRONG: Focal point for Technical Whitepapers, Documentation, and Forums. Use 'filetype:pdf' or specialized searches.")
                                                        .addConstraint("TEMPORAL WEIGHT: Structural/Architectural (Weeks/Months).")
                                                        .addConstraint("ADAPTIVITY: Respect Jordan's real-time indicators if they suggest a system-wide shift that whitepapers haven't caught up to yet.")
                                                        .addConstraint("Quantify everything. If data isn't immediately obvious, search deeper.")
                                                        .build(), client, "/images/alex.png", 0.3, promptRegistry),
                                        createAgent("business", "Jordan", AgentPersona.builder()
                                                        .name("Jordan")
                                                        .role("Market Intelligence Strategist & Business Researcher")
                                                        .expertise("Market trends, financial modeling, ROI analysis, and competitive landscape")
                                                        .tone("Strategic, pragmatic, and highly inquisitive about market shifts.")
                                                        .addConstraint("PRONG: Focal point for Latest News, Social Media Trends, and Financial Reports. Look for real-time pulse.")
                                                        .addConstraint("TEMPORAL WEIGHT: Real-time/Emerging (Minutes/Hours).")
                                                        .addConstraint("ADAPTIVITY: Your data is the 'Radar'. If others don't find it in journals, explicitly argue that your source is a leading indicator.")
                                                        .addConstraint("Focus on uncovering hidden risks and opportunities through thorough research")
                                                        .build(), client, "/images/jordan.png", 0.5, promptRegistry),
                                        createAgent("creative", "Sasha", PersonaLibrary.creativeWriter(), client,
                                                        "/images/sasha.png", 0.9, promptRegistry),
                                        createAgent("research", "Dr. Aris", AgentPersona.builder()
                                                        .name("Dr. Aris")
                                                        .role("Lead Research Scientist & Investigative Academic")
                                                        .expertise("Scientific methodology, interdisciplinary research, and trend forecasting")
                                                        .tone("Methodical, curious, and obsessively evidence-based.")
                                                        .addConstraint("PRONG: Focal point for Published Journals, Academic Research, and Institutional Reports.")
                                                        .addConstraint("TEMPORAL WEIGHT: Foundational/Proven (Years).")
                                                        .addConstraint("ADAPTIVITY: Use journals for 'First Principles'. Do NOT dismiss Jordan's news; instead, look for historical analogs that might explain the event.")
                                                        .addConstraint("Cross-reference information from multiple diverse sources to eliminate bias")
                                                        .build(), client, "/images/aris.png", 0.1, promptRegistry),
                                        createAgent("customer", "Casey", PersonaLibrary.customerSupport(), client,
                                                        "/images/casey.png", 0.6, promptRegistry),
                                        createRahulAgent(client, promptRegistry));

                        log.info("✅ Agent Swarm successfully initialized with {} active agents.", swarm.size());
                        return swarm;
                } catch (Exception e) {
                        log.error("🛑 CRITICAL SYSTEM ERROR: Failed to initialize AI Agents.", e);
                        log.error("👉 CAUSE: {}", e.getMessage());
                        log.error("👉 SUGGESTION: Check your GOOGLE_API_KEY and network connection.");
                        throw new RuntimeException("Agent Swarm Initialization Failed", e);
                }
        }

        private AgentParticipant createRahulAgent(LLMClient client, PromptRegistry promptRegistry) {
                AgentPersona rahulPersona = AgentPersona.builder()
                                .name("Rahul")
                                .role("Cynical Commoner & Adversarial Source Researcher")
                                .expertise("Real-world news, Alternate Viewpoints, Source Verification, Logical fallacies")
                                .tone("Cynical, probing, and highly analytical")
                                .addConstraint("PRONG: Focal point for Adversarial Research & Source Verification.")
                                .addConstraint("ADAPTIVITY: Verify specific citations/links from others. Actively search for alternate views or contradictory data for every 'fact' presented.")
                                .addConstraint("Always look for counter-examples and data points that challenge the group's consensus to break echo chambers.")
                                .addConstraint("MANDATORY: If a term or concept in the user query seems unfamiliar or potentially fabricated, you MUST use web search to verify it before proceeding.")
                                .addConstraint("CURRENT TIME: " + java.time.ZonedDateTime.now().format(
                                                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                                .addCustomAttribute("pessimismLevel", "high")
                                .description("A cynical observer who serves as the group's reality check, verifying sources and hunting for contradictory evidence to prevent collective hallucinations.")
                                .build();

                ReActAgent agent = ReActAgent.builder()
                                .llmClient(client)
                                .persona(rahulPersona)
                                .addTool(createWebSearchTool())
                                .addTool(new io.github.llm4j.agent.tools.DateTimeTool())
                                .maxIterations(12) // Rahul tries extra hard to find flaws
                                .temperature(0.2) // Very precise and analytical
                                .promptRegistry(promptRegistry) // Register registry
                                .build();

                return new AgentParticipant("rahul", "Rahul", rahulPersona, agent, "/images/rahul.png",
                                sharedKnowledgeService, promptRegistry);
        }

        private AgentParticipant createAgent(String id, String name, AgentPersona persona, LLMClient client,
                        String avatarUrl, double temperature, PromptRegistry promptRegistry) {
                // Add common rigor constraints to every persona library template
                AgentPersona.Builder builder = AgentPersona.builder()
                                .name(persona.getName())
                                .role(persona.getRole())
                                .expertise(persona.getExpertise())
                                .tone(persona.getTone())
                                .description(persona.getDescription());

                // Copy existing constraints
                if (persona.getConstraints() != null) {
                        persona.getConstraints().forEach(builder::addConstraint);
                }

                // Add rigid system-wide constraints
                AgentPersona rigidPersona = builder
                                .addConstraint("MANDATORY: Verify all unfamiliar terms in the user query using web search before analyzing.")
                                .addConstraint("Avoid generic phrases like 'phased approach' or 'proceed with caution'. Be specific and data-driven.")
                                .addConstraint("CURRENT TIME awareness: " + java.time.ZonedDateTime.now().format(
                                                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                                .build();

                ReActAgent agent = ReActAgent.builder()
                                .llmClient(client)
                                .persona(rigidPersona)
                                .addTool(createWebSearchTool())
                                .addTool(new io.github.llm4j.agent.tools.DateTimeTool())
                                .maxIterations(10)
                                .temperature(temperature)
                                .promptRegistry(promptRegistry) // Register registry
                                .build();

                return new AgentParticipant(id, name, rigidPersona, agent, avatarUrl, sharedKnowledgeService,
                                promptRegistry);
        }

        private io.github.llm4j.agent.Tool createWebSearchTool() {
                java.util.List<io.github.llm4j.agent.Tool> searchTools = new java.util.ArrayList<>();

                // 1. SerpAPI (Premium - First Choice)
                if (serpApiKey != null && !serpApiKey.trim().isEmpty()) {
                        log.info("🔍 Search Capability: Enabled SerpAPI (High Quality)");
                        searchTools.add(new io.github.llm4j.agent.tools.SerpApiSearchTool(serpApiKey));
                } else {
                        log.warn("⚠️ Search Capability: SerpAPI key missing. Falling back to lower-quality alternatives.");
                }

                // 2. DuckDuckGo (Free Fallback)
                searchTools.add(new io.github.llm4j.agent.tools.DuckDuckGoSearchTool());

                // 3. Google Custom Search (Legacy Fallback)
                if (apiKey != null && !apiKey.trim().isEmpty() && searchCx != null && !searchCx.trim().isEmpty()) {
                        log.info("🔍 Search Capability: Enabled Google Custom Search");
                        searchTools.add(new io.github.llm4j.agent.tools.WebSearchTool(apiKey, searchCx));
                }

                // Create fallback chain
                io.github.llm4j.agent.Tool fallbackTool = new io.github.llm4j.agent.tools.FallbackSearchTool(
                                "WebSearch", searchTools);

                // Wrap in cache to save API credits and speed up repeated queries
                return new io.github.llm4j.agent.tools.CachedSearchTool(fallbackTool);
        }
}
