package io.github.llm4j.loom.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.LLMClient;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.model.MediaCapabilities;
import io.github.llm4j.model.MediaKind;
import io.github.llm4j.model.Message;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Runs the canonical {@code attach} scenario of the Loom Conformance Test Kit ({@code loom/ctk})
 * through this runtime and holds it to the canonical outcomes: a file under {@code media_root}
 * reaches the model once, with its type; a path out of the root, or a file that does not exist, is
 * refused before any model call. The answer comes from the fixture in {@code mocks/}, so no
 * network or key is involved.
 */
class AttachCtkConformanceTest {

    private static final Path CTK = Path.of("..", "ctk").toAbsolutePath().normalize();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SCENARIO = "attach_basic";

    private final List<LLMRequest> sent = Collections.synchronizedList(new ArrayList<>());

    @Test
    void everyCanonicalRunEndsAsTheTraceSays() throws Exception {
        String answer =
                JSON.readTree(Files.readString(CTK.resolve("mocks/" + SCENARIO + ".json")))
                        .get("Vision")
                        .elements()
                        .next()
                        .asText();
        JsonNode canonical =
                JSON.readTree(Files.readString(CTK.resolve("traces/" + SCENARIO + ".json")));

        for (JsonNode expected : canonical.get("scenarios")) {
            sent.clear();
            String outcome = run(expected.get("input").asText(), answer);

            assertEquals(expected.get("outcome").asText(), outcome, expected.toString());
            assertEquals(expected.get("modelCalls").asInt(), sent.size(), expected.toString());
            assertEquals(expected.get("attachments").asText(), attachments(), expected.toString());
        }
    }

    @Test
    void theCanonicalFileIsAnImageByItsBytes() throws Exception {
        byte[] bytes = Files.readAllBytes(CTK.resolve("scripts/media/pixel.png"));

        assertTrue(bytes.length > 8 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G');
    }

    private String run(String picture, String answer) throws Exception {
        LoomScript script =
                new LoomParser(
                                new Lexer(
                                                Files.readString(
                                                        CTK.resolve("scripts/" + SCENARIO + ".loom")))
                                        .tokenize())
                        .parseScript();
        LLMClient model =
                new LLMClient() {
                    @Override
                    public LLMResponse chat(LLMRequest request) {
                        sent.add(request);
                        return LLMResponse.builder()
                                .content(
                                        "```json\n{\"thought\":\"done\",\"final_answer\":\""
                                                + answer
                                                + "\"}\n```")
                                .model("m")
                                .build();
                    }

                    @Override
                    public Stream<LLMResponse> chatStream(LLMRequest request) {
                        return Stream.of(chat(request));
                    }

                    @Override
                    public MediaCapabilities mediaCapabilities(String m) {
                        return MediaCapabilities.of(MediaKind.IMAGE);
                    }
                };
        HarnessExecutor executor = new HarnessExecutor(script, new ToolRegistry(), name -> model);
        executor.setBaseDir(CTK.resolve("scripts"));
        executor.initialize();
        try {
            executor.executeWorkflow("main", new java.util.HashMap<>(Map.of("picture", picture)));
            return "ok";
        } catch (RuntimeException refused) {
            return "refused";
        }
    }

    /** The types of the files the model was sent, joined by commas. */
    private String attachments() {
        return sent.stream()
                .flatMap(r -> r.getMessages().stream())
                .filter(m -> m.getRole() == Message.Role.USER)
                .flatMap(m -> m.getMedia().stream())
                .map(media -> media.mediaType())
                .reduce((a, b) -> a + "," + b)
                .orElse("");
    }
}
