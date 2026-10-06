package io.github.llm4j.loom.cli;

import io.github.llm4j.loom.ast.DecisionDef;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.autonomy.Candidates;
import io.github.llm4j.loom.autonomy.CaseSource;
import io.github.llm4j.loom.autonomy.EpochReplay;
import io.github.llm4j.loom.autonomy.FileLedger;
import io.github.llm4j.loom.autonomy.FileLevelStore;
import io.github.llm4j.loom.autonomy.Ledger;
import io.github.llm4j.loom.autonomy.LevelStore;
import io.github.llm4j.loom.autonomy.Names;
import io.github.llm4j.loom.autonomy.ReplayEngine;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.runtime.FileRunJournal;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/** Where decisions keep their ledger and levels for the command line, and how a past case's run and a candidate's executor are found. */
final class AutonomySupport {

    private AutonomySupport() { }

    /** {@code <store>/autonomy}: the ledger, levels and replays of every decision run from that store. */
    static Path dir(Path store) {
        return store.toAbsolutePath().normalize().resolve("autonomy");
    }

    static Ledger ledger(Path store) {
        return new FileLedger(dir(store));
    }

    static LevelStore levels(Path store) {
        return new FileLevelStore(dir(store));
    }

    /** Remembers which script declares a decision, so {@code weave autonomy} can read its rules without being told. */
    static void rememberScript(Path store, LoomScript script, File scriptFile) {
        for (DecisionDef d : script.getDecisions()) {
            try {
                Path meta = dir(store).resolve(Names.check(d.getName())).resolve("script");
                Files.createDirectories(meta.getParent());
                String text = scriptFile.getAbsolutePath();
                if (!Files.exists(meta) || !Files.readString(meta).equals(text)) {
                    // atomically: a command reading it at this moment sees the old text or the new, never an empty file
                    Path tmp = meta.resolveSibling("script.tmp");
                    Files.writeString(tmp, text);
                    Files.move(tmp, meta, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /** The script a decision was last run from, or empty. */
    static Optional<Path> scriptOf(Path store, String decision) {
        try {
            Path meta = dir(store).resolve(Names.check(decision)).resolve("script");
            if (!Files.exists(meta)) return Optional.empty();
            String text = Files.readString(meta).strip();
            return text.isEmpty() ? Optional.empty() : Optional.of(Path.of(text));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Every decision that has a directory in the store. */
    static java.util.List<String> decisions(Path store) {
        Path dir = dir(store);
        if (!Files.isDirectory(dir)) return java.util.List.of();
        try (var list = Files.list(dir)) {
            return list.filter(Files::isDirectory).map(p -> p.getFileName().toString()).filter(n -> !n.equals("_all")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Runs found by the run directory their ledger records name. */
    static CaseSource source() {
        return locator -> {
            Path dir = Path.of(locator);
            if (!Files.isDirectory(dir) || !Files.exists(dir.resolve(RunSpec.FILE)) || !Files.exists(dir.resolve(TravelCommands.JOURNAL))) return Optional.empty();
            RunSpec spec = RunSpec.read(dir);
            return Optional.of(new CaseSource.OpenedRun(new FileRunJournal(dir.resolve(TravelCommands.JOURNAL)), spec.workflow(), spec.inputs(), Path.of(spec.script())));
        };
    }

    /** Builds the executor a replay runs a case in, the way {@code weave run} builds one (without a journal of its own, trace or budgets). */
    static Candidates candidates(WeaveEnv env) {
        return (loaded, script, baseDir, run, journal) -> {
            ToolRegistry registry = new ToolRegistry();
            HarnessExecutor executor = new HarnessExecutor(loaded, registry, env.models());
            executor.setBaseDir(baseDir);
            executor.setPromptCatalog(io.github.llm4j.loom.prompt.PromptSupport.catalog(loaded, script, env.prompts()));
            executor.setEnvLookup(env.env());
            executor.setSecretStore(env.secrets());
            executor.setClock(env.clock());
            executor.setSleeper(env.sleeper());
            executor.setJournal(journal);
            return executor;
        };
    }

    static ReplayEngine engine(Path store, String decision, WeaveEnv env) {
        return new ReplayEngine(ledger(store), dir(store).resolve(Names.check(decision)).resolve("replays"), source(), candidates(env), env.clock())
                .promptCatalogs((loaded, file) -> io.github.llm4j.loom.prompt.PromptSupport.catalog(loaded, file, env.prompts()));
    }

    /** What a changed agent is replayed with when its decision says {@code test it on past cases}. */
    static EpochReplay inheritance(Path store, String decision, Path script, WeaveEnv env) {
        return new EpochReplay(engine(store, decision, env), script);
    }

    static Map<String, String> none() {
        return Map.of();
    }
}
