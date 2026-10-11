package io.github.llm4j.loom.inspect.state;

/**
 * What one agent's {@code inspect { }} block says, with no dependency on the Loom runtime so it can travel to a server. A null is a rule
 * the block left out: the default applies.
 *
 * @param noProgressRounds with {@code noProgressVariable}: that variable unchanged at the boundary of this many rounds in a row
 */
public record InspectRules(Integer sameAction, Integer sameOutput, Integer toolFailures, Integer ungroundedWarn, Integer ungroundedHigh,
                           Long pausedLongerThanSeconds, Integer noProgressRounds, String noProgressVariable) {

    /** The defaults with this block's rules laid over them. */
    public Thresholds over(Thresholds base) {
        return new Thresholds(
                sameAction != null ? sameAction : base.sameAction(),
                sameOutput != null ? sameOutput : base.sameOutput(),
                ungroundedWarn != null ? ungroundedWarn : base.ungroundedWarn(),
                ungroundedHigh != null ? ungroundedHigh : base.ungroundedHigh(),
                toolFailures != null ? toolFailures : base.toolFailures(),
                pausedLongerThanSeconds != null ? pausedLongerThanSeconds : base.pausedLongerThanSeconds());
    }
}
