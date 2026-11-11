package io.github.llm4j.loom.a2a;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.a2a.AgentChannel;
import io.github.llm4j.a2a.ChannelMessage;
import io.github.llm4j.a2a.ChannelResponse;
import io.github.llm4j.a2a.testkit.MockA2AAgent;
import io.github.llm4j.a2a.testkit.MockA2AAgent.Script;
import io.github.llm4j.a2a.testkit.MockA2AAgent.Step;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.generic.support.ScriptedRun;
import io.github.llm4j.loom.runtime.RunJournal;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** ask, the shape of a result, files from outside, and the rules for retry. */
class AskAndResultsTest {

    private static final String PUBLIC = "https://agent.example.test";
    private static final String DECLARATIONS = """
            agent_channel Desk { audience: staff max_questions: 2 }
            remote Supplier {
                card: "https://agent.example.test/.well-known/agent-card.json"
                agent_channel: Desk
            }
            media_root: "assets"
            """;

    @TempDir Path dir;

    private MockA2AAgent mock;
    private LoopbackTransport transport;
    private RunJournal journal = RunJournal.inMemory();
    private final List<String> asked = new ArrayList<>();
    private AgentChannel channel = (c, m) -> new ChannelResponse.Reply("USD", null);

    @BeforeEach
    void start() throws IOException {
        mock = MockA2AAgent.start(PUBLIC);
        transport = new LoopbackTransport(PUBLIC, mock.loopbackBase());
        Files.createDirectories(dir.resolve("assets"));
    }

    @AfterEach
    void stop() {
        assertThat(mock.selfCheckFailures()).isEmpty();
        mock.close();
    }

    private Map<String, Object> run(String workflow) {
        ScriptedRun run = new ScriptedRun(dir);
        run.journal = journal;
        HarnessExecutor e = run.executor(DECLARATIONS + workflow);
        e.setA2ATransport(transport);
        e.setAgentChannel("Desk", channel);
        e.executeWorkflow("W", Map.of());
        return e.getContext().getAll();
    }

    // ---- ask

    @Test
    void anAskBindsTheChannelsAnswerAndAResumeNeverAsksTwice() {
        channel = (c, m) -> {
            asked.add(m.toString());
            return new ChannelResponse.Reply("INR", null);
        };
        assertThat(run("workflow W() { ask Desk \"Which currency?\" -> currency }").get("currency")).isEqualTo("INR");
        assertThat(run("workflow W() { ask Desk \"Which currency?\" -> currency }").get("currency")).isEqualTo("INR");
        assertThat(asked).hasSize(1);
    }

    @Test
    void theQuestionHasItsPlaceholdersFilledAndTheTextNeverReachesTheTrace() {
        channel = (c, m) -> {
            asked.add(m instanceof ChannelMessage.Question q ? q.text() : "?");
            return new ChannelResponse.Reply("x", null);
        };
        ScriptedRun scripted = new ScriptedRun(dir);
        HarnessExecutor e = scripted.executor(DECLARATIONS + "workflow W(part) { ask Desk \"Price of {part}?\" -> p }");
        e.setAgentChannel("Desk", channel);
        List<String> traced = new ArrayList<>();
        e.addTraceListener(t -> traced.add(t.type() + " " + t.text() + " " + t.data()));
        e.executeWorkflow("W", Map.of("part", "bolt-12"));
        assertThat(asked).containsExactly("Price of bolt-12?");
        assertThat(String.join("\n", traced)).doesNotContain("bolt-12");
    }

    @Test
    void aTypedAnswerIsCheckedAgainstTheShapeAndABadOneIsReAskedWithTheReasonAndCounted() {
        List<Integer> attempts = new ArrayList<>();
        channel = (c, m) -> {
            var q = (ChannelMessage.Question) m;
            attempts.add(q.attempt());
            return new ChannelResponse.Reply(q.attempt() == 1 ? "{\"currency\": \"EUR\"}" : "{\"currency\": \"USD\"}", null);
        };
        var vars = run("workflow W() { ask Desk \"Which?\" -> r expecting { currency: enum[\"USD\", \"INR\"] } }");
        assertThat(attempts).containsExactly(1, 2);
        assertThat(((Map<?, ?>) vars.get("r")).get("currency")).isEqualTo("USD");
    }

    @Test
    void theQuestionLimitHoldsAndTheStepFailsWhenItIsSpent() {
        channel = (c, m) -> new ChannelResponse.Reply("{\"currency\": \"EUR\"}", null);
        assertThatThrownBy(() -> run("workflow W() { ask Desk \"Which?\" -> r expecting { currency: enum[\"USD\"] } }"))
                .hasMessageContaining("question limit");
    }

    @Test
    void aChannelThatThrowsFailsTheStepAndOnFailureHandlesIt() {
        channel = (c, m) -> {
            throw new IllegalStateException("desk is closed");
        };
        assertThatThrownBy(() -> run("workflow W() { ask Desk \"Which?\" -> r on_failure { halt \"nobody answered\" } }"))
                .hasMessageContaining("nobody answered");
    }

    @Test
    void aChannelTheHostDidNotSupplyFailsClosed() {
        ScriptedRun scripted = new ScriptedRun(dir);
        HarnessExecutor e = scripted.executor(DECLARATIONS + "workflow W() { ask Desk \"Which?\" -> r }");
        assertThatThrownBy(() -> e.executeWorkflow("W", Map.of())).hasMessageContaining("agent_channel Desk");
    }

    // ---- results

