package io.github.llm4j.loom.trigger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * Triggers in a SQL table ({@code loom_triggers}), for several instances sharing one database (e.g. Cloud
 * Run behind Cloud Scheduler). A claim is one conditional {@code UPDATE}: the instance whose update
 * changes the row owns the firing. Times are stored as epoch milliseconds.
 */
public class JdbcTriggerStore implements TriggerStore {

    public static final String DDL = """
            CREATE TABLE IF NOT EXISTS loom_triggers (
                id VARCHAR(500) PRIMARY KEY,
                kind VARCHAR(10) NOT NULL,
                spec VARCHAR(200),
                zone VARCHAR(60),
                target VARCHAR(4000) NOT NULL,
                next_fire BIGINT,
                last_fire BIGINT,
                last_outcome VARCHAR(1000),
                note VARCHAR(1000),
                attempts INT NOT NULL,
                misfire VARCHAR(10) NOT NULL,
                overlap VARCHAR(10) NOT NULL,
                enabled BOOLEAN NOT NULL,
                claimed_by VARCHAR(200),
                claimed_at BIGINT
            )""";

    private static final String COLUMNS = "id, kind, spec, zone, target, next_fire, last_fire, last_outcome, note, "
            + "attempts, misfire, overlap, enabled, claimed_by, claimed_at";

    private final DataSource dataSource;

    public JdbcTriggerStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public static void createTable(DataSource dataSource) {
        try (Connection c = dataSource.getConnection(); var s = c.createStatement()) {
            s.execute(DDL);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not create loom_triggers", e);
        }
    }

    @Override
    public void upsert(Trigger t) {
        String update = "UPDATE loom_triggers SET kind=?, spec=?, zone=?, target=?, next_fire=?, last_fire=?, "
                + "last_outcome=?, note=?, attempts=?, misfire=?, overlap=?, enabled=?, claimed_by=NULL, claimed_at=NULL "
                + "WHERE id=?";
        try (Connection c = dataSource.getConnection()) {
            for (int attempt = 0; attempt < 3; attempt++) {
                try (PreparedStatement ps = c.prepareStatement(update)) {
                    int i = bindRule(ps, 1, t);
                    ps.setString(i, t.id());
                    if (ps.executeUpdate() == 1) return;
                }
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO loom_triggers (" + COLUMNS
                        + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL)")) {
                    ps.setString(1, t.id());
                    bindRule(ps, 2, t);
                    ps.executeUpdate();
                    return;
                } catch (SQLException duplicate) {
                    // inserted concurrently: update it instead
                }
            }
            throw new IllegalStateException("Could not store trigger " + t.id());
        } catch (SQLException e) {
            throw new IllegalStateException("Could not store trigger " + t.id(), e);
        }
    }

    /** Binds kind … enabled (12 values) starting at {@code i}; returns the next index. */
    private static int bindRule(PreparedStatement ps, int i, Trigger t) throws SQLException {
        ps.setString(i++, t.kind().name());
        ps.setString(i++, t.spec());
        ps.setString(i++, t.zone().getId());
        ps.setString(i++, TriggerCodec.targetToJson(t.target()));
        setMillis(ps, i++, t.nextFire());
        setMillis(ps, i++, t.lastFire());
        ps.setString(i++, t.lastOutcome());
        ps.setString(i++, t.note());
        ps.setInt(i++, t.attempts());
        ps.setString(i++, t.misfire().name());
        ps.setString(i++, t.overlap().name());
        ps.setBoolean(i++, t.enabled());
        return i;
    }

    @Override
    public Optional<Trigger> get(String id) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM loom_triggers WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public List<Trigger> all() {
        List<Trigger> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM loom_triggers");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(read(rs));
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    @Override
    public List<Trigger> due(Instant now) {
        List<Trigger> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS
                     + " FROM loom_triggers WHERE enabled = TRUE AND next_fire <= ? ORDER BY next_fire")) {
            ps.setLong(1, now.toEpochMilli());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(read(rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    @Override
    public void remove(String id) {
        execute("DELETE FROM loom_triggers WHERE id=?", id);
    }

    @Override
    public boolean claim(String id, String owner, Instant now, Duration staleAfter) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE loom_triggers SET claimed_by=?, claimed_at=? "
                     + "WHERE id=? AND enabled = TRUE AND next_fire <= ? AND (claimed_by IS NULL OR claimed_at < ?)")) {
            ps.setString(1, owner);
            ps.setLong(2, now.toEpochMilli());
            ps.setString(3, id);
            ps.setLong(4, now.toEpochMilli());
            ps.setLong(5, now.minus(staleAfter).toEpochMilli());
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void complete(String id, String owner, Trigger next) {
        if (next == null) {
            execute("DELETE FROM loom_triggers WHERE id=? AND claimed_by=?", id, owner);
            return;
        }
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE loom_triggers SET kind=?, spec=?, zone=?, target=?, "
                     + "next_fire=?, last_fire=?, last_outcome=?, note=?, attempts=?, misfire=?, overlap=?, enabled=?, "
                     + "claimed_by=NULL, claimed_at=NULL WHERE id=? AND claimed_by=?")) {
            int i = bindRule(ps, 1, next);
            ps.setString(i++, id);
            ps.setString(i, owner);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private void execute(String sql, String... args) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setString(i + 1, args[i]);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Trigger read(ResultSet rs) throws SQLException {
        return new Trigger(
                rs.getString("id"),
                Trigger.Kind.valueOf(rs.getString("kind")),
                rs.getString("spec"),
                rs.getString("zone") == null ? null : ZoneId.of(rs.getString("zone")),
                TriggerCodec.targetFromJson(rs.getString("target")),
                millis(rs, "next_fire"),
                millis(rs, "last_fire"),
                rs.getString("last_outcome"),
                rs.getString("note"),
                rs.getInt("attempts"),
                Trigger.Misfire.valueOf(rs.getString("misfire")),
                Trigger.Overlap.valueOf(rs.getString("overlap")),
                rs.getBoolean("enabled"),
                rs.getString("claimed_by"),
                millis(rs, "claimed_at"));
    }

    private static Instant millis(ResultSet rs, String column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : Instant.ofEpochMilli(v);
    }

    private static void setMillis(PreparedStatement ps, int i, Instant t) throws SQLException {
        if (t == null) ps.setNull(i, Types.BIGINT);
        else ps.setLong(i, t.toEpochMilli());
    }
}
