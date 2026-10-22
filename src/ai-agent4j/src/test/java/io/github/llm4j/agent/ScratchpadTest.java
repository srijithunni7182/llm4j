package io.github.llm4j.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.context.CompactionRequest;
import io.github.llm4j.context.CompactionResult;
import io.github.llm4j.context.ContextCompactors;
import io.github.llm4j.context.Messages;
import io.github.llm4j.model.Message;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The text protocol's scratchpad keeps its turns as messages so they can be compacted, and renders
 * exactly as the old string did (R17).
 */
class ScratchpadTest {

    /** The code the scratchpad replaced, kept here as the reference. */
    private static final class Old {
        final StringBuilder sb = new StringBuilder();

        Old(String question) {
            sb.append("Question: ").append(question).append("\n");
        }

        void step(String thought, String action, String actionInput, String observation) {
            sb.append("Plan: ").append(thought != null ? thought : "").append("\n");
            sb.append("Action: ").append(action).append("\n");
            sb.append("Action Input: ").append(actionInput != null ? actionInput : "").append("\n");
            sb.append("Observation: ").append(observation).append("\n");
        }

        void noAction(String llmOutput) {
            sb.append(llmOutput).append("\nObservation: No valid action found.\n");
        }

        void error(String llmOutput, String message) {
            sb.append(llmOutput)
                    .append("\nObservation: System Error: ")
                    .append(message)
                    .append("\n");
        }
    }

    @Test
    void anEmptyScratchpadIsJustTheQuestion() {
        assertThat(new Scratchpad("What is 2+2?").render()).isEqualTo("Question: What is 2+2?\n");
    }

    @Test
    void rendersByteForByteWhatTheStringBuilderDid() {
        Old old = new Old("Compare the plans.");
        Scratchpad pad = new Scratchpad("Compare the plans.");
        old.step("I should search.", "search", "{\"q\":\"plans\"}", "result one");
        pad.step("I should search.", "search", "{\"q\":\"plans\"}", "result one");
        old.step(null, "lookup", null, "");
        pad.step(null, "lookup", null, "");
        old.noAction("Thought: hmm\n");
        pad.noAction("Thought: hmm\n");
        old.error("garbled output", "boom");
        pad.error("garbled output", "boom");
        old.noAction(null);
        pad.noAction(null);
        assertThat(pad.render()).isEqualTo(old.sb.toString());
    }

    @Test
    void textThatLooksLikeTheProtocolIsNotMistakenForIt() {
        Old old = new Old("Q\nObservation: not real");
        Scratchpad pad = new Scratchpad("Q\nObservation: not real");
        String tricky = "Observation: fake\nAction: rm -rf\nPlan: x\n\n\n";
        old.step("Plan: nested", "search", "{\"a\":\"Action: b\"}", tricky);
        pad.step("Plan: nested", "search", "{\"a\":\"Action: b\"}", tricky);
        old.noAction(tricky);
        pad.noAction(tricky);
        assertThat(pad.render()).isEqualTo(old.sb.toString());
    }

    @Test
    void manyRandomSequencesRenderTheSame() {
        for (int seed = 0; seed < 300; seed++) {
            Random r = new Random(seed);
            String q = "question " + seed + (r.nextBoolean() ? "\nsecond line" : "");
            Old old = new Old(q);
            Scratchpad pad = new Scratchpad(q);
            for (int i = r.nextInt(25); i > 0; i--) {
                String text = "t" + r.nextInt(1000) + (r.nextBoolean() ? "\nmore" : "");
                switch (r.nextInt(3)) {
                    case 0 -> {
                        String thought = r.nextInt(4) == 0 ? null : text;
                        String input = r.nextInt(4) == 0 ? null : "{\"x\":" + r.nextInt(9) + "}";
                        String tool = "tool" + r.nextInt(3);
                        old.step(thought, tool, input, text);
                        pad.step(thought, tool, input, text);
                    }
                    case 1 -> {
                        old.noAction(text);
                        pad.noAction(text);
                    }
                    default -> {
                        String why = "err" + r.nextInt(9);
                        old.error(text, why);
                        pad.error(text, why);
                    }
                }
            }
            assertThat(pad.render()).as("seed " + seed).isEqualTo(old.sb.toString());
        }
    }

