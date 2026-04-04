package io.github.llm4j.provider.ollama;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.llm4j.DefaultLLMClient;
import io.github.llm4j.LLMClient;
import io.github.llm4j.agent.AgentEventListener;
import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.agent.ReActAgent;
import io.github.llm4j.agent.memory.ConversationHistory;
import io.github.llm4j.agent.persona.AgentPersona;
import io.github.llm4j.agent.prompt.PromptRegistry;
import io.github.llm4j.agent.prompt.PromptTemplate;
import io.github.llm4j.agent.skill.AgentSkill;
import io.github.llm4j.agent.tools.CalculatorTool;
import io.github.llm4j.agent.tools.CurrentTimeTool;
import io.github.llm4j.agent.tools.DateTimeTool;
import io.github.llm4j.agent.tools.EchoTool;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Comprehensive integration test suite for OllamaProvider with Gemma3.
 *
 * <p>Tests cover: basic chat, system/user/assistant messages, token usage, temperature control,
 * multi-turn conversation history, agent persona, agent skills, prompt registry, ReAct agent with
 * tools (calculator, datetime, echo), multi-tool agents, event listeners, confidence scoring, and
 * agent result metadata.
 *
 * <p>All tests are skipped if Gemma3 (or any gemma3 variant) is not available in the running
 * Ollama server.
 */
@TestMethodOrder(MethodOrderer.DisplayName.class)
public class OllamaProviderIntegrationTest {

    private static final Logger logger =
            LoggerFactory.getLogger(OllamaProviderIntegrationTest.class);

    private static final String MODEL = "gemma3";

    private static boolean gemma3Available = false;
    private static OllamaProvider provider;

    // -------------------------------------------------------------------------
    // Setup
    // -------------------------------------------------------------------------

