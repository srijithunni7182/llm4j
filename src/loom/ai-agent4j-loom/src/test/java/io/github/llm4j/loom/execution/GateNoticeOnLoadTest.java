package io.github.llm4j.loom.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * R4 of docs/specs/28: a packaged host that loads a script never reads {@code eval/golden}, so it is
 * never refused by the gate; it is told once, at WARNING, that the gate is a build-time check.
 */
class GateNoticeOnLoadTest {

    @TempDir Path dir;

    private final List<LogRecord> records = new ArrayList<>();
    private final Handler handler =
            new Handler() {
                @Override
                public void publish(LogRecord r) {
                    records.add(r);
                }

                @Override
                public void flush() {}

                @Override
                public void close() {}
            };
    private final Logger log = Logger.getLogger(LoomLoader.class.getName());

    @BeforeEach
    void listen() {
        log.addHandler(handler);
        LoomLoader.setGateNoticeWanted(true);
    }

    @AfterEach
    void stop() {
        log.removeHandler(handler);
        LoomLoader.setGateNoticeWanted(false);
    }

    private Path script(String name, String source) throws Exception {
        return Files.writeString(dir.resolve(name), source);
    }

    private static final String WITH_EXTRACTOR =
            "extractor Invoice { model: \"gemini-2.5-flash\"  fields { total: number } }\n"
                    + "workflow Main(t) { extract Invoice from t -> i  return i }\n";

    private long warnings() {
        return records.stream()
                .filter(r -> r.getLevel() == Level.WARNING && r.getMessage().contains("weave check"))
                .count();
    }

    @Test
    void aHostWithNoGoldenFolderLoadsAScriptWithAnExtractorAndIsToldOnceNotRefused() throws Exception {
        Path s = script("a.loom", WITH_EXTRACTOR);
        new LoomLoader().load(s.toString());
        new LoomLoader().load(s.toString());
        assertThat(Files.exists(dir.resolve("eval"))).isFalse();
        assertThat(warnings()).isEqualTo(1);
    }

    @Test
    void aScriptWithNothingToGateSaysNothing() throws Exception {
        new LoomLoader().load(script("b.loom", "workflow Main() { note \"hi\" }\n").toString());
        assertThat(warnings()).isZero();
    }

    @Test
    void theWeaveCommandTurnsTheMentionOffBecauseItEnforcesTheGateItself() throws Exception {
        LoomLoader.setGateNoticeWanted(false);
        new LoomLoader().load(script("c.loom", WITH_EXTRACTOR).toString());
        assertThat(warnings()).isZero();
    }
}
