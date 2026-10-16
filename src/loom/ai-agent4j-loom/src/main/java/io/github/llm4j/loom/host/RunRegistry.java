package io.github.llm4j.loom.host;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.loom.runtime.RunSuspended;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import javax.sql.DataSource;

/** Where the host remembers its runs. */
interface RunRegistry {

    void save(RunRecord record);

    Optional<RunRecord> find(String runId);

    List<RunRecord> all();

    /** In this process only. */
    final class Memory implements RunRegistry {
        private final Map<String, RunRecord> records = new ConcurrentHashMap<>();

        @Override public void save(RunRecord r) { records.put(r.runId(), r); }
        @Override public Optional<RunRecord> find(String id) { return Optional.ofNullable(records.get(id)); }
        @Override public List<RunRecord> all() { return sorted(new ArrayList<>(records.values())); }
    }

    /**
     * {@code <dir>/runs/<id>/run.json}, with the keys {@code weave} reads ({@code script}, {@code workflow}, {@code inputs}, {@code store}) beside the
     * host's own, so {@code weave resume} and {@code weave timeline} can open a run the host started.
     */
    final class Files_ implements RunRegistry {
        private static final ObjectMapper JSON = new ObjectMapper();
        private final Path dir;
        private final String scriptRef;

        Files_(Path dir, String scriptRef) {
            this.dir = dir.toAbsolutePath().normalize();
            this.scriptRef = scriptRef;
        }

        Path runDir(String runId) {
            return dir.resolve("runs").resolve(safe(runId));
        }

        @Override
        public synchronized void save(RunRecord r) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("script", scriptRef);
            m.put("workflow", r.workflow());
            m.put("inputs", new LinkedHashMap<>(r.inputs()));
            m.put("store", dir.toString());
            m.put("hostRunId", r.runId());
            m.put("origin", r.origin());
            m.put("status", r.status().name());
            m.put("reason", r.reason() == null ? null : r.reason().name());
            m.put("resumeAt", r.resumeAt() == null ? null : r.resumeAt().toString());
            m.put("stepId", r.stepId());
            m.put("prompt", r.prompt());
            m.put("detail", r.detail());
            m.put("created", r.created().toString());
            m.put("updated", r.updated().toString());
            try {
                Path run = runDir(r.runId());
                Files.createDirectories(run);
                Path tmp = Files.createTempFile(run, "run", ".tmp");
                Files.writeString(tmp, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(m));
                Files.move(tmp, run.resolve("run.json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot write the record of run " + r.runId(), e);
            }
        }

        @Override
        public synchronized Optional<RunRecord> find(String runId) {
            return read(runDir(runId).resolve("run.json"));
        }

        @Override
        public synchronized List<RunRecord> all() {
            Path runs = dir.resolve("runs");
            if (!Files.isDirectory(runs)) return List.of();
            List<RunRecord> out = new ArrayList<>();
            try (Stream<Path> children = Files.list(runs)) {
                children.forEach(d -> read(d.resolve("run.json")).ifPresent(out::add));
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot list the runs in " + runs, e);
            }
            return sorted(out);
        }

        @SuppressWarnings("unchecked")
        private static Optional<RunRecord> read(Path file) {
            if (!Files.isRegularFile(file)) return Optional.empty();
            try {
                Map<String, Object> m = JSON.readValue(file.toFile(), Map.class);
                if (m.get("hostRunId") == null) return Optional.empty(); // a run weave started: not one the host knows
                return Optional.of(new RunRecord((String) m.get("hostRunId"), (String) m.get("workflow"), (Map<String, String>) m.get("inputs"),
                        (String) m.get("origin"), RunRecord.Status.valueOf((String) m.get("status")),
                        m.get("reason") == null ? null : RunSuspended.Reason.valueOf((String) m.get("reason")),
                        m.get("resumeAt") == null ? null : Instant.parse((String) m.get("resumeAt")), (String) m.get("stepId"),
                        (String) m.get("prompt"), (String) m.get("detail"), Instant.parse((String) m.get("created")), Instant.parse((String) m.get("updated"))));
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot read " + file, e);
            }
        }
    }

