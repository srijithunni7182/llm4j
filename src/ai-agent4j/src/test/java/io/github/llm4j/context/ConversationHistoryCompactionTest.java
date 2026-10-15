package io.github.llm4j.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.agent.memory.ConversationHistory;
import io.github.llm4j.agent.memory.InMemoryConversationStore;
import io.github.llm4j.model.Message;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A conversation history with a window in tokens, and a compactor to use when it is exceeded (design §4.8, R20). */
class ConversationHistoryCompactionTest {

    private static final long TOKENS_PER_MESSAGE = CompactionRequest.of(1).sizeOf(List.of(Message.user("m 00 " + "x".repeat(395)))); // about 110

    private static String text(int n) {
        return String.format("m %02d ", n) + "x".repeat(395);
    }

    private static void fill(ConversationHistory h, int from, int to) {
        for (int i = from; i < to; i++) {
            if (i % 2 == 0) h.addUserMessage(text(i));
            else h.addAssistantMessage(text(i));
        }
    }

    private static long size(List<Message> m) {
        return CompactionRequest.of(1).sizeOf(m);
    }

    @Test
    void withoutATokenWindowItBehavesAsItAlwaysDid() {
        ConversationHistory h = ConversationHistory.builder().maxMessages(5).build();
        fill(h, 0, 12);
        assertThat(h.getMessages()).hasSize(5);
        assertThat(h.getMessages().get(0).getContent()).startsWith("m 07");
    }

    @Test
    void theOldConstructorsStillWork() {
        ConversationHistory h = new ConversationHistory(3);
        fill(h, 0, 6);
        assertThat(h.getMessages()).hasSize(3);
        ConversationHistory none = new ConversationHistory(0); // always accepted: it keeps nothing
        fill(none, 0, 3);
        assertThat(none.getMessages()).isEmpty();
        InMemoryConversationStore store = new InMemoryConversationStore();
        ConversationHistory withStore = new ConversationHistory("s", store, 4);
        fill(withStore, 0, 6);
        assertThat(store.loadHistory("s", 10)).hasSize(6);
        assertThat(new ConversationHistory("s", store, 4).getMessages()).hasSize(4);
    }

    @Test
    void aTokenWindowSlidesTheOldestMessagesOutWithoutACompactor() {
        ConversationHistory h = ConversationHistory.builder().maxTokens(TOKENS_PER_MESSAGE * 4 + 10).build();
        fill(h, 0, 20);
        assertThat(size(h.getMessages())).isLessThanOrEqualTo(TOKENS_PER_MESSAGE * 4 + 10);
        assertThat(h.getMessages()).hasSize(4);
        assertThat(h.getMessages().get(3).getContent()).startsWith("m 19");
    }

    @Test
    void theLatestMessageIsKeptEvenWhenItAloneIsBiggerThanTheWindow() {
        ConversationHistory h = ConversationHistory.builder().maxTokens(50).build();
        h.addUserMessage("y".repeat(5_000));
        assertThat(h.getMessages()).hasSize(1);
        h.addAssistantMessage("short");
        assertThat(h.getMessages()).hasSize(1).first().satisfies(m -> assertThat(m.getContent()).isEqualTo("short"));
    }

    @Test
    void aCompactorIsUsedWhenTheWindowIsExceededAndTheOldestMessageIsNotSpecial() {
        List<CompactionResult> seen = new ArrayList<>();
        ConversationHistory h = ConversationHistory.builder().maxTokens(TOKENS_PER_MESSAGE * 8).keepRecent(2)
                .compaction(ContextCompactors.dropOldest()).onCompaction(seen::add).build();
        fill(h, 0, 30);
        assertThat(seen).isNotEmpty();
        assertThat(seen.get(0).strategy()).isEqualTo("drop_oldest");
        assertThat(size(h.getMessages())).isLessThanOrEqualTo(TOKENS_PER_MESSAGE * 8);
        assertThat(h.getMessages().get(0).getContent()).contains("earlier messages were removed"); // not the first user message: that is not protected here
        assertThat(h.getMessages().get(h.getMessages().size() - 1).getContent()).startsWith("m 29");
        assertThat(h.getMessages().get(h.getMessages().size() - 2).getContent()).startsWith("m 28");
    }

