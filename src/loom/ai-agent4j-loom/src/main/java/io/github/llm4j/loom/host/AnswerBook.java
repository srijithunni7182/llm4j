package io.github.llm4j.loom.host;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;

/**
 * The questions runs have asked people and the answers given, by run and step. A run that asks
 * suspends; when it is resumed it asks again and gets the answer from here, so the question is
 * never put twice and the answer survives a restart.
 */
interface AnswerBook {

    record Question(
            String runId,
            String stepId,
            String message,
            List<String> choices,
            String answer,
            String by,
            Instant asked,
            Instant answeredAt) {
        public Question {
            choices = choices == null ? List.of() : List.copyOf(choices);
        }

        boolean answered() {
            return answer != null;
        }
    }

    Optional<Question> find(String runId, String stepId);

    /** Records the question unless it is already there; returns the one now on record. */
    Question ask(String runId, String stepId, String message, List<String> choices, Instant now);

    /**
     * Records the answer to an open question; false when there is none or it was answered already.
     */
    boolean answer(String runId, String stepId, String answer, String by, Instant now);

    final class Memory implements AnswerBook {
        private final Map<String, Question> questions = new ConcurrentHashMap<>();

        private static String key(String run, String step) {
            return run + "\u0000" + step;
        }

        @Override
        public Optional<Question> find(String r, String s) {
            return Optional.ofNullable(questions.get(key(r, s)));
        }

        @Override
        public Question ask(String r, String s, String message, List<String> choices, Instant now) {
            return questions.computeIfAbsent(
                    key(r, s), k -> new Question(r, s, message, choices, null, null, now, null));
        }

        @Override
        public synchronized boolean answer(
                String r, String s, String answer, String by, Instant now) {
            Question q = questions.get(key(r, s));
            if (q == null || q.answered()) return false;
            questions.put(
                    key(r, s),
                    new Question(r, s, q.message(), q.choices(), answer, by, q.asked(), now));
            return true;
        }
    }

    /** {@code <dir>/runs/<id>/questions.json}. */
    final class Files_ implements AnswerBook {
        private static final ObjectMapper JSON = new ObjectMapper();
        private final Path dir;

        Files_(Path dir) {
            this.dir = dir.toAbsolutePath().normalize();
        }

        private Path file(String runId) {
            return dir.resolve("runs").resolve(RunRegistry.safe(runId)).resolve("questions.json");
        }

        @Override
        public synchronized Optional<Question> find(String r, String s) {
            return load(r).stream().filter(q -> q.stepId().equals(s)).findFirst();
        }

        @Override
        public synchronized Question ask(
                String r, String s, String message, List<String> choices, Instant now) {
            List<Question> all = load(r);
            for (Question q : all) if (q.stepId().equals(s)) return q;
            Question q = new Question(r, s, message, choices, null, null, now, null);
            all.add(q);
            store(r, all);
            return q;
        }

        @Override
        public synchronized boolean answer(
                String r, String s, String answer, String by, Instant now) {
            List<Question> all = load(r);
            for (int i = 0; i < all.size(); i++) {
                Question q = all.get(i);
                if (!q.stepId().equals(s)) continue;
                if (q.answered()) return false;
                all.set(
                        i,
                        new Question(r, s, q.message(), q.choices(), answer, by, q.asked(), now));
                store(r, all);
                return true;
            }
            return false;
        }

        private List<Question> load(String runId) {
            Path f = file(runId);
            if (!Files.isRegularFile(f)) return new ArrayList<>();
            try {
                List<Map<String, Object>> rows =
                        JSON.readValue(f.toFile(), new TypeReference<>() {});
                List<Question> out = new ArrayList<>();
                for (Map<String, Object> m : rows) {
                    @SuppressWarnings("unchecked")
                    List<String> choices = (List<String>) m.get("choices");
                    out.add(
                            new Question(
                                    runId,
                                    (String) m.get("stepId"),
                                    (String) m.get("message"),
                                    choices,
                                    (String) m.get("answer"),
                                    (String) m.get("by"),
                                    Instant.parse((String) m.get("asked")),
                                    m.get("answeredAt") == null
                                            ? null
                                            : Instant.parse((String) m.get("answeredAt"))));
                }
                return out;
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot read " + f, e);
            }
        }

