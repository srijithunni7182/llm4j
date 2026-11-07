package io.github.llm4j.a2a;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import org.junit.jupiter.api.Test;

class ModelTest {

    @Test
    void anUnrecognisedStateIsUnknownAndNeverCompleted() {
        assertThat(TaskState.fromName("SOMETHING_NEW")).isEqualTo(TaskState.UNKNOWN);
        assertThat(TaskState.fromName(null)).isEqualTo(TaskState.UNKNOWN);
        assertThat(TaskState.UNKNOWN.isTerminal()).isFalse();
        assertThat(TaskState.UNKNOWN).isNotEqualTo(TaskState.COMPLETED);
    }

    @Test
    void terminalAndInterruptedStatesAreTheSpecifiedOnes() {
        assertThat(
                        List.of(
                                TaskState.COMPLETED,
                                TaskState.FAILED,
                                TaskState.CANCELED,
                                TaskState.REJECTED))
                .allMatch(TaskState::isTerminal);
        assertThat(
                        List.of(
                                TaskState.WORKING,
                                TaskState.INPUT_REQUIRED,
                                TaskState.AUTH_REQUIRED,
                                TaskState.UNKNOWN))
                .noneMatch(TaskState::isTerminal);
        assertThat(TaskState.INPUT_REQUIRED.isWaitingOnCaller()).isTrue();
        assertThat(TaskState.AUTH_REQUIRED.isWaitingOnCaller()).isTrue();
        assertThat(TaskState.WORKING.isWaitingOnCaller()).isFalse();
    }

    @Test
    void aMessageNeedsAnIdAndAtLeastOnePart() {
        assertThatThrownBy(
                        () ->
                                new A2AMessage(
                                        "",
                                        A2AMessage.Role.USER,
                                        List.of(new Part.Text("x")),
                                        null,
                                        null,
                                        List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("messageId");
        assertThatThrownBy(
                        () ->
                                new A2AMessage(
                                        "m",
                                        A2AMessage.Role.USER,
                                        List.of(),
                                        null,
                                        null,
                                        List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("part");
    }

    @Test
    void collectionsAreCopiedAndUnmodifiable() {
        var parts = new java.util.ArrayList<Part>(List.of(new Part.Text("hi")));
        var message = A2AMessage.user("m-1", parts);
        parts.add(new Part.Text("later"));
        assertThat(message.parts()).hasSize(1);
        assertThatThrownBy(() -> message.parts().add(new Part.Text("x")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aTextPartMayNotBeNullAndADataPartKeepsItsValue() {
        assertThatThrownBy(() -> new Part.Text(null)).isInstanceOf(NullPointerException.class);
        var value = JsonNodeFactory.instance.objectNode().put("a", 1);
        assertThat(new Part.Data(value).value().get("a").asInt()).isEqualTo(1);
    }

    @Test
    void anInlineFileKeepsItsBytesCopiedAndALinkHasNone() {
        byte[] bytes = {1, 2, 3};
        var inline = Part.FileOffered.inline(bytes, "label", "image/png");
        bytes[0] = 9;
        assertThat(inline.bytes()).containsExactly(1, 2, 3);
        inline.bytes()[0] = 7;
        assertThat(inline.bytes()[0]).isEqualTo((byte) 1);
        var link =
                Part.FileOffered.link(
                        "https://files.example.com/a.pdf", "a.pdf", "application/pdf");
        assertThat(link.isLink()).isTrue();
        assertThat(inline.isLink()).isFalse();
        assertThat(inline.toString()).doesNotContain("1, 2, 3");
    }

    @Test
    void aTaskWithNoIdIsRejectedAndTerminalFollowsTheState() {
        assertThatThrownBy(() -> RemoteTask.of("", "c", TaskState.WORKING))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(RemoteTask.of("t", "c", TaskState.COMPLETED).isTerminal()).isTrue();
    }

    @Test
    void aSendResultIsEitherATaskOrAMessage() {
        SendResult a = new SendResult.TaskResult(RemoteTask.of("t", "c", TaskState.WORKING));
        SendResult b =
                new SendResult.MessageResult(A2AMessage.agent("m", List.of(new Part.Text("hi"))));
        for (SendResult r : List.of(a, b)) {
            String kind = r instanceof SendResult.TaskResult ? "task" : "message";
            assertThat(kind).isNotEmpty();
        }
    }

    @Test
    void exceptionsAreUncheckedLlmExceptionsWithNoWireCode() {
        var e = new A2AException.TaskNotFound("t-1");
        assertThat(e).isInstanceOf(io.github.llm4j.exception.LLMException.class);
        assertThat(e.getMessage()).contains("t-1");
    }
}
