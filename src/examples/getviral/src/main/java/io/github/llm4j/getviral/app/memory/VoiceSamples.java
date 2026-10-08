package io.github.llm4j.getviral.app.memory;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** A creator's past posts, used for RAG so agents write in their voice. */
@Component
public class VoiceSamples {

    public static final int MAX_PER_USER = 50;

    private final JdbcTemplate jdbc;

    public VoiceSamples(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<String> all(String userId) {
        return jdbc.query("select text from voice_samples where user_id = ? order by created_at desc limit ?",
                (rs, i) -> rs.getString(1), userId, MAX_PER_USER);
    }

    public int add(String userId, List<String> posts, String source) {
        List<String> existing = all(userId);
        int added = 0;
        for (String post : posts) {
            String clean = post == null ? "" : post.strip();
            if (clean.length() < 12 || clean.length() > 5000 || existing.contains(clean)) continue;
            jdbc.update("insert into voice_samples (user_id, text, source, created_at) values (?, ?, ?, ?)",
                    userId, clean, source, Timestamp.from(Instant.now()));
            existing.add(clean);
            added++;
        }
        return added;
    }

    public int count(String userId) {
        Integer n = jdbc.queryForObject("select count(*) from voice_samples where user_id = ?", Integer.class, userId);
        return n == null ? 0 : n;
    }
}