    @Test
    void itCompactsToWellBelowTheWindowSoThatItDoesNotHaveToDoItOnEveryMessage() {
        SummariserModel summariser = SummariserModel.saying("They talked about plans A and B; the price is 500.");
        ConversationHistory h = ConversationHistory.builder().maxTokens(TOKENS_PER_MESSAGE * 10).keepRecent(2)
                .compaction(ContextCompactors.summarize(summariser, 100)).build();
        fill(h, 0, 60);
        assertThat(summariser.requests.size()).isBetween(1, 12); // far fewer than the 50 or so messages added after the window filled
        assertThat(h.getFormattedHistory()).contains("They talked about plans A and B; the price is 500.");
        assertThat(size(h.getMessages())).isLessThanOrEqualTo(TOKENS_PER_MESSAGE * 10);
    }

    @Test
    void aCompactorThatThrowsNeverFailsAnAdd() {
        ContextCompactor boom = (m, r) -> {
            throw new IllegalStateException("bang");
        };
        ConversationHistory h = ConversationHistory.builder().maxTokens(TOKENS_PER_MESSAGE * 4).compaction(boom).build();
        fill(h, 0, 20);
        assertThat(size(h.getMessages())).isLessThanOrEqualTo(TOKENS_PER_MESSAGE * 4);
    }

    @Test
    void aCompactorThatGainsNothingFallsBackToSliding() {
        ContextCompactor nothing = (m, r) -> CompactionResult.of("nothing", m, m, r);
        ConversationHistory h = ConversationHistory.builder().maxTokens(TOKENS_PER_MESSAGE * 4).compaction(nothing).build();
        fill(h, 0, 20);
        assertThat(size(h.getMessages())).isLessThanOrEqualTo(TOKENS_PER_MESSAGE * 4);
        assertThat(h.getMessages().get(h.getMessages().size() - 1).getContent()).startsWith("m 19");
    }

    @Test
    void aPinnedMessageSurvivesCompaction() {
        ConversationHistory h = ConversationHistory.builder().maxTokens(TOKENS_PER_MESSAGE * 8).keepRecent(2).compaction(ContextCompactors.dropOldest()).build();
        h.addMessage(Messages.pinned(Message.user("My name is Asha and I prefer metric units.")));
        fill(h, 0, 40);
        assertThat(h.getMessages().stream().anyMatch(m -> m.getContent().contains("My name is Asha"))).isTrue();
    }

    @Test
    void theMessageCountLimitStillApplies() {
        ConversationHistory h = ConversationHistory.builder().maxMessages(6).maxTokens(TOKENS_PER_MESSAGE * 100).build();
        fill(h, 0, 20);
        assertThat(h.getMessages()).hasSize(6);
    }

    // ── what is kept in the store ──────────────────────────────────────────────────

    @Test
    void byDefaultTheStoreKeepsEverythingEvenThoughTheViewIsCompacted() {
        InMemoryConversationStore store = new InMemoryConversationStore();
        ConversationHistory h = ConversationHistory.builder().sessionId("s").store(store).maxTokens(TOKENS_PER_MESSAGE * 6).keepRecent(2)
                .compaction(ContextCompactors.dropOldest()).build();
        fill(h, 0, 30);
        assertThat(h.getMessages()).hasSizeLessThan(10);
        assertThat(store.loadHistory("s", 1_000)).hasSize(30); // the full record is never lost by accident
    }

