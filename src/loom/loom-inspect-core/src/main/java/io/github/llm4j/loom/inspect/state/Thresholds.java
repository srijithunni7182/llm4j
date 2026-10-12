package io.github.llm4j.loom.inspect.state;

/**
 * When a rule fires. These are the defaults; a script's {@code inspect} block will set them per agent (a later phase). An incident
 * changes nothing about a run: these only decide what the console says.
 *
 * @param sameAction the same tool call (same tool, same input) this many times in one step, rounds of a loop counted together
 * @param sameOutput the same answer from the same step this many times in a row
 * @param ungroundedWarn figures in an answer found in nothing the step saw: warn at this many
 * @param ungroundedHigh and high at this many
 * @param toolFailures a tool failing this many times in a row
 * @param pausedLongerThanSeconds a pause (rate limit, budget window) longer than this is raised from information to a warning
 */
public record Thresholds(int sameAction, int sameOutput, int ungroundedWarn, int ungroundedHigh, int toolFailures, long pausedLongerThanSeconds) {

    public static Thresholds defaults() {
        return new Thresholds(4, 3, 2, 4, 3, 60);
    }
}
