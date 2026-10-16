package io.github.llm4j.loom.host;

import io.github.llm4j.loom.autonomy.FileLedger;
import io.github.llm4j.loom.autonomy.FileLevelStore;
import io.github.llm4j.loom.autonomy.JdbcLedger;
import io.github.llm4j.loom.autonomy.JdbcLevelStore;
import io.github.llm4j.loom.autonomy.Ledger;
import io.github.llm4j.loom.autonomy.LevelStore;
import io.github.llm4j.loom.runtime.FileRunJournal;
import io.github.llm4j.loom.runtime.JdbcRunJournal;
import io.github.llm4j.loom.runtime.RunJournal;
import io.github.llm4j.loom.trigger.FileTriggerStore;
import io.github.llm4j.loom.trigger.JdbcTriggerStore;
import io.github.llm4j.loom.trigger.TriggerStore;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;

/**
 * Where a host keeps what lets a run carry on after a restart: each run's journal, its record, the questions it asked, the triggers that wake it, and the
 * earned-autonomy records. {@link #none()} keeps nothing (runs cannot resume); {@link #file(Path)} uses the folder layout {@code weave} uses;
 * {@link #jdbc(DataSource)} uses tables (see {@link LoomHost#createSchema(DataSource)}).
 */
public final class Persistence {

    private enum Kind { NONE, FILE, JDBC }

    private final Kind kind;
    private final Path dir;
    private final DataSource dataSource;
    private final AnswerBook answers;

    private Persistence(Kind kind, Path dir, DataSource dataSource) {
        this.kind = kind;
        this.dir = dir == null ? null : dir.toAbsolutePath().normalize();
        this.dataSource = dataSource;
        this.answers = switch (kind) {
            case NONE -> new AnswerBook.Memory();
            case FILE -> new AnswerBook.Files_(this.dir);
            case JDBC -> new AnswerBook.Jdbc(dataSource);
        };
    }

    public static Persistence none() {
        return new Persistence(Kind.NONE, null, null);
    }

    /** A folder: {@code <dir>/runs/<id>/} holds each run's {@code journal.json} and {@code run.json}; {@code <dir>/triggers} the triggers; {@code <dir>/autonomy} the records. */
    public static Persistence file(Path dir) {
        return new Persistence(Kind.FILE, dir, null);
    }

    public static Persistence jdbc(DataSource dataSource) {
        if (dataSource == null) throw new IllegalArgumentException("jdbc persistence needs a DataSource");
        return new Persistence(Kind.JDBC, null, dataSource);
    }

    /** True when runs can be resumed: a store exists. */
    public boolean durable() {
        return kind != Kind.NONE;
    }

    /** The folder of {@link #file(Path)}, else null. */
    public Path dir() {
        return dir;
    }

    RunJournal journal(String runId) {
        return switch (kind) {
            case NONE -> null;
            case FILE -> new FileRunJournal(dir.resolve("runs").resolve(RunRegistry.safe(runId)).resolve("journal.json"));
            case JDBC -> new JdbcRunJournal(dataSource, runId);
        };
    }

    /** What an executor is told about where its run lives ({@code setRunLocator}). */
    String locator(String runId) {
        return kind == Kind.FILE ? dir.resolve("runs").resolve(RunRegistry.safe(runId)).toString() : runId;
    }

    TriggerStore triggers() {
        return switch (kind) {
            case NONE -> null;
            case FILE -> new FileTriggerStore(dir);
            case JDBC -> new JdbcTriggerStore(dataSource);
        };
    }

    Ledger ledger() {
        return switch (kind) {
            case NONE -> null;
            case FILE -> new FileLedger(dir.resolve("autonomy"));
            case JDBC -> new JdbcLedger(dataSource);
        };
    }

    LevelStore levels() {
        return switch (kind) {
            case NONE -> null;
            case FILE -> new FileLevelStore(dir.resolve("autonomy"));
            case JDBC -> new JdbcLevelStore(dataSource);
        };
    }

    RunRegistry registry(String scriptRef) {
        return switch (kind) {
            case NONE -> new RunRegistry.Memory();
            case FILE -> new RunRegistry.Files_(dir, scriptRef);
            case JDBC -> new RunRegistry.Jdbc(dataSource);
        };
    }

    AnswerBook answers() {
        return answers;
    }

    /** Creates every table a JDBC host uses; safe to call again. */
    static void createSchema(DataSource ds) {
        JdbcRunJournal.createTable(ds);
        JdbcTriggerStore.createTable(ds);
        JdbcLedger.createTable(ds);
        JdbcLevelStore.createTable(ds);
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute(RunRegistry.Jdbc.DDL);
            st.execute(AnswerBook.Jdbc.DDL);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot create the host's tables", e);
        }
    }
}
