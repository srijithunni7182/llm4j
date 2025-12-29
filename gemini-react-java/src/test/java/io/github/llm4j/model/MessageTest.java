package io.github.llm4j.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class MessageTest {

    @Test
    void testMessageBuilder() {
        Message message = Message.builder()
                .role(Message.Role.USER)
                .content("Hello, world!")
                .name("testUser")
                .build();

        assertThat(message.getRole()).isEqualTo(Message.Role.USER);
        assertThat(message.getContent()).isEqualTo("Hello, world!");
        assertThat(message.getName()).isEqualTo("testUser");
    }

    @Test
    void testFactoryMethods() {
        Message systemMsg = Message.system("System prompt");
        Message userMsg = Message.user("User message");
        Message assistantMsg = Message.assistant("Assistant message");

        assertThat(systemMsg.getRole()).isEqualTo(Message.Role.SYSTEM);
        assertThat(systemMsg.getContent()).isEqualTo("System prompt");

        assertThat(userMsg.getRole()).isEqualTo(Message.Role.USER);
        assertThat(userMsg.getContent()).isEqualTo("User message");

        assertThat(assistantMsg.getRole()).isEqualTo(Message.Role.ASSISTANT);
        assertThat(assistantMsg.getContent()).isEqualTo("Assistant message");
    }

    @Test
    void testNullRoleThrows() {
        assertThatThrownBy(() -> Message.builder().content("test").build()).isInstanceOf(NullPointerException.class);
    }

    @Test
    void testNullContentThrows() {
        assertThatThrownBy(() -> Message.builder().role(Message.Role.USER).build())
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void testEquality() {
        Message msg1 = Message.user("Hello");
        Message msg2 = Message.user("Hello");
        Message msg3 = Message.user("Goodbye");

        assertThat(msg1).isEqualTo(msg2);
        assertThat(msg1).isNotEqualTo(msg3);
        assertThat(msg1.hashCode()).isEqualTo(msg2.hashCode());
    }

    @Test
    void testRoleFromValue() {
        assertThat(Message.Role.fromValue("system")).isEqualTo(Message.Role.SYSTEM);
        assertThat(Message.Role.fromValue("user")).isEqualTo(Message.Role.USER);
        assertThat(Message.Role.fromValue("assistant")).isEqualTo(Message.Role.ASSISTANT);
    }

    @Test
    void testInvalidRoleThrows() {
        assertThatThrownBy(() -> Message.Role.fromValue("invalid")).isInstanceOf(IllegalArgumentException.class);
    }
}