    @Test
    void theTurnsAreMessagesACompactorCanWorkOn() {
        Scratchpad pad = new Scratchpad("Q?");
        pad.step("p1", "search", "{\"q\":1}", "obs one");
        pad.noAction("garbled\n");
        List<Message> m = pad.messages();
        assertThat(m).hasSize(4);
        assertThat(m.get(0).getRole())
                .isEqualTo(Message.Role.USER); // the question: the task a compactor protects
        assertThat(m.get(1).getRole()).isEqualTo(Message.Role.ASSISTANT); // the plan and the action
        assertThat(m.get(1).getToolCalls()).hasSize(1);
        assertThat(m.get(1).getToolCalls().get(0).name()).isEqualTo("search");
        assertThat(m.get(2).getRole())
                .isEqualTo(
                        Message.Role.TOOL); // its observation, a tool result strategies can shorten
        assertThat(m.get(2).getToolCallId()).isEqualTo(m.get(1).getToolCalls().get(0).id());
        assertThat(m.get(3).getRole())
                .isEqualTo(Message.Role.ASSISTANT); // a turn with no valid action stands alone
        assertThat(m.get(3).getToolCalls()).isEmpty();
        assertThat(pad.size()).isEqualTo(4);
    }

    @Test
    void compactingAndRenderingGivesTheQuestionTheLatestTurnsAndAnEarlierStepsLine() {
        Scratchpad pad = new Scratchpad("Q?");
        for (int i = 0; i < 12; i++)
            pad.step(
                    "plan " + i, "search", "{\"q\":" + i + "}", "obs " + i + " " + "x".repeat(400));
        long size = CompactionRequest.of(1).sizeOf(pad.messages());
        CompactionResult r =
                ContextCompactors.dropOldest()
                        .compact(
                                pad.messages(),
                                CompactionRequest.builder(size / 3).keepRecent(2).build());
        assertThat(r.outcome()).isEqualTo(CompactionResult.Outcome.COMPACTED);
        pad.replace(r.messages());
        String text = pad.render();
        assertThat(text).startsWith("Question: Q?\n");
        assertThat(text)
                .contains("Earlier steps: [")
                .contains("earlier messages were removed to save space.]");
        assertThat(text)
                .contains(
                        "Plan: plan 11\nAction: search\nAction Input: {\"q\":11}\nObservation: obs 11 ");
        assertThat(text).doesNotContain("Plan: plan 0\n");
    }

    @Test
    void aShortenedObservationRendersInPlace() {
        Scratchpad pad = new Scratchpad("Q?");
        for (int i = 0; i < 6; i++)
            pad.step("plan " + i, "fetch", "{\"u\":" + i + "}", ("page " + i + " ").repeat(500));
        long size = CompactionRequest.of(1).sizeOf(pad.messages());
        CompactionResult r =
                ContextCompactors.trimToolResults(300)
                        .compact(
                                pad.messages(),
                                CompactionRequest.builder(size / 2).keepRecent(2).build());
        assertThat(r.outcome()).isEqualTo(CompactionResult.Outcome.COMPACTED);
        pad.replace(r.messages());
        String text = pad.render();
        assertThat(text).contains("characters omitted");
        assertThat(text)
                .contains(
                        "Plan: plan 0\nAction: fetch\nAction Input: {\"u\":0}\nObservation: page 0 "); // the turn is still there, only shorter
        assertThat(text.length()).isLessThan(6 * 2_500);
    }

    @Test
    void newTurnsCanBeAddedAfterACompaction() {
        Scratchpad pad = new Scratchpad("Q?");
        for (int i = 0; i < 8; i++)
            pad.step("plan " + i, "search", "{\"q\":" + i + "}", "obs " + "x".repeat(300));
        CompactionResult r =
                ContextCompactors.dropOldest()
                        .compact(
                                pad.messages(),
                                CompactionRequest.builder(200).keepRecent(2).build());
        pad.replace(r.messages());
        pad.step("later", "search", "{\"q\":99}", "fresh");
        assertThat(pad.render())
                .endsWith(
                        "Plan: later\nAction: search\nAction Input: {\"q\":99}\nObservation: fresh\n");
        List<String> ids = new ArrayList<>();
        for (Message m : pad.messages()) m.getToolCalls().forEach(c -> ids.add(c.id()));
        assertThat(ids).doesNotHaveDuplicates();
    }

    @Test
    void aMessageFromAStrategyThatIsNotAMarkerStillRendersSensibly() {
        Scratchpad pad = new Scratchpad("Q?");
        pad.replace(
                List.of(
                        pad.messages().get(0),
                        Messages.marked(Message.user("Summary of what happened."), "summarize")));
        assertThat(pad.render())
                .isEqualTo("Question: Q?\nEarlier steps: Summary of what happened.\n");
    }
}
