package io.github.llm4j.hexamind.eval.live;

import io.github.llm4j.agent.prompt.FileSystemPromptRegistry;
import io.github.llm4j.agent.prompt.PromptRegistry;
import io.github.llm4j.eval.assertions.AgentAssertions;
import io.github.llm4j.eval.compare.PromptComparison;
import io.github.llm4j.eval.compare.PromptComparisonAssertions;
import io.github.llm4j.eval.dataset.EvalScenario;
import io.github.llm4j.eval.report.EvalReportExtension;
import io.github.llm4j.hexamind.eval.EvalSupport;
import io.github.llm4j.hexamind.eval.GoldenDataset;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Layer 2: the prompts. Each scenario names one prompt id; the prompt's instructions are applied to the
 * scenario's input (through Alex with recorded search when the prompt needs tools, otherwise a plain
 * model call) and the output is judged against the rule the prompt exists to enforce. Then the
 * candidate rewrites in {@code eval/candidate-prompts.yaml} are compared with the current prompts, both
 * orders, by a pairwise judge.
 */
@ExtendWith(EvalReportExtension.class)
@org.junit.jupiter.api.Order(3)
class PromptEvalTest {

    private static final PromptRegistry CURRENT = EvalSupport.prompts();
    private static final PromptRegistry CANDIDATE =
            new FileSystemPromptRegistry(Path.of("eval", "candidate-prompts.yaml"));

    @BeforeAll
    static void declare() {
        EvalSupport.declare();
        EvalSupport.GUARD.stage("prompts", 1.20);
    }

    static Stream<EvalScenario> scenarios() {
        return GoldenDataset.prompts().stream();
    }

    private static String promptId(EvalScenario s) {
        return GoldenDataset.lines(s, "PROMPT:").get(0);
    }

    /** The prompt's instructions without its {{placeholder}} lines: the case supplies those. */
    private static String instructions(PromptRegistry registry, String id) {
        String template = registry.get(id).orElseThrow().getTemplate();
        return template.lines().filter(l -> !l.contains("{{")).collect(Collectors.joining("\n")).strip();
    }

    private static boolean usesTools(EvalScenario s) {
        return s.expectedTools() != null && !s.expectedTools().isEmpty();
    }

    /** The agent's run (through Alex, with recorded search) when the prompt needs tools, else a plain answer. */
    private static Object run(EvalScenario s, PromptRegistry registry, String label) {
        String instr = instructions(registry, promptId(s));
        if (usesTools(s)) {
            return EvalSupport.run("alex", s, "prompt:" + label, s.input() + "\n\n" + instr);
        }
        return EvalSupport.plain(instr, s.input());
    }

    private static Object output(EvalScenario s, PromptRegistry registry, String label) {
        Object r = run(s, registry, label);
        return r instanceof io.github.llm4j.agent.AgentResult a ? a.getFinalAnswer() : r;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void currentPromptEnforcesItsRule(EvalScenario s) {
        Object out = run(s, CURRENT, "current");
        SoftAssertions soft = new SoftAssertions();
        if (out instanceof io.github.llm4j.agent.AgentResult r) {
            for (String t : s.expectedTools()) {
                soft.check(() -> AgentAssertions.assertThat(r).usesTool(t));
            }
        }
        soft.assertThat((Object) out)
                .is(EvalSupport.rubric("Prompt rule", s, out, true, EvalSupport.judgeCache(), EvalSupport.JUDGE_ID, 1));
        soft.assertAll();
    }

    @Test
    void candidatesDoNotRegress() {
        List<EvalScenario> targets =
                scenarios().filter(s -> CANDIDATE.get(promptId(s)).isPresent()).collect(Collectors.toList());
        PromptComparison.Result result =
                PromptComparison.using(EvalSupport.judgeClient())
                        .criteria(
                                "Which output follows the prompt's intent better: it verifies and challenges"
                                        + " fabricated terms, is specific and concise, avoids clichés, and is actionable?")
                        .variantA("current", s -> output(s, CURRENT, "current"))
                        .variantB("candidate", s -> output(s, CANDIDATE, "candidate"))
                        .scenarios(targets)
                        .swapPositions(true)
                        .cache(EvalSupport.judgeCache())
                        .judgeIdentifier(EvalSupport.JUDGE_ID)
                        .run();
        PromptComparisonAssertions.assertThat(result).hasNoErrors();
        PromptComparisonAssertions.assertThat(result).doesNotRegress(0.05);
    }
}
