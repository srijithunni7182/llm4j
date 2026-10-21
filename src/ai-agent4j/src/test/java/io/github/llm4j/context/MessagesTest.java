package io.github.llm4j.context;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.model.Message;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MessagesTest {

    @Test
    void aMessageCanBePinnedWhereItIsCreated() {
        Message plain = Message.user("hello");
        Message pinned = Messages.pinned(plain);
        assertThat(Messages.isPinned(plain)).isFalse();
        assertThat(Messages.isPinned(pinned)).isTrue();
        assertThat(pinned.getContent()).isEqualTo("hello");
        assertThat(pinned.getRole()).isEqualTo(Message.Role.USER);
    }

    @Test
    void pinningKeepsWhatTheMessageAlreadyCarried() {
        Message m =
                Message.builder()
                        .role(Message.Role.USER)
                        .content("x")
                        .providerData(Map.of("a", 1))
                        .build();
        assertThat(Messages.pinned(m).getProviderData())
                .containsEntry("a", 1)
                .containsEntry(Messages.PINNED, true);
    }

    @Test
    void readsTheCompactionMarker() {
        Message marker =
                Message.builder()
                        .role(Message.Role.USER)
                        .content("[3 earlier messages were removed to save space.]")
                        .providerData(
                                Map.of(Messages.COMPACTION, "drop_oldest", Messages.DROPPED, 3))
                        .build();
        assertThat(Messages.compactionOf(marker)).contains("drop_oldest");
        assertThat(Messages.dropped(marker)).isEqualTo(3);
        assertThat(Messages.compactionOf(Message.user("x"))).isEmpty();
        assertThat(Messages.dropped(Message.user("x"))).isZero();
    }

    @Test
    void aStrategyMarksWhatItChanges() {
        Message m = Messages.marked(Message.assistant("short"), "my_strategy");
        assertThat(Messages.compactionOf(m)).contains("my_strategy");
        assertThat(m.getContent()).isEqualTo("short");
    }
}
