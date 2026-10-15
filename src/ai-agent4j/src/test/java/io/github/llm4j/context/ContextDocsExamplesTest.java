package io.github.llm4j.context;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.agent.AgentEventListener;
import io.github.llm4j.agent.ReActAgent;
import io.github.llm4j.agent.memory.ConversationHistory;
import io.github.llm4j.model.Message;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The code samples in wiki/Context-and-Compaction.md, compiled and run. Change a sample here and in the page together. */
class ContextDocsExamplesTest {

    @TempDir
    Path dir;

    private static List<Message> longConversation() {
        Convo c = new Convo().system("You are a research agent.").task("Compare the two plans.");
        for (int i = 0; i < 30; i++) c.step("search", 1_500);
        return c.build();
    }

    @Test
    void useAStrategyByHand() {
        List<Message> messages = longConversation();
        // --- sample: by hand
        CompactionResult result = ContextCompactors.dropOldest()
                .compact(messages, CompactionRequest.of(4_000));
        List<Message> smaller = result.messages();
        System.out.println(result.strategy() + ": " + result.tokensBefore() + " -> " + result.tokensAfter()
                + " tokens, " + result.droppedMessages() + " messages removed");
        // --- end sample
        assertThat(result.tokensAfter()).isLessThanOrEqualTo(4_000);
        assertThat(smaller).hasSizeLessThan(messages.size());
    }

    @Test
    void chainStrategies() {
        List<Message> messages = longConversation();
        // --- sample: chain
        ContextCompactor compactor = ContextCompactors.chain(
                ContextCompactors.trimToolResults(2_000),   // first shorten big tool results
                ContextCompactors.dropOldest());            // then drop the oldest exchanges if still too big
        CompactionResult result = compactor.compact(messages, CompactionRequest.of(3_000));
        // --- end sample
        assertThat(result.tokensAfter()).isLessThanOrEqualTo(3_000);
    }

    @Test
    void protectMessages() {
        // --- sample: protect
        Message budget = Messages.pinned(Message.user("The budget is 500 and must not change."));   // kept by every strategy
        CompactionRequest request = CompactionRequest.builder(3_000)
                .keepRecent(6)                                           // the last 6 messages are never touched
                .pinned(m -> m.getContent().startsWith("DECISION:"))     // nor is anything that matches a rule of your own
                .build();
        // --- end sample
        List<Message> messages = new java.util.ArrayList<>(longConversation());
        messages.add(2, budget);
        assertThat(ContextCompactors.dropOldest().compact(messages, request).messages()).contains(budget);
    }

    @Test
    void writeYourOwnAsALambda() {
        List<Message> messages = longConversation();
        // --- sample: lambda
        ContextCompactor keepTheTaskOnly = (msgs, request) ->
                CompactionResult.of("keep_task_only", msgs, msgs.subList(0, 2), request);
        // --- end sample
        assertThat(keepTheTaskOnly.compact(messages, CompactionRequest.of(100)).messages()).hasSize(2);
    }

    // --- sample: class
    /** Cuts long assistant thoughts to 300 characters. Tool calls, the task and recent messages are left alone. */
    static final class ShortenThoughts implements ContextCompactor {
        @Override
        public CompactionResult compact(List<Message> messages, CompactionRequest request) {
            int protectedFrom = Math.max(0, messages.size() - request.keepRecent());   // the last messages stay as they are
            List<Message> out = new ArrayList<>(messages);
            for (int i = 0; i < protectedFrom; i++) {
                Message m = messages.get(i);
                if (m.getRole() == Message.Role.ASSISTANT && !request.pinned().test(m) && m.getContent().length() > 300) {
                    String cut = m.getContent().substring(0, 300) + " …";
                    out.set(i, Messages.marked(m.withContent(cut), name()));   // mark what you change
                }
            }
            return CompactionResult.of(name(), messages, out, request);          // a result that is not smaller comes back as NO_GAIN
        }

        @Override
        public String name() {
            return "shorten_thoughts";
        }
    }
    // --- end sample

    @Test
    void theClassSampleWorks() {
        List<Message> in = new Convo().system("s").task("t")
                .add(Message.assistant("x".repeat(2_000))).step("a", 100).step("b", 100).step("c", 100).step("d", 100).build();
        CompactionResult r = new ShortenThoughts().compact(in, CompactionRequest.builder(1).keepRecent(2).build());
        assertThat(r.outcome()).isEqualTo(CompactionResult.Outcome.COMPACTED);
        assertThat(r.messages().get(2).getContent()).hasSize(302);
        assertThat(r.messages().subList(r.messages().size() - 2, r.messages().size())).isEqualTo(in.subList(in.size() - 2, in.size()));
        assertThat(r.strategy()).isEqualTo("shorten_thoughts");
    }

    @Test
    void findByName() {
        // --- sample: by name
        ContextCompactor c = ContextCompactors.byName("trim_tool_results", Map.of("max_chars", "1000"));
        ContextCompactor chain = ContextCompactors.byName("chain",
                Map.of("strategies", "trim_tool_results, drop_oldest"));
        // --- end sample
        assertThat(c.name()).isEqualTo("trim_tool_results");
        assertThat(chain.name()).startsWith("chain(");
    }

