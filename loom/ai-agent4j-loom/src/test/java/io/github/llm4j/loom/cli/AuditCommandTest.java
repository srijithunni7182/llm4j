package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code weave audit}: the report, its formats, and an exit code a build can gate on. */
class AuditCommandTest {

    @TempDir
    Path dir;

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();

    WeaveEnv env() {
        return new WeaveEnv(m -> null, q -> "", new PrintStream(out, true), new PrintStream(err, true), Clock.systemUTC(), d -> { },
                c -> new io.github.llm4j.loom.trigger.system.CommandRunner.Result(0, "", ""), List.of("weave"));
    }

    AuditCommand command(String source, String format, String failOn) throws Exception {
        Path f = dir.resolve("w.loom");
        Files.writeString(f, source);
        AuditCommand c = new AuditCommand();
        c.script = f.toFile();
        c.format = format;
        c.failOn = failOn;
        return c;
    }

    static final String RISKY = """
            tool Files { use: file  root: "data"  mode: read }
            tool Hook  { use: webhook  url: env.HOOK }
            agent A { model: "m"  system: "s"  tools: [web_search, Files, Hook] }
            workflow Main(q) { delegate "{q}" to A -> r }
            """;

    @Test
    void aHighFindingFailsTheBuildByDefaultAndTheThresholdCanBeMoved() throws Exception {
        assertThat(AuditCommand.audit(command(RISKY, "md", "high"), env())).isEqualTo(1);
        assertThat(out.toString()).contains("# Security audit: w.loom").contains("LA01").contains("LLM01:2025 Prompt Injection");
        assertThat(AuditCommand.audit(command(RISKY, "md", "none"), env())).isZero();

        String calm = "budget { tokens: 1000 }\nagent A { model: \"m\"  system: \"s\"  tools: [web_search] }\nworkflow Main(q) { delegate \"{q}\" to A -> r }\n";
        assertThat(AuditCommand.audit(command(calm, "md", "high"), env())).isZero();
        assertThat(AuditCommand.audit(command(calm, "md", "info"), env())).isZero();
    }

    @Test
    void jsonGoesToTheTerminalAndToAFile() throws Exception {
        AuditCommand c = command(RISKY, "json", "none");
        c.out = dir.resolve("report.json").toFile();

        assertThat(AuditCommand.audit(c, env())).isZero();

        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(Files.readString(c.out.toPath()));
        assertThat(json.get("summary").get("high").asInt()).isEqualTo(1);
        assertThat(out.toString()).contains("\"rule\" : \"LA01\"");
    }

    @Test
    void badOptionsAndAnUnreadableScriptAreRefusedWithExitTwo() throws Exception {
        assertThat(AuditCommand.audit(command(RISKY, "pdf", "high"), env())).isEqualTo(2);
        assertThat(AuditCommand.audit(command(RISKY, "md", "critical"), env())).isEqualTo(2);
        AuditCommand missing = command(RISKY, "md", "high");
        missing.script = new File(dir.toFile(), "nope.loom");
        assertThat(AuditCommand.audit(missing, env())).isEqualTo(2);
        assertThat(err.toString()).contains("--format").contains("--fail-on").contains("could not be read");
        assertThat(WeaveCLI.commandLine().getSubcommands()).containsKey("audit");
    }
}
