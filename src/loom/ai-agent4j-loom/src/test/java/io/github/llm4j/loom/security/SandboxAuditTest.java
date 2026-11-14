package io.github.llm4j.loom.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Phase 35, D19 and G66: LA31 (a program run without a sandbox), LA32 (a workflow reaches a process
 * it started) and LA33 (a sandbox reaches the network) fire at the right severity, and not
 * otherwise.
 */
class SandboxAuditTest {

    static final String BASE =
            """
            budget { tokens: 1000 }
            tool Data { use: workspace  root: "work"  allow: "**" }
            """;

    static List<Finding> rule(String rule, String tools) {
        LoomScript script = new LoomParser(new Lexer(BASE + tools + "\nworkflow Main() { }").tokenize()).parseScript();
        return SecurityAudit.audit(script, "t.loom").findings().stream().filter(f -> f.rule().equals(rule)).toList();
    }

    static final String CONTAINER = "tool Py { use: sandbox  workspace: Data  isolation: container  image: \"python:3.12-slim\"  allow: \"python3\" }";

    @Test
    void la31FiresForProcessIsolationAndRisesWithTheNetworkAndSecrets() {
        assertThat(rule("LA31", CONTAINER)).as("container is the sandbox").isEmpty();
        var open = rule("LA31", "tool Py { use: sandbox  workspace: Data  isolation: process  network: open  allow: \"python3, ls\" }");
        assertThat(open).singleElement().satisfies(f -> {
            assertThat(f.severity()).isEqualTo(Severity.MEDIUM);
            assertThat(f.risk()).contains("Py").contains("python3 can run any command").contains("reach any host");
            assertThat(f.owasp()).contains(Owasp.LLM06);
        });
        var both = rule("LA31", "tool Py { use: sandbox  workspace: Data  isolation: process  network: open  allow: \"ls\"  env.TOKEN: secret.PYPI }");
        assertThat(both).singleElement().satisfies(f -> {
            assertThat(f.severity()).isEqualTo(Severity.HIGH);
            assertThat(f.risk()).contains("TOKEN from secret.PYPI").doesNotContain("any command");
        });
        assertThat(rule("LA31", "tool Py { use: sandbox  workspace: Data  isolation: process  allow: \"ls\" }"))
                .singleElement()
                .satisfies(f -> assertThat(f.severity()).isEqualTo(Severity.MEDIUM));
    }

    @Test
    void la32FiresForAnHttpToolThatReachesAServedProgramOnly() {
        assertThat(rule("LA32", CONTAINER + "\ntool Api { use: http  base_url: \"https://api.example.com\" }")).isEmpty();
        var found = rule("LA32", CONTAINER + "\ntool App { use: http  reaches: Py }");
        assertThat(found).singleElement().satisfies(f -> {
            assertThat(f.severity()).isEqualTo(Severity.INFO);
            assertThat(f.risk()).contains("App").contains("Py").contains("one port");
        });
    }

    @Test
    void la33FiresForAnOpenOrAListedNetworkAndCallsOutAWideHost() {
        assertThat(rule("LA33", CONTAINER)).as("network: none").isEmpty();
        assertThat(rule("LA33", CONTAINER.replace("}", " network: open }")))
                .singleElement()
                .satisfies(f -> assertThat(f.severity()).isEqualTo(Severity.MEDIUM));
        var listed = rule("LA33", CONTAINER.replace("}", " network: allow  hosts: \"pypi.org, *.pythonhosted.org\" }"));
        assertThat(listed).singleElement().satisfies(f -> {
            assertThat(f.severity()).isEqualTo(Severity.INFO);
            assertThat(f.risk()).contains("pypi.org").contains("*.pythonhosted.org is wide");
        });
    }
}
