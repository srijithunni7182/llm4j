package io.github.llm4j.loom.autonomy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** The three kinds of ledger and level store, so a contract is run against each of them. */
final class Stores {

    private static final AtomicInteger DB = new AtomicInteger();

    final String kind;
    final Ledger ledger;
    final LevelStore levels;

    private Stores(String kind, Ledger ledger, LevelStore levels) {
        this.kind = kind;
        this.ledger = ledger;
        this.levels = levels;
    }

    static Stores memory() {
        return new Stores("memory", new MemoryLedger(), new MemoryLevelStore());
    }

    static Stores file(Path dir) {
        return new Stores("file", new FileLedger(dir), new FileLevelStore(dir));
    }

    static Stores jdbc() {
        org.h2.jdbcx.JdbcDataSource db = new org.h2.jdbcx.JdbcDataSource();
        db.setURL("jdbc:h2:mem:autonomy" + DB.incrementAndGet() + ";DB_CLOSE_DELAY=-1");
        JdbcLedger.createTable(db);
        JdbcLevelStore.createTable(db);
        return new Stores("jdbc", new JdbcLedger(db), new JdbcLevelStore(db));
    }

    /** Runs the check against memory, a file store in a fresh directory under {@code root}, and an in-memory H2 database. */
    static void each(Path root, Consumer<Stores> check) {
        try {
            check.accept(memory());
            Path dir = Files.createTempDirectory(root, "store");
            check.accept(file(dir));
            check.accept(jdbc());
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    static List<String> kinds() {
        return List.of("memory", "file", "jdbc");
    }
}
