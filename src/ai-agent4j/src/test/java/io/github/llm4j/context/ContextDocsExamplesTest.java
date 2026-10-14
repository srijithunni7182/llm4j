package io.github.llm4j.context;

import static org.assertj.core.api.Assertions.assertThat;

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
        Message budget = Messages.pinned(Message.user("The budget is 500 and must not change."));
        CompactionRequest request = CompactionRequest.builder(3_000)
                .keepRecent(6)                           // the last 6 messages are never touched
                .pinned(Messages::isPinned)              // nor is anything pinned
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
        assertThat(found).isGreaterThanOrEqualTo(8);
    }
}
