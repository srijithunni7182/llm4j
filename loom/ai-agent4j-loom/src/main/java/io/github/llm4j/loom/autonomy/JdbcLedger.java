package io.github.llm4j.loom.autonomy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;

/**
 * The ledger in one table, {@code loom_ledger}. A record with the same (decision, id) is not inserted twice: the primary key refuses it
 * and the refusal is read as "already there".
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

    private final DataSource dataSource;

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

    @Override
    public void append(Rec record) {
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement exists = c.prepareStatement("SELECT 1 FROM loom_ledger WHERE decision = ? AND id = ?")) {
                exists.setString(1, record.decision());
                exists.setString(2, record.id());
                try (ResultSet rs = exists.executeQuery()) {
                    if (rs.next()) return;
                }
            }
            try (PreparedStatement in = c.prepareStatement("INSERT INTO loom_ledger (decision, id, at, kind, body) VALUES (?, ?, ?, ?, ?)")) {
                in.setString(1, record.decision());
                in.setString(2, record.id());
                in.setString(3, record.at().toString());
                in.setString(4, record.kind());
                in.setString(5, FileLedger.json(record));
                in.executeUpdate();
            } catch (SQLException duplicate) {
                if (!isDuplicate(c, record)) throw duplicate; // lost a race to the same record: fine
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot append to the ledger", e);
        }
    }

    private static boolean isDuplicate(Connection c, Rec record) throws SQLException {
        try (PreparedStatement exists = c.prepareStatement("SELECT 1 FROM loom_ledger WHERE decision = ? AND id = ?")) {
            exists.setString(1, record.decision());
            exists.setString(2, record.id());
            try (ResultSet rs = exists.executeQuery()) {
                return rs.next();
            }
        }
    }

    @Override
    public List<Rec> records(String decision) {
        List<Rec> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT body FROM loom_ledger WHERE decision = ? ORDER BY seq")) {
            ps.setString(1, decision);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(FileLedger.parse(rs.getString(1)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read the ledger", e);
        }
        return out;
    }

    @Override
    public void purgeFields(String decision, Instant before) {
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
    }
}