    /** Table {@code llm4j_loom_runs}. */
    final class Jdbc implements RunRegistry {
        static final String DDL = """
                CREATE TABLE IF NOT EXISTS llm4j_loom_runs (
                    run_id      VARCHAR(200) PRIMARY KEY,
                    workflow    VARCHAR(200) NOT NULL,
                    inputs_json TEXT,
                    origin      VARCHAR(200),
                    status      VARCHAR(20) NOT NULL,
                    reason      VARCHAR(20),
                    resume_at   BIGINT,
                    step_id     VARCHAR(512),
                    prompt      TEXT,
                    detail      TEXT,
                    created     BIGINT NOT NULL,
                    updated     BIGINT NOT NULL
                )""";
        private static final ObjectMapper JSON = new ObjectMapper();
        private final DataSource ds;

        Jdbc(DataSource ds) { this.ds = ds; }

        @Override
        public void save(RunRecord r) {
            String inputs;
            try {
                inputs = JSON.writeValueAsString(r.inputs());
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException("inputs are not JSON", e);
            }
            try (Connection c = ds.getConnection()) {
                try (PreparedStatement up = c.prepareStatement("UPDATE llm4j_loom_runs SET workflow=?, inputs_json=?, origin=?, status=?, reason=?, resume_at=?, "
                        + "step_id=?, prompt=?, detail=?, updated=? WHERE run_id=?")) {
                    up.setString(1, r.workflow());
                    up.setString(2, inputs);
                    up.setString(3, r.origin());
                    up.setString(4, r.status().name());
                    up.setString(5, r.reason() == null ? null : r.reason().name());
                    up.setObject(6, r.resumeAt() == null ? null : r.resumeAt().toEpochMilli());
                    up.setString(7, r.stepId());
                    up.setString(8, r.prompt());
                    up.setString(9, r.detail());
                    up.setLong(10, r.updated().toEpochMilli());
                    up.setString(11, r.runId());
                    if (up.executeUpdate() > 0) return;
                }
                try (PreparedStatement in = c.prepareStatement("INSERT INTO llm4j_loom_runs (run_id, workflow, inputs_json, origin, status, reason, resume_at, "
                        + "step_id, prompt, detail, created, updated) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")) {
                    in.setString(1, r.runId());
                    in.setString(2, r.workflow());
                    in.setString(3, inputs);
                    in.setString(4, r.origin());
                    in.setString(5, r.status().name());
                    in.setString(6, r.reason() == null ? null : r.reason().name());
                    in.setObject(7, r.resumeAt() == null ? null : r.resumeAt().toEpochMilli());
                    in.setString(8, r.stepId());
                    in.setString(9, r.prompt());
                    in.setString(10, r.detail());
                    in.setLong(11, r.created().toEpochMilli());
                    in.setLong(12, r.updated().toEpochMilli());
                    in.executeUpdate();
                }
            } catch (SQLException e) {
                throw new IllegalStateException("Cannot save the record of run " + r.runId() + " (is the schema created? see LoomHost.createSchema)", e);
            }
        }

        @Override
        public Optional<RunRecord> find(String runId) {
            return query("SELECT * FROM llm4j_loom_runs WHERE run_id = ?", runId).stream().findFirst();
        }

        @Override
        public List<RunRecord> all() {
            return sorted(query("SELECT * FROM llm4j_loom_runs"));
        }

        @SuppressWarnings("unchecked")
        private List<RunRecord> query(String sql, String... args) {
            List<RunRecord> out = new ArrayList<>();
            try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) ps.setString(i + 1, args[i]);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long resume = rs.getLong("resume_at");
                        boolean noResume = rs.wasNull();
                        String reason = rs.getString("reason");
                        String inputs = rs.getString("inputs_json");
                        out.add(new RunRecord(rs.getString("run_id"), rs.getString("workflow"), inputs == null ? Map.of() : JSON.readValue(inputs, Map.class),
                                rs.getString("origin"), RunRecord.Status.valueOf(rs.getString("status")), reason == null ? null : RunSuspended.Reason.valueOf(reason),
                                noResume ? null : Instant.ofEpochMilli(resume), rs.getString("step_id"), rs.getString("prompt"), rs.getString("detail"),
                                Instant.ofEpochMilli(rs.getLong("created")), Instant.ofEpochMilli(rs.getLong("updated"))));
                    }
                }
            } catch (SQLException | IOException e) {
                throw new IllegalStateException("Cannot read the runs", e);
            }
            return out;
        }
    }

    static List<RunRecord> sorted(List<RunRecord> list) {
        list.sort(Comparator.comparing(RunRecord::created).thenComparing(RunRecord::runId));
        return list;
    }

    /** A run id as a folder name: the characters {@code weave} allows. */
    static String safe(String runId) {
        return runId.replaceAll("[^A-Za-z0-9._@-]", "-");
    }
}
