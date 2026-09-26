package io.github.llm4j.getviral.app.runs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The shared, append-only run log and the pending human questions, in the database — so the instance
 * that runs a workflow, the one streaming it to the browser, and the one receiving an answer can all
 * be different Cloud Run instances.
 */
@Component
public class RunStore {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };
    private static final TypeReference<List<String>> LIST = new TypeReference<>() { };

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public RunStore(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void append(String runId, int seq, String type, Map<String, Object> data, long tMillis) {
        jdbc.update("insert into run_events (run_id, seq, type, data_json, t_ms) values (?, ?, ?, ?, ?)",
                runId, seq, type, write(data), tMillis);
    }

    /** Events after {@code afterSeq}, oldest first, in the same shape the studio receives over SSE. */
    public List<Map<String, Object>> eventsAfter(String runId, int afterSeq, int limit) {
        return jdbc.query("select seq, type, data_json, t_ms from run_events where run_id = ? and seq > ? order by seq limit ?",
                (rs, i) -> {
                    Map<String, Object> event = new LinkedHashMap<>();
                    event.put("seq", rs.getInt("seq"));
                    event.put("type", rs.getString("type"));
                    event.put("t", rs.getLong("t_ms"));
                    event.put("data", read(rs.getString("data_json")));
                    return event;
                }, runId, afterSeq, limit);
    }

    public List<Map<String, Object>> eventsOfType(String runId, String type) {
        return jdbc.query("select data_json from run_events where run_id = ? and type = ? order by seq",
                (rs, i) -> read(rs.getString("data_json")), runId, type);
    }

    public void createQuestion(String questionId, String runId, String kind, String message, List<String> options) {
        jdbc.update("insert into run_questions (id, run_id, kind, message, options_json, created_at) values (?, ?, ?, ?, ?, ?)",
                questionId, runId, kind, message, write(options), Timestamp.from(Instant.now()));
    }

    public Optional<String> answer(String questionId) {
        List<String> answers = jdbc.query("select answer from run_questions where id = ? and answered_at is not null",
                (rs, i) -> rs.getString("answer"), questionId);
        return answers.stream().findFirst();
    }

    /** Records an answer if the question belongs to the run and is still open. */
    public boolean submitAnswer(String runId, String questionId, String answer) {
        return jdbc.update("update run_questions set answer = ?, answered_at = ? where id = ? and run_id = ? and answered_at is null",
                answer, Timestamp.from(Instant.now()), questionId, runId) == 1;
    }

    public List<Map<String, Object>> openQuestions(String runId) {
        return jdbc.query("select id, kind, message, options_json from run_questions where run_id = ? and answered_at is null order by created_at",
                (rs, i) -> {
                    Map<String, Object> q = new LinkedHashMap<>();
                    q.put("id", rs.getString("id"));
                    q.put("kind", rs.getString("kind"));
                    q.put("message", rs.getString("message"));
                    q.put("options", readList(rs.getString("options_json")));
                    return q;
                }, runId);
    }

    public String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise run data", e);
        }
    }

    public Map<String, Object> read(String value) {
        try {
            return value == null ? Map.of() : json.readValue(value, MAP);
        } catch (JsonProcessingException e) {
            return Map.of("unreadable", true);
        }
    }

    private List<String> readList(String value) {
        try {
            return value == null ? List.of() : json.readValue(value, LIST);
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }
}