    @Test
    void theStoreCanBeRewrittenWhenAskedTo() {
        InMemoryConversationStore store = new InMemoryConversationStore();
        ConversationHistory h = ConversationHistory.builder().sessionId("s").store(store).maxTokens(TOKENS_PER_MESSAGE * 6).keepRecent(2)
                .compaction(ContextCompactors.dropOldest()).persistCompacted(true).build();
        fill(h, 0, 30);
        List<Message> stored = store.loadHistory("s", 1_000);
        assertThat(stored).hasSizeLessThan(12);
        assertThat(stored).containsExactlyElementsOf(h.getMessages());
        // a new history on the same session starts from the compacted record, markers and all
        ConversationHistory again = ConversationHistory.builder().sessionId("s").store(store).maxTokens(TOKENS_PER_MESSAGE * 6).build();
        assertThat(again.getMessages()).containsExactlyElementsOf(stored);
    }

    @Test
    void historyLoadedFromAStoreIsBroughtWithinTheWindow() {
        InMemoryConversationStore store = new InMemoryConversationStore();
        for (int i = 0; i < 40; i++) store.saveMessage("s", i % 2 == 0 ? Message.user(text(i)) : Message.assistant(text(i)));
        ConversationHistory h = ConversationHistory.builder().sessionId("s").store(store).maxMessages(100).maxTokens(TOKENS_PER_MESSAGE * 5)
                .compaction(ContextCompactors.dropOldest()).keepRecent(2).build();
        assertThat(size(h.getMessages())).isLessThanOrEqualTo(TOKENS_PER_MESSAGE * 5);
        assertThat(h.getMessages().get(h.getMessages().size() - 1).getContent()).startsWith("m 39");
    }

    // ── set-up ─────────────────────────────────────────────────────────────────────

    @Test
    void theSettingsMustMakeSense() {
        assertThatThrownBy(() -> ConversationHistory.builder().maxTokens(0)).hasMessageContaining("maxTokens");
        assertThatThrownBy(() -> ConversationHistory.builder().maxMessages(0)).hasMessageContaining("maxMessages");
        assertThatThrownBy(() -> ConversationHistory.builder().keepRecent(-1)).hasMessageContaining("keepRecent");
        assertThatThrownBy(() -> ConversationHistory.builder().compactTo(0)).hasMessageContaining("compactTo");
        assertThatThrownBy(() -> ConversationHistory.builder().compactTo(1)).hasMessageContaining("compactTo");
        assertThatThrownBy(() -> ConversationHistory.builder().compaction(ContextCompactors.dropOldest()).build()).hasMessageContaining("maxTokens");
        assertThatThrownBy(() -> ConversationHistory.builder().persistCompacted(true).maxTokens(100).build()).hasMessageContaining("store");
    }

    @Test
    void clearEmptiesTheViewAndTheStore() {
        InMemoryConversationStore store = new InMemoryConversationStore();
        ConversationHistory h = ConversationHistory.builder().sessionId("s").store(store).maxTokens(1_000).build();
        fill(h, 0, 4);
        h.clear();
        assertThat(h.getMessages()).isEmpty();
        assertThat(store.loadHistory("s", 10)).isEmpty();
    }

    // ── an agent using it ──────────────────────────────────────────────────────────

    @Test
    void anAgentSendsTheCompactedHistoryNotTheWholeOfIt() {
        ConversationHistory h = ConversationHistory.builder().maxTokens(TOKENS_PER_MESSAGE * 6).keepRecent(2)
                .compaction(ContextCompactors.dropOldest()).build();
        fill(h, 0, 40);
        ScriptedModel model = ScriptedModel.of(true, false, ScriptedModel.answer("ok"));
        io.github.llm4j.agent.ReActAgent.builder().llmClient(model).addTool(new ScriptedModel.FixedTool("search", "x")).conversationHistory(h).build().run("And now?");
        String prompt = model.requests.get(0).getMessages().get(1).getContent();
        assertThat(prompt).contains("m 39").doesNotContain("m 05");
        assertThat(prompt).contains("earlier messages were removed");
    }
}
