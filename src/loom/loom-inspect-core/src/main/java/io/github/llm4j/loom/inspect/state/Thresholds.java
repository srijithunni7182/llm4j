package io.github.llm4j.loom.inspect.state;

/**
 * When a rule fires. These are the defaults; a script's {@code .inspect} file sets them per agent. An incident
 * changes nothing about a run: these only decide what the console says.
 *
 * @param sameAction the same tool call (same tool, same input) this many times in one step, rounds of a loop counted together
 * @param sameOutput the same answer from the same step this many times in a row
 * @param ungroundedWarn figures in an answer found in nothing the step saw: warn at this many
 * @param ungroundedHigh and high at this many
 * @param toolFailures a tool failing this many times in a row
 * @param pausedLongerThanSeconds a pause (rate limit, budget window) longer than this is raised from information to a warning
 * @param contextWarn an agent's context fill (0 to 1) at which "nearly full" is raised as a warning
 * @param contextHigh and as high
 * @param truncatedAnswers model calls of an agent cut off by the token limit before an incident is raised
 * @param thrashCompactions compactions of one agent within {@code thrashOfCalls} model calls that count as thrash
 * @param thrashOfCalls the number of model calls the compactions are counted over
 * @param toolRate a tool whose success rate over its last {@code toolRateWindow} calls is below this (0 to 1) is raised
 * @param toolRateWindow how many of a tool's latest calls the rate is taken over
 * @param spikeFactor a call this many times slower than the running median of its kind is a latency spike
 * @param accessDeniedCount a tool refusing access this many times within {@code accessDeniedSeconds} is raised
 * @param accessDeniedSeconds the span the refusals are counted over
 * @param injectionScan whether results of untrusted tools are scanned for injected instructions
 * @param trifectaAccepted the written reason this agent's use of all three legs of the lethal trifecta is accepted, or null when it is not
 */
public record Thresholds(int sameAction, int sameOutput, int ungroundedWarn, int ungroundedHigh, int toolFailures, long pausedLongerThanSeconds,
                         double contextWarn, double contextHigh, int truncatedAnswers, int thrashCompactions, int thrashOfCalls,
                         double toolRate, int toolRateWindow, double spikeFactor,
                         int accessDeniedCount, long accessDeniedSeconds, boolean injectionScan, String trifectaAccepted) {

    /** The fewest calls a success rate is judged on, and a spike needs this many earlier samples. */
    public static final int MIN_RATE_CALLS = 5;
    public static final int MIN_SPIKE_SAMPLES = 10;
    /** A call under this many milliseconds is never a spike, however slow it is relative to a very fast median. */
    public static final long SPIKE_FLOOR_MILLIS = 2_000;

    /** The rules that existed before the security ones, with the defaults for those. */
    public Thresholds(int sameAction, int sameOutput, int ungroundedWarn, int ungroundedHigh, int toolFailures, long pausedLongerThanSeconds,
                      double contextWarn, double contextHigh, int truncatedAnswers, int thrashCompactions, int thrashOfCalls,
                      double toolRate, int toolRateWindow, double spikeFactor) {
        this(sameAction, sameOutput, ungroundedWarn, ungroundedHigh, toolFailures, pausedLongerThanSeconds, contextWarn, contextHigh, truncatedAnswers,
                thrashCompactions, thrashOfCalls, toolRate, toolRateWindow, spikeFactor, 3, 60, true, null);
    }

    /** The rules that existed before the telemetry ones, with the defaults for those. */
    public Thresholds(int sameAction, int sameOutput, int ungroundedWarn, int ungroundedHigh, int toolFailures, long pausedLongerThanSeconds) {
        this(sameAction, sameOutput, ungroundedWarn, ungroundedHigh, toolFailures, pausedLongerThanSeconds, 0.85, 0.95, 1, 4, 10, 0.70, 10, 5.0);
    }

    public static Thresholds defaults() {
        return new Thresholds(4, 3, 2, 4, 3, 60);
    }
}
