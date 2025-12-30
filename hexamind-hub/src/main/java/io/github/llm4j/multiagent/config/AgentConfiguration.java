package io.github.llm4j.multiagent.config;

import io.github.llm4j.DefaultLLMClient;
import io.github.llm4j.LLMClient;
import io.github.llm4j.agent.ReActAgent;
import io.github.llm4j.agent.persona.AgentPersona;
import io.github.llm4j.agent.persona.PersonaLibrary;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.multiagent.model.AgentParticipant;
import io.github.llm4j.provider.google.GoogleProvider;
import io.github.llm4j.agent.rag.embedding.EmbeddingProvider;
import io.github.llm4j.agent.rag.embedding.GeminiEmbeddingProvider;
import io.github.llm4j.multiagent.service.SharedKnowledgeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import java.util.List;

/**
 * Configuration for AI agents.
 */
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
                LLMConfig config = LLMConfig.builder()
                                .apiKey(apiKey)
                                .build();
                return new GeminiEmbeddingProvider(config);
        }

        @Bean
        public List<AgentParticipant> agents(LLMClient client) {
                return List.of(
                                createAgent("tech", "Alex", PersonaLibrary.technicalAnalyst(), client,
                                                "/images/alex.png", 0.3),
                                createAgent("business", "Jordan", PersonaLibrary.businessConsultant(), client,
                                                "/images/jordan.png", 0.5),
                                createAgent("creative", "Sasha", PersonaLibrary.creativeWriter(), client,
                                                "/images/sasha.png", 0.9),
                                createAgent("research", "Dr. Aris", PersonaLibrary.researchScientist(), client,
                                                "/images/aris.png", 0.1),
                                createAgent("customer", "Casey", PersonaLibrary.customerSupport(), client,
                                                "/images/casey.png", 0.6),
                                createRahulAgent(client));
        }

        private AgentParticipant createRahulAgent(LLMClient client) {
                AgentPersona rahulPersona = AgentPersona.builder()
                                .name("Rahul")
                                .role("Cynical Commoner & Analytical Skeptic")
                                .expertise("Real-world news, Data analysis, Logical fallacies")
                                .tone("Cynical, probing, and highly analytical")
                                .addConstraint("Always look for counter-examples and data points that challenge the group's consensus")
                                .addConstraint("Only agree if at least 80% of the proposed points are backed by solid logic or data")
                                .addConstraint("Strictly avoid corporate clichés like 'phased approach', 'proceed with caution', or 'holistic strategy' unless they are backed by specific, non-obvious data.")
                                .addConstraint("MANDATORY: If a term or concept in the user query seems unfamiliar or potentially fabricated, you MUST use web search to verify it before proceeding.")
                                .addConstraint("CURRENT TIME: " + java.time.ZonedDateTime.now().format(
                                                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                                .addCustomAttribute("pessimismLevel", "high")
                                .description("A cynical observer who regularly consumes news and loves to poke holes in optimistic or surface-level analysis. Challenges every assumption with 'What if this fails?' or 'Where is the data for this?'.")
                                .build();

                ReActAgent agent = ReActAgent.builder()
                                .llmClient(client)
                                .persona(rahulPersona)
                                .addTool(createWebSearchTool())
                                .addTool(new io.github.llm4j.agent.tools.DateTimeTool())
                                .maxIterations(12) // Rahul tries extra hard to find flaws
                                .temperature(0.2) // Very precise and analytical
                                .build();

                return new AgentParticipant("rahul", "Rahul", rahulPersona, agent, "/images/rahul.png",
                                sharedKnowledgeService);
        }

        private AgentParticipant createAgent(String id, String name, AgentPersona persona, LLMClient client,
                        String avatarUrl, double temperature) {
                // Add common rigor constraints to every persona library template
                AgentPersona rigidPersona = AgentPersona.builder()
                                .name(persona.getName())
                                .role(persona.getRole())
                                .expertise(persona.getExpertise())
                                .tone(persona.getTone())
                                .description(persona.getDescription())
                                // Copy existing constraints
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
                                .build();

                return new AgentParticipant(id, name, rigidPersona, agent, avatarUrl, sharedKnowledgeService);
        }

        private io.github.llm4j.agent.Tool createWebSearchTool() {
                java.util.List<io.github.llm4j.agent.Tool> searchTools = new java.util.ArrayList<>();

                // 1. SerpAPI (Premium - First Choice)
                if (serpApiKey != null && !serpApiKey.trim().isEmpty()) {
                        searchTools.add(new io.github.llm4j.agent.tools.SerpApiSearchTool(serpApiKey));
                }

                // 2. DuckDuckGo (Free Fallback)
                searchTools.add(new io.github.llm4j.agent.tools.DuckDuckGoSearchTool());

                // 3. Google Custom Search (Legacy Fallback)
                if (apiKey != null && !apiKey.trim().isEmpty() && searchCx != null && !searchCx.trim().isEmpty()) {
                        searchTools.add(new io.github.llm4j.agent.tools.WebSearchTool(apiKey, searchCx));
                }

                // Create fallback chain
                io.github.llm4j.agent.Tool fallbackTool = new io.github.llm4j.agent.tools.FallbackSearchTool(
                                "WebSearch", searchTools);

                // Wrap in cache to save API credits and speed up repeated queries
                return new io.github.llm4j.agent.tools.CachedSearchTool(fallbackTool);
        }
}