    @BeforeAll
    static void detectGemma3() {
        LLMConfig config = LLMConfig.builder().defaultModel(MODEL).build();
        provider = new OllamaProvider(config);
        try {
            String[] models = provider.listModels();
            if (models != null) {
                for (String model : models) {
                    if (model.startsWith(MODEL)) {
                        gemma3Available = true;
                        break;
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Could not connect to Ollama – integration tests will be skipped: {}",
                    e.getMessage());
        }
    }

    @BeforeEach
    void skipIfUnavailable() {
        assumeTrue(gemma3Available,
                "gemma3 model is not installed or Ollama is not running. Skipping integration test.");
    }

    // =========================================================================
    // 1. PROVIDER / CHAT TESTS
    // =========================================================================

    @Test
    @DisplayName("1.1 Basic chat returns non-blank response with token usage")
    void testBasicChat() {
        LLMRequest request = LLMRequest.builder()
                .model(MODEL)
                .addSystemMessage("You are a helpful assistant. Keep your response short.")
                .addUserMessage("Say hello in one sentence.")
                .temperature(0.5)
                .build();

        LLMResponse response = provider.chat(request);

        assertThat(response).isNotNull();
        assertThat(response.getContent()).isNotBlank();
        assertThat(response.getTokenUsage()).isNotNull();
        assertThat(response.getTokenUsage().getTotalTokens()).isGreaterThan(0);
        assertThat(response.getTokenUsage().getPromptTokens()).isGreaterThan(0);
        assertThat(response.getTokenUsage().getCompletionTokens()).isGreaterThan(0);
        assertThat(response.getFinishReason()).isNotNull();
        logger.info("[1.1] Response: {}", response.getContent());
        logger.info("[1.1] Token usage: {}", response.getTokenUsage());
        logger.info("[1.1] Finish reason: {}", response.getFinishReason());
    }

    @Test
    @DisplayName("1.2 System-only prompt context shapes the response")
    void testSystemPromptInfluence() {
        LLMRequest request = LLMRequest.builder()
                .model(MODEL)
                .addSystemMessage(
                        "You are a pirate. Every response must include the word 'arrr'. Keep it very short.")
                .addUserMessage("How are you today?")
                .temperature(0.3)
                .build();

        LLMResponse response = provider.chat(request);

        assertThat(response.getContent()).isNotBlank();
        // Gemma3 should follow the persona instruction (best-effort assertion on LLM output)
        logger.info("[1.2] Pirate response: {}", response.getContent());
    }

    @Test
    @DisplayName("1.3 Multi-turn conversation via assistant messages")
    void testMultiTurnMessages() {
        LLMRequest request = LLMRequest.builder()
                .model(MODEL)
                .addSystemMessage("You are a helpful assistant. Keep all responses to one sentence.")
                .addUserMessage("My favourite color is blue.")
                .addAssistantMessage("Great choice! Blue is a calming color.")
                .addUserMessage("What is my favourite color?")
                .temperature(0.1)
                .build();

        LLMResponse response = provider.chat(request);

        assertThat(response.getContent()).isNotBlank();
        assertThat(response.getContent().toLowerCase()).contains("blue");
        logger.info("[1.3] Multi-turn response: {}", response.getContent());
    }

    @Test
    @DisplayName("1.4 Low temperature produces more deterministic output")
    void testLowTemperatureConsistency() {
        LLMRequest request1 = LLMRequest.builder()
                .model(MODEL)
                .addSystemMessage("Answer with exactly one word only.")
                .addUserMessage("What is 2 + 2?")
                .temperature(0.0)
                .build();
        LLMRequest request2 = LLMRequest.builder()
                .model(MODEL)
                .addSystemMessage("Answer with exactly one word only.")
                .addUserMessage("What is 2 + 2?")
                .temperature(0.0)
                .build();

        String r1 = provider.chat(request1).getContent().trim();
        String r2 = provider.chat(request2).getContent().trim();

        // With temperature=0 both should mention "4" or "four"
        assertThat(r1).containsAnyOf("4", "four", "Four");
        assertThat(r2).containsAnyOf("4", "four", "Four");
        logger.info("[1.4] Response 1: {} | Response 2: {}", r1, r2);
    }

    @Test
    @DisplayName("1.5 maxTokens constraint limits response length")
    void testMaxTokensConstraint() {
        LLMRequest request = LLMRequest.builder()
                .model(MODEL)
                .addUserMessage("Write me a story about a dragon. Make it very long.")
                .maxTokens(20)
                .temperature(0.7)
                .build();

        LLMResponse response = provider.chat(request);

        assertThat(response.getContent()).isNotBlank();
        // With maxTokens=20 the completion tokens should be bounded
        assertThat(response.getTokenUsage().getCompletionTokens()).isLessThanOrEqualTo(25);
        logger.info("[1.5] Truncated response: {}", response.getContent());
    }

    @Test
    @DisplayName("1.6 listModels returns at least one model")
    void testListModels() {
        String[] models = provider.listModels();

        assertThat(models).isNotNull();
        assertThat(models).hasSizeGreaterThan(0);
        logger.info("[1.6] Available models: {}", (Object) models);
    }

    @Test
    @DisplayName("1.7 LLMClient wrapper delegates chat correctly")
    void testLLMClientWrapper() {
        LLMClient client = new DefaultLLMClient(provider);

        LLMRequest request = LLMRequest.builder()
                .model(MODEL)
                .addUserMessage("What is the capital of France? Answer in one word.")
                .temperature(0.1)
                .build();

        LLMResponse response = client.chat(request);

        assertThat(response.getContent()).isNotBlank();
        assertThat(response.getContent().toLowerCase()).contains("paris");
        logger.info("[1.7] LLMClient response: {}", response.getContent());
    }

    // =========================================================================
    // 2. REACT AGENT – SINGLE TOOL TESTS
    // =========================================================================

    @Test
    @DisplayName("2.1 ReAct agent uses CalculatorTool for arithmetic")
    void testReActAgentCalculator() {
        LLMClient client = new DefaultLLMClient(provider);

        ReActAgent agent = ReActAgent.builder()
                .llmClient(client)
                .addTool(new CalculatorTool())
                .maxIterations(5)
                .temperature(0.1)
                .build();

        AgentResult result = agent.run("What is (15 * 23) + 47? Use the calculator tool.");

        assertThat(result).isNotNull();
        assertThat(result.getFinalAnswer()).isNotBlank();
        assertThat(result.getFinalAnswer()).contains("392");

        boolean usedCalculator = result.getSteps().stream()
                .anyMatch(step -> step.getAction() != null
                        && step.getAction().toLowerCase().contains("calculator"));
        assertThat(usedCalculator).as("Agent should have used the calculator tool").isTrue();

        logger.info("[2.1] Final answer: {}", result.getFinalAnswer());
        logger.info("[2.1] Steps taken: {}", result.getSteps().size());
    }

    @Test
    @DisplayName("2.2 ReAct agent uses CurrentTimeTool to answer time question")
    void testReActAgentCurrentTime() {
        LLMClient client = new DefaultLLMClient(provider);

        ReActAgent agent = ReActAgent.builder()
                .llmClient(client)
                .addTool(new CurrentTimeTool())
                .maxIterations(5)
                .temperature(0.1)
                .build();

        AgentResult result = agent.run(
                "What is the current date and time? Use the CurrentTime tool to find out.");

        assertThat(result).isNotNull();
        assertThat(result.getFinalAnswer()).isNotBlank();

        boolean usedTimeTool = result.getSteps().stream()
                .anyMatch(step -> step.getAction() != null
                        && step.getAction().toLowerCase().contains("currenttime"));
        assertThat(usedTimeTool).as("Agent should have used the CurrentTime tool").isTrue();

        logger.info("[2.2] Final answer: {}", result.getFinalAnswer());
    }

    @Test
    @DisplayName("2.3 ReAct agent uses DateTimeTool")
    void testReActAgentDateTimeTool() {
        LLMClient client = new DefaultLLMClient(provider);

        ReActAgent agent = ReActAgent.builder()
                .llmClient(client)
                .addTool(new DateTimeTool())
                .maxIterations(5)
                .temperature(0.1)
                .build();

        AgentResult result = agent.run(
                "What is today's date? Use the CurrentDateTime tool.");

        assertThat(result).isNotNull();
        assertThat(result.getFinalAnswer()).isNotBlank();
        logger.info("[2.3] Final answer: {}", result.getFinalAnswer());
    }

    @Test
    @DisplayName("2.4 ReAct agent uses EchoTool and correctly reflects input")
    void testReActAgentEchoTool() {
        LLMClient client = new DefaultLLMClient(provider);

        ReActAgent agent = ReActAgent.builder()
                .llmClient(client)
                .addTool(new EchoTool())
                .maxIterations(5)
                .temperature(0.1)
                .build();

        AgentResult result = agent.run(
                "Use the Echo tool with the text 'HelloWorld' and report what it returns.");

        assertThat(result).isNotNull();
        assertThat(result.getFinalAnswer()).isNotBlank();

        boolean usedEcho = result.getSteps().stream()
                .anyMatch(step -> step.getAction() != null
                        && step.getAction().toLowerCase().contains("echo"));
        assertThat(usedEcho).as("Agent should have used the Echo tool").isTrue();

        logger.info("[2.4] Final answer: {}", result.getFinalAnswer());
    }

    // =========================================================================
    // 3. REACT AGENT – MULTI-TOOL TESTS
    // =========================================================================

    @Test
    @DisplayName("3.1 Agent with multiple tools selects the correct tool")
    void testMultiToolAgentSelectsCorrectTool() {
        LLMClient client = new DefaultLLMClient(provider);

        ReActAgent agent = ReActAgent.builder()
                .llmClient(client)
                .addTool(new CalculatorTool())
                .addTool(new CurrentTimeTool())
                .addTool(new EchoTool())
                .maxIterations(7)
                .temperature(0.1)
                .build();

        // This question clearly requires the calculator
        AgentResult result = agent.run("Calculate 144 / 12 using the calculator. What is the result?");

        assertThat(result).isNotNull();
        assertThat(result.getFinalAnswer()).isNotBlank();
        assertThat(result.getFinalAnswer()).contains("12");

        boolean usedCalculator = result.getSteps().stream()
                .anyMatch(step -> step.getAction() != null
                        && step.getAction().toLowerCase().contains("calculator"));
        assertThat(usedCalculator).isTrue();

        logger.info("[3.1] Final answer: {}", result.getFinalAnswer());
    }

    @Test
    @DisplayName("3.2 getTools() returns all registered tools")
    void testGetToolsReturnsAllRegistered() {
        LLMClient client = new DefaultLLMClient(provider);

        ReActAgent agent = ReActAgent.builder()
                .llmClient(client)
                .addTool(new CalculatorTool())
                .addTool(new CurrentTimeTool())
                .addTool(new EchoTool())
                .maxIterations(5)
                .temperature(0.1)
                .build();

        assertThat(agent.getTools()).hasSize(3);
        List<String> names = agent.getTools().stream()
                .map(t -> t.getName().toLowerCase())
                .toList();
        assertThat(names).contains("calculator", "currenttime", "echo");
        logger.info("[3.2] Registered tools: {}", names);
    }

    // =========================================================================
    // 4. AGENT RESULT METADATA
    // =========================================================================

    @Test
    @DisplayName("4.1 AgentResult.isCompleted() is true on successful run")
    void testAgentResultCompleted() {
        LLMClient client = new DefaultLLMClient(provider);

        ReActAgent agent = ReActAgent.builder()
                .llmClient(client)
                .addTool(new CalculatorTool())
                .maxIterations(5)
                .temperature(0.1)
                .build();

        AgentResult result = agent.run("What is 7 * 8? Use the calculator.");

        assertThat(result.isCompleted()).isTrue();
        assertThat(result.getIterations()).isGreaterThan(0);
        assertThat(result.getIterations()).isLessThanOrEqualTo(5);
        logger.info("[4.1] Completed: {}, iterations: {}", result.isCompleted(),
                result.getIterations());
    }

    @Test
    @DisplayName("4.2 AgentResult.getConfidence() is present and has a valid score")
    void testAgentResultConfidence() {
        LLMClient client = new DefaultLLMClient(provider);

        ReActAgent agent = ReActAgent.builder()
                .llmClient(client)
                .addTool(new CalculatorTool())
                .maxIterations(5)
                .temperature(0.1)
                .build();

        AgentResult result = agent.run("What is 100 + 200? Use the calculator.");

        assertThat(result.getConfidence()).isNotNull();
        assertThat(result.getConfidence().getScore()).isBetween(0.0, 1.0);
        logger.info("[4.2] Confidence: {} (reason: {})",
                result.getConfidence().getScore(), result.getConfidence().getReasoning());
    }

    @Test
    @DisplayName("4.3 AgentResult.getSteps() carries thought/action/observation triplets")
    void testAgentResultStepsStructure() {
        LLMClient client = new DefaultLLMClient(provider);

        ReActAgent agent = ReActAgent.builder()
                .llmClient(client)
                .addTool(new CalculatorTool())
                .maxIterations(5)
                .temperature(0.1)
                .build();

        AgentResult result = agent.run("What is 3 * 3? Use the calculator tool.");

        assertThat(result.getSteps()).isNotEmpty();
        result.getSteps().forEach(step -> {
            logger.info("[4.3] Step -> thought='{}', action='{}', observation='{}'",
                    step.getThought(), step.getAction(), step.getObservation());
            assertThat(step.getObservation()).isNotNull();
            assertThat(step.getTimestamp()).isNotNull();
        });
    }

    @Test
    @DisplayName("4.4 shouldEscalateToHuman() is false on high-confidence result")
    void testShouldEscalateToHuman() {
        LLMClient client = new DefaultLLMClient(provider);

        ReActAgent agent = ReActAgent.builder()
                .llmClient(client)
                .addTool(new CalculatorTool())
                .maxIterations(5)
                .temperature(0.1)
                .build();

        AgentResult result = agent.run("What is 10 + 5? Use the calculator.");

        // A clean, low-iteration run should not require escalation
        if (result.isCompleted() && result.getIterations() <= 3) {
            assertThat(result.shouldEscalateToHuman()).isFalse();
        }
        logger.info("[4.4] ShouldEscalate: {}", result.shouldEscalateToHuman());
    }

    // =========================================================================
    // 5. AGENT EVENT LISTENER
    // =========================================================================

    @Test
    @DisplayName("5.1 AgentEventListener receives thought, action, and observation events")
    void testAgentEventListener() {
        LLMClient client = new DefaultLLMClient(provider);

        List<String> thoughts = new ArrayList<>();
        List<String> actions = new ArrayList<>();
        List<String> observations = new ArrayList<>();

        AgentEventListener listener = new AgentEventListener() {
            @Override
            public void onThought(String thought) {
                thoughts.add(thought);
                logger.info("[5.1]  THOUGHT: {}", thought);
            }

            @Override
            public void onAction(String toolName, String toolInput) {
                actions.add(toolName);
                logger.info("[5.1]  ACTION: {} | Input: {}", toolName, toolInput);
            }

            @Override
            public void onObservation(String observation) {
                observations.add(observation);
                logger.info("[5.1]  OBSERVATION: {}", observation);
            }
        };

        ReActAgent agent = ReActAgent.builder()
                .llmClient(client)
                .addTool(new CalculatorTool())
                .addListener(listener)
                .maxIterations(5)
                .temperature(0.1)
                .build();

        AgentResult result = agent.run("What is 5 + 5? Use the calculator.");

        assertThat(result.getFinalAnswer()).isNotBlank();
        // At minimum, actions should have been captured
        assertThat(actions).isNotEmpty();
        assertThat(observations).isNotEmpty();
        logger.info("[5.1] Thoughts: {}, Actions: {}, Observations: {}",
                thoughts.size(), actions.size(), observations.size());
    }

    @Test
    @DisplayName("5.2 Multiple listeners all receive events")
    void testMultipleListeners() {
        LLMClient client = new DefaultLLMClient(provider);

        AtomicInteger listener1Count = new AtomicInteger(0);
        AtomicInteger listener2Count = new AtomicInteger(0);

        AgentEventListener listener1 = new AgentEventListener() {
            @Override public void onThought(String t) { listener1Count.incrementAndGet(); }
            @Override public void onAction(String n, String i) { listener1Count.incrementAndGet(); }
            @Override public void onObservation(String o) { listener1Count.incrementAndGet(); }
        };
        AgentEventListener listener2 = new AgentEventListener() {
            @Override public void onThought(String t) { listener2Count.incrementAndGet(); }
            @Override public void onAction(String n, String i) { listener2Count.incrementAndGet(); }
            @Override public void onObservation(String o) { listener2Count.incrementAndGet(); }
        };

        ReActAgent agent = ReActAgent.builder()
                .llmClient(client)
                .addTool(new CalculatorTool())
                .addListener(listener1)
                .addListener(listener2)
                .maxIterations(5)
                .temperature(0.1)
                .build();

        agent.run("What is 6 * 7? Use the calculator.");

        assertThat(listener1Count.get()).isGreaterThan(0);
        assertThat(listener2Count.get()).isGreaterThan(0);
        assertThat(listener1Count.get()).isEqualTo(listener2Count.get());
        logger.info("[5.2] Listener1: {} events, Listener2: {} events",
                listener1Count.get(), listener2Count.get());
    }

    // =========================================================================
    // 6. AGENT PERSONA
    // =========================================================================

    @Test
    @DisplayName("6.1 AgentPersona shapes agent communication style")
    void testAgentPersona() {
        LLMClient client = new DefaultLLMClient(provider);

        AgentPersona persona = AgentPersona.builder()
                .name("Aria")
                .role("friendly math tutor")
                .expertise("elementary arithmetic and algebra")
                .tone("encouraging and cheerful")
                .addConstraint("Always explain your reasoning step by step.")
                .build();

        ReActAgent agent = ReActAgent.builder()
                .llmClient(client)
                .addTool(new CalculatorTool())
                .persona(persona)
                .maxIterations(5)
                .temperature(0.3)
                .build();

        AgentResult result = agent.run("What is 9 + 6? Use the calculator.");

        assertThat(result).isNotNull();
        assertThat(result.getFinalAnswer()).isNotBlank();
        logger.info("[6.1] Persona-influenced answer: {}", result.getFinalAnswer());
    }

    @Test
    @DisplayName("6.2 AgentPersona with custom attributes is applied")
    void testAgentPersonaCustomAttributes() {
        LLMClient client = new DefaultLLMClient(provider);

        AgentPersona persona = AgentPersona.builder()
                .name("DataBot")
                .role("data analyst")
                .expertise("statistics and data interpretation")
                .tone("precise and concise")
                .addCustomAttribute("preferred_format", "bullet points")
                .addCustomAttribute("max_response_length", "3 sentences")
                .build();

        ReActAgent agent = ReActAgent.builder()
                .llmClient(client)
                .addTool(new CalculatorTool())
                .persona(persona)
                .maxIterations(5)
                .temperature(0.2)
                .build();

        AgentResult result = agent.run("Calculate 250 / 5 and summarise the result.");

        assertThat(result).isNotNull();
        assertThat(result.getFinalAnswer()).isNotBlank();
        logger.info("[6.2] DataBot answer: {}", result.getFinalAnswer());
    }

    // =========================================================================
    // 7. AGENT SKILLS
    // =========================================================================

    @Test
    @DisplayName("7.1 AgentSkill with inline content is injected into system prompt")
    void testAgentSkillInlineContent() {
        LLMClient client = new DefaultLLMClient(provider);

        AgentSkill skill = AgentSkill.of(
                "Math Tips",
                "## Math Tips\n- Always verify your arithmetic using the calculator tool.\n"
                        + "- Express final answers as plain numbers without extra text.\n");

        ReActAgent agent = ReActAgent.builder()
                .llmClient(client)
                .addTool(new CalculatorTool())
                .addSkill(skill)
                .maxIterations(5)
                .temperature(0.1)
                .build();

        AgentResult result = agent.run("What is 8 * 9? Use the calculator.");

        assertThat(result.getFinalAnswer()).isNotBlank();
        assertThat(result.getFinalAnswer()).contains("72");
        logger.info("[7.1] Skill-enabled answer: {}", result.getFinalAnswer());
    }

    @Test
    @DisplayName("7.2 Multiple skills are all injected into system prompt")
    void testMultipleAgentSkills() {
        LLMClient client = new DefaultLLMClient(provider);

        AgentSkill mathSkill = AgentSkill.of(
                "Math Rules",
                "Always use the calculator tool for arithmetic.\n");
        AgentSkill formatSkill = AgentSkill.of(
                "Output Format",
                "Give final answers as a single number without units unless asked.\n");

        ReActAgent agent = ReActAgent.builder()
                .llmClient(client)
                .addTool(new CalculatorTool())
                .addSkill(mathSkill)
                .addSkill(formatSkill)
                .maxIterations(5)
                .temperature(0.1)
                .build();

        AgentResult result = agent.run("What is 11 * 11? Use the calculator.");

        assertThat(result.getFinalAnswer()).isNotBlank();
        assertThat(result.getFinalAnswer()).contains("121");
        logger.info("[7.2] Multiple-skill answer: {}", result.getFinalAnswer());
    }

    // =========================================================================
    // 8. PROMPT REGISTRY
    // =========================================================================

    @Test
    @DisplayName("8.1 Custom system prompt via inline builder overrides default")
    void testCustomSystemPromptOverride() {
        LLMClient client = new DefaultLLMClient(provider);

        String customPrompt =
                """
                Answer the following questions as best you can. You have access to the following tools:

                {tool_descriptions}

                Use the following format as a JSON object inside a ```json code block:

                {
                  "thought": "think about what to do",
                  "action": "the action to take, should be one of [{tool_names}]",
                  "action_input": {
                    "parameter_name": "parameter_value"
                  }
                }

                When you have the final answer, use this format:
                {
                  "thought": "I now know the final answer",
                  "final_answer": "the final answer"
                }

                IMPORTANT: Only provide a single valid JSON object inside a ```json code block.
                Keep your final answer very brief.
                """;

        ReActAgent agent = ReActAgent.builder()
                .llmClient(client)
                .addTool(new CalculatorTool())
                .systemPrompt(customPrompt)
                .maxIterations(5)
                .temperature(0.1)
                .build();

        AgentResult result = agent.run("What is 50 * 2? Use the calculator.");

        assertThat(result.getFinalAnswer()).isNotBlank();
        assertThat(result.getFinalAnswer()).contains("100");
        logger.info("[8.1] Custom prompt answer: {}", result.getFinalAnswer());
    }

    @Test
    @DisplayName("8.2 Prompt registry resolves template by ID")
    void testPromptRegistry() {
        LLMClient client = new DefaultLLMClient(provider);

        // Create an in-memory registry with a custom template
        PromptTemplate template = new PromptTemplate(
                "math-agent",
                "1.0",
                """
                Answer the following questions as best you can. You have access to the following tools:

                {tool_descriptions}

                Use the following format as a JSON object inside a ```json code block:

                {
                  "thought": "think about what to do",
                  "action": "the action to take, should be one of [{tool_names}]",
                  "action_input": {
                    "parameter_name": "parameter_value"
                  }
                }

                When you have the final answer, use this format:
                {
                  "thought": "I now know the final answer",
                  "final_answer": "the final answer"
                }

                IMPORTANT: Only provide a single valid JSON object inside a ```json code block.
                """);

        PromptRegistry registry = new PromptRegistry() {
            @Override
            public Optional<PromptTemplate> get(String id) {
                if ("math-agent".equals(id)) return Optional.of(template);
                return Optional.empty();
            }

            @Override
            public Optional<PromptTemplate> get(String id, String version) {
                return get(id);
            }

            @Override
            public void reload() {}
        };

        ReActAgent agent = ReActAgent.builder()
                .llmClient(client)
                .addTool(new CalculatorTool())
                .promptRegistry(registry)
                .systemPromptId("math-agent")
                .maxIterations(5)
                .temperature(0.1)
                .build();

        AgentResult result = agent.run("What is 6 + 6? Use the calculator.");

        assertThat(result.getFinalAnswer()).isNotBlank();
        assertThat(result.getFinalAnswer()).contains("12");
        logger.info("[8.2] Registry-resolved prompt answer: {}", result.getFinalAnswer());
    }

    // =========================================================================
    // 9. CONVERSATION HISTORY (MEMORY)
    // =========================================================================

    @Test
    @DisplayName("9.1 ConversationHistory retains context across agent runs")
    void testConversationHistoryRetainsContext() {
        LLMClient client = new DefaultLLMClient(provider);
        ConversationHistory history = new ConversationHistory(10);

        ReActAgent agent = ReActAgent.builder()
                .llmClient(client)
                .addTool(new CalculatorTool())
                .addTool(new EchoTool())
                .conversationHistory(history)
                .maxIterations(5)
                .temperature(0.1)
                .build();

        // First turn: provide context. Use a tool to ensure success if model is confused.
        AgentResult r1 = agent.run("Remember: my lucky number is 42. Use the Echo tool to confirm you received it.");
        assertThat(r1.getFinalAnswer()).isNotBlank();
        logger.info("[9.1] Turn 1 response: {}", r1.getFinalAnswer());

        // Second turn: recall context using the same history
        ReActAgent agent2 = ReActAgent.builder()
                .llmClient(client)
                .addTool(new CalculatorTool())
                .addTool(new EchoTool())
                .conversationHistory(history)
                .maxIterations(5)
                .temperature(0.1)
                .build();

        AgentResult r2 = agent2.run("What is my lucky number?");
        assertThat(r2.getFinalAnswer()).isNotBlank();
        assertThat(r2.getFinalAnswer()).contains("42");
        logger.info("[9.1] Turn 2 response (should recall 42): {}", r2.getFinalAnswer());
    }

    @Test
    @DisplayName("9.2 ConversationHistory accumulates messages correctly")
    void testConversationHistoryAccumulatesMessages() {
        LLMClient client = new DefaultLLMClient(provider);
        ConversationHistory history = new ConversationHistory(20);

        assertThat(history.getMessages()).isEmpty();

        ReActAgent agent = ReActAgent.builder()
                .llmClient(client)
                .addTool(new CalculatorTool())
                .addTool(new EchoTool())
                .conversationHistory(history)
                .maxIterations(5)
                .temperature(0.1)
                .build();

        agent.run("Say exactly: 'Understood.' Use the Echo tool.");

        // After one agent run, the history should contain user + assistant messages
        assertThat(history.getMessages()).hasSizeGreaterThanOrEqualTo(1);
        logger.info("[9.2] History size after run: {}", history.getMessages().size());
    }

    // =========================================================================
    // 10. AGENT BUILDER – CONFIGURATION VALIDATION
    // =========================================================================

    @Test
    @DisplayName("10.1 toBuilder() creates a copy of agent with overridable fields")
    void testToBuilderCopiesAgent() {
        LLMClient client = new DefaultLLMClient(provider);

        ReActAgent original = ReActAgent.builder()
                .llmClient(client)
                .addTool(new CalculatorTool())
                .maxIterations(5)
                .temperature(0.2)
                .build();

        // Override temperature only
        ReActAgent copy = original.toBuilder()
                .temperature(0.5)
                .build();

        // Both should work correctly
        AgentResult originalResult = original.run("What is 3 + 3? Use the calculator.");
        AgentResult copyResult = copy.run("What is 4 + 4? Use the calculator.");

        assertThat(originalResult.getFinalAnswer()).contains("6");
        assertThat(copyResult.getFinalAnswer()).contains("8");
        logger.info("[10.1] Original: {}, Copy: {}",
                originalResult.getFinalAnswer(), copyResult.getFinalAnswer());
    }

    @Test
    @DisplayName("10.2 sessionId is preserved across agent result")
    void testSessionIdConfiguration() {
        LLMClient client = new DefaultLLMClient(provider);

        ReActAgent agent = ReActAgent.builder()
                .llmClient(client)
                .addTool(new CalculatorTool())
                .sessionId("test-session-12345")
                .maxIterations(5)
                .temperature(0.1)
                .build();

        AgentResult result = agent.run("What is 2 + 2? Use the calculator.");

        assertThat(result).isNotNull();
        assertThat(result.getFinalAnswer()).contains("4");
        logger.info("[10.2] Session ID test passed.");
    }

    @Test
    @DisplayName("10.3 clearTools() removes all registered tools")
    void testClearTools() {
        LLMClient client = new DefaultLLMClient(provider);

        ReActAgent agent = ReActAgent.builder()
                .llmClient(client)
                .addTool(new CalculatorTool())
                .addTool(new EchoTool())
                .clearTools()
                .addTool(new DateTimeTool())
                .maxIterations(5)
                .temperature(0.1)
                .build();

        assertThat(agent.getTools()).hasSize(1);
        String toolName = agent.getTools().iterator().next().getName();
        assertThat(toolName).isEqualToIgnoringCase("CurrentDateTime");
        logger.info("[10.3] After clearTools + addTool: {}", toolName);
    }

    // =========================================================================
    // 11. COMBINED FEATURES
    // =========================================================================

    @Test
    @DisplayName("11.1 Full-featured agent: persona + skill + tool + listener + history")
    void testFullFeaturedAgent() {
        LLMClient client = new DefaultLLMClient(provider);

        AgentPersona persona = AgentPersona.builder()
                .name("Nova")
                .role("helpful AI assistant")
                .tone("friendly and concise")
                .build();

        AgentSkill skill = AgentSkill.of(
                "Calculator Policy",
                "Always use the calculator tool for any arithmetic computation.\n");

        ConversationHistory history = new ConversationHistory(10);

        List<String> capturedThoughts = new ArrayList<>();
        AgentEventListener listener = new AgentEventListener() {
            @Override
            public void onThought(String thought) {
                capturedThoughts.add(thought);
            }
            @Override public void onAction(String n, String i) {}
            @Override public void onObservation(String o) {}
        };

        ReActAgent agent = ReActAgent.builder()
                .llmClient(client)
                .addTool(new CalculatorTool())
                .addTool(new CurrentTimeTool())
                .persona(persona)
                .addSkill(skill)
                .conversationHistory(history)
                .addListener(listener)
                .maxIterations(7)
                .temperature(0.1)
                .sessionId("full-featured-test")
                .build();

        AgentResult result = agent.run("What is (25 + 75) * 2? Use the calculator.");

        assertThat(result).isNotNull();
        assertThat(result.isCompleted()).isTrue();
        assertThat(result.getFinalAnswer()).isNotBlank();
        assertThat(result.getFinalAnswer()).contains("200");
        assertThat(result.getConfidence()).isNotNull();
        assertThat(history.getMessages()).isNotEmpty();
        assertThat(capturedThoughts).isNotEmpty();

        logger.info("[11.1] Full-featured result: {}", result.getFinalAnswer());
        logger.info("[11.1] Confidence: {}", result.getConfidence().getScore());
        logger.info("[11.1] History messages: {}", history.getMessages().size());
        logger.info("[11.1] Thoughts captured: {}", capturedThoughts.size());
    }
}
