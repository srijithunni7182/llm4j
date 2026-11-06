package io.github.llm4j;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.agent.ReActAgent;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.model.Media;
import io.github.llm4j.model.MediaCapabilities;
import io.github.llm4j.model.MediaKind;
import io.github.llm4j.model.MediaPolicy;
import io.github.llm4j.model.Message;
import io.github.llm4j.model.TinyMedia;
import io.github.llm4j.privacy.MaskingLLMClient;
import io.github.llm4j.provider.openai.OpenAiCompatibleProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The code samples in wiki/Media-Input.md, compiled and run against a local server. Change a sample
 * here and in the page together; the last test fails if the page shows something else.
 */
class MediaDocsExamplesTest {

    private static final String CHAT =
            "{\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"42.17\"},"
                    + "\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":9,\"completion_tokens\":2,\"total_tokens\":11}}";

    private MockWebServer server;

    @BeforeEach
    void start() throws Exception {
        server = new MockWebServer();
        server.start();
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(CHAT));
    }

    @AfterEach
    void stop() throws Exception {
        server.shutdown();
    }

    private LLMClient client() {
        LLMConfig cfg =
                LLMConfig.builder()
                        .apiKey("test-key")
                        .baseUrl(server.url("/v1").toString().replaceAll("/$", ""))
                        .defaultModel("gpt-5-mini")
                        .build();
        return new DefaultLLMClient(OpenAiCompatibleProvider.openai(cfg));
    }

    @Test
    void attachSomethingToAMessage(@TempDir Path dir) throws Exception {
        Path receiptFile = Files.write(dir.resolve("receipt.png"), TinyMedia.png());
        LLMClient client = client();
        // --- sample: attach
        MediaPolicy policy = MediaPolicy.defaults();
        Media receipt = Media.image(receiptFile, policy);

        LLMResponse reply = client.chat(LLMRequest.builder()
                .addMessage(Message.user("What is the total on this receipt?", List.of(receipt)))
                .build());
        // --- end sample
        assertThat(reply.getContent()).isEqualTo("42.17");
        JsonNode content = new ObjectMapper().readTree(server.takeRequest().getBody().readUtf8())
                .at("/messages/0/content");
        assertThat(content.at("/0/type").asText()).isEqualTo("image_url");
        assertThat(content.at("/1/text").asText()).isEqualTo("What is the total on this receipt?");
    }

    @Test
    void askWhatAModelTakes() {
        LLMClient client = client();
        // --- sample: capability
        MediaCapabilities caps = client.mediaCapabilities("gpt-5-mini");   // null: the model this client is configured with
        boolean sees = caps.accepts(MediaKind.IMAGE);
        // --- end sample
        assertThat(sees).isTrue();
        assertThat(caps.accepts(MediaKind.VIDEO)).isFalse();
    }

    @Test
    void askAnAgentAboutIt(@TempDir Path dir) throws Exception {
        Media receipt = Media.image(Files.write(dir.resolve("r.png"), TinyMedia.png()), MediaPolicy.defaults());
        ReActAgent agent = ReActAgent.builder().llmClient(client()).build();
        // --- sample: agent
        AgentResult answer = agent.run("What is the total on this receipt?", List.of(receipt));
        // --- end sample
        assertThat(answer.getFinalAnswer()).isNotBlank();
        JsonNode first = new ObjectMapper().readTree(server.takeRequest().getBody().readUtf8())
                .at("/messages");
        boolean carried = false;
        for (JsonNode message : first) carried |= message.at("/content").isArray();
        assertThat(carried).as("the media went to the model with the question").isTrue();
    }

    @Test
    void sendMediaThroughMaskingOnPurpose() {
        LLMClient base = client();
        // --- sample: masking
        LLMClient client = new MaskingLLMClient(base).allowUnscannedMedia();
        // --- end sample
        assertThat(client.mediaCapabilities("gpt-5-mini").accepts(MediaKind.IMAGE)).isTrue();
    }

    @Test
    void thePageShowsExactlyTheSamplesThatAreTested() throws Exception {
        String source = Files.readString(Path.of("src/test/java/io/github/llm4j/MediaDocsExamplesTest.java"));
        String page = Files.readString(Path.of("wiki/Media-Input.md"));
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("// --- sample: ([^\\n]+)\\n(.*?)\\n\\s*// --- end sample", java.util.regex.Pattern.DOTALL).matcher(source);
        int found = 0;
        while (m.find()) {
            found++;
            assertThat(page).as("the page shows the '" + m.group(1) + "' sample").contains(m.group(2).stripIndent().strip());
        }
        assertThat(found).isEqualTo(4);
    }
}