        private void store(String runId, List<Question> all) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Question q : all) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("stepId", q.stepId());
                m.put("message", q.message());
                m.put("choices", q.choices());
                m.put("answer", q.answer());
                m.put("by", q.by());
                m.put("asked", q.asked().toString());
                m.put("answeredAt", q.answeredAt() == null ? null : q.answeredAt().toString());
                rows.add(m);
            }
            try {
                Path f = file(runId);
                Files.createDirectories(f.getParent());
                Path tmp = Files.createTempFile(f.getParent(), "questions", ".tmp");
                Files.writeString(
                        tmp, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(rows));
                Files.move(
                        tmp,
                        f,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot write the questions of run " + runId, e);
            }
        }
    }

    /** Table {@code llm4j_loom_answers}. */
    final class Jdbc implements AnswerBook {
        static final String DDL =
                """
                CREATE TABLE IF NOT EXISTS llm4j_loom_answers (
                    run_id      VARCHAR(200) NOT NULL,
                    step_id     VARCHAR(512) NOT NULL,
                    message     TEXT,
                    choices     TEXT,
                    answer      TEXT,
                    answered_by VARCHAR(200),
                    asked       BIGINT NOT NULL,
                    answered_at BIGINT,
                    PRIMARY KEY (run_id, step_id)
                )""";
        private static final ObjectMapper JSON = new ObjectMapper();
        private final DataSource ds;

        Jdbc(DataSource ds) {
            this.ds = ds;
        }

        @Override
        public Optional<Question> find(String r, String s) {
            try (Connection c = ds.getConnection();
                    PreparedStatement ps =
                            c.prepareStatement(
                                    "SELECT * FROM llm4j_loom_answers WHERE run_id = ? AND step_id = ?")) {
                ps.setString(1, r);
                ps.setString(2, s);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return Optional.empty();
                    long at = rs.getLong("answered_at");
                    boolean none = rs.wasNull();
                    @SuppressWarnings("unchecked")
                    List<String> choices =
                            rs.getString("choices") == null
                                    ? List.of()
                                    : JSON.readValue(rs.getString("choices"), List.class);
                    return Optional.of(
                            new Question(
                                    r,
                                    s,
                                    rs.getString("message"),
                                    choices,
                                    rs.getString("answer"),
                                    rs.getString("answered_by"),
                                    Instant.ofEpochMilli(rs.getLong("asked")),
                                    none ? null : Instant.ofEpochMilli(at)));
                }
            } catch (SQLException | IOException e) {
                throw new IllegalStateException(
                        "Cannot read the answers (is the schema created? see LoomHost.createSchema)",
                        e);
            }
        }

        @Override
        public Question ask(String r, String s, String message, List<String> choices, Instant now) {
            Optional<Question> existing = find(r, s);
            if (existing.isPresent()) return existing.get();
            try (Connection c = ds.getConnection();
                    PreparedStatement ps =
                            c.prepareStatement(
                                    "INSERT INTO llm4j_loom_answers (run_id, step_id, message, choices, asked) VALUES (?,?,?,?,?)")) {
                ps.setString(1, r);
                ps.setString(2, s);
                ps.setString(3, message);
                ps.setString(4, JSON.writeValueAsString(choices == null ? List.of() : choices));
                ps.setLong(5, now.toEpochMilli());
                ps.executeUpdate();
            } catch (SQLException | IOException e) {
                return find(r, s)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Cannot record the question",
                                                e)); // a concurrent insert won
            }
            return find(r, s).orElseThrow();
        }

        @Override
        public boolean answer(String r, String s, String answer, String by, Instant now) {
            try (Connection c = ds.getConnection();
                    PreparedStatement ps =
                            c.prepareStatement(
                                    "UPDATE llm4j_loom_answers SET answer = ?, answered_by = ?, answered_at = ? WHERE run_id = ? AND step_id = ? AND answer IS NULL")) {
                ps.setString(1, answer);
                ps.setString(2, by);
                ps.setLong(3, now.toEpochMilli());
                ps.setString(4, r);
                ps.setString(5, s);
                return ps.executeUpdate() > 0;
            } catch (SQLException e) {
                throw new IllegalStateException("Cannot record the answer", e);
            }
        }
    }
}
