package io.github.llm4j.agent.task;

/**
 * What running a {@link Task} does to the world outside the process. The runtime uses it to decide whether a
 * simulated run may execute the task, whether a crashed run may repeat it, and whether a rewind must ask first.
 */
public enum TaskEffect {

    /** Pure computation over its inputs (a policy check, a calculation). Safe to run any time, any number of times. */
    NONE,

    /** Observes the outside world and changes nothing (a lookup). Safe to run in a simulation and to repeat. */
    READS,

    /** May change something outside the process (a payment, a ticket, an e-mail). The default: it is the safe assumption. */
    CHANGES
}
