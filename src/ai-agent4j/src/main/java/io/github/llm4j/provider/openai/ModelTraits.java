package io.github.llm4j.provider.openai;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * What a particular model accepts and how its thinking is switched, decided from the model's name.
 * A dialect says how a vendor differs; this says how one of that vendor's models differs (an OpenAI
 * reasoning model rejects a custom temperature, a Kimi K3 fixes its sampling). The lookups work on
 * name patterns, so a model that did not exist when this was written still gets the safe answer for
 * its family rather than a failed request, and an unrecognised name gets {@link #DEFAULT}.
 *
 * @param acceptsTemperature whether a custom {@code temperature} may be sent
 * @param acceptsTopP whether a custom {@code top_p} may be sent
 * @param acceptsStop whether {@code stop} sequences may be sent
 * @param reasoningOn request fields that switch the model's thinking on (sent when the caller asks
 *     to see reasoning on a stream)
 * @param reasoningOff request fields that switch it off (sent otherwise); empty when the model has
 *     no switch
 */
public record ModelTraits(
        boolean acceptsTemperature,
        boolean acceptsTopP,
        boolean acceptsStop,
        Map<String, Object> reasoningOn,
        Map<String, Object> reasoningOff) {

    /**
     * A model nothing is known about: everything is accepted and thinking is left as the vendor
     * defaults it.
     */
    public static final ModelTraits DEFAULT = new ModelTraits(true, true, true, Map.of(), Map.of());

    public ModelTraits {
        reasoningOn = reasoningOn == null ? Map.of() : Map.copyOf(reasoningOn);
        reasoningOff = reasoningOff == null ? Map.of() : Map.copyOf(reasoningOff);
    }

    /** The same traits with sampling settings ({@code temperature}, {@code top_p}) refused. */
    private ModelTraits withoutSampling() {
        return new ModelTraits(false, false, acceptsStop, reasoningOn, reasoningOff);
    }

    private ModelTraits withoutStop() {
        return new ModelTraits(acceptsTemperature, acceptsTopP, false, reasoningOn, reasoningOff);
    }

    private ModelTraits withThinkingSwitch(Map<String, Object> on, Map<String, Object> off) {
        return new ModelTraits(acceptsTemperature, acceptsTopP, acceptsStop, on, off);
    }

    // ── OpenAI ──────────────────────────────────────────────────────────────────────────────

    /**
     * o-series and gpt-5 and later (except the chat variants) reason, and reject custom sampling
     * settings and stop sequences.
     */
    private static final Pattern OPENAI_REASONING =
            Pattern.compile("^(o\\d+|gpt-(?:[5-9]|\\d{2,}))(?![0-9]).*");

    /** OpenAI: reasoning models take no temperature, top_p or stop. */
    public static ModelTraits openai(String model) {
        String m = lower(model);
        if (OPENAI_REASONING.matcher(m).matches() && !m.contains("chat"))
            return DEFAULT.withoutSampling().withoutStop();
        return DEFAULT;
    }

    // ── xAI ─────────────────────────────────────────────────────────────────────────────────

    /**
     * xAI: the reasoning models (grok-4 and later, grok-3-mini) reject stop sequences; the
     * non-reasoning variants take them.
     */
    public static ModelTraits xai(String model) {
        String m = lower(model);
        boolean reasons =
                !m.contains("non-reasoning")
                        && (m.matches("^grok-(?:[4-9]|\\d{2,}).*")
                                || m.startsWith("grok-3-mini")
                                || m.contains("reasoning"));
        return reasons ? DEFAULT.withoutStop() : DEFAULT;
    }

    // ── Kimi ────────────────────────────────────────────────────────────────────────────────

    private static final Pattern KIMI_FIXED_SAMPLING =
            Pattern.compile("^kimi-k(?:2\\.[5-9]\\d*|2\\.\\d{2,}|[3-9]).*");
    private static final Pattern KIMI_SWITCHABLE = Pattern.compile("^kimi-k2\\.[56](?!\\d).*");

    /**
     * Kimi: K2.5 and later (and K3) fix temperature and top_p, and K2.5/K2.6 can have thinking
     * switched on or off.
     */
    public static ModelTraits kimi(String model) {
        String m = lower(model);
        ModelTraits t =
                KIMI_FIXED_SAMPLING.matcher(m).matches() ? DEFAULT.withoutSampling() : DEFAULT;
        if (KIMI_SWITCHABLE.matcher(m).matches()) {
            t =
                    t.withThinkingSwitch(
                            Map.of("thinking", Map.of("type", "enabled")),
                            Map.of("thinking", Map.of("type", "disabled")));
        }
        return t;
    }

    // ── Qwen ────────────────────────────────────────────────────────────────────────────────

    private static final Pattern QWEN_HYBRID =
            Pattern.compile(
                    "^qwen(?:3(?:\\.\\d+)?)?-(?:max|plus|flash|turbo)(?:-\\d.*|-latest)?$|^qwen3(?:\\.\\d+)?-\\d+b(?:-a\\d+b)?$");

    /**
     * Qwen: the hybrid models think or not on the {@code enable_thinking} switch; thinking-only and
     * other models have none.
     */
    public static ModelTraits qwen(String model) {
        String m = lower(model);
        if (QWEN_HYBRID.matcher(m).matches()
                && !m.contains("thinking")
                && !m.contains("instruct")) {
            return DEFAULT.withThinkingSwitch(
                    Map.of("enable_thinking", true), Map.of("enable_thinking", false));
        }
        return DEFAULT;
    }

    private static String lower(String model) {
        return model == null ? "" : model.toLowerCase(Locale.ROOT);
    }
}
