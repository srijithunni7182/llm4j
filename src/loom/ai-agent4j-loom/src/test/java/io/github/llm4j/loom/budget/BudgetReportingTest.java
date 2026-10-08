package io.github.llm4j.loom.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.audit.NoOpAuditLogger;
import io.github.llm4j.budget.BudgetExceeded;
import io.github.llm4j.loom.execution.SpendReport;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/** Verification plan, Requirement 8 (V8.1, V8.2). */
class BudgetReportingTest {

    @Test
    void v8_1_totalsAgreeByAgentAndByStep() {
        BudgetScript run = new BudgetScript("""
                budget { tokens: 5000 }
                agent Writer { model: "test/model" system: "You are Writer." budget { per_call: 50 } }
                agent Critic { model: "test/model" system: "You are Critic." budget { per_call: 50 } }
                workflow Main() {
                    delegate "w1" to Writer -> w1
                    delegate "c1" to Critic -> c1
                    delegate "w2" to Writer -> w2
                    delegate "c2" to Critic -> c2
                    delegate "w3" to Writer -> w3
                    delegate "c3" to Critic -> c3
                }
                """, e -> e.setPriceTable(BudgetScript.PRICES)).run();
        SpendReport report = run.executor.spend();
        SpendReport.Totals total = report.total();
        assertThat(total.tokens()).isEqualTo(900);
        assertThat(total.calls()).isEqualTo(6);
        assertThat(total.cost()).isEqualByComparingTo("0.0012");
        assertThat(total.estimated()).isFalse();
        assertThat(report.byAgent()).containsOnlyKeys("Writer", "Critic");
        assertThat(report.byStep()).hasSize(6);
        for (Map<String, SpendReport.Totals> split : List.of(report.byAgent(), report.byStep())) {
            assertThat(split.values().stream().mapToLong(SpendReport.Totals::tokens).sum()).isEqualTo(total.tokens());
            assertThat(split.values().stream().mapToLong(SpendReport.Totals::calls).sum()).isEqualTo(total.calls());
            assertThat(split.values().stream().map(SpendReport.Totals::cost).reduce(java.math.BigDecimal.ZERO,
                    java.math.BigDecimal::add)).isEqualByComparingTo(total.cost());
        }
        assertThat(report.byAgent().get("Writer").tokens()).isEqualTo(450);
    }

    @Test
    void v8_1b_estimatedChargesAreMarked() {
        io.github.llm4j.budget.Charge reported = new io.github.llm4j.budget.Charge(100, 50, 1, null, false);
        io.github.llm4j.budget.Charge estimated = new io.github.llm4j.budget.Charge(100, 110, 1, null, true);
        SpendReport clean = new SpendReport(List.of(new SpendReport.Line("Main/s0", "Writer", "m", reported)));
        SpendReport mixed = new SpendReport(List.of(new SpendReport.Line("Main/s0", "Writer", "m", reported),
                new SpendReport.Line("Main/s1", "Local", "ollama/x", estimated)));
        assertThat(clean.total().estimated()).isFalse();
        assertThat(clean.table()).doesNotContain("estimated");
        assertThat(mixed.total().estimated()).isTrue();
        assertThat(mixed.byAgent().get("Writer").estimated()).isFalse();
        assertThat(mixed.byAgent().get("Local").estimated()).isTrue();
        assertThat(mixed.table()).contains("(some usage was estimated: a provider reported none)");
    }

    @Test
    void v8_2_warningAndRefusalAreAudited() {
        List<String> events = new CopyOnWriteArrayList<>();
        List<Map<String, Object>> data = new CopyOnWriteArrayList<>();
        NoOpAuditLogger audit = new NoOpAuditLogger() {
            @Override
            public void logConversationEvent(String sessionId, String userId, String eventType, Map<String, Object> metadata) {
                if (eventType.startsWith("budget_")) {
                    events.add(eventType);
                    data.add(metadata);
                }
            }
        };
        BudgetScript run = new BudgetScript("""
                budget { tokens: 300 warn_at: 50% }
                agent Writer { model: "test/model" system: "You are Writer." budget { per_call: 50 } }
                workflow Main() {
                    delegate "one" to Writer -> a
                    delegate "two" to Writer -> b
                    delegate "three" to Writer -> c
                }
                """, e -> e.setAuditLogger(audit));
        assertThatThrownBy(run::run).isInstanceOf(BudgetExceeded.class);
        assertThat(events).containsExactly("budget_warning", "budget_refused");
        assertThat(data).allSatisfy(d -> assertThat(d).containsKeys("budget", "spentTokens", "spentCalls", "spentCost", "limits"));
        assertThat(data.get(0)).containsEntry("budget", "run").containsEntry("spentTokens", "150");
        assertThat(data.get(1)).containsEntry("spentTokens", "300").containsEntry("limits", "tokens 300");
    }

    @Test
    void theTableHasARowPerAgentAndATotal() {
        BudgetScript run = new BudgetScript("""
                budget { tokens: 5000 }
                agent Writer { model: "test/model" system: "You are Writer." budget { per_call: 50 } }
                agent Critic { model: "test/model" system: "You are Critic." budget { per_call: 50 } }
                workflow Main() {
                    delegate "w" to Writer -> w
                    delegate "c" to Critic -> c
                }
                """).run();
        String[] lines = run.executor.spend().table().split("\n");
        assertThat(lines[0]).matches("agent\\s+calls\\s+prompt\\s+completion\\s+cost");
        assertThat(lines[1]).matches("Writer\\s+1\\s+100\\s+50\\s+\\$0");
        assertThat(lines[2]).matches("Critic\\s+1\\s+100\\s+50\\s+\\$0");
        assertThat(lines[3]).matches("total\\s+2\\s+200\\s+100\\s+\\$0");
    }
}
