package io.github.llm4j.loom.host;

import static io.github.llm4j.loom.host.HostEnv.AGENTS;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Phase 33b: a child is a sibling run in the host's store, with a record that names its parent (D3). */
class LoomHostChildrenTest {

    @TempDir Path dir;

    private static final String SOURCE =
            AGENTS
                    + """
            workflow Child(topic) { delegate "research {topic}" to Researcher -> r  return r }
            workflow Main(q) {
                start Child(topic = q) budget { tokens: 100000 } -> work
                wait work timeout 5m -> out
            }
            """;

    @ParameterizedTest
    @ValueSource(strings = {"file", "jdbc"})
    void aChildIsARunOfItsOwnWhoseRecordNamesItsParent(String kind) {
        HostEnv env = new HostEnv(kind, dir).open(SOURCE);

        RunOutcome out = env.host.run(RunRequest.of("Main", Map.of("q", "x")));

        assertThat(out).isInstanceOf(RunOutcome.Completed.class);
        assertThat(env.models.total()).as("the child asked the model once").isEqualTo(1);
        List<RunRecord> all = env.host.runs();
        assertThat(all).hasSize(2);
        RunRecord child = all.stream().filter(r -> r.parentRunId() != null).findFirst().orElseThrow();
        assertThat(child.parentRunId()).isEqualTo(out.runId());
        assertThat(child.workflow()).isEqualTo("Child");
        assertThat(child.status()).isEqualTo(RunRecord.Status.COMPLETED);
        RunRecord parent = env.host.run(out.runId()).orElseThrow();
        assertThat(parent.parentRunId()).isNull();
    }

    @Test
    void aRegistryTableMadeBeforeChildrenExistedGainsTheParentColumnAndKeepsItsRows() throws Exception {
        HostEnv env = new HostEnv("jdbc", dir);
        try (Connection c = env.ds.getConnection();
                Statement st = c.createStatement()) {
            st.execute("DROP TABLE llm4j_loom_runs");
            st.execute(
                    "CREATE TABLE llm4j_loom_runs (run_id VARCHAR(200) PRIMARY KEY, workflow VARCHAR(200) NOT NULL,"
                            + " inputs_json TEXT, origin VARCHAR(200), status VARCHAR(20) NOT NULL, reason VARCHAR(20),"
                            + " resume_at BIGINT, step_id VARCHAR(512), prompt TEXT, detail TEXT, created BIGINT NOT NULL,"
                            + " updated BIGINT NOT NULL)");
            st.execute(
                    "INSERT INTO llm4j_loom_runs (run_id, workflow, status, created, updated) VALUES ('old', 'Main', 'COMPLETED', 1, 2)");
        }

        LoomHost.createSchema(env.ds);
        LoomHost.createSchema(env.ds); // and again: safe on every start

        env.open(SOURCE);
        RunRecord old = env.host.run("old").orElseThrow();
        assertThat(old.workflow()).isEqualTo("Main");
        assertThat(old.parentRunId()).isNull();
    }

    @Test
    void theRecordKeepsItsOldConstructorForCodeWrittenBeforeChildren() {
        var now = java.time.Instant.EPOCH;
        RunRecord record =
                new RunRecord(
                        "r", "Main", Map.of(), "api", RunRecord.Status.RUNNING, null, null, null, null,
                        null, now, now);
        assertThat(record.parentRunId()).isNull();
    }
}
