package io.github.llm4j.evalreport;

import static io.github.llm4j.eval.judge.LlmJudgeCondition.llmJudged;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.eval.dataset.EvalScenario;
import io.github.llm4j.eval.export.EvalRun;
import io.github.llm4j.eval.export.ExportConfig;
import io.github.llm4j.eval.judge.InMemoryJudgeCache;
import io.github.llm4j.eval.report.EvalRecorder;
import io.github.llm4j.eval.testing.FakeJudge;
import io.github.llm4j.evalreport.config.ReportConfig;
import io.github.llm4j.evalreport.format.RunStore;
import io.github.llm4j.evalreport.model.ReportModel;
import io.github.llm4j.evalreport.render.HtmlRenderer;
import io.github.llm4j.evalreport.render.MarkdownSummary;
import io.github.llm4j.evalreport.render.StaticRenderer;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** "Which agent scored how much on what": results broken down by the scenario's agent tag. */
class BreakdownTest {

    private static EvalScenario scenario(String id, String agent, List<String> dims) {
        return new EvalScenario(
                id,
                "Question " + id,
                null,
                null,
                null,
                null,
                null,
                id,
                dims,
                List.of("agent:" + agent));
    }

    private static void judge(EvalScenario s, int rating) {
        EvalRun.get().bindTest("com.acme.AgentTest", s.id());
        EvalRun.get().bindScenario(s);
        llmJudged("Rubric")
                .criteria("c")
                .scenario(s)
                .judge(FakeJudge.rating(rating))
                .cache(InMemoryJudgeCache.create())
                .threshold(0.7)
                .build()
                .matches("an answer");
        EvalRun.get().recordTest("com.acme.AgentTest", s.id(), "PASSED", 5, null);
        EvalRun.get().unbind();
    }

    private static ReportModel model(Path root) {
        EvalRun.resetForTests();
        System.setProperty(ExportConfig.DIR, root.toString());
        System.setProperty(ExportConfig.RUN_ID, "RUN-BREAKDOWN-0001");
        try {
            EvalRecorder.reset();
            EvalRecorder.activate();
            List<EvalScenario> all =
                    List.of(
                            scenario("a-1", "alex", List.of("fact-checking", "safety")),
                            scenario("a-2", "alex", List.of("fact-checking")),
                            scenario("r-1", "rahul", List.of("fact-checking")),
                            scenario("r-2", "rahul", List.of("safety")));
            EvalRun.get().declareDataset("ds", "ds.yaml", "ds.yaml", all);
            EvalRun.get().declareJudge("judge-main", "fake", "fake-1", 0.0, 1, "MEAN");
            judge(all.get(0), 5); // alex fact-checking + safety pass
            judge(all.get(1), 1); // alex fact-checking fails
            judge(all.get(2), 5); // rahul fact-checking passes
            judge(all.get(3), 1); // rahul safety fails
            EvalRun.get().finish();
        } finally {
            System.clearProperty(ExportConfig.DIR);
            System.clearProperty(ExportConfig.RUN_ID);
            EvalRecorder.reset();
        }
        return EvalReport.build(new RunStore(root), null, null, true, ReportConfig.defaults());
    }

    @Test
    void eachAgentGetsARowAndEachDimensionAColumn(@TempDir Path root) {
        ReportModel m = model(root);
        assertThat(m.breakdowns()).hasSize(1);
        var b = m.breakdowns().get(0);
        assertThat(b.key()).isEqualTo("agent");
        assertThat(b.rows()).extracting(r -> r.value()).containsExactly("alex", "rahul");
        assertThat(b.columns())
                .extracting(c -> c.dimension())
                .containsExactlyInAnyOrder("fact-checking", "safety");

        var alex = b.rows().get(0);
        var alexFact = alex.cells().get(indexOf(b, "fact-checking"));
        assertThat(alexFact.passed()).isEqualTo(1);
        assertThat(alexFact.failed()).isEqualTo(1);
        assertThat(alexFact.rate()).isEqualTo(50.0);
        assertThat(alexFact.failedCases()).containsExactly("a-2");
        var alexSafety = alex.cells().get(indexOf(b, "safety"));
        assertThat(alexSafety.rate()).isEqualTo(100.0);

        var rahul = b.rows().get(1);
        assertThat(rahul.cells().get(indexOf(b, "safety")).failedCases()).containsExactly("r-2");
        assertThat(rahul.caseIds()).hasSize(2);
        assertThat(alex.overall().passed() + alex.overall().failed()).isEqualTo(3);
    }

    private static int indexOf(ReportModel.BreakdownView b, String dimension) {
        for (int i = 0; i < b.columns().size(); i++) {
            if (b.columns().get(i).dimension().equals(dimension)) {
                return i;
            }
        }
        throw new AssertionError(dimension);
    }

    @Test
    void theBreakdownReachesEveryRenderer(@TempDir Path root) {
        ReportModel m = model(root);
        assertThat(MarkdownSummary.summary(m))
                .contains("By agent")
                .contains("| alex |")
                .contains("50% (1/2)");
        assertThat(StaticRenderer.render(m)).contains("by dimension").contains("alex");
        String html = HtmlRenderer.render(m);
        assertThat(html).contains("\"breakdowns\"").contains("viewBreakdownRow");
    }

    @Test
    void noAgentTagsMeansNoBreakdown(@TempDir Path root) {
        EvalRun.resetForTests();
        System.setProperty(ExportConfig.DIR, root.toString());
        System.setProperty(ExportConfig.RUN_ID, "RUN-BREAKDOWN-0002");
        try {
            EvalRecorder.reset();
            EvalRecorder.activate();
            EvalScenario s =
                    new EvalScenario(
                            "x",
                            "q",
                            null,
                            null,
                            null,
                            null,
                            null,
                            "x",
                            List.of("safety"),
                            List.of("family:demo"));
            EvalRun.get().declareDataset("ds", "ds.yaml", "ds.yaml", List.of(s));
            judge(s, 5);
            EvalRun.get().finish();
        } finally {
            System.clearProperty(ExportConfig.DIR);
            System.clearProperty(ExportConfig.RUN_ID);
            EvalRecorder.reset();
        }
        ReportModel m =
                EvalReport.build(new RunStore(root), null, null, true, ReportConfig.defaults());
        assertThat(m.breakdowns()).isEmpty();
    }
}
