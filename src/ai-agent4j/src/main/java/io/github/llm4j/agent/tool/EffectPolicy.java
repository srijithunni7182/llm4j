package io.github.llm4j.agent.tool;

/**
 * How the effect journal treats a side-effect tool.
 *
 * @param onUnknown what to do when an earlier attempt's outcome is unknown
 * @param idempotent the receiver deduplicates by {@code Idempotency-Key}, so an unknown attempt is simply repeated
 * @param maxPerRun calls allowed in one run (0: no limit)
 */
public record EffectPolicy(OnUnknown onUnknown, boolean idempotent, int maxPerRun) {

    public enum OnUnknown { SKIP, RETRY }

    public static final EffectPolicy DEFAULT = new EffectPolicy(OnUnknown.SKIP, false, 0);

    public static OnUnknown parse(String value) {
        return "retry".equals(value) ? OnUnknown.RETRY : OnUnknown.SKIP;
    }
}
