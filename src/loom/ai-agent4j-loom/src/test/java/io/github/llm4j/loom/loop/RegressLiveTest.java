package io.github.llm4j.loom.loop;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.LLMClient;
import io.github.llm4j.loom.execution.LLMClientFactory;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * R19, R21, R24, R25, R27, R30 of spec 14: live comparisons under a cap, judges, approved cases,
 * cost.
 */
class RegressLiveTest {

    @TempDir Path dir;

    private static final ObjectMapper M = new ObjectMapper();

    /** A model that answers a fixed text and counts what it was asked. */
    static final class FakeModels implements LLMClientFactory {
        final AtomicInteger calls = new AtomicInteger();
        final String answer;

        FakeModels(String answer) {
            this.answer = answer;
        }

        @Override
        public LLMClient createClient(String model) {
            return new LLMClient() {
                @Override
                public LLMResponse chat(LLMRequest r) {
                    calls.incrementAndGet();
                    return LLMResponse.builder()
                            .content(answer)
                            .model(model)
                            .tokenUsage(100, 20, 120)
                            .build();
                }

                @Override
                public Stream<LLMResponse> chatStream(LLMRequest r) {
                    return Stream.of(chat(r));
                }
            };
        }
    }

    private Path store(Path script, int orders) throws Exception {
        Path store = dir.resolve("store");
        for (int i = 0; i < orders; i++) {
            var body = M.createObjectNode();
            body.putObject("input").put("message", "message " + i);
            body.putArray("humans").add("yes");
            var models = body.putObject("models");
            models.putObject("Intent").put("kind", "order_status").put("urgent", "no");
            models.put("Replier", "On its way.");
            Runs.run(store, "o" + i, script, body, Map.of("message", "message " + i));
        }
        return store;
    }

    private Regress.Request request(
            Path candidate, Path base, Path store, Regress.Live live, Judges.Judge judge, int limit)
            throws Exception {
        Baselines b = Baselines.of(candidate, base, null, dir.resolve("t" + System.nanoTime()));
        return new Regress.Request(
                candidate,
                b,
                store,
                null,
                limit,
                Sampler.Strategy.STRATIFIED,
                7,
                "none",
                null,
                null,
                30,
                live,
                judge,
                null,
                null);
    }

    private Path reworded() throws Exception {
        return Runs.project(
                dir.resolve("reworded"),
                Runs.TRIAGE.replace("Reply to: {message}", "Please reply politely to: {message}"));
    }

    @Test
    void liveModelsAreAskedOnlyWhereTheQuestionChangedAndTheReportSeparatesThoseRuns()
            throws Exception {
        Path base = Runs.project(dir.resolve("proj"), Runs.TRIAGE);
        Path store = store(base, 3);
        FakeModels models = new FakeModels("Freshly worded.");
        Spent spent = new Spent(100_000L, null);
        Regress.Live live = new Regress.Live(models, null, k -> null, spent);
        Regress.Report r = Regress.run(request(reworded(), base, store, live, null, 200));
        assertThat(models.calls.get()).isEqualTo(3);
        assertThat(r.liveCompared()).isEqualTo(3);
        assertThat(spent.tokens()).isEqualTo(360);
        assertThat(spent.calls()).isEqualTo(3);
        String text = RegressReport.text(r, false, null);
        assertThat(text)
                .contains("with live models: 3")
                .contains("(measured)")
                .doesNotContain("STOPPED");
        // unchanged runs with the same candidate need no model at all
        FakeModels none = new FakeModels("unused");
        Regress.Report same =
                Regress.run(
                        request(
                                Runs.project(dir.resolve("same"), Runs.TRIAGE),
                                base,
                                store,
                                new Regress.Live(none, null, k -> null, new Spent(1000L, null)),
                                null,
                                200));
        assertThat(none.calls.get()).isZero();
        assertThat(same.count(Regress.Verdict.SAME)).isEqualTo(3);
    }

    @Test
    void theCapStopsTheComparisonCleanlyAndThePartialReportSaysSo() throws Exception {
        Path base = Runs.project(dir.resolve("proj"), Runs.TRIAGE);
        Path store = store(base, 5);
        FakeModels models = new FakeModels("Freshly worded.");
        Spent spent =
                new Spent(150L, null); // room for one call of 120 tokens, then the cap is reached
        Regress.Report r =
                Regress.run(
                        request(
                                reworded(),
                                base,
                                store,
                                new Regress.Live(models, null, k -> null, spent),
                                null,
                                200));
        assertThat(r.stoppedByCap()).isTrue();
        assertThat(models.calls.get()).isLessThanOrEqualTo(2);
        assertThat(r.count(Regress.Verdict.NOT_COMPARED)).isGreaterThanOrEqualTo(3);
        assertThat(RegressReport.text(r, false, null)).contains("STOPPED BY THE SPEND CAP");
    }

