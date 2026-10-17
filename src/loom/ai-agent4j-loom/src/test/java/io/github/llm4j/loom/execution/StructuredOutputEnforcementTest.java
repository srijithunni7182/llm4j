package io.github.llm4j.loom.execution;

import static org.junit.jupiter.api.Assertions.*;

import io.github.llm4j.LLMClient;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * An agent whose answer does not match its {@code output_schema} is a failed attempt: it is told what was wrong and asked again, and
 * when that does not help the step fails and its {@code on_failure} runs. It used to be passed on as raw text with a log line.
 */
class StructuredOutputEnforcementTest {

    private static final String SCRIPT = """
            agent Reviewer {
                model: "test"
                system: "You are Reviewer."
                output_schema: {
                    verdict: enum["OK", "REWRITE"],
                    advice: string,
                    items: list<{ name: string, n: number }>
                }
            }
            agent Plain { model: "test"  system: "You are Plain." }

            workflow Main() {
                delegate "Review it" to Reviewer -> review_result
                    on_failure { note "failed: {_error}" }
                note "verdict={review_result.verdict}"
            }

            workflow Free() {
                delegate "Say anything" to Plain -> plain_text
                note "free: {plain_text}"
            }

            workflow Step() {
                delegate "Pick" to Plain -> picked_step expecting { pick: enum["A", "B"] }
                    on_failure { note "step failed: {_error}" }
                note "pick={picked_step.pick}"
            }
            """;

    private record Outcome(List<String> notes, List<String> requests, List<TraceEvent> events, Map<String, Object> variables, int calls) {}

    private static final String GOOD = "{\"verdict\": \"OK\", \"advice\": \"fine\", \"items\": [{\"name\": \"a\", \"n\": 1}]}";

    private static Outcome run(String workflow, String... replies) {
        List<String> requests = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger calls = new AtomicInteger();
        LLMClientFactory factory = model -> new LLMClient() {
            @Override
            public LLMResponse chat(LLMRequest request) {
                requests.add(request.getMessages().get(request.getMessages().size() - 1).getContent());
                int i = Math.min(calls.getAndIncrement(), replies.length - 1);
                return LLMResponse.builder().content(replies[i]).model(model).build();
            }

            @Override
            public Stream<LLMResponse> chatStream(LLMRequest request) {
                return Stream.empty();
            }
        };
        LoomScript script = new LoomParser(new Lexer(SCRIPT).tokenize()).parseScript();
        HarnessExecutor executor = new HarnessExecutor(script, new ToolRegistry(), factory);
        List<TraceEvent> events = Collections.synchronizedList(new ArrayList<>());
        executor.addTraceListener(events::add);
        executor.initialize();
        executor.executeWorkflow(workflow, Map.of());
        List<String> notes = events.stream().filter(e -> e.type().equals(TraceEvent.NOTE)).map(TraceEvent::text).toList();
        return new Outcome(notes, requests, events, executor.getContext().getAll(), calls.get());
    }

    @Test
    void anAnswerThatMatchesIsAStructuredValueAsBefore() {
        Outcome o = run("Main", "```json\n" + GOOD + "\n```");

        assertEquals(List.of("verdict=OK"), o.notes());
        assertEquals(1, o.calls());
        assertTrue(o.variables().get("review_result") instanceof Map);
    }

    @Test
    void jsonInsideChatterOrWithoutAClosingFenceIsStillFound() {
        assertEquals(List.of("verdict=OK"), run("Main", "Sure! Here is my review:\n" + GOOD + "\nHope that helps.").notes());
        assertEquals(List.of("verdict=OK"), run("Main", "```json\n" + GOOD + "\n").notes());
        assertEquals(List.of("verdict=OK"), run("Main", "{\"advice\": \"has } and { inside\", \"verdict\": \"OK\", \"items\": []}").notes());
    }

