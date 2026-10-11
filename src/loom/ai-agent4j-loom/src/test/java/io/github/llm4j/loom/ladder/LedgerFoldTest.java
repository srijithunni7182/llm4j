package io.github.llm4j.loom.ladder;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.autonomy.Rec;
import io.github.llm4j.loom.autonomy.expr.V;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Records in the ledger folded into cases, and cases into what an expression sees. */
@Tag("L3")
class LedgerFoldTest {

    static final Instant T0 = Instant.parse("2026-10-01T10:00:00Z");

    static Instant at(long seconds) {
        return T0.plusSeconds(seconds);
    }

    static Rec caseRec(String id, int gen, long sec, String level, String scope, Map<String, String> fields, int epoch, String identity) {
        return new Rec(id + "#case#" + gen, "D", Rec.CASE, at(sec), id, gen,
                Rec.map("scope", scope, "level", level, "how", level, "fields", fields, "identity", identity, "epoch", epoch, "servedModel", "m-1"));
    }

    static Rec proposed(String id, int gen, long sec, Map<String, Object> body) {
        return new Rec(id + "#proposed#" + gen, "D", Rec.PROPOSED, at(sec), id, gen, body);
    }

    static Rec responded(String id, int gen, long sec, Map<String, Object> body) {
        return new Rec(id + "#responded#" + gen, "D", Rec.RESPONDED, at(sec), id, gen, body);
    }

    static Rec outcome(String id, long sec, String name, Object value) {
        return new Rec(id + "#outcome#" + name + "#" + sec, "D", Rec.OUTCOME, at(sec), id, 1, Rec.map("result", name, "value", value));
    }

    @Test
    void aChoiceCaseFoldsIntoItsPartsWithTheTimeThePersonTook() {
        List<LCase> cases = LedgerFold.cases(List.of(
                caseRec("c1", 1, 0, "watch", "gold", Map.of("amount", "250"), 1, "id-a"),
                proposed("c1", 1, 5, Rec.map("choice", "approve", "reasoning", "fine", "confidence", 0.8)),
                responded("c1", 1, 65, Rec.map("kind", "accept", "choice", "approve", "by", "lead", "shown", false, "millis", 60000L))));
        assertThat(cases).hasSize(1);
        LCase c = cases.get(0);
        assertThat(c.id()).isEqualTo("c1");
        assertThat(c.scope()).isEqualTo("gold");
        assertThat(c.level()).isEqualTo("watch");
        assertThat(c.epoch()).isEqualTo(1);
        assertThat(c.fields()).containsEntry("amount", "250");
        assertThat(c.proposalChoice()).isEqualTo("approve");
        assertThat(c.reasoning()).isEqualTo("fine");
        assertThat(c.confidence()).isEqualTo(0.8);
        assertThat(c.servedModel()).isEqualTo("m-1");
        assertThat(c.responded()).isTrue();
        assertThat(c.responseKind()).isEqualTo("accept");
        assertThat(c.responseChoice()).isEqualTo("approve");
        assertThat(c.responder()).isEqualTo("lead");
        assertThat(c.shown()).isFalse();
        assertThat(c.blind()).isTrue();
        assertThat(c.reviewMillis()).isEqualTo(60000L);
        assertThat(c.superseded()).isFalse();
    }

    @Test
    void anArtifactCaseKeepsTheProposalAndTheEditedFinalVersion() {
        List<LCase> cases = LedgerFold.cases(List.of(
                caseRec("c1", 1, 0, "review", "all", Map.of(), 1, "id-a"),
                proposed("c1", 1, 5, Rec.map("value", "Hello wrld", "reasoning", "draft")),
                responded("c1", 1, 100, Rec.map("kind", "edit", "final", "Hello world", "by", "editor", "shown", true, "millis", 95000L))));
        LCase c = cases.get(0);
        assertThat(c.proposalValue()).isEqualTo("Hello wrld");
        assertThat(c.responseKind()).isEqualTo("edit");
        assertThat(c.responseFinal()).isEqualTo("Hello world");
        assertThat(c.shown()).isTrue();
        assertThat(c.blind()).isFalse();
    }

    @Test
    void anAutonomousCaseIsDecidedByTheAgentAndIsNotBlindEvidence() {
        LCase c = LedgerFold.cases(List.of(
                caseRec("c1", 1, 0, "act", "all", Map.of(), 1, "id-a"),
                proposed("c1", 1, 1, Rec.map("choice", "approve")),
                responded("c1", 1, 1, Rec.map("kind", "accept", "choice", "approve", "by", "agent", "shown", false)))).get(0);
        assertThat(c.responder()).isEqualTo("agent");
        assertThat(c.blind()).isFalse();
    }

