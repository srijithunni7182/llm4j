package io.github.llm4j.loom.execution;

import io.github.llm4j.LLMClient;
import io.github.llm4j.loom.ast.EvaluatorDef;
import io.github.llm4j.loom.ast.ExtractorDef;
import io.github.llm4j.loom.policy.PolicyCatalog;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Runs one golden case of an extractor or an evaluator on its own, with the same prompt and the same
 * reading of the answer a workflow uses (docs/specs/28-evaluation-first, R10 and R11). It holds no
 * state and makes no decision about whether the answer was right: the caller compares.
 */
public final class NodeCaseRunner {

    private NodeCaseRunner() {}

    /**
     * What an extractor read from one text.
     *
     * @param fields the fields found
     * @param missing the optional fields the text did not state
     * @param failure why no fields were read (the answer broke the schema, or a required field was
     *     not stated), else null
     */
    public record Extraction(
            Map<String, Object> fields,
            List<String> missing,
            String failure,
            long inputTokens,
            long outputTokens) {}

    /**
     * What an evaluator said about one text.
     *
     * @param answers each rule id to {@code violated}, {@code clean} or {@code unsure}
     * @param unavailable true when no model could answer, so every rule is unsure
     */
    public record Judgement(
            Map<String, String> answers,
            boolean unavailable,
            String reason,
            long inputTokens,
            long outputTokens) {}

    /** Reads {@code text} with the extractor, using the instructions the caller resolved. */
    public static Extraction extract(
            ExtractorDef def, String instructions, String text, LLMClient client) {
        String inputs =
                ExtractRunner.OPEN
                        + ">>>\n"
                        + ExtractRunner.sanitize(text)
                        + '\n'
                        + ExtractRunner.CLOSE
                        + '\n';
        LLMRequest request = ExtractRunner.build(def, instructions, inputs).build();
        LLMResponse response = client.chat(request);
        long in = response.getTokenUsage() == null ? 0 : response.getTokenUsage().getPromptTokens();
        long out = response.getTokenUsage() == null ? 0 : response.getTokenUsage().getCompletionTokens();
        try {
            ExtractAnswer.Fields fields = ExtractAnswer.read(response.getContent(), def);
            return new Extraction(fields.values(), fields.missing(), null, in, out);
        } catch (StructuredAnswer.SchemaViolation | ExtractAnswer.StatedMissing e) {
            return new Extraction(Map.of(), List.of(), e.getMessage(), in, out);
        }
    }

    /** Judges {@code text} against the rules of the policies the evaluator checks. */
    public static Judgement judge(
            EvaluatorDef def,
            List<PolicyCatalog.Resolved> policies,
            String text,
            Function<String, LLMClient> clients) {
        PolicyEvaluator.Verdict verdict = PolicyEvaluator.evaluate(def, policies, text, clients);
        Map<String, String> answers = new LinkedHashMap<>();
        verdict.rules()
                .forEach(
                        (id, rule) ->
                                answers.put(
                                        id,
                                        switch (rule.answer()) {
                                            case "yes" -> "violated";
                                            case "no" -> "clean";
                                            default -> "unsure";
                                        }));
        return new Judgement(
                answers,
                verdict.unavailable(),
                verdict.reason(),
                verdict.inputTokens(),
                verdict.outputTokens());
    }
}
