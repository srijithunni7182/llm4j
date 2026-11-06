package io.github.llm4j.provider.openai;

import java.util.regex.Pattern;

/**
 * The OpenAI and xAI model names that take media, by pattern over the whole name. A cell is here
 * because the vendor's documentation or a live call says so; a model that matches nothing takes no
 * media, so a new model is refused until someone records it. Confirmed live on 2026-11-06: {@code
 * gpt-5-mini} (picture, PDF), {@code gpt-audio} and {@code gpt-audio-mini} (sound), {@code
 * grok-4.20-0309-non-reasoning} (picture).
 */
final class MediaModels {

    /**
     * OpenAI models that take pictures and PDFs: gpt-4o, gpt-4.1, gpt-4.5, gpt-5 and later, o1, o3
     * and o4 and chatgpt-4o. Not the audio models (they take sound), not o1-mini or o3-mini (text
     * only), not gpt-4-turbo or gpt-3.5, not gpt-oss.
     */
    static final Pattern OPENAI_SEES =
            Pattern.compile(
                    "^(?!.*audio)(?!o[13]-mini)(?:gpt-(?:4o|4\\.1|4\\.5|[5-9])|o[134]|chatgpt-4o)(?:[-.].*)?$");

    /** OpenAI's audio-input models on chat completions. */
    static final Pattern OPENAI_HEARS = Pattern.compile("^gpt-(?:4o-(?:mini-)?)?audio(?:[-.].*)?$");

    /** The Grok 4 family and the Grok 2 vision models. */
    static final Pattern XAI_SEES =
            Pattern.compile("^grok-(?:4(?:[-.].*)?|[0-9.]+-vision(?:[-.].*)?)$");

    /** Moonshot's vision models, named so. */
    static final Pattern KIMI_SEES = Pattern.compile("^(?=.*vision).+$");

    /** Qwen's vision-language models ({@code qwen-vl-max}, {@code qwen3-vl-plus}). */
    static final Pattern QWEN_SEES = Pattern.compile("^(?=.*(?:-vl|vl-)).+$");

    private MediaModels() {}
}
