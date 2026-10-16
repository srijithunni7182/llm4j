package io.github.llm4j.loom.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.execution.LootLoader;
import io.github.llm4j.loom.execution.ScriptSource;
import io.github.llm4j.loom.execution.ToolRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Scripts and loot files that are not files on disk, and the schema a database host needs. V8.24–V8.27. */
class LoomHostLoadingTest {

    @TempDir
    Path dir;

    private Path jar(Map<String, String> entries) throws IOException {
        Path jar = dir.resolve("scripts.jar");
        try (OutputStream out = Files.newOutputStream(jar); JarOutputStream jos = new JarOutputStream(out)) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                jos.putNextEntry(new JarEntry(e.getKey()));
                jos.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                jos.closeEntry();
            }
        }
        return jar;
    }

    /** Reads scripts from a class loader, as an application reads the ones packaged in its jar; an import is relative to the importer. */
    private static ScriptSource from(ClassLoader loader) {
        return new ScriptSource() {
            @Override
            public String read(String location) throws IOException {
                try (InputStream in = loader.getResourceAsStream(location)) {
                    if (in == null) throw new IOException("not in the jar: " + location);
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            }

            @Override
            public String resolve(String from, String imported) {
                return URI.create(from).resolve(imported).normalize().toString();
            }
        };
    }

    private static final String MAIN = """
            import "lib/agents.loom"
            workflow Main() { delegate "go" to Writer -> out }
            """;
    private static final String AGENTS = "agent Writer { model: \"test/model\" system: \"You are Writer.\" }\n";

    @Tag("SB-V8.24")
    @Test
    void aScriptItsImportsAndItsLootFileAreReadFromAJar() throws Exception {
        Path jar = jar(Map.of("jar/main.loom", MAIN, "jar/lib/agents.loom", AGENTS,
                "jar/tools.loot", "Echo = io.github.llm4j.agent.tools.EchoTool\nEcho.reach = none\n"));
        Set<String> tmpBefore = tmpFiles();
        try (URLClassLoader loader = new URLClassLoader(new URL[] {jar.toUri().toURL()}, null)) {
            LoomScript script = new LoomLoader().load("jar/main.loom", from(loader));
            assertThat(script.getAgents()).extracting(a -> a.getName()).containsExactly("Writer");
            assertThat(script.getWorkflows()).extracting(w -> w.getName()).containsExactly("Main");

            ToolRegistry tools = new ToolRegistry();
            try (var reader = new InputStreamReader(loader.getResourceAsStream("jar/tools.loot"), StandardCharsets.UTF_8)) {
                new LootLoader().loadIntoRegistry(reader, tools);
            }
            assertThat(tools.names()).containsExactly("Echo");
            try (var reader = new InputStreamReader(loader.getResourceAsStream("jar/tools.loot"), StandardCharsets.UTF_8)) {
                assertThat(LootLoader.reaches(reader)).containsExactly(Map.entry("Echo", "none"));
            }

            HostEnv env = new HostEnv("file", dir.resolve("store"));
            env.host = LoomHost.builder(script, tools, env.models).persistence(env.persistence()).clock(env.clock).build();
            assertThat(env.host.run(RunRequest.of("Main", Map.of()))).isInstanceOf(RunOutcome.Completed.class);
            assertThat(env.models.calls("Writer")).isEqualTo(1);
        }
        assertThat(tmpFiles()).as("no temporary file was made for the script or the loot file").isEqualTo(tmpBefore);
    }

    @Tag("SB-V8.25")
    @Test
    void aCircularImportInsideAJarIsNamed() throws Exception {
        Path jar = jar(Map.of("jar/main.loom", MAIN, "jar/lib/agents.loom", "import \"../main.loom\"\n" + AGENTS));
        try (URLClassLoader loader = new URLClassLoader(new URL[] {jar.toUri().toURL()}, null)) {
            assertThatThrownBy(() -> new LoomLoader().load("jar/main.loom", from(loader))).hasMessageContaining("Circular dependency detected").hasMessageContaining("jar/main.loom");
        }
    }

    @Tag("SB-V8.26")
    @Test
    void thePathBasedLoaderAndTheSourceBasedLoaderReadEveryScriptInTheRepositoryAlike() throws Exception {
        Path repo = Path.of("../../..").toAbsolutePath().normalize();
        List<Path> scripts;
        try (Stream<Path> all = Files.walk(repo)) {
            scripts = all.filter(p -> p.toString().endsWith(".loom") && !p.toString().contains("/target/") && !p.toString().contains("/node_modules/")).sorted().toList();
        }
        assertThat(scripts).hasSizeGreaterThan(5);
        List<String> different = new ArrayList<>();
        for (Path script : scripts) {
            String viaPath = digest(() -> new LoomLoader().load(script.toString()));
            String viaSource = digest(() -> new LoomLoader().load(script.toAbsolutePath().normalize().toString(), ScriptSource.fileSystem()));
            if (!viaPath.equals(viaSource)) different.add(script + ": " + viaPath + " vs " + viaSource);
        }
        assertThat(different).isEmpty();
    }

    private interface Load { LoomScript load() throws Exception; }

    private static String digest(Load load) {
        try {
            LoomScript s = load.load();
            return "agents=" + s.getAgents().stream().map(a -> a.getName()).toList() + " workflows=" + s.getWorkflows().stream().map(w -> w.getName()).toList()
                    + " tools=" + s.getTools().stream().map(t -> t.getName()).toList() + " schedules=" + s.getSchedules().size() + " decisions=" + s.getDecisions().size();
        } catch (Exception e) {
            return "fails: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    @Tag("SB-V8.27")
    @Test
    void theSchemaIsCreatedOnceAndCreatingItAgainIsHarmless() throws Exception {
        HostEnv env = new HostEnv("jdbc", dir); // creates the schema once already
        LoomHost.createSchema(env.ds);
        LoomHost.createSchema(env.ds);
        Set<String> tables = new TreeSet<>();
        try (Connection c = env.ds.getConnection(); ResultSet rs = c.getMetaData().getTables(null, "PUBLIC", "%", new String[] {"TABLE"})) {
            while (rs.next()) tables.add(rs.getString("TABLE_NAME").toLowerCase());
        }
        assertThat(tables).contains("loom_journal", "loom_triggers", "llm4j_loom_runs", "llm4j_loom_answers");
        assertThat(tables).hasSizeGreaterThanOrEqualTo(6); // plus the ledger and the level store
    }

    private static Set<String> tmpFiles() throws IOException {
        try (Stream<Path> files = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".loot") || n.endsWith(".loom")).collect(java.util.stream.Collectors.toSet());
        }
    }

    @Tag("SB-V8.30")
    @Test
    void validateListsEveryProblemAndWritesNothing() throws Exception {
        String broken = HostEnv.AGENTS + """
                workflow Main() {
                    delegate "x" to Nobody -> a
                    run NoSuchTask(a = a) -> b
                }
                """;
        HostEnv env = new HostEnv("file", dir.resolve("store")).open(broken);
        assertThatThrownBy(env.host::validate).isInstanceOf(io.github.llm4j.loom.execution.LoomLoadException.class)
                .hasMessageContaining("Nobody").hasMessageContaining("NoSuchTask");
        assertThat(env.dir.resolve("store")).doesNotExist(); // validation left nothing behind
        HostEnv fine = new HostEnv("file", dir.resolve("store2")).open(HostEnv.THREE_STEPS);
        fine.host.validate();
        assertThat(fine.host.runs()).isEmpty();
    }
}
