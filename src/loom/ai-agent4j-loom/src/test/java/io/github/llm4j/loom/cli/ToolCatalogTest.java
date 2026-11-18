package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The Guide reads the tools catalog from the jar (phase 37): the copy it reads is the repository's own page, every tool in
 * the at-a-glance table has a section the Guide can fetch, and `weave guide tools` prints it for people.
 */
class ToolCatalogTest {

    @Test
    void theCatalogInTheJarIsTheRepositorysPage() throws Exception {
        assertThat(ToolCatalog.text()).isEqualTo(Files.readString(Path.of("../../../docs/TOOLS_CATALOG.md")));
    }

    @Test
    void everyToolAtAGlanceHasASectionTheGuideCanRead() {
        String glance = ToolCatalog.glance();
        assertThat(ToolCatalog.names()).contains("http", "openapi", "mcp", "webhook", "sandbox", "browser");
        for (String name : ToolCatalog.names()) {
            assertThat(glance).as(name).contains("`" + name + "`");
            assertThat(ToolCatalog.section(name)).as(name).get().asString().startsWith("### ").contains("`" + name + "`");
        }
        assertThat(ToolCatalog.section("openapi")).get().asString().contains("One declared tool is one operation").doesNotContain("### 🧩");
        assertThat(ToolCatalog.section("warp")).isEmpty();
    }

    @Test
    void theGuideFetchesASectionOrSaysWhichToolsThereAre() throws Exception {
        Path dir = Files.createTempDirectory("catalog");
        Path p = dir.resolve("p");
        assertThat(WeaveCLI.commandLine().execute("init", "empty", p.toString(), "--flat")).isZero();
        java.io.PrintStream sink = new java.io.PrintStream(new java.io.ByteArrayOutputStream());
        java.time.Clock clock = java.time.Clock.systemUTC();
        GuideMcpServer s = new GuideMcpServer(p.resolve("main.loom"), WeaveEnv.system().withStreams(sink, sink).withEnvLookup(k -> null),
                new GuideEvents(p, clock), new GuideLearner(dir.resolve("home"), p, clock), (a, args) -> "shown");
        String call = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"facts\",\"arguments\":{\"catalog\":\"%s\"}}}";

        assertThat(s.handle(call.formatted("mcp")).path("result").path("content").get(0).path("text").asText()).contains("`mcp`").contains("effect");
        assertThat(s.handle(call.formatted("list")).path("result").path("content").get(0).path("text").asText()).contains("At a glance");
        var unknown = s.handle(call.formatted("warp")).path("result");
        assertThat(unknown.path("isError").asBoolean()).isTrue();
        assertThat(unknown.path("content").get(0).path("text").asText()).contains("openapi");
    }

    @Test
    void peopleReadTheSameCatalogWithWeaveGuideFacts() {
        var out = new java.io.ByteArrayOutputStream();
        var err = new java.io.ByteArrayOutputStream();
        WeaveEnv env = WeaveEnv.system().withStreams(new java.io.PrintStream(out, true), new java.io.PrintStream(err, true));
        assertThat(GuideFactsCommand.catalog("openapi", env)).isZero();
        assertThat(out.toString()).contains("`openapi`");
        assertThat(GuideFactsCommand.catalog("list", env)).isZero();
        assertThat(out.toString()).contains("At a glance");
        assertThat(GuideFactsCommand.catalog("warp", env)).isEqualTo(2);
        assertThat(err.toString()).contains("mcp");
    }
}
