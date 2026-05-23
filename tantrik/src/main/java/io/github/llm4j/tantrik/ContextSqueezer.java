package io.github.llm4j.tantrik;

public class ContextSqueezer {
    private final int maxEstimatedTokens;

    public ContextSqueezer(int maxEstimatedTokens) {
        if (maxEstimatedTokens <= 0) {
            throw new IllegalArgumentException("maxEstimatedTokens must be positive");
        }
        this.maxEstimatedTokens = maxEstimatedTokens;
    }

    public SqueezeResult squeeze(String context) {
        int estimated = estimateTokens(context);
        if (estimated <= maxEstimatedTokens) {
            return new SqueezeResult(context, estimated, false, 1.0d);
        }

        int maxChars = Math.max(maxEstimatedTokens * 4, 120);
        int headChars = (int) (maxChars * 0.65);
        int tailChars = maxChars - headChars;
        String trimmed = context.substring(0, Math.min(headChars, context.length()))
                + "\n\n...[context squeezed by Tantrik]...\n\n"
                + context.substring(Math.max(0, context.length() - tailChars));
        double compressionRatio = (double) trimmed.length() / Math.max(1, context.length());
        return new SqueezeResult(trimmed, estimated, true, compressionRatio);
    }

    public int estimateTokens(String content) {
        if (content == null || content.isEmpty()) {
            return 0;
        }
        return (int) Math.ceil(content.length() / 4.0d);
    }

    public record SqueezeResult(String context, int inputTokensEstimate, boolean squeezed, double compressionRatio) {}
}
