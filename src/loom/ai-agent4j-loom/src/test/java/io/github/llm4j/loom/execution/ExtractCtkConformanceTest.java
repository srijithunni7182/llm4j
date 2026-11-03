package io.github.llm4j.loom.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.LLMClient;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Runs the canonical extraction scenario of the Loom Conformance Test Kit ({@code loom/ctk})
 * through this runtime and holds its trace to the canonical one: which extractor ran and the names
 * of the fields it found and left missing. The fixture in {@code mocks/} says what the model
 * answers, so no network or key is involved.
 */
class ExtractCtkConformanceTest {

    private static final Path CTK = Path.of("..", "ctk").toAbsolutePath().normalize();
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void theTraceMatchesTheCanonicalOne() throws Exception {
        String scenario = "extract_fields";
        JsonNode mock =
                JSON.readTree(Files.readString(CTK.resolve("mocks/" + scenario + ".json")))
                        .get("Invoice");
        String input = mock.fieldNames().next();
        String answer = mock.get(input).asText();
        LoomScript script =
                new LoomParser(
                                new Lexer(
                                                Files.readString(
                                                        CTK.resolve("scripts/" + scenario + ".loom")))
                                        .tokenize())
                        .parseScript();
        LLMClient model =
                new LLMClient() {
                    @Override
                    public LLMResponse chat(LLMRequest request) {
                        return LLMResponse.builder().content(answer).model("m").build();
                    }

                    @Override
                    public Stream<LLMResponse> chatStream(LLMRequest request) {
                        return Stream.empty();
                    }
                };
        HarnessExecutor executor = new HarnessExecutor(script, new ToolRegistry(), name -> model);
        List<TraceEvent> events = new ArrayList<>();
        executor.addTraceListener(events::add);
        executor.initialize();
        executor.executeWorkflow("main", new HashMap<>(Map.of("text", input)));

        List<Map<String, String>> actual = new ArrayList<>();
        for (TraceEvent ev : events) {
            if (!ev.type().equals(TraceEvent.EXTRACT_END)) continue;
            Map<String, String> step = new java.util.LinkedHashMap<>();
            step.put("kind", "extract");
            step.put("extractor", String.valueOf(ev.data().get("extractor")));
            step.put("present", join(ev.data().get("present")));
            step.put("missing", join(ev.data().get("missing")));
            actual.add(step);
        }
        List<Map<String, String>> expected = new ArrayList<>();
        for (JsonNode s :
                JSON.readTree(Files.readString(CTK.resolve("traces/" + scenario + ".json")))
                        .get("steps")) {
            Map<String, String> step = new java.util.LinkedHashMap<>();
            for (String f : List.of("kind", "extractor", "present", "missing"))
                step.put(f, s.get(f).asText());
            expected.add(step);
        }
        assertEquals(expected, actual);
    }

    private static String join(Object names) {
        return String.join(",", ((List<?>) names).stream().map(String::valueOf).toList());
    }
}
