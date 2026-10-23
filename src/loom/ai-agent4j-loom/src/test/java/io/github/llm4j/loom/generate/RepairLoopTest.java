package io.github.llm4j.loom.generate;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R12 to R14, R16 of spec 12: the loop terminates, never gets worse, and respects the cap. */
class RepairLoopTest {

    @TempDir Path dir;

    /** A gate that fails while the script holds BAD markers, with a problem for each. */
    static Gate markers(String id) {
        return new Gate() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public GateResult run(Workspace ws, Plan plan) {
                try {
                    String text = Files.readString(ws.script());
                    List<GateResult.Problem> p = new ArrayList<>();
                    int line = 0;
                    for (String l : text.split("\n")) {
                        line++;
                        if (l.contains("BAD"))
                            p.add(new GateResult.Problem("main.loom", line, "X", "remove BAD"));
                    }
                    return new GateResult(id, "markers", p);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }
        };
    }

    static String script(int bad) {
        return "workflow Main() {\n" + "    note \"BAD\"\n".repeat(bad) + "}\n";
    }

    private RepairLoop.Outcome run(
            ScriptedModel model, int perGate, int overall, Spend spend, Gate... gates) {
        return RepairLoop.run(
                model,
                "m",
                GenerateFixtures.plan(),
                "system",
                "write it",
                Map.of(),
                List.of(gates),
                new RepairLoop.Settings(
                        perGate,
                        overall,
                        spend,
                        Writer.Layout.FLAT,
                        new Writer.Stamp("t", "d"),
                        dir));
    }

    private static Spend roomy() {
        return new Spend(1_000_000, null, null);
    }

    @Test
    void aGoodFirstDraftIsDoneInOneAttempt() throws Exception {
        ScriptedModel model = ScriptedModel.of(ProjectReplies.withScript(script(0)));
        RepairLoop.Outcome o = run(model, 4, 8, roomy(), markers("G1"));
        assertThat(o.passed()).isTrue();
        assertThat(o.attempts()).hasSize(1);
        assertThat(model.calls()).isEqualTo(1);
        o.workspace().close();
    }

    @Test
    void aFailureIsFedBackAndOnlyTheFilesTheModelSendsAreChanged() throws Exception {
        Map<String, String> first = new LinkedHashMap<>();
        first.put("main.loom", script(2));
        first.put("README.md", "# keep me\n");
        ScriptedModel model =
                ScriptedModel.of(ProjectReplies.files(first), ProjectReplies.withScript(script(0)));
        RepairLoop.Outcome o = run(model, 4, 8, roomy(), markers("G1"));
        assertThat(o.passed()).isTrue();
        assertThat(o.attempts()).hasSize(2);
        // the repair request shows the files as they were, what failed and where, and asks for only
        // what changes
        String repair = model.asked.get(1);
        assertThat(repair)
                .contains("Gate G1 (markers) failed")
                .contains("main.loom:2: [X] remove BAD")
                .contains("# keep me")
                .contains("only the files that must change");
        // the file the model did not resend is still there
        assertThat(Files.readString(o.workspace().dir().resolve("README.md")))
                .contains("# keep me");
        o.workspace().close();
    }

    @Test
    void aWorseAttemptIsThrownAwayAndTheBestIsRetriedWithTheFeedback() throws Exception {
        ScriptedModel model =
                ScriptedModel.of(
                        ProjectReplies.withScript(script(1)),
                        ProjectReplies.withScript(script(5)),
                        ProjectReplies.withScript(script(0)));
        RepairLoop.Outcome o = run(model, 4, 8, roomy(), markers("G1"));
        assertThat(o.passed()).isTrue();
        assertThat(o.attempts())
                .extracting(RepairLoop.Attempt::kept)
                .containsExactly(true, false, true);
        assertThat(o.attempts().get(1).note()).contains("thrown away");
        // after the bad attempt the model is shown the best draft (one BAD), not its own five
        String third = model.asked.get(2);
        assertThat(third).contains("made things worse and was thrown away");
        assertThat(third.lines().filter(l -> l.contains("note \"BAD\"")).count()).isEqualTo(1);
    }

    @Test
    void aModelThatOscillatesOrRepeatsItselfStopsAtTheAttemptLimit() throws Exception {
        ScriptedModel stuck = ScriptedModel.of(ProjectReplies.withScript(script(2)));
        RepairLoop.Outcome o = run(stuck, 3, 8, roomy(), markers("G1"));
        assertThat(o.status()).isEqualTo(RepairLoop.Status.GATE_FAILED);
        assertThat(o.attempts()).hasSize(3);
        assertThat(o.stoppedBy()).contains("gate G1 still failed after 3 attempts");
        assertThat(o.workspace()).isNotNull();
        o.workspace().close();
        ScriptedModel flip =
                new ScriptedModel(n -> ProjectReplies.withScript(script(n % 2 == 0 ? 1 : 3)), 150);
        RepairLoop.Outcome f = run(flip, 4, 8, roomy(), markers("G1"));
        assertThat(f.status()).isEqualTo(RepairLoop.Status.GATE_FAILED);
        assertThat(f.attempts().size()).isLessThanOrEqualTo(4);
        f.workspace().close();
    }