    @Test
    void knowTheWindow() throws Exception {
        Path windowsFile = dir.resolve("windows.properties");
        Files.writeString(windowsFile, "# model = window [/ tokens kept for the reply]\nacme/big-model = 200000 / 8192\nmy-local-model = 32000\n");
        // --- sample: windows
        ContextWindows windows = ContextWindows.load(windowsFile)
                .with("acme/other-model", ContextWindow.of(128_000).reservingForOutput(4_096));
        ContextWindow window = windows.window("acme/big-model").orElseThrow();   // usableInput() = 191,808
        // --- end sample
        assertThat(window.usableInput()).isEqualTo(191_808);
        assertThat(windows.window("acme/other-model")).isPresent();
    }

    @Test
    void aPolicy() {
        // --- sample: policy
        ContextPolicy policy = ContextPolicy.builder()
                .window(ContextWindow.of(200_000).reservingForOutput(8_000))
                .triggerAt(0.75)       // compact when the next prompt would pass 75 % of the usable window
                .targetAt(0.40)        // and bring it down to about 40 %
                .keepRecent(6)
                .maxCompactions(8)
                .build();
        // --- end sample
        assertThat(policy.triggerTokens()).hasValue(144_000L);
        assertThat(policy.targetTokens()).hasValue(76_800L);
    }

    @Test
    void useItInAnAgent() {
        io.github.llm4j.LLMClient client = ReActAgentCompactionNativeTest.looping(9);
        io.github.llm4j.agent.Tool search = ReActAgentCompactionNativeTest.search(1_600);
        // --- sample: agent
        ReActAgent agent = ReActAgent.builder()
                .llmClient(client)
                .addTool(search)
                .contextWindow(4_000)          // or: .budgetModel("acme/big-model").contextWindows(windows)
                .compaction(ContextCompactors.chain(
                        ContextCompactors.trimToolResults(2_000),
                        ContextCompactors.dropOldest()))
                .build();
        // --- end sample
        assertThat(agent.run("Find everything.").getFinalAnswer()).isEqualTo("done after 9");
    }

    @Test
    void watchWhatItDoes() {
        io.github.llm4j.LLMClient client = ReActAgentCompactionNativeTest.looping(9);
        io.github.llm4j.agent.Tool search = ReActAgentCompactionNativeTest.search(1_600);
        java.util.List<String> lines = new ArrayList<>();
        // --- sample: listener
        AgentEventListener listener = new AgentEventListener() {
            @Override public void onThought(String thought) {}
            @Override public void onAction(String tool, String input) {}
            @Override public void onObservation(String observation) {}

            @Override
            public void onContextUsage(ContextUsage usage) {
                usage.fraction().ifPresent(f -> lines.add(String.format("context %.0f%% full (%s)", f * 100, usage.source())));
            }

            @Override
            public void onCompaction(CompactionEvent e) {
                lines.add(e.result().strategy() + " " + e.result().outcome() + ": " + e.result().tokensBefore()
                        + " -> " + e.result().tokensAfter() + " tokens" + (e.failure() != null ? " (" + e.failure() + ")" : ""));
            }
        };
        // --- end sample
        ReActAgent.builder().llmClient(client).addTool(search).contextWindow(4_000).compaction(ContextCompactors.dropOldest())
                .addListener(listener).build().run("Find everything.");
        assertThat(lines).anyMatch(l -> l.startsWith("context")).anyMatch(l -> l.startsWith("drop_oldest COMPACTED"));
    }

    /** The scratchpad the text-protocol model reads after a compaction, from a real run; the page shows it verbatim. */
    static String textProtocolExample() {
        ScriptedModel model = ScriptedModel.by(false, true, n -> n < 6
                ? ScriptedModel.textStep("Look at page " + n + ".", "fetch", "{\"page\": " + n + "}")
                : ScriptedModel.textAnswer("done"));
        io.github.llm4j.agent.Tool fetch = new ScriptedModel.FixedTool("fetch", n -> "page " + n + " says " + "blah ".repeat(12).strip());
        ContextPolicy policy = ContextPolicy.builder().window(ContextWindow.of(520)).triggerAt(0.8).targetAt(0.6).keepRecent(2).build();
        ReActAgent.builder().llmClient(model).addTool(fetch).contextPolicy(policy).compaction(ContextCompactors.dropOldest()).build().run("Summarise the pages.");
        for (io.github.llm4j.model.LLMRequest r : model.requests) {
            String prompt = r.getMessages().get(r.getMessages().size() - 1).getContent();
            if (prompt.contains("Earlier steps")) {
                try {
                    Files.writeString(Path.of("target/text-protocol-example.txt"), prompt.strip());
                } catch (java.io.IOException ignored) {
                    // only a convenience for whoever updates the page
                }
                return prompt.strip();
            }
        }
        throw new AssertionError("the example run never compacted; first request was " + ReActAgentCompactionNativeTest.size(model.requests.get(0)) + " tokens, last " + ReActAgentCompactionNativeTest.size(model.requests.get(model.requests.size() - 1)));
    }

