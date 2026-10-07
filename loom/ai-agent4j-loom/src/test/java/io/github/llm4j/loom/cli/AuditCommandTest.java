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

    static final String WITH_OWN_TOOL = """
            budget { tokens: 1000 }
            agent A { model: "m"  system: "s"  tools: [web_search, Checker] }
            workflow Main(q) { delegate "{q}" to A -> r }
            """;

    AuditCommand withLoot(String loot) throws Exception {
        AuditCommand c = command(WITH_OWN_TOOL, "md", "high");
        Path l = dir.resolve("tools.loot");
        Files.writeString(l, loot);
        c.lootFile = l.toFile();
        return c;
    }

    @Test
    void anOwnToolIsAssumedToDoEverythingUntilItsLootEntryDeclaresWhatItReaches() throws Exception {
        assertThat(AuditCommand.audit(command(WITH_OWN_TOOL, "md", "high"), env())).as("no declaration: the trifecta, a high finding").isEqualTo(1);

        out.reset();
        assertThat(AuditCommand.audit(withLoot("Checker = io.github.llm4j.loom.cli.AuditCommandTest$Probe\nChecker.reach = reads\n"), env())).isZero();
        assertThat(out.toString()).doesNotContain("LA14").contains("Checker").contains("declared reach: reads");
    }

    @Test
    void aWordThatIsNotOneOfTheFiveOrADeclarationForAnUnmappedToolIsRefused() throws Exception {
        assertThat(AuditCommand.audit(withLoot("Checker = x.Y\nChecker.reach = harmless\n"), env())).isEqualTo(2);
        assertThat(err.toString()).contains("Checker.reach must be one of").contains("harmless");

        err.reset();
        assertThat(AuditCommand.audit(withLoot("Other.reach = reads\n"), env())).isEqualTo(2);
        assertThat(err.toString()).contains("Other is not mapped to a class");

        err.reset();
        AuditCommand missing = command(WITH_OWN_TOOL, "md", "high");
        missing.lootFile = dir.resolve("nothing.loot").toFile();
        assertThat(AuditCommand.audit(missing, env())).isEqualTo(2);
        assertThat(err.toString()).contains("nothing.loot does not exist");
    }

    @Test
    void aReachLineIsNotLoadedAsATool() throws Exception {
        Path l = dir.resolve("mapped.loot");
        Files.writeString(l, "Checker = io.github.llm4j.loom.cli.AuditCommandTest$Probe\nChecker.reach = reads\n");
        io.github.llm4j.loom.execution.ToolRegistry registry = new io.github.llm4j.loom.execution.ToolRegistry();
        new io.github.llm4j.loom.execution.LootLoader().loadIntoRegistry(l.toString(), registry);
        assertThat(registry.names()).as("the reach line is a declaration, not a tool").containsExactly("Checker");
        assertThat(io.github.llm4j.loom.execution.LootLoader.reaches(l)).containsExactly(java.util.Map.entry("Checker", "reads"));
    }

    public static final class Probe implements io.github.llm4j.agent.Tool {
        public String getName() { return "checker"; }
        public String getDescription() { return "d"; }
        public String execute(java.util.Map<String, Object> args) { return "ok"; }
    }
}
