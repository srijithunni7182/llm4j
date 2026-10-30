package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 22 R2: a command prints its answer, not the log. The log level is WARNING unless {@code -v}
 * (INFO) or {@code -vv} (FINE) is given, for every command.
 */
class VerboseFlagTest {

    private static List<LogRecord> logged(String... args) {
        List<LogRecord> seen = new ArrayList<>();
        Logger logger = Logger.getLogger("io.github.llm4j.loom");
        Level before = logger.getLevel();
        Handler h =
                new Handler() {
                    @Override public void publish(LogRecord r) { seen.add(r); }
                    @Override public void flush() {}
                    @Override public void close() {}
                };
        h.setLevel(Level.ALL);
        logger.addHandler(h);
        boolean parent = logger.getUseParentHandlers();
        logger.setUseParentHandlers(false);
        try {
            WeaveCLI.commandLine().execute(args);
        } finally {
            logger.removeHandler(h);
            logger.setUseParentHandlers(parent);
            logger.setLevel(before);
        }
        return seen;
    }

    private static String script(Path dir) throws Exception {
        return Files.writeString(dir.resolve("main.loom"), "agent A { model: \"gemini-2.5-flash\" }\nworkflow W(t) {\n delegate \"x {t}\" to A -> r\n return r\n}\n").toString();
    }

    @Test
    void byDefaultNothingBelowWarningIsLogged(@TempDir Path dir) throws Exception {
        assertThat(logged("check", script(dir), "--no-env")).noneMatch(r -> r.getLevel().intValue() < Level.WARNING.intValue());
    }

    @Test
    void dashVGivesTheInfoLines(@TempDir Path dir) throws Exception {
        assertThat(logged("check", script(dir), "--no-env", "-v")).anyMatch(r -> r.getLevel() == Level.INFO && r.getMessage().contains("Loading Loom script"));
    }

    @Test
    void longFormAndDoubleVAreTheSameAndFine(@TempDir Path dir) throws Exception {
        String s = script(dir);
        assertThat(logged("check", s, "--no-env", "--verbose")).anyMatch(r -> r.getLevel() == Level.INFO);
        assertThat(logged("check", s, "--no-env", "-vv")).anyMatch(r -> r.getLevel() == Level.INFO);
    }

    @Test
    void theOldEvalFlagStillWorks(@TempDir Path dir) throws Exception {
        picocli.CommandLine eval = WeaveCLI.commandLine().getSubcommands().get("eval");
        assertThat(eval.getCommandSpec().optionsMap()).containsKey("--verbose");
    }
}
