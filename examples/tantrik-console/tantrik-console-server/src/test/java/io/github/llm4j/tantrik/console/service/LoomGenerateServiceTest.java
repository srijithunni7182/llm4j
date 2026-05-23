package io.github.llm4j.tantrik.console.service;

import io.github.llm4j.tantrik.console.model.GenerateLoomRequest;
import io.github.llm4j.tantrik.console.model.GenerateLoomResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link LoomGenerateService} mock mode.
 *
 * <p>Mock mode must return a deterministic template without calling any external LLM.
 * Since no LLM is involved, the service can be instantiated directly with a model name string.
 *
 * <p>Validates: Requirements 7.3
 */
class LoomGenerateServiceTest {

    private LoomGenerateService service;

    @BeforeEach
    void setUp() {
        // Instantiate directly — mock mode never touches the LLM client,
        // so no mocking infrastructure is needed.
        service = new LoomGenerateService("gpt-4o-mini");
    }

    // -------------------------------------------------------------------------
    // Mock mode — script content
    // -------------------------------------------------------------------------

    @Test
    void mockMode_returnsNonEmptyScript() {
        GenerateLoomRequest request = new GenerateLoomRequest("build a research pipeline", true);

        GenerateLoomResponse response = service.generate(request);

        assertThat(response.script())
                .as("script must not be null or blank in mock mode")
                .isNotNull()
                .isNotBlank();
    }

    @Test
    void mockMode_scriptContainsAgentKeyword() {
        GenerateLoomRequest request = new GenerateLoomRequest("build a research pipeline", true);

        GenerateLoomResponse response = service.generate(request);

        assertThat(response.script())
                .as("mock script must contain the 'agent' keyword")
                .contains("agent");
    }

    @Test
    void mockMode_scriptContainsWorkflowKeyword() {
        GenerateLoomRequest request = new GenerateLoomRequest("build a research pipeline", true);

        GenerateLoomResponse response = service.generate(request);

        assertThat(response.script())
                .as("mock script must contain the 'workflow' keyword")
                .contains("workflow");
    }

    // -------------------------------------------------------------------------
    // Mock mode — workflow name
    // -------------------------------------------------------------------------

    @Test
    void mockMode_returnsNonNullWorkflowName() {
        GenerateLoomRequest request = new GenerateLoomRequest("build a research pipeline", true);

        GenerateLoomResponse response = service.generate(request);

        assertThat(response.workflowName())
                .as("workflowName must not be null or blank in mock mode")
                .isNotNull()
                .isNotBlank();
    }

    // -------------------------------------------------------------------------
    // Mock mode — determinism (no LLM call)
    // -------------------------------------------------------------------------

    @Test
    void mockMode_isDeterministic() {
        GenerateLoomRequest request = new GenerateLoomRequest("build a research pipeline", true);

        GenerateLoomResponse first = service.generate(request);
        GenerateLoomResponse second = service.generate(request);

        assertThat(first.script())
                .as("mock mode must return the same script on every call (no LLM randomness)")
                .isEqualTo(second.script());
        assertThat(first.workflowName())
                .as("mock mode must return the same workflowName on every call")
                .isEqualTo(second.workflowName());
    }

    // -------------------------------------------------------------------------
    // parseWorkflowName — package-private helper
    // -------------------------------------------------------------------------

    @Test
    void parseWorkflowName_extractsFirstWorkflowName() {
        String script = """
                agent bot {
                    model "ollama/llama3"
                    system "You are a bot."
                }

                workflow MyPipeline {
                    delegate bot "Do the work."
                }
                """;

        String name = service.parseWorkflowName(script);

        assertThat(name).isEqualTo("MyPipeline");
    }

    @Test
    void parseWorkflowName_returnsUnknownWorkflow_whenNoMatch() {
        String script = "agent bot { model \"ollama/llama3\" }";

        String name = service.parseWorkflowName(script);

        assertThat(name).isEqualTo("UnknownWorkflow");
    }
}
