package io.github.llm4j.agent;

import io.github.llm4j.model.Message;
import io.github.llm4j.model.ToolCall;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The running record the text protocol sends back to the model each iteration: the question, then one block per step. It keeps its turns
 * as messages, in the same shape a native tool-calling conversation has (an assistant message with a call, and a tool message with the
 * result), so that the same {@link io.github.llm4j.context.ContextCompactor}s can shorten or drop old turns. Rendered without any
 * compaction it is exactly the text the loop used to build by appending to a string.
 */
final class Scratchpad {

    private static final String INPUT = "input";

    private final List<Message> turns = new ArrayList<>();
    private int ids;

    Scratchpad(String question) {
        turns.add(Message.user("Question: " + question + "\n"));
    }

    /** A step: the plan, the action with its input, and what came back. */
    void step(String plan, String action, String actionInput, String observation) {
        String id = "s" + (++ids);
        String name = String.valueOf(action);
        ToolCall call = new ToolCall(id, name, Map.of(INPUT, actionInput != null ? actionInput : ""));
        turns.add(Message.assistantToolCalls(plan != null ? plan : "", List.of(call), Map.of()));
        turns.add(Message.toolResult(id, name, String.valueOf(observation)));
    }

    static final String STEER = "Message from the user: ";
    static final String SETTLE = "Your answer was not accepted: ";

    /** Something the user said while the agent was working. */
    void steer(String message) {
        turns.add(Message.user(STEER + message));
    }

    /** An answer a hook did not accept, with the message the model is shown about it. */
    void sentBack(String answerReply, String message) {
        turns.add(Message.assistant(answerReply));
        turns.add(Message.user(SETTLE + message));
    }

    /** A reply that named no usable action, with the note the model is shown about it. */
    void noAction(String llmOutput) {
        turns.add(Message.assistant(llmOutput + "\nObservation: No valid action found.\n"));
    }

    /** A reply that could not be handled, with the error the model is shown about it. */
    void error(String llmOutput, String message) {
        turns.add(Message.assistant(llmOutput + "\nObservation: System Error: " + message + "\n"));
    }

    /** The turns, to be compacted. */
    List<Message> messages() {
        return List.copyOf(turns);
    }

    int size() {
        return turns.size();
    }

    /** Continues from a compacted version of {@link #messages()}. */
    void replace(List<Message> compacted) {
        turns.clear();
        turns.addAll(compacted);
    }

    String render() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < turns.size(); i++) {
            Message m = turns.get(i);
            switch (m.getRole()) {
                case USER -> sb.append(i == 0 ? m.getContent()
                        : m.getContent().startsWith(STEER) ? m.getContent() + "\n"
                        : "Earlier steps: " + m.getContent() + "\n");
                case TOOL -> sb.append("Observation: ").append(m.getContent()).append("\n");
                case ASSISTANT -> {
                    if (m.getToolCalls().isEmpty()) {
                        sb.append(m.getContent());
                    } else {
                        ToolCall call = m.getToolCalls().get(0);
                        sb.append("Plan: ").append(m.getContent()).append("\n");
                        sb.append("Action: ").append(call.name()).append("\n");
                        sb.append("Action Input: ").append(call.arguments().get(INPUT)).append("\n");
                    }
                }
                default -> sb.append(m.getContent());
            }
        }
        return sb.toString();
    }
}
