package io.github.llm4j.loom.inspect.state;

/**
 * What one agent's {@code inspect { }} block says, with no dependency on the Loom runtime so it can travel to a server. A null is a rule
 * the block left out: the default applies.
 *
 * @param noProgressRounds with {@code noProgressVariable}: that variable unchanged at the boundary of this many rounds in a row
 */
public record InspectRules(Integer sameAction, Integer sameOutput, Integer toolFailures, Integer ungroundedWarn, Integer ungroundedHigh,
                           Long pausedLongerThanSeconds, Integer noProgressRounds, String noProgressVariable, Telemetry telemetry) {

    /**
     * The rules about context, truncation, compaction, tool success and latency; a null is a rule left out. Kept apart so that a rules value written
     * before they existed (an old snapshot, a central file) reads as it did.
     */
    public record Telemetry(Double contextWarn, Double contextHigh, Integer truncatedAnswers, Integer thrashCompactions, Integer thrashOfCalls,
                            Double toolRate, Integer toolRateWindow, Double spikeFactor) {

        /** True when it sets nothing. */
        public boolean isEmpty() {
            return contextWarn == null && contextHigh == null && truncatedAnswers == null && thrashCompactions == null && thrashOfCalls == null
                    && toolRate == null && toolRateWindow == null && spikeFactor == null;
        }

        /** This one's rules where it sets one, else the other's. */
        public Telemetry over(Telemetry base) {
            if (base == null) return this;
            return new Telemetry(
                    contextWarn != null ? contextWarn : base.contextWarn, contextHigh != null ? contextHigh : base.contextHigh,
                    truncatedAnswers != null ? truncatedAnswers : base.truncatedAnswers, thrashCompactions != null ? thrashCompactions : base.thrashCompactions,
                    thrashOfCalls != null ? thrashOfCalls : base.thrashOfCalls, toolRate != null ? toolRate : base.toolRate,
                    toolRateWindow != null ? toolRateWindow : base.toolRateWindow, spikeFactor != null ? spikeFactor : base.spikeFactor);
        }
    }

    /** The rules that existed before the telemetry ones. */
    public InspectRules(Integer sameAction, Integer sameOutput, Integer toolFailures, Integer ungroundedWarn, Integer ungroundedHigh,
                        Long pausedLongerThanSeconds, Integer noProgressRounds, String noProgressVariable) {
        this(sameAction, sameOutput, toolFailures, ungroundedWarn, ungroundedHigh, pausedLongerThanSeconds, noProgressRounds, noProgressVariable, null);
    }

    /** The defaults with this block's rules laid over them. */
    public Thresholds over(Thresholds base) {
        Telemetry t = telemetry == null ? new Telemetry(null, null, null, null, null, null, null, null) : telemetry;
        return new Thresholds(
                sameAction != null ? sameAction : base.sameAction(),
                sameOutput != null ? sameOutput : base.sameOutput(),
                ungroundedWarn != null ? ungroundedWarn : base.ungroundedWarn(),
                ungroundedHigh != null ? ungroundedHigh : base.ungroundedHigh(),
                toolFailures != null ? toolFailures : base.toolFailures(),
                pausedLongerThanSeconds != null ? pausedLongerThanSeconds : base.pausedLongerThanSeconds(),
                t.contextWarn != null ? t.contextWarn : base.contextWarn(),
                t.contextHigh != null ? t.contextHigh : base.contextHigh(),
                t.truncatedAnswers != null ? t.truncatedAnswers : base.truncatedAnswers(),
                t.thrashCompactions != null ? t.thrashCompactions : base.thrashCompactions(),
                t.thrashOfCalls != null ? t.thrashOfCalls : base.thrashOfCalls(),
                t.toolRate != null ? t.toolRate : base.toolRate(),
                t.toolRateWindow != null ? t.toolRateWindow : base.toolRateWindow(),
                t.spikeFactor != null ? t.spikeFactor : base.spikeFactor());
    }
}