    @Test
    void outcomesAccumulateInOrderWithTheirValuesAndALateOneIsFound() {
        List<Rec> recs = new ArrayList<>(List.of(
                caseRec("c1", 1, 0, "act", "all", Map.of(), 1, "id-a"),
                proposed("c1", 1, 1, Rec.map("choice", "approve")),
                responded("c1", 1, 2, Rec.map("kind", "accept", "choice", "approve", "by", "agent"))));
        assertThat(LedgerFold.cases(recs).get(0).outcomes()).isEmpty();
        recs.add(outcome("c1", 1000, "reverted", null));
        recs.add(outcome("c1", 2000, "views_7d", 5400));
        LCase c = LedgerFold.cases(recs).get(0);
        assertThat(c.outcomes()).extracting(LCase.Outcome::name).containsExactly("reverted", "views_7d");
        assertThat(c.outcomes().get(1).value()).isEqualTo(5400);
        assertThat(c.latestOutcome()).isEqualTo("views_7d");
    }

    @Test
    void aRewoundCaseKeepsBothGenerationsAndOnlyTheLaterOneCounts() {
        List<LCase> cases = LedgerFold.cases(List.of(
                caseRec("c1", 1, 0, "watch", "all", Map.of(), 1, "id-a"),
                proposed("c1", 1, 1, Rec.map("choice", "reject")),
                responded("c1", 1, 2, Rec.map("kind", "accept", "choice", "reject", "by", "lead")),
                caseRec("c1", 2, 50, "watch", "all", Map.of(), 1, "id-a"),
                proposed("c1", 2, 51, Rec.map("choice", "approve")),
                responded("c1", 2, 52, Rec.map("kind", "accept", "choice", "approve", "by", "lead"))));
        assertThat(cases).hasSize(2);
        assertThat(cases.get(0).superseded()).isTrue();
        assertThat(cases.get(1).superseded()).isFalse();
        assertThat(cases.get(1).responseChoice()).isEqualTo("approve");
    }

    @Test
    void anUnansweredCaseAndOrphanRecordsAreTolerated() {
        List<LCase> cases = LedgerFold.cases(List.of(
                caseRec("c1", 1, 0, "watch", "all", Map.of(), 1, "id-a"),
                proposed("ghost", 1, 1, Rec.map("choice", "approve")),
                outcome("ghost", 5, "reverted", null)));
        assertThat(cases).hasSize(1);
        assertThat(cases.get(0).responded()).isFalse();
        assertThat(cases.get(0).proposalChoice()).isNull();
    }

    @Test
    void aCaseWrittenByTheEarlierEngineReadsAsAnAcceptOrAReject() {
        List<Rec> recs = List.of(
                caseRec("old1", 1, 0, "watch", "gold", Map.of("amount", "40"), 1, "id-a"),
                proposed("old1", 1, 1, Rec.map("choice", "approve", "reasoning", "r")),
                new Rec("old1#decided#1", "D", Rec.DECIDED, at(60), "old1", 1, Rec.map("verdict", "approve", "decider", "lead", "shown", false, "millis", 59000L)),
                caseRec("old2", 1, 100, "suggest", "gold", Map.of(), 1, "id-a"),
                proposed("old2", 1, 101, Rec.map("choice", "approve")),
                new Rec("old2#decided#1", "D", Rec.DECIDED, at(160), "old2", 1, Rec.map("verdict", "reject", "decider", "lead", "shown", true, "millis", 1000L)));
        List<LCase> cases = LedgerFold.cases(recs);
        assertThat(cases.get(0).responseKind()).isEqualTo("accept");
        assertThat(cases.get(0).responseChoice()).isEqualTo("approve");
        assertThat(cases.get(0).blind()).isTrue();
        assertThat(cases.get(0).reviewMillis()).isEqualTo(59000L);
        assertThat(cases.get(1).responseKind()).isEqualTo("reject");
        assertThat(cases.get(1).responseChoice()).isEqualTo("reject");
        assertThat(cases.get(1).shown()).isTrue();
        assertThat(HistoryBuilder.history(cases, "gold", 1).cases()).hasSize(2);
    }

    // ---------- what an expression sees ----------

