package io.github.llm4j.a2a;

import java.io.OutputStream;
import java.time.Instant;
import java.util.Objects;

/**
 * A read-only view for a channel: where the exchange is happening and how to accept a file. It does
 * not expose the workflow's variables. The runtime builds it.
 */
public final class ChannelContext {

    /** Which way the exchange goes. */
    public enum Direction {
        /** A remote agent asks us a question. */
        INBOUND_QUESTION,
        /** A workflow asks the channel a question ({@code ask}). */
        OUTBOUND_ASK
    }

    private final String runId;
    private final String stepId;
    private final String workflowName;
    private final Direction direction;
    private final String counterpart;
    private final Instant deadline;
    private final int questionsLeft;
    private final ReceivedFileSink sink;

    /**
     * Creates a context.
     *
     * @param runId the run
     * @param stepId the step
     * @param workflowName the workflow
     * @param direction which way the exchange goes
     * @param counterpart a remote agent's name, or the caller's id
     * @param deadline when the exchange times out, or null
     * @param questionsLeft questions still allowed
     * @param sink where received files are written, or null for none (then receiving fails closed)
     */
    public ChannelContext(
            String runId,
            String stepId,
            String workflowName,
            Direction direction,
            String counterpart,
            Instant deadline,
            int questionsLeft,
            ReceivedFileSink sink) {
        this.runId = Objects.requireNonNull(runId, "runId");
        this.stepId = Objects.requireNonNull(stepId, "stepId");
        this.workflowName = Objects.requireNonNull(workflowName, "workflowName");
        this.direction = Objects.requireNonNull(direction, "direction");
        this.counterpart = Objects.requireNonNull(counterpart, "counterpart");
        this.deadline = deadline;
        this.questionsLeft = questionsLeft;
        this.sink = sink;
    }

    /** The run id. */
    public String runId() {
        return runId;
    }

    /** The step id. */
    public String stepId() {
        return stepId;
    }

    /** The workflow name. */
    public String workflowName() {
        return workflowName;
    }

    /** Which way the exchange goes. */
    public Direction direction() {
        return direction;
    }

    /** The remote agent's name, or the caller's id. Untrusted when it came from outside. */
    public String counterpart() {
        return counterpart;
    }

    /** When the exchange times out, or null. */
    public Instant deadline() {
        return deadline;
    }

    /** How many questions are still allowed. */
    public int questionsLeft() {
        return questionsLeft;
    }

    /**
     * Opens a stream to accept a file. The runtime owns the destination, counts the bytes, decides
     * the type from them and refuses a bad file when the stream is closed.
     *
     * @param label the sender's name for the file; never used as a path
     * @param claimedMediaType what the sender says it is, or empty
     * @throws A2AException.Refused if there is no media root, or too many files
     */
    public OutputStream receiveFile(String label, String claimedMediaType) {
        if (sink == null) {
            throw new A2AException.Refused(
                    "this script has no media_root, so received files are refused; declare media_root to accept files");
        }
        return sink.open(label, claimedMediaType);
    }

    /** The same context with a different number of questions left. */
    ChannelContext withQuestionsLeft(int value) {
        return new ChannelContext(
                runId, stepId, workflowName, direction, counterpart, deadline, value, sink);
    }
}
