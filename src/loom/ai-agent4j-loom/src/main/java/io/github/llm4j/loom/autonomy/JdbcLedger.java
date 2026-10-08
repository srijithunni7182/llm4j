package io.github.llm4j.loom.autonomy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

/**
 * The ledger in one table, {@code loom_ledger}. A record with the same (decision, id) is not inserted twice: the primary key refuses it
 * and the refusal is read as "already there". Reads ask only for rows newer than the last one seen, so a long ledger is read once.
 */
public class JdbcLedger implements Ledger {

    public static final String DDL = """
            CREATE TABLE IF NOT EXISTS loom_ledger (
                seq      BIGINT GENERATED ALWAYS AS IDENTITY,
                decision VARCHAR(128) NOT NULL,
                id       VARCHAR(512) NOT NULL,
                at       VARCHAR(40)  NOT NULL,
                kind     VARCHAR(32)  NOT NULL,
                body     TEXT         NOT NULL,
                PRIMARY KEY (decision, id)
            )""";

    private static final class State {
        final LedgerCache cache = new LedgerCache();
        long seen;
    }

    private final DataSource dataSource;
    private final Map<String, State> states = new HashMap<>();

    public JdbcLedger(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public static void createTable(DataSource dataSource) {
        try (Connection c = dataSource.getConnection(); var st = c.createStatement()) {
            st.execute(DDL);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot create loom_ledger", e);
        }
    }

    /** Reads the rows added since this instance last looked (by anyone). */
    private State refresh(String decision) {
        State st = states.computeIfAbsent(decision, d -> new State());
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT seq, body FROM loom_ledger WHERE decision = ? AND seq > ? ORDER BY seq")) {
            ps.setString(1, decision);
            ps.setLong(2, st.seen);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    st.seen = rs.getLong(1);
                    st.cache.add(FileLedger.parse(rs.getString(2)));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read the ledger", e);
        }
        return st;
    }

    @Override
    public synchronized void append(Rec record) {
        State st = refresh(record.decision());
        if (st.cache.has(record.id())) return;
        try (Connection c = dataSource.getConnection(); PreparedStatement in = c.prepareStatement("INSERT INTO loom_ledger (decision, id, at, kind, body) VALUES (?, ?, ?, ?, ?)")) {
            in.setString(1, record.decision());
            in.setString(2, record.id());
            in.setString(3, record.at().toString());
            in.setString(4, record.kind());
            in.setString(5, FileLedger.json(record));
            in.executeUpdate();
        } catch (SQLException duplicate) {
            // lost a race to the same record from another process: it is there, which is all that was wanted
            if (!exists(record)) throw new IllegalStateException("Cannot append to the ledger", duplicate);
        }
        refresh(record.decision());
    }

    private boolean exists(Rec record) {
        try (Connection c = dataSource.getConnection(); PreparedStatement exists = c.prepareStatement("SELECT 1 FROM loom_ledger WHERE decision = ? AND id = ?")) {
            exists.setString(1, record.decision());
            exists.setString(2, record.id());
            try (ResultSet rs = exists.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read the ledger", e);
        }
    }

    @Override
    public synchronized List<Rec> records(String decision) {
        return refresh(decision).cache.records();
    }

    @Override
    public synchronized List<Rec> recordsOfKind(String decision, String... kinds) {
        return refresh(decision).cache.ofKinds(kinds);
    }

    @Override
    public synchronized List<Case> cases(String decision) {
        return refresh(decision).cache.cases();
    }

    @Override
    public synchronized void purgeFields(String decision, Instant before) {
        try (Connection c = dataSource.getConnection(); PreparedStatement up = c.prepareStatement("UPDATE loom_ledger SET body = ? WHERE decision = ? AND id = ?")) {
            for (Rec r : records(decision)) {
                Rec purged = Purge.apply(r, before);
                if (purged.equals(r)) continue;
                up.setString(1, FileLedger.json(purged));
                up.setString(2, decision);
                up.setString(3, r.id());
                up.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot purge the ledger", e);
        }
        states.remove(decision); // read again, from the rewritten rows
    }
}
