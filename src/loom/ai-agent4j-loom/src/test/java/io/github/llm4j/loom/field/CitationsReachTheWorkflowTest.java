package io.github.llm4j.loom.field;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.DefaultLLMClient;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.provider.google.GoogleProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

/**
 * F09: the sources of a grounded Gemini answer never reached the workflow ({@code
 * {research_citations}} stayed empty and a fallback skipped the Sources section silently). The
 * answer here is the one recorded from the real API on 2026-10-22 (see
 * docs/specs/17-honest-results/VERIFICATION.md); the test runs it through the real Google adapter.
 */
class CitationsReachTheWorkflowTest {
    private static String recorded() throws IOException {
        try (var in =
                CitationsReachTheWorkflowTest.class.getResourceAsStream(
                        "/field/gemini-grounded-recorded.json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** The recorded answer with its text replaced, keeping the grounding block as recorded. */
    private static String recordedWithText(String text) throws IOException {
        var root = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(recorded());
        ((com.fasterxml.jackson.databind.node.ObjectNode)
                        root.path("candidates").get(0).path("content").path("parts").get(0))
                .put("text", text);
        return JSON.writeValueAsString(root);
    }

    /** The recorded answer with no grounding block: the model answered without searching. */
    private static String recordedWithoutGrounding() throws IOException {
        var root = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(recorded());
        ((com.fasterxml.jackson.databind.node.ObjectNode) root.path("candidates").get(0))
                .remove("groundingMetadata");
        return JSON.writeValueAsString(root);
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private static HarnessExecutor executor(String script, MockWebServer gemini) {
        HarnessExecutor e =
                new HarnessExecutor(
                        new LoomParser(new Lexer(script).tokenize()).parseScript(),
                        new ToolRegistry(),
                        model ->
                                new DefaultLLMClient(
                                        new GoogleProvider(
                                                LLMConfig.builder()
                                                        .apiKey("test-key")
                                                        .baseUrl(gemini.url("/v1beta").toString())
                                                        .defaultModel(model)
                                                        .build())));
        e.initialize();
        return e;
    }

    private Object citationsAfter(String workflowBody) throws Exception {
        return runWith(workflowBody, recorded()).getContext().getVariable("research_citations");
    }

    private HarnessExecutor runWith(String workflowBody, String geminiAnswer) throws Exception {
        try (MockWebServer gemini = new MockWebServer()) {
            gemini.start();
            for (int i = 0; i < 4; i++)
                gemini.enqueue(
                        new MockResponse()
                                .setHeader("Content-Type", "application/json")
                                .setBody(geminiAnswer));
            HarnessExecutor e =
                    executor(
                            "agent Researcher { model: \"gemini-2.5-flash\" system: \"Research.\""
                                    + " grounding: web }\n"
                                    + "agent Other { model: \"gemini-2.5-flash\" system: \"Other.\" }\n"
                                    + "workflow Main() {\n"
                                    + workflowBody
                                    + "\n}\n",
                            gemini);
            e.executeWorkflow("Main", Map.of());
            return e;
        }
    }

    private static void assertThreeSources(Object citations) {
        assertThat(citations).isInstanceOf(List.class);
        assertThat((List<?>) citations).hasSize(3);
    }

    @Test
    void sourcesReachTheWorkflowFromAPlainDelegate() throws Exception {
        assertThreeSources(citationsAfter("  delegate \"rate?\" to Researcher -> research"));
    }

    @Test
    void sourcesReachTheWorkflowFromAParallelBranch() throws Exception {
        assertThreeSources(
                citationsAfter(
                        "  parallel {\n    delegate \"rate?\" to Researcher -> research\n"
                                + "    delegate \"other\" to Other -> side\n  }"));
    }

    @Test
    void sourcesReachTheWorkflowFromAStepWithAnExpectedShape() throws Exception {
        try (MockWebServer ignored = new MockWebServer()) {
            Object citations =
                    runWith(
                                    "  delegate \"rate?\" to Researcher -> research expecting {"
                                            + " rate: string }",
                                    recordedWithText("{\"rate\": \"5.50%\"}"))
                            .getContext()
                            .getVariable("research_citations");

            assertThreeSources(citations);
        }
    }

    @Test
    void aGroundedAgentThatAnsweredWithoutSearchingIsNamedInTheSummaryNotLeftSilent()
            throws Exception {
        var e =
                runWith(
                        "  delegate \"rate?\" to Researcher -> research",
                        recordedWithoutGrounding());

        assertThat(String.valueOf(e.getContext().getVariable("research_citations")))
                .isIn("", "null");
        assertThat(e.groundedWithoutSources()).containsEntry("Researcher", 1L);
    }

    @Test
    void anAgentThatIsNotGroundedIsNeverReportedForMissingSources() throws Exception {
        var e = runWith("  delegate \"other\" to Other -> side", recordedWithoutGrounding());

        assertThat(e.groundedWithoutSources()).isEmpty();
    }
}