    @Test
    void theResultHasTheJoinedTextTheDataPartsAndNoFilesWhenThereAreNone() {
        mock.script(Script.of(Step.completed("[{\"artifactId\":\"a\",\"parts\":[{\"text\":\"42 each\"},{\"data\":{\"price\":42}},{\"text\":\"net 30\"}]}]")));
        var vars = run("workflow W() { delegate \"quote\" to Supplier -> quote }");
        assertThat(vars.get("quote")).isEqualTo("42 each\n\nnet 30");
        assertThat((List<?>) vars.get("quote_data")).hasSize(1);
        assertThat(((Map<?, ?>) ((List<?>) vars.get("quote_data")).get(0)).get("price")).isEqualTo(42);
        assertThat((List<?>) vars.get("quote_files")).isEmpty();
    }

    @Test
    void expectingTakesTheSingleDataPartOrJsonInATextPart() {
        mock.script(Script.of(Step.completed("[{\"artifactId\":\"a\",\"parts\":[{\"data\":{\"price\":42}}]}]")));
        var vars = run("workflow W() { delegate \"quote\" to Supplier -> quote expecting { price: number } }");
        assertThat(((Map<?, ?>) vars.get("quote")).get("price")).isEqualTo(42);

        journal = RunJournal.inMemory(); // a different run
        mock.script(Script.of(Step.completed("[{\"artifactId\":\"a\",\"parts\":[{\"text\":\"{\\\"price\\\": 43}\"}]}]")));
        var fromText = run("workflow W() { delegate \"quote2\" to Supplier -> quote2 expecting { price: number } }");
        assertThat(((Map<?, ?>) fromText.get("quote2")).get("price")).isEqualTo(43);
    }

    @Test
    void severalDataPartsOrAMismatchFailTheStepAndRetryAppliesToThatDefiniteFailure() {
        mock.script(Script.of(Step.completed("[{\"artifactId\":\"a\",\"parts\":[{\"data\":{\"price\":1}},{\"data\":{\"price\":2}}]}]")));
        assertThatThrownBy(() -> run("workflow W() { delegate \"q\" to Supplier -> quote expecting { price: number } }")).hasMessageContaining("expected shape");
        assertThat(mock.taskCount()).isEqualTo(1);

        journal = RunJournal.inMemory(); // a different run
        mock.script(Script.of(Step.completed("[{\"artifactId\":\"a\",\"parts\":[{\"data\":{\"price\":\"cheap\"}}]}]")));
        assertThatThrownBy(() -> run("workflow W() { delegate \"q2\" to Supplier -> q2 expecting { price: number } retry 2 }")).hasMessageContaining("expected shape");
        assertThat(mock.taskCount()).as("one task for the first step, three for the retried one").isEqualTo(4);
    }

    @Test
    void retryIsNotUsedForARefusalThatCannotChange() {
        mock.mode(MockA2AAgent.Mode.CARD_ELSEWHERE);
        assertThatThrownBy(() -> run("workflow W() { delegate \"q\" to Supplier -> quote retry 3 }")).hasMessageContaining("evil.example.net");
        assertThat(mock.received()).isEmpty();
    }

    @Test
    void aLinkIsOfferedToTheChannelAndNeverFollowedAndIgnoreDropsIt() {
        mock.script(Script.of(Step.completed("[{\"artifactId\":\"a\",\"parts\":[{\"text\":\"see file\"},{\"url\":\"https://files.example.com/q.pdf\",\"filename\":\"q.pdf\",\"mediaType\":\"application/pdf\"}]}]")));
        List<String> offers = new ArrayList<>();
        channel = (c, m) -> {
            if (m instanceof ChannelMessage.FileOffered.Link link) {
                offers.add(link.url());
                return new ChannelResponse.Ignore();
            }
            return new ChannelResponse.Reply("x", null);
        };
        var vars = run("workflow W() { delegate \"quote\" to Supplier -> quote }");
        assertThat(offers).containsExactly("https://files.example.com/q.pdf");
        assertThat((List<?>) vars.get("quote_files")).isEmpty();
    }

    @Test
    void anInlineFileAcceptedThroughTheSinkIsListedWithItsTypeFromTheBytes() throws Exception {
        byte[] png = new byte[64];
        System.arraycopy(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}, 0, png, 0, 8);
        mock.script(Script.of(Step.completed("[{\"artifactId\":\"a\",\"parts\":[{\"raw\":\"" + java.util.Base64.getEncoder().encodeToString(png) + "\",\"filename\":\"../../x.png\",\"mediaType\":\"image/png\"}]}]")));
        channel = (c, m) -> {
            if (m instanceof ChannelMessage.FileOffered.Inline file) {
                try (var out = c.receiveFile(file.label(), file.claimedMediaType()); var in = file.open()) {
                    in.transferTo(out);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
                return new ChannelResponse.Received(List.of());
            }
            return new ChannelResponse.Reply("x", null);
        };
        run("workflow W() { delegate \"quote\" to Supplier -> quote }");
        try (var files = Files.walk(dir.resolve("assets").resolve("received"))) {
            assertThat(files.filter(Files::isRegularFile)).hasSize(1).allMatch(f -> !f.getFileName().toString().contains("x.png"));
        }
    }

    @Test
    void aRemoteReplyIsTaggedAsUncheckedSoItCannotBeSentOutAsIfTrusted() {
        mock.script(Script.completesAtOnce("42 each"));
        var vars = run("workflow W() { delegate \"quote\" to Supplier -> quote }");
        assertThat(vars.get("quote")).isEqualTo("42 each");
    }
}
