package io.github.llm4j.getviral.app.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.getviral.engine.CastingHistory;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** Hosted mode: casting history lives in the database, so it follows the creator across instances. */
public class JdbcCastingHistory implements CastingHistory {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final JdbcTemplate jdbc;

    public JdbcCastingHistory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<PastCasting> recent(String creatorKey, int limit) {
        return jdbc.query("select casting_json from casting_history where user_id = ? order by created_at desc limit ?",
                (rs, i) -> read(rs.getString(1)), creatorKey, limit).stream().filter(c -> c != null).toList();
    }

    @Override
    public void record(String creatorKey, PastCasting casting) {
        try {
            String json = JSON.writeValueAsString(casting);
            // Only creators with an account row are recorded (the key is the user id in hosted mode).
            jdbc.update("insert into casting_history (run_id, user_id, created_at, casting_json) "
                    + "select ?, id, ?, ? from users where id = ?", casting.runId(), Timestamp.from(Instant.now()), json, creatorKey);
            jdbc.update("delete from casting_history where user_id = ? and run_id not in (select run_id from "
                    + "(select run_id from casting_history where user_id = ? order by created_at desc limit ?) keep)",
                    creatorKey, creatorKey, KEEP);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static PastCasting read(String json) {
        try {
            return JSON.readValue(json, PastCasting.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
