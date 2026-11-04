package io.github.llm4j.provider.anthropic;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * What differs between Claude models, in one place. Current models (Fable 5.x, Opus 4.7 and later,
 * Sonnet 5.x) reject {@code temperature}/{@code top_p} with a 400; older ones accept them. The list
 * is an allowlist, so a model released after this was written is treated as not accepting them —
 * sending nothing is harmless, a 400 isn't. See
 * https://docs.claude.com/en/docs/about-claude/models.
 */
public final class AnthropicModels {

    private static final Pattern ACCEPTS_SAMPLING =
            Pattern.compile(
                    "claude-(3.*" // every Claude 3.x
                            + "|haiku-4-5.*" // Haiku 4.5
                            + "|(opus|sonnet)-4-6.*" // Opus 4.6, Sonnet 4.6
                            + "|(opus|sonnet|haiku)-4-5.*"
                            + "|(opus|sonnet)-4-1.*"
                            + "|(opus|sonnet)-4(-\\d{8})?" // Opus 4, Sonnet 4 (optionally dated)
                            + ")");

    /**
     * Models that think adaptively and can say what they thought: Fable, Mythos, Opus 4.6+, Sonnet
     * 4.6+ and Haiku 5.x.
     */
    private static final Pattern ADAPTIVE_THINKING =
            Pattern.compile(
                    "claude-(fable-.*|mythos-.*|opus-(4-[6-9]|[5-9]).*|sonnet-(4-[6-9]|[5-9]).*|haiku-[5-9].*)");

    private AnthropicModels() {}

    /**
     * Whether {@code thinking: {type: "adaptive", display: "summarized"}} may be sent to this
     * model. Older models need a token budget for thinking, which this provider does not set, so
     * they are treated as unable to show reasoning (the request is sent without it).
     */
    public static boolean supportsAdaptiveThinking(String model) {
        return model != null && ADAPTIVE_THINKING.matcher(model.toLowerCase(Locale.ROOT)).matches();
    }

    /** Whether {@code temperature} and {@code top_p} may be sent to this model. */
    public static boolean acceptsSampling(String model) {
        return model != null && ACCEPTS_SAMPLING.matcher(model.toLowerCase(Locale.ROOT)).matches();
    }

    /**
     * Models the structured-outputs documentation lists as accepting a JSON-Schema format (read
     * 2026-11-04). An allowlist, so a model released after this was written stays on the prompt
     * route until it is added here.
     */
    private static final Pattern STRUCTURED_OUTPUT =
            Pattern.compile(
                    "claude-(?:(?:fable|mythos|opus|sonnet|haiku)-5(?:-\\d+)?"
                            + "|mythos-preview"
                            + "|haiku-4-5(?:-\\d{8})?"
                            + "|opus-4-[5-8](?:-\\d{8})?"
                            + "|sonnet-4-[56](?:-\\d{8})?)");

    /** Whether {@code model} accepts {@code output_config.format} with a JSON Schema. */
    public static boolean supportsStructuredOutput(String model) {
        return model != null && STRUCTURED_OUTPUT.matcher(model.toLowerCase(Locale.ROOT)).matches();
    }
}