    @Test
    void theOverallLimitStopsALoopThatMovesBetweenGates() throws Exception {
        // each attempt fixes one gate and breaks the other: G1 fails, then G2, ...
        ScriptedModel model =
                new ScriptedModel(
                        n ->
                                ProjectReplies.withScript(
                                        n % 2 == 0
                                                ? script(2)
                                                : "workflow Main() {\n    note \"ODD\"\n}\n"),
                        150);
        Gate odd =
                new Gate() {
                    public String id() {
                        return "G2";
                    }

                    public GateResult run(Workspace ws, Plan plan) {
                        try {
                            return Files.readString(ws.script()).contains("ODD")
                                    ? new GateResult(
                                            "G2",
                                            "odd",
                                            List.of(
                                                    new GateResult.Problem(
                                                            "main.loom", 2, "", "no ODD")))
                                    : GateResult.pass("G2", "odd");
                        } catch (IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                    }
                };
        RepairLoop.Outcome o = run(model, 100, 5, roomy(), markers("G1"), odd);
        assertThat(o.status()).isEqualTo(RepairLoop.Status.GATE_FAILED);
        assertThat(o.attempts()).hasSize(5);
        assertThat(o.stoppedBy()).contains("limit of 5 attempts");
        o.workspace().close();
    }

    @Test
    void aReplyWithNoFilesOrAnEscapingPathIsAFailureTheModelIsToldAbout() throws Exception {
        Map<String, String> escape = new LinkedHashMap<>();
        escape.put("../evil.loom", "x");
        ScriptedModel model =
                ScriptedModel.of(
                        "I could not do it.",
                        ProjectReplies.files(escape),
                        ProjectReplies.withScript(script(0)));
        RepairLoop.Outcome o = run(model, 4, 8, roomy(), markers("G1"));
        assertThat(o.passed()).isTrue();
        assertThat(model.asked.get(1)).contains("held no files");
        assertThat(model.asked.get(2)).contains("leaves the project folder");
        assertThat(Files.exists(dir.resolveSibling("evil.loom"))).isFalse();
        o.workspace().close();
    }

    @Test
    void theSpendCapStopsBeforeTheNextCallAndKeepsTheBestDraft() throws Exception {
        ScriptedModel model = new ScriptedModel(n -> ProjectReplies.withScript(script(2)), 150);
        Spend spend = new Spend(250, null, null);
        RepairLoop.Outcome o = run(model, 10, 10, spend, markers("G1"));
        assertThat(o.status()).isEqualTo(RepairLoop.Status.CAP);
        // 150 + 150 = 300 tokens: the third call is never made
        assertThat(model.calls()).isEqualTo(2);
        assertThat(o.stoppedBy()).contains("spend cap of 250 tokens");
        assertThat(o.workspace()).isNotNull();
        o.workspace().close();
    }

    @Test
    void aMoneyCapCountsOnlyWhenThePriceIsKnown() {
        io.github.llm4j.budget.PriceTable.Price price =
                new io.github.llm4j.budget.PriceTable.Price(
                        new BigDecimal("1000000"), new BigDecimal("1000000"));
        Spend priced = new Spend(1_000_000, new BigDecimal("0.50"), price);
        priced.beforeCall();
        priced.add(
                io.github.llm4j.model.LLMResponse.builder()
                        .content("x")
                        .model("m")
                        .tokenUsage(1, 0, 1)
                        .build());
        org.assertj.core.api.Assertions.assertThatThrownBy(priced::beforeCall)
                .isInstanceOf(Spend.CapReached.class)
                .hasMessageContaining("$0.50");
        Spend unpriced = new Spend(1_000_000, new BigDecimal("0.50"), null);
        unpriced.add(
                io.github.llm4j.model.LLMResponse.builder()
                        .content("x")
                        .model("m")
                        .tokenUsage(1, 0, 1)
                        .build());
        unpriced.beforeCall();
        assertThat(unpriced.cost()).isNull();
        assertThat(priced.cost()).isEqualByComparingTo("1");
    }

    @Test
    void nothingIsLeftBehindInTheTemporaryFolderWhenTheLoopEnds() throws Exception {
        ScriptedModel model =
                ScriptedModel.of(
                        ProjectReplies.withScript(script(1)),
                        ProjectReplies.withScript(script(4)),
                        ProjectReplies.withScript(script(0)));
        RepairLoop.Outcome o = run(model, 4, 8, roomy(), markers("G1"));
        o.workspace().close();
        try (var left = Files.list(dir)) {
            assertThat(left.toList()).isEmpty();
        }
    }
}