    @Test
    void thePageShowsWhatTheTextProtocolModelReads() throws Exception {
        String example = textProtocolExample();
        String page = Files.readString(Path.of("wiki/Context-and-Compaction.md"));
        assertThat(page).as("the page shows this text-protocol prompt:\n" + example).contains(example);
    }

    @Test
    void summarise() {
        io.github.llm4j.LLMClient cheapClient = SummariserModel.saying("Notes: looked at 20 pages; the price is 500.");
        io.github.llm4j.LLMClient client = ReActAgentCompactionNativeTest.looping(30);
        io.github.llm4j.agent.Tool search = ReActAgentCompactionNativeTest.search(1_200);
        // --- sample: summarize
        ContextCompactor compactor = ContextCompactors.chain(
                ContextCompactors.trimToolResults(2_000),   // shorten big results first: free
                ContextCompactors.summarize(cheapClient),   // then have a (cheaper) model write notes on the oldest steps
                ContextCompactors.dropOldest());            // and if that is not enough, drop the oldest
        // --- end sample
        ReActAgent agent = ReActAgent.builder().llmClient(client).addTool(search).contextWindow(4_000).compaction(compactor).maxIterations(100).build();
        io.github.llm4j.agent.AgentResult r = agent.run("Find everything.");
        assertThat(r.getFinalAnswer()).isEqualTo("done after 30");
        assertThat(r.getUsage().getLlmCalls()).isGreaterThan(31); // the summaries are counted
    }

    @Test
    void handleAPromptThatCannotFit() {
        io.github.llm4j.LLMClient client = ReActAgentCompactionNativeTest.looping(5);
        io.github.llm4j.agent.Tool search = ReActAgentCompactionNativeTest.search(400);
        ReActAgent agent = ReActAgent.builder().llmClient(client).addTool(search).contextWindow(40).build();
        String question = "Find everything.";
        String outcome;
        // --- sample: overflow
        try {
            outcome = agent.run(question).getFinalAnswer();
        } catch (io.github.llm4j.exception.ContextOverflowException e) {
            // the prompt could not be made to fit the model's window, even after compaction and one retry
            outcome = "too long: " + e.getMessage();
        }
        // --- end sample
        assertThat(outcome).startsWith("too long: the prompt").contains("cannot fit");
    }

    @Test
    void aHistoryWithAWindowInTokens() {
        io.github.llm4j.LLMClient cheapClient = SummariserModel.saying("They compared plans A and B; the price is 500.");
        io.github.llm4j.LLMClient client = ScriptedModel.of(true, false, ScriptedModel.answer("ok"));
        // --- sample: history
        ConversationHistory history = ConversationHistory.builder()
                .maxTokens(8_000)                                       // how much of the past the agent is shown
                .compaction(ContextCompactors.summarize(cheapClient))   // summarise the oldest part instead of just forgetting it
                .keepRecent(4)                                          // the last 4 messages are never touched
                .build();
        ReActAgent agent = ReActAgent.builder().llmClient(client).conversationHistory(history).build();
        // --- end sample
        for (int i = 0; i < 60; i++) {
            if (i % 2 == 0) history.addUserMessage("question " + i + " " + "x".repeat(900));
            else history.addAssistantMessage("answer " + i + " " + "y".repeat(900));
        }
        assertThat(agent.run("And now?").getFinalAnswer()).isEqualTo("ok");
        assertThat(history.getFormattedHistory()).contains("They compared plans A and B");
        assertThat(CompactionRequest.of(1).sizeOf(history.getMessages())).isLessThanOrEqualTo(8_000);
    }

    @Test
    void theSeededWindows() {
        io.github.llm4j.LLMClient client = ReActAgentCompactionNativeTest.looping(3);
        // --- sample: defaults
        ReActAgent agent = ReActAgent.builder()
                .llmClient(client)
                .budgetModel("claude-opus-5-5")                   // the model name your prices already use
                .contextWindows(ContextWindows.defaults())        // the short list that ships, checked against the provider's documentation
                .compaction(ContextCompactors.dropOldest())
                .build();
        // --- end sample
        assertThat(ContextWindows.defaults().window("claude-opus-5-5").orElseThrow().usableInput()).isEqualTo(872_000);
        assertThat(agent).isNotNull();
    }

    @Test
    void thePageShowsExactlyTheSamplesThatAreTested() throws Exception {
        String source = Files.readString(Path.of("src/test/java/io/github/llm4j/context/ContextDocsExamplesTest.java"));
        String page = Files.readString(Path.of("wiki/Context-and-Compaction.md"));
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("// --- sample: ([^\\n]+)\\n(.*?)\\n\\s*// --- end sample", java.util.regex.Pattern.DOTALL).matcher(source);
        int found = 0;
        while (m.find()) {
            found++;
            String sample = m.group(2).stripIndent().strip();
            assertThat(page).as("the page shows the '" + m.group(1) + "' sample").contains(sample);
        }
        assertThat(found).isGreaterThanOrEqualTo(14);
    }
}
