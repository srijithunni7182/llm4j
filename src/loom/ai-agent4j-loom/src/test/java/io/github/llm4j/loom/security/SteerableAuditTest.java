package io.github.llm4j.loom.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import java.util.List;
import org.junit.jupiter.api.Test;

class SteerableAuditTest {

    private static List<Finding> findings(String source) {
        LoomScript script = new LoomParser(new Lexer(source).tokenize()).parseScript();
        return SecurityAudit.audit(script, "t.loom").findings().stream()
                .filter(f -> f.rule().equals("LA18"))
                .toList();
    }

    @Test
    void aSteerableAgentIsListedAsAnInputChannel() {
        var found =
                findings(
                        "agent A { model: \"m\" steerable: true streaming: true }\nbudget { tokens: 1000 }");
        assertThat(found).hasSize(1);
        assertThat(found.get(0).severity()).isEqualTo(Severity.INFO);
        assertThat(found.get(0).title()).contains("person's text");
    }

    @Test
    void anAgentThatIsNotSteerableIsNotListed() {
        assertThat(findings("agent A { model: \"m\" }")).isEmpty();
    }

    @Test
    void aSteerableAgentWithPrivateDataAndAWayOutIsAMediumFinding() {
        var found =
                findings(
                        """
                tool Crm { use: file  root: "data"  mode: read }
                tool Mail { use: email  host: "smtp.example.com"  from: "a@example.com"  to: "me@example.com" }
                agent A { model: "m" tools: [Crm, Mail] steerable: true streaming: true }
                """);
        assertThat(found).hasSize(1);
        assertThat(found.get(0).severity()).isEqualTo(Severity.MEDIUM);
    }
}