    @Test
    void historyHoldsOnlyAnsweredCurrentEpochCasesInOrder() {
        List<Rec> recs = new ArrayList<>();
        recs.addAll(List.of(caseRec("a", 1, 0, "watch", "all", Map.of(), 1, "old"), proposed("a", 1, 1, Rec.map("choice", "x")),
                responded("a", 1, 2, Rec.map("kind", "accept", "choice", "x", "by", "p"))));
        recs.addAll(List.of(caseRec("b", 1, 10, "watch", "all", Map.of(), 2, "new"), proposed("b", 1, 11, Rec.map("choice", "x")),
                responded("b", 1, 12, Rec.map("kind", "accept", "choice", "x", "by", "p"))));
        recs.addAll(List.of(caseRec("c", 1, 20, "watch", "all", Map.of(), 2, "new"), proposed("c", 1, 21, Rec.map("choice", "x"))));
        recs.addAll(List.of(caseRec("d", 1, 30, "watch", "other", Map.of(), 2, "new"), proposed("d", 1, 31, Rec.map("choice", "x")),
                responded("d", 1, 32, Rec.map("kind", "accept", "choice", "x", "by", "p"))));
        List<LCase> all = LedgerFold.cases(recs);
        V.Cs cur = HistoryBuilder.history(all, "all", 2);
        assertThat(cur.cases()).hasSize(1);
        assertThat(cur.cases().get(0).get("id")).isEqualTo(V.str("b"));
        assertThat(HistoryBuilder.history(all, "all", 1).cases()).hasSize(1);
        assertThat(HistoryBuilder.history(all, "other", 2).cases()).hasSize(1);
        assertThat(HistoryBuilder.history(all, "all", -1).cases()).hasSize(2);       // every epoch (history.all)
    }

    @Test
    void aCaseAsAnExpressionSeesIt() {
        List<Rec> recs = List.of(
                caseRec("c1", 1, 0, "watch", "gold", Map.of("amount", "250", "reason", "late"), 1, "id-a"),
                proposed("c1", 1, 5, Rec.map("choice", "approve", "value", "text", "reasoning", "r", "confidence", 0.5)),
                responded("c1", 1, 65, Rec.map("kind", "edit", "choice", "reject", "final", "text2", "by", "lead", "shown", false, "millis", 60000L)),
                outcome("c1", 3000, "reverted", null),
                outcome("c1", 4000, "views_7d", 5400));
        V.Rec r = HistoryBuilder.history(LedgerFold.cases(recs), "gold", 1).cases().get(0);
        assertThat(r.get("amount")).isEqualTo(V.num(250));                       // remembered numbers are numbers
        assertThat(r.get("reason")).isEqualTo(V.str("late"));
        assertThat(r.get("scope")).isEqualTo(V.str("gold"));
        assertThat(r.get("level")).isEqualTo(V.str("watch"));
        assertThat(r.get("epoch")).isEqualTo(V.num(1));
        assertThat(r.get("at")).isEqualTo(V.num(at(0).toEpochMilli()));
        V.Rec proposal = (V.Rec) r.get("proposal");
        assertThat(proposal.get("choice")).isEqualTo(V.str("approve"));
        assertThat(proposal.get("value")).isEqualTo(V.str("text"));
        assertThat(proposal.get("confidence")).isEqualTo(V.num(0.5));
        V.Rec response = (V.Rec) r.get("response");
        assertThat(response.get("kind")).isEqualTo(V.str("edit"));
        assertThat(response.get("choice")).isEqualTo(V.str("reject"));
        assertThat(response.get("final")).isEqualTo(V.str("text2"));
        assertThat(response.get("by")).isEqualTo(V.str("lead"));
        assertThat(r.get("shown")).isEqualTo(V.bool(false));
        assertThat(r.get("blind")).isEqualTo(V.bool(true));
        assertThat(r.get("review_time")).isEqualTo(V.dur(60000));
        assertThat(r.get("latency")).isEqualTo(V.dur(60000));
        assertThat(r.get("proposed_at")).isEqualTo(V.num(at(5).toEpochMilli()));
        assertThat(r.get("responded_at")).isEqualTo(V.num(at(65).toEpochMilli()));
        assertThat(r.get("outcome")).isEqualTo(V.str("views_7d"));
        V.Rec outcomes = (V.Rec) r.get("outcomes");
        assertThat(outcomes.get("reverted")).isEqualTo(V.bool(true));
        assertThat(outcomes.get("views_7d")).isEqualTo(V.num(5400));
        assertThat(outcomes.get("nothing")).isEqualTo(V.UNDEFINED);
    }

