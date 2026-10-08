package io.github.llm4j.loom.eval;

import io.github.llm4j.eval.dataset.EvalScenario;
import io.github.llm4j.eval.judge.JudgeVerdict;
import io.github.llm4j.LLMClient;
import io.github.llm4j.eval.judge.JudgeCalls;
import java.util.LinkedHashMap;
import java.util.Map;

/** Decides whether a piece of text meets a judged expectation ({@code rubric} or {@code expect} line). */
public interface RubricJudge {

    /** The verdict on {@code subject} against {@code criteria}, for the scenario it came from. */
    JudgeVerdict judge(String criteria, String subject, EvalScenario scenario);

    /** A judge that asks a model; what it is shown besides the answer is the scenario's input and context. */
    static RubricJudge model(LLMClient client, String identifier) {
        JudgeCalls calls = JudgeCalls.using(client).judgeIdentifier(identifier);
        return (criteria, subject, scenario) -> {
            Map<String, String> sections = new LinkedHashMap<>();
            if (scenario.input() != null) sections.put("Input", scenario.input());
            if (scenario.context() != null && !scenario.context().isEmpty()) sections.put("Context", String.join("\n", scenario.context()));
            return calls.rate("rubric", criteria, subject, sections);
        };
    }

    /** The score a judged line needs to count as met. */
    double THRESHOLD = 0.7;
}
