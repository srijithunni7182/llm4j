package io.github.llm4j.loom.inspect.state;

/**
 * What one agent's {@code inspect { }} block says, with no dependency on the Loom runtime so it can
 * travel to a server. A null is a rule the block left out: the default applies.
 *
 * @param noProgressRounds with {@code noProgressVariable}: that variable unchanged at the boundary
 *     of this many rounds in a row
 * @param security the security rules (access refused, injection scan, an accepted trifecta); null
 *     when the block sets none
 */
public record InspectRules(
        Integer sameAction,
        Integer sameOutput,
        Integer toolFailures,
        Integer ungroundedWarn,
        Integer ungroundedHigh,
        Long pausedLongerThanSeconds,
        Integer noProgressRounds,
        String noProgressVariable,
        Telemetry telemetry,
        Security security) {

    /**
     * The security rules; a null is a rule left out. Kept apart, like {@link Telemetry}, so that a
     * rules value written before they existed reads as it did.
     *
     * @param accessDeniedCount with {@code accessDeniedSeconds}: a tool refusing access (permission
     *     or authentication) this many times within that span
     * @param injectionScan whether results of untrusted tools are scanned for injected instructions
     *     (default on)
     * @param trifectaAccepted a written reason: the author has accepted that this agent uses all
     *     three legs of the lethal trifecta, so it is information, not an alarm
     */
    public record Security(
            Integer accessDeniedCount,
            Long accessDeniedSeconds,
            Boolean injectionScan,
            String trifectaAccepted) {

        public boolean isEmpty() {
            return accessDeniedCount == null
                    && accessDeniedSeconds == null
                    && injectionScan == null
                    && trifectaAccepted == null;
        }

        /** This one's rules where it sets one, else the other's. */
        public Security over(Security base) {
            if (base == null) return this;
            return new Security(
                    accessDeniedCount != null ? accessDeniedCount : base.accessDeniedCount,
                    accessDeniedSeconds != null ? accessDeniedSeconds : base.accessDeniedSeconds,
                    injectionScan != null ? injectionScan : base.injectionScan,
                    trifectaAccepted != null ? trifectaAccepted : base.trifectaAccepted);
        }
    }

    /** The rules that existed before the security ones. */
    public InspectRules(
            Integer sameAction,
            Integer sameOutput,
            Integer toolFailures,
            Integer ungroundedWarn,
            Integer ungroundedHigh,
            Long pausedLongerThanSeconds,
            Integer noProgressRounds,
            String noProgressVariable,
            Telemetry telemetry) {
        this(
                sameAction,
                sameOutput,
                toolFailures,
                ungroundedWarn,
                ungroundedHigh,
                pausedLongerThanSeconds,
                noProgressRounds,
                noProgressVariable,
                telemetry,
                null);
    }

    /**
     * The rules about context, truncation, compaction, tool success and latency; a null is a rule
     * left out. Kept apart so that a rules value written before they existed (an old snapshot, a
     * central file) reads as it did.
     */
    public record Telemetry(
            Double contextWarn,
            Double contextHigh,
            Integer truncatedAnswers,
            Integer thrashCompactions,
            Integer thrashOfCalls,
            Double toolRate,
            Integer toolRateWindow,
            Double spikeFactor) {

        /** True when it sets nothing. */
        public boolean isEmpty() {
            return contextWarn == null
                    && contextHigh == null
                    && truncatedAnswers == null
                    && thrashCompactions == null
                    && thrashOfCalls == null
                    && toolRate == null
                    && toolRateWindow == null
                    && spikeFactor == null;
        }

        /** This one's rules where it sets one, else the other's. */
        public Telemetry over(Telemetry base) {
            if (base == null) return this;
            return new Telemetry(
                    contextWarn != null ? contextWarn : base.contextWarn,
                            contextHigh != null ? contextHigh : base.contextHigh,
                    truncatedAnswers != null ? truncatedAnswers : base.truncatedAnswers,
                            thrashCompactions != null ? thrashCompactions : base.thrashCompactions,
                    thrashOfCalls != null ? thrashOfCalls : base.thrashOfCalls,
                            toolRate != null ? toolRate : base.toolRate,
                    toolRateWindow != null ? toolRateWindow : base.toolRateWindow,
                            spikeFactor != null ? spikeFactor : base.spikeFactor);
        }
    }

    /** The rules that existed before the telemetry ones. */
    public InspectRules(
            Integer sameAction,
            Integer sameOutput,
            Integer toolFailures,
            Integer ungroundedWarn,
            Integer ungroundedHigh,
            Long pausedLongerThanSeconds,
            Integer noProgressRounds,
            String noProgressVariable) {
        this(
                sameAction,
                sameOutput,
                toolFailures,
                ungroundedWarn,
                ungroundedHigh,
                pausedLongerThanSeconds,
                noProgressRounds,
                noProgressVariable,
                null);
    }

    /** The defaults with this block's rules laid over them. */
    public Thresholds over(Thresholds base) {
        Telemetry t =
                telemetry == null
                        ? new Telemetry(null, null, null, null, null, null, null, null)
                        : telemetry;
        Security sec = security == null ? new Security(null, null, null, null) : security;
        return new Thresholds(
                sameAction != null ? sameAction : base.sameAction(),
                sameOutput != null ? sameOutput : base.sameOutput(),
                ungroundedWarn != null ? ungroundedWarn : base.ungroundedWarn(),
                ungroundedHigh != null ? ungroundedHigh : base.ungroundedHigh(),
                toolFailures != null ? toolFailures : base.toolFailures(),
                pausedLongerThanSeconds != null
                        ? pausedLongerThanSeconds
                        : base.pausedLongerThanSeconds(),
                t.contextWarn != null ? t.contextWarn : base.contextWarn(),
                t.contextHigh != null ? t.contextHigh : base.contextHigh(),
                t.truncatedAnswers != null ? t.truncatedAnswers : base.truncatedAnswers(),
                t.thrashCompactions != null ? t.thrashCompactions : base.thrashCompactions(),
                t.thrashOfCalls != null ? t.thrashOfCalls : base.thrashOfCalls(),
                t.toolRate != null ? t.toolRate : base.toolRate(),
                t.toolRateWindow != null ? t.toolRateWindow : base.toolRateWindow(),
                t.spikeFactor != null ? t.spikeFactor : base.spikeFactor(),
                sec.accessDeniedCount != null ? sec.accessDeniedCount : base.accessDeniedCount(),
                sec.accessDeniedSeconds != null
                        ? sec.accessDeniedSeconds
                        : base.accessDeniedSeconds(),
                sec.injectionScan != null ? sec.injectionScan : base.injectionScan(),
                sec.trifectaAccepted != null ? sec.trifectaAccepted : base.trifectaAccepted());
    }
}
