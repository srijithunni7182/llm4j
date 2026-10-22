package io.github.llm4j.loom.ast;

import java.time.Duration;

/**
 * {@code sleep 90s}: the workflow waits for a fixed time, then carries on. With a durable journal
 * the run pauses (it holds no thread) and is woken at the recorded time; without one it waits in
 * place.
 */
public class SleepStmt extends LocatedStatement {
    private final Duration duration;
    private final String text;

    /**
     * @param duration how long to sleep; always positive
     * @param text the duration as the script wrote it ({@code 90s}), for messages and the graph
     */
    public SleepStmt(Duration duration, String text) {
        this.duration = duration;
        this.text = text;
    }

    public Duration getDuration() {
        return duration;
    }

    /** The duration as written in the script, for example {@code 5m}. */
    public String getText() {
        return text;
    }
}