    @Test
    void whatHasNotHappenedIsUndefinedNotFalse() {
        List<Rec> recs = List.of(
                caseRec("c1", 1, 0, "act", "all", Map.of(), 1, "id-a"),
                proposed("c1", 1, 1, Rec.map("choice", "approve")),
                responded("c1", 1, 1, Rec.map("kind", "accept", "choice", "approve", "by", "agent")));
        V.Rec r = HistoryBuilder.history(LedgerFold.cases(recs), "all", 1).cases().get(0);
        assertThat(r.get("outcome")).isEqualTo(V.UNDEFINED);
        assertThat(r.get("amount")).isEqualTo(V.UNDEFINED);
        assertThat(((V.Rec) r.get("proposal")).get("value")).isEqualTo(V.UNDEFINED);
        assertThat(((V.Rec) r.get("proposal")).get("confidence")).isEqualTo(V.UNDEFINED);
        assertThat(((V.Rec) r.get("response")).get("final")).isEqualTo(V.UNDEFINED);
        assertThat(r.get("blind")).isEqualTo(V.bool(false));
    }

    @Test
    void textThatLooksLikeANumberIsOneAndTextThatDoesNotStaysText() {
        assertThat(HistoryBuilder.typed("250")).isEqualTo(V.num(250));
        assertThat(HistoryBuilder.typed("-3.5")).isEqualTo(V.num(-3.5));
        assertThat(HistoryBuilder.typed("1e3")).isEqualTo(V.str("1e3"));
        assertThat(HistoryBuilder.typed("007")).isEqualTo(V.num(7));
        assertThat(HistoryBuilder.typed("12abc")).isEqualTo(V.str("12abc"));
        assertThat(HistoryBuilder.typed("")).isEqualTo(V.str(""));
        assertThat(HistoryBuilder.typed("true")).isEqualTo(V.bool(true));
        assertThat(HistoryBuilder.typed("False")).isEqualTo(V.str("False"));
    }

    @Test
    void aDeclaredTypeDecidesHowARememberedValueIsRead() {
        assertThat(HistoryBuilder.declared("250", "number")).isEqualTo(V.num(250));
        assertThat(HistoryBuilder.declared("abc", "number")).isEqualTo(V.UNDEFINED);
        assertThat(HistoryBuilder.declared("007", "text")).isEqualTo(V.str("007"));
        assertThat(HistoryBuilder.declared("true", "flag")).isEqualTo(V.bool(true));
        assertThat(HistoryBuilder.declared("maybe", "flag")).isEqualTo(V.UNDEFINED);
        assertThat(HistoryBuilder.declared("12", "something-else")).isEqualTo(V.num(12));
        List<Rec> recs = List.of(
                caseRec("c1", 1, 0, "watch", "gold", Map.of("amount", "250", "zip", "01234"), 1, "id-a"),
                proposed("c1", 1, 5, Rec.map("choice", "approve")),
                responded("c1", 1, 65, Rec.map("kind", "accept", "choice", "approve", "by", "lead")));
        V.Rec r = HistoryBuilder.history(LedgerFold.cases(recs), "gold", 1, Map.of("zip", "text")).cases().get(0);
        assertThat(r.get("zip")).isEqualTo(V.str("01234"));              // not 1234
        assertThat(r.get("amount")).isEqualTo(V.num(250));
    }

    @Test
    void aSupersededGenerationOfACaseIsNotInTheHistory() {
        List<LCase> cases = LedgerFold.cases(List.of(
                caseRec("c1", 1, 0, "watch", "all", Map.of(), 1, "id-a"),
                proposed("c1", 1, 1, Rec.map("choice", "reject")),
                responded("c1", 1, 2, Rec.map("kind", "accept", "choice", "reject", "by", "lead")),
                caseRec("c1", 2, 50, "watch", "all", Map.of(), 1, "id-a"),
                proposed("c1", 2, 51, Rec.map("choice", "approve")),
                responded("c1", 2, 52, Rec.map("kind", "accept", "choice", "approve", "by", "lead"))));
        V.Cs history = HistoryBuilder.history(cases, "all", 1);
        assertThat(history.cases()).hasSize(1);
        assertThat(((V.Rec) history.cases().get(0).get("response")).get("choice")).isEqualTo(V.str("approve"));
    }
}
