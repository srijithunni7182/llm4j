package io.github.llm4j.loom.wtest;

import io.github.llm4j.loom.runtime.HumanInterface;
import io.github.llm4j.loom.runtime.RunSuspended;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * The people of a test case: a question put to a person ({@code human_prompt}, an approval, a
 * decision) takes the next of the case's {@code humans:} answers, in order. When the answers run
 * out the run is suspended, as it would be waiting for a person, and the case can say so with
 * {@code final: suspended}. Every question is recorded.
 */
final class ScriptedHumans implements HumanInterface {

    private final Deque<String> answers = new ArrayDeque<>();
    private final Recorder recorder;

    ScriptedHumans(List<String> answers, Recorder recorder) {
        this.answers.addAll(answers);
        this.recorder = recorder;
    }

    void add(List<String> more) {
        answers.addAll(more);
    }

    @Override
    public String promptHuman(String message) {
        return next("", message, "prompt");
    }

    @Override
    public String promptHuman(String stepId, String message) {
        return next(stepId, message, "prompt");
    }

    @Override
    public String promptHuman(String stepId, String message, Hints hints) {
        String kind =
                hints == null ? "prompt" : hints.kind().name().toLowerCase(java.util.Locale.ROOT);
        return next(stepId, message, kind);
    }

    private String next(String stepId, String message, String kind) {
        recorder.asked(kind, message);
        String answer = answers.poll();
        if (answer == null) throw new RunSuspended(stepId, message);
        return answer;
    }
}
