package io.github.llm4j.loom.inspect;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.inspect.state.InspectRules;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** R31, R32 in the add-on: the scan switch per agent, and what each agent can reach, from the static audit of the script. */
class InspectSessionSecurityTest {

    private static InspectRules scan(Boolean on) {
        return new InspectRules(null, null, null, null, null, null, null, null, null, new InspectRules.Security(null, null, on, null));
    }

    @Test
    void theScanIsOnUnlessARuleTurnsItOff() {
        assertThat(InspectSession.scanOn(Map.of(), "A")).isTrue();
        assertThat(InspectSession.scanOn(Map.of(), null)).isTrue();
        assertThat(InspectSession.scanOn(Map.of("A", scan(false)), "A")).isFalse();
        assertThat(InspectSession.scanOn(Map.of("A", scan(false)), "B")).isTrue();
    }

    @Test
    void theStarBlockAppliesToEveryoneWhoSaysNothingAndAnAgentsOwnRuleWins() {
        Map<String, InspectRules> rules = Map.of("*", scan(false), "B", scan(true));
        assertThat(InspectSession.scanOn(rules, "A")).isFalse();
        assertThat(InspectSession.scanOn(rules, "B")).isTrue();
        assertThat(InspectSession.scanOn(rules, null)).isFalse();
    }

    @Test
    void aRuleThatSetsOtherThingsLeavesTheScanOn() {
        InspectRules other = new InspectRules(3, null, null, null, null, null, null, null);
        assertThat(InspectSession.scanOn(Map.of("A", other), "A")).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void whatEachAgentCanReachComesFromTheAuditOfTheScript() {
        String source = """
                tool Search { use: duckduckgo }
                tool Files { use: file  root: "data"  mode: read }
                agent Reader { model: "m" tools: [Search, Files] }
                agent Quiet { model: "m" }
                workflow Main() { delegate "go" to Reader -> r }
                """;
        HarnessExecutor e = new HarnessExecutor(new LoomParser(new Lexer(source).tokenize()).parseScript(), new ToolRegistry(), model -> null);
        List<Map<String, Object>> profile = InspectSession.securityProfile(e.observable());
        assertThat(profile).extracting(m -> m.get("agent")).containsExactly("Reader", "Quiet");
        Map<String, Object> reader = profile.get(0);
        assertThat((List<Object>) reader.get("untrusted")).contains("Search");
        assertThat((List<Object>) reader.get("private")).contains("Files");
        assertThat((List<?>) reader.get("outward")).isEmpty();
        assertThat((List<?>) profile.get(1).get("untrusted")).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void aReachDeclaredInTheLootFileIsUsedNotAssumed() {
        String source = """
                agent Researcher { model: "m" tools: [Fetch, DB] }
                workflow Main() { delegate "go" to Researcher -> r }
                """;
        HarnessExecutor e = new HarnessExecutor(new LoomParser(new Lexer(source).tokenize()).parseScript(), new ToolRegistry(), model -> null);
        Map<String, Object> assumed = InspectSession.securityProfile(e.observable()).get(0);
        assertThat((List<Object>) assumed.get("outward")).as("host tools nobody described are assumed to reach everything").containsExactlyInAnyOrder("Fetch", "DB");
        e.setToolReach(Map.of("Fetch", "fetches", "DB", "reads"));
        Map<String, Object> declared = InspectSession.securityProfile(e.observable()).get(0);
        assertThat((List<Object>) declared.get("untrusted")).containsExactly("Fetch");
        assertThat((List<Object>) declared.get("private")).containsExactly("DB");
        assertThat((List<Object>) declared.get("outward")).isEmpty();
    }
}
