package io.github.llm4j.loom.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * A run journal in any SQL database, one row per step, so a run can be resumed by whichever server
 * picks it up. Expects this table (created by your migrations, or {@link #createTable}):
 * <pre>
 * CREATE TABLE loom_journal (
 *     run_id     VARCHAR(64)  NOT NULL,
 *     step_id    VARCHAR(512) NOT NULL,
 *     kind       VARCHAR(20)  NOT NULL,
 *     value_json TEXT,
 *     PRIMARY KEY (run_id, step_id)
 * );
 * </pre>
 */
public class JdbcRunJournal implements RunJournal {

    public static final String DDL = """
            CREATE TABLE IF NOT EXISTS loom_journal (
                run_id     VARCHAR(64)  NOT NULL,
                step_id    VARCHAR(512) NOT NULL,
                kind       VARCHAR(20)  NOT NULL,
                value_json TEXT,
                PRIMARY KEY (run_id, step_id)
            )""";

    private static final ObjectMapper JSON = new ObjectMapper();
    private final DataSource dataSource;
    private final String runId;

    public JdbcRunJournal(DataSource dataSource, String runId) {
        this.dataSource = dataSource;
        this.runId = runId;
    }

    public static void createTable(DataSource dataSource) {
        try (Connection c = dataSource.getConnection(); var st = c.createStatement()) {
            st.execute(DDL);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot create loom_journal", e);
        }
    }

    @Override
    public Optional<Entry> get(String stepId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT kind, value_json FROM loom_journal WHERE run_id = ? AND step_id = ?")) {
            ps.setString(1, runId);
            ps.setString(2, stepId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(entry(rs.getString(1), rs.getString(2))) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read the run journal", e);
        }
    }

    @Override
    public void put(String stepId, Entry entry) {
        String json;
        try {
            json = JSON.writeValueAsString(entry.value());
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Step result is not JSON-serialisable: " + stepId, e);
        }
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement up = c.prepareStatement(
                    "UPDATE loom_journal SET kind = ?, value_json = ? WHERE run_id = ? AND step_id = ?")) {
                up.setString(1, entry.kind());
                up.setString(2, json);
                up.setString(3, runId);
                up.setString(4, stepId);
                if (up.executeUpdate() > 0) return;
            }
            try (PreparedStatement in = c.prepareStatement(
                    "INSERT INTO loom_journal (run_id, step_id, kind, value_json) VALUES (?, ?, ?, ?)")) {
                in.setString(1, runId);
                in.setString(2, stepId);
                in.setString(3, entry.kind());
                in.setString(4, json);
                in.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot write the run journal", e);
        }
    }

    @Override
    public Map<String, Entry> all() {
        Map<String, Entry> out = new LinkedHashMap<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT step_id, kind, value_json FROM loom_journal WHERE run_id = ?")) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.put(rs.getString(1), entry(rs.getString(2), rs.getString(3)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read the run journal", e);
        }
        return out;
    }

    private static Entry entry(String kind, String json) {
        try {
            return new Entry(kind, json == null ? null : JSON.readValue(json, Object.class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt run journal entry", e);
        }
    }

    @Override
    public boolean isDurable() {
        return true;
    }
}
