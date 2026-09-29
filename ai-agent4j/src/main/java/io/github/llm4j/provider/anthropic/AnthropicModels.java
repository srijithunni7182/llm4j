package io.github.llm4j.provider.anthropic;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * What differs between Claude models, in one place. Current models (Fable 5.x, Opus 4.7 and later,
 * Sonnet 5.x) reject {@code temperature}/{@code top_p} with a 400; older ones accept them. The list is
 * an allowlist, so a model released after this was written is treated as not accepting them — sending
 * nothing is harmless, a 400 isn't. See https://docs.claude.com/en/docs/about-claude/models.
 */
public final class AnthropicModels {

    private static final Pattern ACCEPTS_SAMPLING = Pattern.compile(
            "claude-(3.*"                        // every Claude 3.x
                    + "|haiku-4-5.*"             // Haiku 4.5
                    + "|(opus|sonnet)-4-6.*"     // Opus 4.6, Sonnet 4.6
                    + "|(opus|sonnet|haiku)-4-5.*"
                    + "|(opus|sonnet)-4-1.*"
                    + "|(opus|sonnet)-4(-\\d{8})?"  // Opus 4, Sonnet 4 (optionally dated)
                    + ")");

    private AnthropicModels() {}

    /** Whether {@code temperature} and {@code top_p} may be sent to this model. */
    public static boolean acceptsSampling(String model) {
        return model != null && ACCEPTS_SAMPLING.matcher(model.toLowerCase(Locale.ROOT)).matches();
    }
}
