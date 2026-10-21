package io.github.llm4j.spring;

/**
 * Published after a trigger (a schedule, or the wake-up of a sleeping run) has been fired; {@code
 * outcome} says how it went.
 */
public record TriggerFiredEvent(String triggerId, String runId, String outcome, long lateMillis) {}
