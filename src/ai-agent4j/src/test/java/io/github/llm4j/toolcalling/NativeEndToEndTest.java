package io.github.llm4j.toolcalling;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.DefaultLLMClient;
import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.agent.ReActAgent;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.config.RetryPolicy;
import io.github.llm4j.provider.LLMProvider;
import io.github.llm4j.provider.anthropic.AnthropicProvider;
import io.github.llm4j.provider.google.GoogleProvider;
import io.github.llm4j.toolcalling.Scripted.Recorder;
import java.util.Map;
import java.util.function.Function;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;

/** An agent on a real provider, against a local mock: the whole native conversation, including what goes back on the second request. */
class NativeEndToEndTest {

    static final ObjectMapper JSON = new ObjectMapper();

    private void converse(Function<LLMConfig, LLMProvider> provider, String firstReply, String secondReply, String assistantCheck) throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(firstReply));
            server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(secondReply));
            LLMConfig config = LLMConfig.builder().apiKey("test-key-0001").baseUrl(server.url("/").toString().replaceAll("/$", ""))
                    .defaultModel("claude-haiku-4-5").retryPolicy(RetryPolicy.builder().maxRetries(0).build()).build();
            Recorder calc = new Recorder("calc", "4");
            calc.schema = io.github.llm4j.model.ToolSchema.object().string("expression", "e.g. 2+2", true).build();

            AgentResult r = ReActAgent.builder().llmClient(new DefaultLLMClient(provider.apply(config))).addTool(calc).build().run("What is 2+2?");

            assertEquals("The answer is 4.", r.getFinalAnswer());
            assertTrue(r.isCompleted());
            assertEquals(Map.of("expression", "2+2"), calc.seen.get(0));
            assertEquals(1, r.getSteps().size());
            assertEquals(AgentResult.StepOutcome.EXECUTED, r.getSteps().get(0).getOutcome());
            assertEquals(2, server.getRequestCount());

            RecordedRequest first = server.takeRequest();
            assertTrue(JSON.readTree(first.getBody().readUtf8()).toString().contains("\"calc\""), "the first request offered the tool");
            JsonNode second = JSON.readTree(server.takeRequest().getBody().readUtf8());
            assertTrue(second.toString().contains(assistantCheck), "the second request carries the model's own turn: " + second);
            assertTrue(second.toString().contains("\"4\""), "and the tool's result");
        }
    }

    @Test
    void anAgentOnGeminiCallsAToolAndAnswers() throws Exception {
        converse(GoogleProvider::new,
                "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"functionCall\":{\"name\":\"calc\",\"args\":{\"expression\":\"2+2\"}},\"thoughtSignature\":\"SIG-1\"}]},\"finishReason\":\"STOP\"}],\"usageMetadata\":{\"promptTokenCount\":9,\"candidatesTokenCount\":3,\"totalTokenCount\":12}}",
                "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"The answer is 4.\"}]},\"finishReason\":\"STOP\"}],\"usageMetadata\":{\"promptTokenCount\":20,\"candidatesTokenCount\":5,\"totalTokenCount\":25}}",
                "SIG-1");
    }

    @Test
    void anAgentOnClaudeCallsAToolAndAnswers() throws Exception {
        converse(AnthropicProvider::new,
                "{\"id\":\"m1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-haiku-4-5\",\"content\":[{\"type\":\"thinking\",\"thinking\":\"add\",\"signature\":\"SIG-2\"},{\"type\":\"tool_use\",\"id\":\"toolu_9\",\"name\":\"calc\",\"input\":{\"expression\":\"2+2\"}}],\"stop_reason\":\"tool_use\",\"usage\":{\"input_tokens\":9,\"output_tokens\":3}}",
                "{\"id\":\"m2\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-haiku-4-5\",\"content\":[{\"type\":\"text\",\"text\":\"The answer is 4.\"}],\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":20,\"output_tokens\":5}}",
                "SIG-2");
    }
}
