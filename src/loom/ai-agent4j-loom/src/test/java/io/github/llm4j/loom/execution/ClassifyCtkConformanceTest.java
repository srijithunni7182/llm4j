package io.github.llm4j.loom.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.classify.ClassifyUsage;
import io.github.llm4j.eval.testing.ScriptedClassifier;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Runs the canonical classification scenarios of the Loom Conformance Test Kit ({@code loom/ctk}) through this runtime and holds its trace
 * to the canonical one: which classifier ran, how its answer was judged (ok or unsure) and which labels it bound. The fixture in
 * {@code mocks/} says what the classifier answers, so no network or key is involved.
 */
@Tag("R12")
class ClassifyCtkConformanceTest {

    private static final Path CTK = Path.of("..", "ctk").toAbsolutePath().normalize();
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The classifier's behaviour is the fixture: for a given text, its answers (a label and probability, a yes, or a position). */
    private static ScriptedClassifier fixture(JsonNode answers) {
        ScriptedClassifier.Builder b = ScriptedClassifier.builder().calibrated(true).usage(new ClassifyUsage(100, 10, 150, "jev", "jev-1.13.0"));
        answers.fields().forEachRemaining(e -> {
            JsonNode a = e.getValue();
            if (a.has("label")) b.choice(e.getKey(), a.get("label").asText(), a.get("probability").asDouble());
            else if (a.has("yes")) b.yesNo(e.getKey(), a.get("yes").asDouble());
            else b.score(e.getKey(), a.get("position").asDouble());
        });
        return b.build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"classify_basic", "classify_unsure"})
    void theTraceMatchesTheCanonicalOne(String scenario) throws Exception {
        JsonNode mock = JSON.readTree(Files.readString(CTK.resolve("mocks/" + scenario + ".json"))).get("Intent");
        String input = mock.fieldNames().next();
        LoomScript script = new LoomParser(new Lexer(Files.readString(CTK.resolve("scripts/" + scenario + ".loom"))).tokenize()).parseScript();
        HarnessExecutor executor = new HarnessExecutor(script, new ToolRegistry(), model -> { throw new IllegalStateException("the scenario uses no chat model"); });
        ScriptedClassifier classifier = fixture(JSON.readTree(mock.get(input).asText()));
        executor.setClassifierFactory((model, samples) -> classifier);
        executor.setEnvLookup(name -> name.equals("JEV_API_KEY") ? "test-key" : null);
        List<TraceEvent> events = new ArrayList<>();
        executor.addTraceListener(events::add);
        executor.initialize();
        executor.executeWorkflow("main", new HashMap<>(Map.of("message", input)));

        List<Map<String, String>> actual = new ArrayList<>();
        for (TraceEvent ev : events) {
            if (!ev.type().equals(TraceEvent.CLASSIFY)) continue;
            Map<String, String> step = new java.util.LinkedHashMap<>();
            step.put("kind", "classify");
            step.put("classifier", String.valueOf(ev.data().get("classifier")));
            step.put("outcome", String.valueOf(ev.data().get("outcome")));
            step.put("labels", ((Map<?, ?>) ev.data().get("labels")).entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining(",")));
            actual.add(step);
        }
        List<Map<String, String>> expected = new ArrayList<>();
        for (JsonNode s : JSON.readTree(Files.readString(CTK.resolve("traces/" + scenario + ".json"))).get("steps")) {
            Map<String, String> step = new java.util.LinkedHashMap<>();
            for (String f : List.of("kind", "classifier", "outcome", "labels")) step.put(f, s.get(f).asText());
            expected.add(step);
        }
        assertEquals(expected, actual);
    }
}
