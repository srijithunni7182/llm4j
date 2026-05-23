package io.github.llm4j.tantrik.console.service;

import io.github.llm4j.LLMClient;
import io.github.llm4j.loom.execution.DefaultLLMClientFactory;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.tantrik.console.model.GenerateLoomRequest;
import io.github.llm4j.tantrik.console.model.GenerateLoomResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service for generating Loom DSL scripts from natural-language prompts.
 *
 * <p>Supports two modes:
 * <ul>
 *   <li><b>Mock mode</b> ({@code mockMode=true}): returns a deterministic hardcoded template
 *       containing at least one {@code agent} block and one {@code workflow} block, without
 *       calling any external LLM.</li>
 *   <li><b>LLM mode</b> ({@code mockMode=false}): builds a system prompt instructing the LLM
 *       to produce a valid Loom script, calls the configured LLM client, and parses the
 *       workflow name from the response.</li>
 * </ul>
 */
@Service
public class LoomGenerateService {

    /** Regex to extract the primary workflow name from a Loom script. */
    private static final Pattern WORKFLOW_NAME_PATTERN =
            Pattern.compile("\\bworkflow\\s+(\\w+)", Pattern.MULTILINE);

    /**
     * Deterministic template returned in mock mode.
     * Contains at least one {@code agent} block and one {@code workflow} block.
     */
    private static final String MOCK_SCRIPT = """
            agent researcher {
                model "ollama/llama3"
                system "You are a research assistant. Gather information on the given topic."
            }

            agent summarizer {
                model "ollama/llama3"
                system "You are a summarization expert. Produce concise summaries."
            }

            workflow GeneratedWorkflow {
                delegate researcher "Research the topic thoroughly."
                delegate summarizer "Summarize the research findings."
            }
            """;

    private static final String MOCK_WORKFLOW_NAME = "GeneratedWorkflow";

    private final String modelName;

    public LoomGenerateService(
            @Value("${tantrik.console.llm.model:gpt-4o-mini}") String modelName) {
        this.modelName = modelName;
    }

    /**
     * Generates a Loom DSL script from the given request.
     *
     * @param request the generation request containing the prompt and mode flag
     * @return a response containing the generated script and the detected workflow name
     * @throws RuntimeException if the LLM call fails (LLM mode only)
     */
    public GenerateLoomResponse generate(GenerateLoomRequest request) {
        if (request.mockMode()) {
            return new GenerateLoomResponse(MOCK_SCRIPT, MOCK_WORKFLOW_NAME);
        }
        return generateWithLlm(request.prompt());
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private GenerateLoomResponse generateWithLlm(String prompt) {
        String systemPrompt = buildSystemPrompt();
        String userMessage = buildUserMessage(prompt);

        LLMRequest llmRequest = LLMRequest.builder()
                .model(modelName)
                .addSystemMessage(systemPrompt)
                .addUserMessage(userMessage)
                .temperature(0.7)
                .maxTokens(2048)
                .build();

        LLMClient client;
        try {
            client = new DefaultLLMClientFactory().createClient(modelName);
        } catch (Exception ex) {
            throw new RuntimeException(
                    "Failed to create LLM client for model '" + modelName + "': " + ex.getMessage(), ex);
        }

        LLMResponse response;
        try {
            response = client.chat(llmRequest);
        } catch (Exception ex) {
            throw new RuntimeException(
                    "LLM call failed while generating Loom script: " + ex.getMessage(), ex);
        }

        String script = response.getContent();
        if (script == null || script.isBlank()) {
            throw new RuntimeException(
                    "LLM returned an empty response when generating a Loom script.");
        }

        String workflowName = parseWorkflowName(script);
        return new GenerateLoomResponse(script, workflowName);
    }

    /**
     * Builds the system prompt that instructs the LLM to produce a valid Loom script.
     */
    private String buildSystemPrompt() {
        return """
                You are an expert Loom DSL script generator. Your task is to produce a syntactically \
                valid Loom script based on the user's natural-language description.

                Rules:
                1. The script MUST contain at least one `agent` block with a `model` and `system` field.
                2. The script MUST contain at least one `workflow` block that orchestrates the agents.
                3. Use only valid Loom DSL keywords: agent, workflow, delegate, parallel, loop, \
                handoff, broadcast, observe, guardrail, note, alt.
                4. Agent names and workflow names must be valid identifiers (letters, digits, underscores).
                5. Output ONLY the raw Loom script — no markdown fences, no explanations, no preamble.

                Example structure:
                agent myAgent {
                    model "ollama/llama3"
                    system "You are a helpful assistant."
                }

                workflow MyWorkflow {
                    delegate myAgent "Perform the task."
                }
                """;
    }

    /**
     * Builds the user message that includes the natural-language prompt.
     */
    private String buildUserMessage(String prompt) {
        return "Generate a Loom script for the following project idea:\n\n" + prompt;
    }

    /**
     * Parses the first workflow name found in the script using a regex.
     * Returns {@code "UnknownWorkflow"} if no workflow declaration is found.
     */
    String parseWorkflowName(String script) {
        Matcher matcher = WORKFLOW_NAME_PATTERN.matcher(script);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "UnknownWorkflow";
    }
}