    @Test
    void aJudgeSpendsFromTheSameCapAndNeverRunsWithoutOne() throws Exception {
        Path base = Runs.project(dir.resolve("proj"), Runs.TRIAGE);
        Path store = store(base, 2);
        // the candidate changes the text a run returns, without changing its route
        Path other =
                Runs.project(
                        dir.resolve("other"),
                        Runs.TRIAGE
                                .replace("return reply", "return \"Changed: \" + reply")
                                .replace("\"Changed: \" + reply", "reply"));
        FakeModels judgeModel = new FakeModels("DIFFERENT");
        Spent spent = new Spent(1000L, null);
        Judges.Judge judge =
                Judges.rubric(
                        judgeModel.createClient("judge"), "judge", "Tone matters.", spent, null);
        assertThat(judge.differs("a", "b")).isTrue();
        assertThat(spent.tokens()).isEqualTo(120);
        Spent none = new Spent(100L, null);
        none.add(100, 1, null);
        Judges.Judge capped =
                Judges.rubric(judgeModel.createClient("judge"), "judge", "x", none, null);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> capped.differs("a", "b"))
                .isInstanceOf(Regress.CapReached.class);
        FakeModels unsure = new FakeModels("maybe");
        Judges.Judge vague =
                Judges.rubric(unsure.createClient("j"), "j", "x", new Spent(1000L, null), null);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> vague.differs("a", "b"))
                .hasMessageContaining("neither SAME nor DIFFERENT");
        assertThat(store).exists();
        assertThat(other).exists();
    }

    @Test
    void approvedCapturedCasesAreAlwaysRunAndAFlipOnOneIsReportedWhateverTheSample()
            throws Exception {
        Path base = Runs.project(dir.resolve("proj"), Runs.TRIAGE);
        Path store = store(base, 4);
        // a captured, approved case for the script
        RunRecord run = StoreReader.find("o0", store);
        Capture.Captured c =
                Capture.capture(
                        run,
                        new Capture.Options(
                                dir.resolve("cases"),
                                Capture.Expect.OBSERVED,
                                null,
                                null,
                                null,
                                true,
                                null,
                                null),
                        true);
        Approval.approve(c.file(), "ana", null, Map.of());
        Path broken =
                Runs.project(
                        dir.resolve("broken"),
                        Runs.TRIAGE.replace(
                                "intent.kind == \"order_status\"", "intent.kind == \"refund\""));
        Regress.Request req = request(broken, base, store, null, null, 1);
        req =
                new Regress.Request(
                        req.candidate(),
                        req.baselines(),
                        req.store(),
                        null,
                        1,
                        Sampler.Strategy.RANDOM,
                        7,
                        "none",
                        null,
                        null,
                        30,
                        null,
                        null,
                        null,
                        List.of(dir.resolve("cases")));
        Regress.Report r = Regress.run(req);
        assertThat(r.sample().chosen()).hasSize(1);
        assertThat(r.approvedRun()).isEqualTo(1);
        assertThat(r.approvedFlips()).hasSize(1);
        assertThat(RegressReport.text(r, false, null))
                .contains("[approved]")
                .contains("Approved captured cases: 1");
    }

    @Test
    void costIsLabelledEstimatedWhenItComesFromTheScriptsAndMeasuredWhenItWasSpent()
            throws Exception {
        Path base = Runs.project(dir.resolve("proj"), Runs.TRIAGE);
        Path store = store(base, 2);
        Regress.Request r0 = request(reworded(), base, store, null, null, 200);
        Regress.Costing costing =
                script ->
                        Map.of(
                                "Main",
                                script.toString().contains("reworded")
                                        ? new Regress.CostFigure(1400, new BigDecimal("0.0014"))
                                        : new Regress.CostFigure(1000, new BigDecimal("0.0010")));
        Regress.Report r =
                Regress.run(
                        new Regress.Request(
                                r0.candidate(),
                                r0.baselines(),
                                r0.store(),
                                null,
                                200,
                                Sampler.Strategy.STRATIFIED,
                                7,
                                "none",
                                null,
                                null,
                                30,
                                null,
                                null,
                                costing,
                                null));
        String text = RegressReport.text(r, false, null);
        assertThat(text)
                .contains("1000 to 1400 tokens (+40 %)")
                .contains("estimated from the prompts and settings")
                .contains("not measured");
        assertThat(RegressReport.costUp(r)).isBetween(39.0, 41.0);
    }

    @Test
    void twoHundredRecordedRunsAreComparedInWellUnderThirtySeconds() throws Exception {
        Path base = Runs.project(dir.resolve("proj"), Runs.TRIAGE);
        Path store = store(base, 200);
        Path same =
                Runs.project(
                        dir.resolve("same"),
                        Runs.TRIAGE.replace("Reply to: {message}", "Reply to: {message}"));
        long t0 = System.nanoTime();
        Regress.Report r = Regress.run(request(same, base, store, null, null, 200));
        long seconds = (System.nanoTime() - t0) / 1_000_000_000L;
        assertThat(r.comparisons()).hasSize(200);
        assertThat(seconds).as("seconds for 200 runs").isLessThan(30);
        assertThat(Files.exists(store)).isTrue();
    }
}
