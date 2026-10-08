package io.github.llm4j.loom.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.budget.BudgetExceeded;
import io.github.llm4j.loom.runtime.JdbcRunJournal;
import io.github.llm4j.loom.runtime.RunJournal;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Verification plan, Requirement 7 (V7.1–V7.4) and scenario E2E-2. */
class BudgetDurabilityTest {

    private static String script(int tokens) {
        return "budget { tokens: " + tokens + " }\n"
                + "agent Writer { model: \"test/model\" system: \"You are Writer.\" budget { per_call: 50 } }\n"
                + """
                workflow Main() {
                    delegate "one" to Writer -> first
                    delegate "two" to Writer -> second
                    delegate "three" to Writer -> third
                    delegate "four" to Writer -> fourth
                }
                """;
    }

    private static long usageEntries(RunJournal journal) {
        return journal.all().values().stream().filter(e -> e.kind().equals("usage")).count();
    }

    @Test
    void v7_1_everyCompletedStepJournalsItsUsage() {
        RunJournal journal = RunJournal.inMemory();
        new BudgetScript(script(1000), e -> e.setJournal(journal)).run();
        assertThat(usageEntries(journal)).isEqualTo(4);
        assertThat(journal.all().values()).filteredOn(e -> e.kind().equals("usage"))
                .allSatisfy(e -> assertThat(((Map<?, ?>) e.value()).get("prompt")).isEqualTo(100L));
    }

    @Test
    void v7_2_aReplayedRunIsNotChargedTwice() {
        RunJournal journal = RunJournal.inMemory();
        new BudgetScript(script(1000), e -> e.setJournal(journal)).run();
        BudgetScript replay = new BudgetScript(script(1000), e -> e.setJournal(journal)).run();
        assertThat(replay.calls.get()).isZero();
        assertThat(replay.executor.spend().total().tokens()).isEqualTo(600);
        assertThat(replay.executor.getRunBudget().spent().tokens()).isEqualTo(600);
        assertThat(replay.var("fourth")).isEqualTo("Writer#4");
    }

    @Test
    void v7_3_raiseTheCapAndResume() {
        RunJournal journal = RunJournal.inMemory();
        BudgetScript first = new BudgetScript(script(300), e -> e.setJournal(journal));
        assertThatThrownBy(first::run).isInstanceOf(BudgetExceeded.class);
        assertThat(first.calls.get()).isEqualTo(2);

        BudgetScript resumed = new BudgetScript(script(1000), e -> e.setJournal(journal)).run();
        assertThat(resumed.calls.get()).isEqualTo(2); // steps three and four only
        assertThat(resumed.executor.spend().total().tokens()).isEqualTo(600); // = one uninterrupted run
        assertThat(resumed.var("first")).isEqualTo("Writer#1");
        assertThat(resumed.var("fourth")).isEqualTo("Writer#2"); // this executor's model numbers its own calls
    }

    @Test
    void v7_4_limitsComeFromTheScriptNotTheJournal() {
        RunJournal journal = RunJournal.inMemory();
        assertThatThrownBy(new BudgetScript(script(300), e -> e.setJournal(journal))::run).isInstanceOf(BudgetExceeded.class);
        BudgetScript resumed = new BudgetScript(script(1000), e -> e.setJournal(journal));
        assertThat(resumed.executor.getRunBudget().limits().tokens()).isEqualTo(1000);
        BudgetScript overridden = new BudgetScript(script(1000), e -> {
            e.setJournal(journal);
            e.setBudgetOverrides(450L, null, null);
        });
        assertThat(overridden.executor.getRunBudget().limits().tokens()).isEqualTo(450);
    }

    @Test
    void e2e2_stopRaiseResumeThroughASqlJournal() {
        org.h2.jdbcx.JdbcDataSource db = new org.h2.jdbcx.JdbcDataSource();
        db.setURL("jdbc:h2:mem:budget-e2e2;DB_CLOSE_DELAY=-1");
        JdbcRunJournal.createTable(db);

        BudgetScript first = new BudgetScript(script(300), e -> e.setJournal(new JdbcRunJournal(db, "run-7")));
        assertThatThrownBy(first::run).isInstanceOf(BudgetExceeded.class);

        // A new executor, as on another server, reading the same database.
        BudgetScript resumed = new BudgetScript(script(1000), e -> e.setJournal(new JdbcRunJournal(db, "run-7"))).run();
        assertThat(resumed.calls.get()).isEqualTo(2);
        assertThat(resumed.executor.spend().total().tokens()).isEqualTo(600);
        assertThat(resumed.executor.spend().byStep()).hasSize(4);
    }
}
