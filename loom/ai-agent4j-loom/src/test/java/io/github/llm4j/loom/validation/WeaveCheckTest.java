package io.github.llm4j.loom.validation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.cli.CliProbe;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verification plan V3.1–V3.5. */
class WeaveCheckTest {

    @TempDir
    Path dir;

    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    final AtomicInteger modelClients = new AtomicInteger();

    int check(String source, boolean lenient, Map<String, String> env) throws Exception {
        Path f = dir.resolve("s.loom");
        Files.writeString(f, source);
        return CliProbe.check(f.toFile(), lenient, new PrintStream(out, true), env::get, modelClients);
    }

    @Test
    void v3_1_aValidScriptIsReadyAndNothingIsCalled() throws Exception {
        assertThat(check("""
                agent A { model: "gemini-2.5-flash" system: "You are A." }
                workflow Main() { delegate "x" to A -> x }
                """, false, Map.of())).isZero();
        assertThat(out.toString()).contains("✓ s.loom: ready to run");
        assertThat(modelClients.get()).isZero();
    }

    @Test
    void v3_2_allProblemsWithLines() throws Exception {
        assertThat(check(NothingIgnoredTest.MEMORY + "agent C { model: \"m\" tools: [Nope] }\n"
                + "workflow W() { guardrail (ODD) { note \"x\" } }\n", false, Map.of())).isEqualTo(2);
        assertThat(out.toString()).contains("✗ line 4: agent B").contains("✗ line 7: agent C").contains("✗ line 8: guardrail ODD")
                .contains("3 problems in s.loom");
    }

    @Test
    void v3_5_lenientWarnsButPasses() throws Exception {
        assertThat(check(NothingIgnoredTest.MEMORY, true, Map.of())).isZero();
        assertThat(out.toString()).contains("⚠ line 4").contains("(1 warning)");
    }

    @Test
    void parseErrorsAreReported() throws Exception {
        assertThat(check("agent { oops", false, Map.of())).isEqualTo(2);
        assertThat(out.toString()).startsWith("✗ s.loom: ");
    }
}
