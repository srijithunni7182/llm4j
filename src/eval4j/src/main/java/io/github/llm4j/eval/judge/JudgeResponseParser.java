package io.github.llm4j.eval.judge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a judge LLM's response into a {@link JudgeVerdict}. Uses the same fenced-JSON-block
 * convention already relied on elsewhere in ai-agent4j (see {@code ReActAgent.parseResponse}): the
 * model is asked to answer with a single ```json code block, which is more reliably extracted than
 * parsing the whole response as JSON, since judge models often add a sentence of preamble.
 *
 * <p>The judge returns a discrete 1-5 {@code rating} plus its {@code reasoning}, per {@link
 * JudgePrompt}'s rubric, not a raw score — this parser converts the rating to a normalized 0.0-1.0
 * score ({@code (rating - 1) / 4.0}) for {@link JudgeVerdict}, so {@link LlmJudgeCondition}'s
 * threshold comparison is unaffected by this internal representation.
 */
final class JudgeResponseParser {

    private static final Pattern JSON_BLOCK_PATTERN =
            Pattern.compile("```json\\s*\\n?(.*?)\\n?```", Pattern.DOTALL);

    private static final int MIN_RATING = 1;
    private static final int MAX_RATING = 5;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private JudgeResponseParser() {}

    static JudgeVerdict parse(String judgeOutput) {
        if (judgeOutput == null || judgeOutput.isBlank()) {
            throw new JudgeEvaluationException("Judge returned an empty response.");
        }

        String jsonText = extractJson(judgeOutput);
        JsonNode node;
        try {
            node = OBJECT_MAPPER.readTree(jsonText);
        } catch (Exception e) {
            JudgeVerdict salvaged = salvageRating(jsonText);
            if (salvaged != null) {
                return salvaged;
            }
            throw new JudgeEvaluationException(
                    "Judge response was not valid JSON: " + judgeOutput, e);
        }

        if (!node.has("rating")) {
            throw new JudgeEvaluationException(
                    "Judge response is missing a \"rating\" field: " + judgeOutput);
        }

        int rating = node.get("rating").asInt();
        if (rating < MIN_RATING || rating > MAX_RATING) {
            throw new JudgeEvaluationException(
                    "Judge returned an out-of-range rating: "
                            + rating
                            + " (expected "
                            + MIN_RATING
                            + "-"
                            + MAX_RATING
                            + ")");
        }

        String reasoning = node.has("reasoning") ? node.get("reasoning").asText() : "";
        double score = (rating - MIN_RATING) / (double) (MAX_RATING - MIN_RATING);
        return new JudgeVerdict(score, "[" + rating + "/5] " + reasoning);
    }

    private static final Pattern RATING_FIELD = Pattern.compile("\"rating\"\\s*:\\s*\"?([1-5])\"?");
    private static final Pattern REASONING_FIELD =
            Pattern.compile("\"reasoning\"\\s*:\\s*\"(.*)\"\\s*,\\s*\"rating\"", Pattern.DOTALL);

    /**
     * Judges sometimes emit a well-formed rating next to reasoning that contains unescaped quotes,
     * which breaks strict JSON parsing even though the verdict is unambiguous. Recover the rating
     * (and best-effort reasoning) instead of failing the whole evaluation; anything that doesn't
     * contain exactly one in-range rating is still rejected.
     */
    private static JudgeVerdict salvageRating(String text) {
        Matcher matcher = RATING_FIELD.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        int rating = Integer.parseInt(matcher.group(1));
        if (matcher.find()) {
            return null; // ambiguous: more than one rating field
        }
        Matcher reasoning = REASONING_FIELD.matcher(text);
        String why = reasoning.find() ? reasoning.group(1) : "(reasoning unparseable)";
        double score = (rating - MIN_RATING) / (double) (MAX_RATING - MIN_RATING);
        return new JudgeVerdict(score, "[" + rating + "/5] " + why);
    }

    private static String extractJson(String judgeOutput) {
        Matcher matcher = JSON_BLOCK_PATTERN.matcher(judgeOutput);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return judgeOutput.trim();
    }
}
