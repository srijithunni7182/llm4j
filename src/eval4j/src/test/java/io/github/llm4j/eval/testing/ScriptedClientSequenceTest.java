package io.github.llm4j.eval.testing;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.Message;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScriptedClientSequenceTest {

    private static String ask(ScriptedClient c, String text) {
        return c.chat(LLMRequest.builder().messages(List.of(Message.user(text))).build()).getContent();
    }

    @Test
    void aSequenceAnswersInTurnAndTheLastAnswerRepeats() {
        ScriptedClient c = new ScriptedClient().whenSeenThen("Rewrite", "first", "second").otherwise("none");
        assertThat(ask(c, "Rewrite 1")).isEqualTo("first");
        assertThat(ask(c, "Rewrite 2")).isEqualTo("second");
        assertThat(ask(c, "Rewrite 3")).isEqualTo("second");
        assertThat(ask(c, "something else")).isEqualTo("none");
    }

    @Test
    void aSequenceNotReachedDoesNotAdvance() {
        ScriptedClient c = new ScriptedClient().whenSeenThen("X", "a", "b");
        ask(c, "no match");
        assertThat(ask(c, "X")).isEqualTo("a");
    }

    @Test
    void theCurrentTaskMatcherIgnoresWhatEarlierAgentsSaid() {
        ScriptedClient c = new ScriptedClient().whenTaskSeen("Round 2", "late").otherwise("early");
        String history = "Conversation History:\nAgent [A] Task: do Round 2 things Response: ok\n\nCurrent Task:\nRound 1 findings please";
        assertThat(ask(c, history)).as("'Round 2' is only in the history").isEqualTo("early");
        assertThat(ask(c, "Conversation History:\nx\n\nCurrent Task:\nRound 2 please")).isEqualTo("late");
        assertThat(ScriptedClient.currentTask("no marker here")).isEqualTo("no marker here");
    }
}
