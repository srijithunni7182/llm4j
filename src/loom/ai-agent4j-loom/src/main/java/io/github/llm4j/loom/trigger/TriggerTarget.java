package io.github.llm4j.loom.trigger;

import java.time.Instant;

/**
 * Does what a trigger says — supplied by the host, which owns agent factories, secrets and inputs. The
 * {@code weave} CLI ships one that rebuilds runs from their run directory.
 */
@FunctionalInterface
public interface TriggerTarget {

    /**
     * @param trigger the trigger being fired
     * @param runId   the run to resume or start: the resume trigger's run, or {@code <schedule>@<slot>}
     *                for a scheduled workflow; null for an agent task
     */
    Outcome fire(Trigger trigger, String runId) throws Exception;

    /** How a firing ended. */
    record Outcome(Status status, Instant resumeAt, String message) {

        public enum Status { DONE, FAILED, SUSPENDED, HUMAN, SKIPPED, SKIPPED_OVERLAP }

        public static Outcome done() {
            return new Outcome(Status.DONE, null, null);
        }

        public static Outcome failed(String message) {
            return new Outcome(Status.FAILED, null, message);
        }

        public static Outcome suspended(Instant resumeAt, String message) {
            return new Outcome(Status.SUSPENDED, resumeAt, message);
        }

        public static Outcome human(String message) {
            return new Outcome(Status.HUMAN, null, message);
        }

        @Override
        public String toString() {
            return status + (resumeAt != null ? " until " + resumeAt : "") + (message != null ? ": " + message : "");
        }
    }
}