    @Test
    void yamlIsRejectedThenAskedAgainWithTheReasonAndTheSecondAnswerIsUsed() {
        // exactly what Gemini did to the software factory: YAML with block scalars instead of JSON
        Outcome o = run("Main", "verdict: OK\nadvice: |\n  fine\nitems:\n  - name: a\n    n: 1\n", GOOD);

        assertEquals(List.of("verdict=OK"), o.notes());
        assertEquals(2, o.calls(), "one wrong answer, one correction");
        assertTrue(o.requests().get(1).contains("Your previous reply was rejected"), "the model is told");
        assertTrue(o.requests().get(1).contains("not JSON") || o.requests().get(1).contains("not valid JSON"), "and why");
        assertEquals(1, o.events().stream().filter(e -> e.type().equals(TraceEvent.SCHEMA_VIOLATION)).count());
    }

    @Test
    void whenTheCorrectionDoesNotHelpTheStepFailsAndItsHandlerRuns() {
        Outcome o = run("Main", "not json at all", "still not json");

        assertEquals(2, o.calls(), "the first answer and exactly one correction when no retry is declared");
        assertTrue(o.notes().get(0).startsWith("failed: "), o.notes().toString());
        assertTrue(o.notes().get(0).contains("did not match the schema") || o.notes().get(0).contains("not JSON"), o.notes().get(0));
        assertEquals("verdict=", o.notes().get(1), "the workflow went on, but with nothing: not with the raw text as if it were the answer");
        assertNull(o.variables().get("review_result"));
    }

    @Test
    void aMissingFieldAWrongEnumWordAndAWrongTypeAreNamedByPath() {
        Outcome missing = run("Main", "{\"verdict\": \"OK\", \"items\": []}", "{\"verdict\": \"OK\", \"items\": []}");
        assertTrue(missing.notes().get(0).contains("advice: missing"), missing.notes().get(0));

        Outcome wrongWord = run("Main", "{\"verdict\": \"MAYBE\", \"advice\": \"x\", \"items\": []}", "{\"verdict\": \"MAYBE\", \"advice\": \"x\", \"items\": []}");
        assertTrue(wrongWord.notes().get(0).contains("verdict: \"MAYBE\" is not one of [OK, REWRITE]"), wrongWord.notes().get(0));

        String badItem = "{\"verdict\": \"OK\", \"advice\": \"x\", \"items\": [{\"name\": \"a\", \"n\": 1}, {\"name\": \"b\", \"n\": \"two\"}]}";
        Outcome nested = run("Main", badItem, badItem);
        assertTrue(nested.notes().get(0).contains("items.1.n: expected a number"), nested.notes().get(0));
    }

    @Test
    void anAnswerCutOffMidwayIsRecognisedAsUnfinished() {
        Outcome o = run("Main", "{\"verdict\": \"OK\", \"advice\": \"this was cut o", "{\"verdict\": \"OK\", \"advice\": \"x\", \"items\": []}");

        assertEquals(List.of("verdict=OK"), o.notes());
        assertTrue(o.requests().get(1).contains("cut off"), "the correction says the answer looked cut off");
    }

    @Test
    void extraFieldsAreIgnoredAndNumbersMayStandForText() {
        Outcome o = run("Main", "{\"verdict\": \"OK\", \"advice\": 7, \"items\": [], \"mood\": \"cheerful\"}");

        assertEquals(List.of("verdict=OK"), o.notes());
    }

    @Test
    void anAgentWithNoSchemaStillGivesBackWhateverItSaid() {
        assertEquals(List.of("free: a plain sentence, not JSON"), run("Free", "a plain sentence, not JSON").notes());
    }

    @Test
    void aStepsOwnExpectingSchemaIsEnforcedToo() {
        assertEquals(List.of("pick=B"), run("Step", "{\"pick\": \"C\"}", "{\"pick\": \"B\"}").notes());
        Outcome failed = run("Step", "{\"pick\": \"C\"}", "{\"pick\": \"D\"}");
        assertTrue(failed.notes().get(0).startsWith("step failed: "), failed.notes().toString());
    }
}
