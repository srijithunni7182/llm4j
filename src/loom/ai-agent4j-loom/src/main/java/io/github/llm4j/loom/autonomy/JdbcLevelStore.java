package io.github.llm4j.loom.autonomy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;

/** Levels in a table, {@code loom_levels}; the version column makes {@link #compareAndSet} a conditional update. */
public class JdbcLevelStore implements LevelStore {

    public static final String DDL = """
            CREATE TABLE IF NOT EXISTS loom_levels (
                decision VARCHAR(128) NOT NULL,
                scope    VARCHAR(512) NOT NULL,
                version  INT          NOT NULL,
                body     TEXT         NOT NULL,
                PRIMARY KEY (decision, scope)
            )""";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String FREEZE = "#freeze";
    private final DataSource dataSource;

    public JdbcLevelStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public static void createTable(DataSource dataSource) {
        try (Connection c = dataSource.getConnection(); var st = c.createStatement()) {
            st.execute(DDL);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot create loom_levels", e);
        }
    }

    private static String key(String decision) {
        return decision.equals("*") ? decision : Names.check(decision);
    }

    private static String write(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static Map<String, Object> read(String json) {
        try {
            return JSON.readValue(json, new TypeReference<Map<String, Object>>() { });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt level record", e);
        }
    }

    @Override
    public Optional<LevelState> get(String decision, String scope) {
        String name = key(decision);
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement("SELECT body FROM loom_levels WHERE decision = ? AND scope = ?")) {
            ps.setString(1, name);
            ps.setString(2, scope);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(FileLevelStore.fromMap(read(rs.getString(1)))) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read the level store", e);
        }
    }

    @Override
    public boolean compareAndSet(String decision, String scope, LevelState expected, LevelState next) {
        try (Connection c = dataSource.getConnection()) {
            if (expected == null) {
                try (PreparedStatement in = c.prepareStatement("INSERT INTO loom_levels (decision, scope, version, body) VALUES (?, ?, ?, ?)")) {
                    in.setString(1, key(decision));
                    in.setString(2, scope);
                    in.setInt(3, next.version());
                    in.setString(4, write(FileLevelStore.toMap(next)));
                    in.executeUpdate();
                    return true;
                } catch (SQLException exists) {
                    return false; // someone else created it first
                }
            }
            try (PreparedStatement up = c.prepareStatement("UPDATE loom_levels SET version = ?, body = ? WHERE decision = ? AND scope = ? AND version = ?")) {
                up.setInt(1, next.version());
                up.setString(2, write(FileLevelStore.toMap(next)));
                up.setString(3, key(decision));
                up.setString(4, scope);
                up.setInt(5, expected.version());
                return up.executeUpdate() == 1;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot write the level store", e);
        }
    }

    @Override
    public Map<String, LevelState> scopes(String decision) {
        Map<String, LevelState> out = new LinkedHashMap<>();
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement("SELECT scope, body FROM loom_levels WHERE decision = ? AND scope <> ? ORDER BY scope")) {
            ps.setString(1, key(decision));
            ps.setString(2, FREEZE);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.put(rs.getString(1), FileLevelStore.fromMap(read(rs.getString(2))));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read the level store", e);
        }
        return out;
    }

    @Override
    public Optional<Freeze> freeze(String decision) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement("SELECT body FROM loom_levels WHERE decision = ? AND scope = ?")) {
            ps.setString(1, key(decision));
            ps.setString(2, FREEZE);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                Map<String, Object> m = read(rs.getString(1));
                return Optional.of(new Freeze((String) m.get("reason"), Instant.parse((String) m.get("at"))));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read the level store", e);
        }
    }

    @Override
    public void setFreeze(String decision, Freeze freeze) {
        clearFreeze(decision);
        try (Connection c = dataSource.getConnection(); PreparedStatement in = c.prepareStatement("INSERT INTO loom_levels (decision, scope, version, body) VALUES (?, ?, 0, ?)")) {
            in.setString(1, key(decision));
            in.setString(2, FREEZE);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("reason", freeze.reason());
            m.put("at", freeze.at().toString());
            in.setString(3, write(m));
            in.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot write the level store", e);
        }
    }

    @Override
    public void clearFreeze(String decision) {
        try (Connection c = dataSource.getConnection(); PreparedStatement del = c.prepareStatement("DELETE FROM loom_levels WHERE decision = ? AND scope = ?")) {
            del.setString(1, key(decision));
            del.setString(2, FREEZE);
            del.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot write the level store", e);
        }
    }
}
