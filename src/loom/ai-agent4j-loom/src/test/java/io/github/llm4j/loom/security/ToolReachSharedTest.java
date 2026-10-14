package io.github.llm4j.loom.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.ToolDef;
import io.github.llm4j.loom.execution.LoomLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R28: the audit and the trace read a tool's reach from one place, so they cannot disagree. */
class ToolReachSharedTest {

    @TempDir
    Path dir;

    private static final String[] KINDS = {
            "tool T { use: calculator }",
            "tool T { use: duckduckgo }",
            "tool T { use: webhook  url: env.HOOK }",
            "tool T { use: email  host: \"smtp.example.com\"  from: \"a@example.com\"  to: \"me@example.com\" }",
            "tool T { use: email  host: \"smtp.example.com\"  from: \"a@example.com\"  allow_to: \"*@example.com\" }",
            "tool T { use: http  base_url: \"https://api.example.com\" }",
            "tool T { use: http  base_url: \"https://api.example.com\"  methods: \"GET, POST\" }",
            "tool T { use: file  root: \"data\"  mode: read }",
            "tool T { use: file  root: \"data\"  mode: write }",
            "tool T { use: shell  allow: \"df\" }",
            "tool T { use: sql  url: env.DB_URL }",
            "tool T { use: openapi  spec: \"https://example.com/petstore.json\" }",
            "tool T { use: skill_registry  url: \"https://skills.example.com\" }",
            "tool T { use: class  class: \"com.acme.Tool\" }",
            "tool T { use: class  class: \"com.acme.Tool\"  reach: reads }",
            "tool T { use: class  class: \"com.acme.Tool\"  reach: fetches }",
            "tool T { use: class  class: \"com.acme.Tool\"  reach: sends }",
            "tool T { use: class  class: \"com.acme.Tool\"  reach: none }",
    };

    private LoomScript load(String declaration) throws Exception {
        Path f = dir.resolve("s" + System.nanoTime() + ".loom");
        Files.writeString(f, declaration + "\nagent A { model: \"m\" system: \"s\" tools: [T] }\nworkflow Main(q) { delegate \"{q}\" to A -> r }\n");
        return new LoomLoader().load(f.toString());
    }

    private static Map<String, ToolDef> byName(LoomScript s) {
        Map<String, ToolDef> m = new LinkedHashMap<>();
        for (ToolDef t : s.getTools()) m.put(t.getName(), t);
        return m;
    }

    private static String legs(SecurityAudit.AgentProfile p) {
        return (p.outward().isEmpty() ? "" : "outward,") + (p.privateData().isEmpty() ? "" : "private,") + (p.untrusted().isEmpty() ? "" : "untrusted");
    }

    @Test
    void forEveryKindTheTraceSaysWhatTheAuditSays() throws Exception {
        for (String declaration : KINDS) {
            LoomScript script = load(declaration);
            SecurityAudit.AgentProfile profile = SecurityAudit.audit(script, "s").agents().get(0);
            ToolReach reach = ToolReach.resolve("T", byName(script), Map.of());
            String expected = legs(profile).replaceAll(",$", "");
            assertThat(reach.legs()).as(declaration).isEqualTo(expected);
        }
    }

    @Test
    void aToolTheAuditCannotSeeIntoIsAllThreeAndNotKnown() {
        ToolReach r = ToolReach.resolve("lookup", Map.of(), Map.of());
        assertThat(r.legs()).isEqualTo("outward,private,untrusted");
        assertThat(r.known()).isFalse();
    }

    @Test
    void aDeclaredReachInTheLootFileIsBelievedAndKnown() {
        ToolReach reads = ToolReach.resolve("lookup", Map.of(), Map.of("lookup", "reads"));
        assertThat(reads.legs()).isEqualTo("private");
        assertThat(reads.known()).isTrue();
        assertThat(ToolReach.resolve("lookup", Map.of(), Map.of("lookup", "none")).legs()).isEmpty();
        assertThat(ToolReach.resolve("lookup", Map.of(), Map.of("lookup", "bogus")).known()).isFalse();
    }

    @Test
    void aBuiltInToolUsedByNameIsKnown() {
        assertThat(ToolReach.resolve("web_search", Map.of(), Map.of()).legs()).isEqualTo("untrusted");
        assertThat(ToolReach.resolve("calculator", Map.of(), Map.of()).legs()).isEmpty();
        assertThat(ToolReach.resolve("calculator", Map.of(), Map.of()).known()).isTrue();
    }

    @Test
    void legsAreSortedAndStable() {
        List<String> all = List.of("outward", "private", "untrusted");
        assertThat(ToolReach.resolve("x", Map.of(), Map.of()).legs().split(",")).containsExactlyElementsOf(all);
        assertThat(new ToolReach(true, true, false, true).legs()).isEqualTo("private,untrusted");
        assertThat(all.stream().collect(Collectors.joining(","))).isEqualTo(ToolReach.resolve("x", Map.of(), Map.of()).legs());
    }
}
