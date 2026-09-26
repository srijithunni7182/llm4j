package io.github.llm4j.getviral;

import static io.github.llm4j.eval.assertions.AgentAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.eval.dataset.EvalScenario;
import io.github.llm4j.eval.dataset.EvalScenarios;
import io.github.llm4j.eval.report.EvalReportExtension;
import io.github.llm4j.eval.report.PassRate;
import io.github.llm4j.getviral.engine.GetViralEngine;
import io.github.llm4j.getviral.studio.StudioRun;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * A YAML golden dataset of creator briefs, each run through the full workflow. Individual checks
 * are recorded into a {@link PassRate} so the suite gates on an overall quality bar — the right
 * shape for noisy, model-dependent behaviour when pointed at a real LLM.
 */
@ExtendWith(EvalReportExtension.class)
class GoldenBriefsEvalTest {

    @TempDir
    static Path dataDir;

    private static final PassRate PASS_RATE = new PassRate();

    static Stream<EvalScenario> briefs() {
        return EvalScenarios.fromYamlResource("getviral-golden.yaml").stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("briefs")
    void goldenBriefProducesAnOnBriefShippablePack(EvalScenario scenario) {
        GetViralEngine engine = GetViralTestSupport.engine(dataDir);
        GetViralEngine.Brief brief = GetViralTestSupport.brief(scenario.input(), "golden." + scenario.name());
        StudioRun run = GetViralTestSupport.newRun(brief);
        run.autopilot(GetViralTestSupport.creator(0, null, false));

        GetViralEngine.Outcome outcome = engine.run(run, brief);
        assertThat(outcome.status()).isEqualTo(StudioRun.Status.DONE);

        List<AgentResult> scout = outcome.executor().results().get("TrendScout");
        PASS_RATE.record(() -> assertThat(scout.get(scout.size() - 1))
                .usesToolsInOrder(scenario.expectedTools().toArray(String[]::new)));

        List<AgentResult> strategist = outcome.executor().results().get("Strategist");
        PASS_RATE.record(() -> assertThat(strategist.get(strategist.size() - 1))
                .hasFinalAnswerContaining(scenario.expectedOutputContains()));

        PASS_RATE.record(() -> assertThat(outcome.badges()).allSatisfy(b -> assertThat(b.passed()).isTrue()));
    }

    @AfterAll
    static void datasetClearsTheBar() {
        PASS_RATE.requireAtLeast(0.9);
    }
}
