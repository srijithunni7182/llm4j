package io.github.llm4j.loom.generic.foundation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.agent.Tool;
import io.github.llm4j.loom.ast.ToolDef;
import io.github.llm4j.loom.generic.support.Declared;
import io.github.llm4j.tools.support.Fuzz;
import io.github.llm4j.tools.support.RecordingEffects;
import io.github.llm4j.loom.tools.ToolFactory;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Things every generic kind shares: registration, option syntax, descriptions, and never throwing at the agent. */
class GenericKindsTest {

    @TempDir
    Path dir;

    @Test
    @Tag("V1.1")
    void allSixKindsAreRegisteredAndNoneIsABareBuiltInName() {
        assertThat(new ToolFactory().kinds()).contains("webhook", "email", "http", "file", "shell", "sql");
        assertThat(ToolFactory.BUILT_INS.keySet()).doesNotContain("webhook", "email", "http", "file", "shell", "sql");
    }

    @Test
    @Tag("V1.2")
    @Tag("V1.4")
    void optionValuesCanCarryUnitsAndKeysCanBeQuoted() {
        ToolDef def = Declared.parse("""
                tool Api { use: http  base_url: "https://api.example.com"  timeout: 20s  max_bytes: 64k  retries: 3
                           "header.X-Trace-Id": "abc"  header.Accept: "application/json"  ms: "kept as a key" }
                """);
        Map<String, String> o = new LinkedHashMap<>();
        def.getOptions().forEach((k, v) -> o.put(k, v.value()));
        assertThat(o).containsEntry("timeout", "20s").containsEntry("max_bytes", "64k").containsEntry("retries", "3")
                .containsEntry("header.X-Trace-Id", "abc").containsEntry("header.Accept", "application/json").containsEntry("ms", "kept as a key");
    }

    @Test
    @Tag("V1.2")
    void aNumberFollowedByAKeyIsNotMistakenForAUnit() {
        ToolDef def = Declared.parse("tool Api { use: http  base_url: \"https://a.example.com\"  retries: 2 s: x }");
        assertThat(def.getOptions().get("retries").value()).isEqualTo("2");
        assertThat(def.getOptions()).containsKey("s");
    }

    @Test
    @Tag("V1.4")
    void headerOptionsAreOnlyAcceptedWhereTheyMakeSense() {
        Declared d = new Declared(Map.of("T", "token-value-1234"), dir);
        assertThat(d.problems("tool N { use: file  \"header.X-A\": \"b\" }")).anyMatch(p -> p.contains("unknown option header.X-A"));
        assertThat(d.problems("tool N { use: http  base_url: \"https://a.example.com\"  \"header.X-A\": \"b\" }")).isEmpty();
        assertThat(d.problems("tool N { use: http  base_url: \"https://a.example.com\"  \"header.\": \"b\" }")).anyMatch(p -> p.contains("unknown option"));
        assertThat(d.problems("tool N { use: http  base_url: \"https://a.example.com\"  \"header.X-Token\": env.MISSING }")).anyMatch(p -> p.contains("MISSING"));
    }

    @Test
    @Tag("V1.2")
    void unknownOptionsListWhatTheKindTakes() {
        Declared d = new Declared(Map.of(), dir);
        assertThat(d.problems("tool N { use: file  colour: red }")).anyMatch(p -> p.contains("unknown option colour") && p.contains("mode") && p.contains("root"));
    }

    @Test
    @Tag("V1.9")
    @Tag("V1.8")
    void descriptionIsAcceptedByExistingKindsToo() throws Exception {
        Tool t = new Declared(Map.of(), dir).create("tool Calc { use: calculator  description: \"Use for money only.\" }", new RecordingEffects());
        assertThat(t.getName()).isEqualTo("Calc");
        assertThat(t.getDescription()).endsWith("Use for money only.");
        assertThat(t.execute(Map.of("expression", "2+2"))).contains("4");
    }
}
