package io.github.llm4j.loom.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.a2a.ChannelResponse;
import io.github.llm4j.a2a.testkit.MockA2AAgent;
import io.github.llm4j.a2a.testkit.MockA2AAgent.Script;
import io.github.llm4j.a2a.testkit.MockA2AAgent.Step;
import io.github.llm4j.loom.a2a.LoopbackTransport;
import io.github.llm4j.loom.generic.support.ScriptedRun;
import io.github.llm4j.loom.runtime.RunJournal;
import io.github.llm4j.loom.runtime.RunSuspended;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs the canonical A2A scenario of the Loom Conformance Test Kit ({@code loom/ctk}) through this
 * runtime against the mock agent: a call that completes, a call whose remote asks a question that
 * the channel answers, and a card that names another host. The scenario is in terms of the
 * observable result (the bound text, the messages sent, the questions asked, the kinds of trace
 * events), so another runtime can run the same files.
 */
class A2ACtkConformanceTest {

    private static final Path CTK = Path.of("..", "ctk").toAbsolutePath().normalize();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PUBLIC = "https://agent.example.test";
    private static final int MOST_RESUMES = 10;

    @TempDir Path dir;

    private static Step step(JsonNode node) {
        String text = node.path("text").asText("");
        return switch (node.get("state").asText()) {
            case "working" -> Step.working();
            case "input_required" -> Step.asks(text);
            default -> Step.completedWithText(text);
        };
    }

    @Test
    void theOutcomeMatchesTheCanonicalOneForEveryCase() throws Exception {
        JsonNode mocks = JSON.readTree(Files.readString(CTK.resolve("mocks/a2a_call.json")));
        JsonNode expected = JSON.readTree(Files.readString(CTK.resolve("traces/a2a_call.json"))).get("cases");
        String source = Files.readString(CTK.resolve("scripts/a2a_call.loom"));

        for (String name : List.of("complete", "question", "card_elsewhere")) {
            JsonNode mockCase = mocks.get(name);
            JsonNode want = expected.get(name);
            try (MockA2AAgent mock = MockA2AAgent.start(PUBLIC)) {
                if ("card_elsewhere".equals(mockCase.path("mode").asText())) {
                    mock.mode(MockA2AAgent.Mode.CARD_ELSEWHERE);
                } else {
                    List<Step> steps = new ArrayList<>();
                    mockCase.get("steps").forEach(s -> steps.add(step(s)));
                    mock.script(Script.of(steps.toArray(new Step[0])));
                }
                List<String> questions = new ArrayList<>();
                Set<String> kinds = new LinkedHashSet<>();
                RunJournal journal = RunJournal.inMemory();
                LoopbackTransport transport = new LoopbackTransport(PUBLIC, mock.loopbackBase());
                String outcome = "failed";
                Object quote = null;
                for (int i = 0; i < MOST_RESUMES; i++) {
                    ScriptedRun run = new ScriptedRun(dir);
                    run.journal = journal;
                    HarnessExecutor e = run.executor(source);
                    e.setA2ATransport(transport);
                    e.setAgentChannel("Desk", (context, message) -> {
                        questions.add(message.toString());
                        return new ChannelResponse.Reply(mockCase.path("channelReply").asText("ok"), null);
                    });
                    e.setClock(Clock.fixed(Instant.parse("2026-11-07T10:00:00Z"), ZoneOffset.UTC));
                    e.addTraceListener(t -> {
                        if (t.type().equals(TraceEvent.REMOTE) || t.type().equals(TraceEvent.EXCHANGE)) {
                            String kind = String.valueOf(t.data().get("kind"));
                            if (!kind.equals("state")) kinds.add(t.type() + ":" + kind);
                        }
                    });
                    try {
                        e.executeWorkflow("main", new java.util.HashMap<>(Map.of("part", "12")));
                        outcome = "completed";
                        quote = e.getContext().getAll().get("quote");
                        break;
                    } catch (RunSuspended suspended) {
                        // the host's job: run it again when the wake time comes
                    } catch (RuntimeException failure) {
                        break;
                    }
                }
                assertEquals(want.get("outcome").asText(), outcome, name);
                if (want.has("quote")) assertEquals(want.get("quote").asText(), quote, name);
                assertEquals(want.get("messagesSent").asInt(), mock.receivedMessageIds().size(), name);
                assertEquals(want.get("questionsAsked").asInt(), questions.size(), name);
                Set<String> wantKinds = new LinkedHashSet<>();
                want.get("traceKinds").forEach(k -> wantKinds.add(k.asText()));
                assertTrue(kinds.containsAll(wantKinds), name + ": trace kinds " + kinds + " should hold " + wantKinds);
                assertTrue(mock.selfCheckFailures().isEmpty(), name + ": every message the runtime sent was valid A2A");
            }
        }
    }
}
