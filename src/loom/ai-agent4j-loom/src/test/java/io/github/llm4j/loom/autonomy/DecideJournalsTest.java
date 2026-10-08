package io.github.llm4j.loom.autonomy;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.runtime.FileRunJournal;
import io.github.llm4j.loom.runtime.JdbcRunJournal;
import io.github.llm4j.loom.runtime.RunJournal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A decision works under every journal and ledger Loom has, and survives a crash and a resume under each (spec loom-earned-autonomy R1.6). */
class DecideJournalsTest {

    @TempDir
    Path dir;

    private static final AtomicInteger DB = new AtomicInteger();

    private RunJournal journal(String kind, String runId) throws Exception {
        return switch (kind) {
            case "memory" -> RunJournal.inMemory();
            case "file" -> new FileRunJournal(Files.createDirectories(dir.resolve("journals").resolve(runId)).resolve("journal.json"));
            default -> {
                org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
                ds.setURL("jdbc:h2:mem:decide" + DB.incrementAndGet() + ";DB_CLOSE_DELAY=-1");
                JdbcRunJournal.createTable(ds);
                yield new JdbcRunJournal(ds, runId);
            }
        };
    }

    @Test
    @Tag("EA-V1.5")
    void aDecisionRunsAndResumesAfterACrashUnderMemoryFileAndDatabaseJournalsWithAnyLedger() throws Exception {
        for (String kind : Stores.kinds()) {
            Stores stores = switch (kind) {
                case "memory" -> Stores.memory();
                case "file" -> Stores.file(Files.createDirectories(dir.resolve("store-" + kind)));
                default -> Stores.jdbc();
            };
            for (int crashAt = 1; crashAt <= 12; crashAt += 3) {
                DecideHarness h = new DecideHarness(dir, stores.ledger, stores.levels, Scripts2.refund(""));
                String runId = kind + "-" + crashAt;
                RunJournal durable = journal(kind, runId);
                try {
                    h.executor(h.newRun(new FaultJournal(durable, crashAt, crashAt % 2 == 0)), runId).executeWorkflow("Triage", h.inputs("gold", 20));
                } catch (FaultJournal.Crash expected) {
                    // the process died here
                }
                var resumed = h.executor(h.newRun(durable), runId);
                resumed.executeWorkflow("Triage", h.inputs("gold", 20));

                assertThat(resumed.getContext().getAll().get("verdict")).as(kind + " " + crashAt).isEqualTo("approve");
                assertThat(stores.ledger.cases("Refund").stream().filter(c -> c.id().startsWith(runId + "/"))).as(kind + " " + crashAt).hasSize(1);
            }
        }
    }
}
