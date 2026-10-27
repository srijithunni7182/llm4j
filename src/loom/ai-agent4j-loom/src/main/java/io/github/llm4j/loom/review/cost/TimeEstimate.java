package io.github.llm4j.loom.review.cost;

import java.util.List;
import java.util.Map;

/**
 * How long one workflow takes, from the script alone: the typical run and the longest allowed one,
 * the steps on the longest path, and the places it stops for a person or a timer (those waits are
 * listed, not added: nobody can say how long a person takes).
 *
 * @param typicalMillis a normal run, without waits
 * @param worstMillis the longest allowed run, without waits; only the bounded part when {@code
 *     unbounded}
 * @param unbounded true when something can repeat without limit and takes time
 * @param criticalPath the steps the worst case waits on, in order
 * @param waits where the run stops for a person or a timer, such as {@code human_prompt (line 12)}
 * @param sleepMillis the fixed sleeps on the main path, summed once
 * @param assumptions the latency assumptions used, in seconds
 * @param worstQuestions the most questions the workflow can put to a person in one run
 * @param questionsUnbounded true when a question sits in something that can repeat without limit
 */
public record TimeEstimate(
        long typicalMillis,
        long worstMillis,
        boolean unbounded,
        List<String> criticalPath,
        List<String> waits,
        long sleepMillis,
        Map<String, Long> assumptions,
        long worstQuestions,
        boolean questionsUnbounded) {

    /** An estimate that does not count the questions put to people. */
    public TimeEstimate(
            long typicalMillis,
            long worstMillis,
            boolean unbounded,
            List<String> criticalPath,
            List<String> waits,
            long sleepMillis,
            Map<String, Long> assumptions) {
        this(
                typicalMillis,
                worstMillis,
                unbounded,
                criticalPath,
                waits,
                sleepMillis,
                assumptions,
                0,
                false);
    }

    public TimeEstimate {
        criticalPath = List.copyOf(criticalPath);
        waits = List.copyOf(waits);
        assumptions = Map.copyOf(assumptions);
    }
}
