package io.github.llm4j.loom.inspect.map;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.autonomy.Level;
import io.github.llm4j.loom.autonomy.LevelState;
import io.github.llm4j.loom.autonomy.LevelStore;
import io.github.llm4j.loom.autonomy.MemoryLevelStore;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AutonomyViewTest {

    private static final String SCRIPT = """
            agent Triager {
                model: "m"
                system: "You are Triager."
                output_schema: { choice: string, reasoning: string, confidence: number }
            }
            decision Refund {
                proposed by: Triager
                choices: approve, reject, escalate
                ask: lead
                dangerous mistake: propose approve, person decides reject
            }
            workflow Main() { note "x" }
            """;

    private final LoomScript script = new LoomParser(new Lexer(SCRIPT).tokenize()).parseScript();

    @Test
    void aDecisionNobodyHasAskedYetShowsWhereItWouldStart_andNothingIsCreatedByLooking() {
        LevelStore store = new MemoryLevelStore();
        Map<String, Object> d = AutonomyView.of(script, store).get(0);
        assertThat(d).containsEntry("name", "Refund").containsEntry("agent", "Triager").containsEntry("started", false).containsEntry("level", "watch");
        assertThat(store.scopes("Refund")).isEmpty();
    }

    @Test
    void theLowestScopeLevelSpeaksForTheDecision_withEachScopeListed_andAFreezeShown() {
        LevelStore store = new MemoryLevelStore();
        store.compareAndSet("Refund", "gold", null, new LevelState(Level.ACT, 1, "id", false, Instant.parse("2026-10-11T10:00:00Z"), "earned", 1));
        store.compareAndSet("Refund", "new", null, new LevelState(Level.SUGGEST, 1, "id", true, Instant.parse("2026-10-11T11:00:00Z"), "by hand", 1));
        store.setFreeze("Refund", new LevelStore.Freeze("review", Instant.parse("2026-10-11T12:00:00Z")));
        Map<String, Object> d = AutonomyView.of(script, store).get(0);
        assertThat(d).containsEntry("started", true).containsEntry("level", "suggest").containsEntry("frozen", true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> scopes = (List<Map<String, Object>>) d.get("scopes");
        assertThat(scopes).extracting(s -> s.get("scope")).containsExactlyInAnyOrder("gold", "new");
        assertThat(scopes).filteredOn(s -> "new".equals(s.get("scope"))).singleElement().satisfies(s -> assertThat(s).containsEntry("forced", true).containsEntry("reason", "by hand"));
    }

    @Test
    void aScriptWithoutDecisionsHasNoLadders() {
        LoomScript plain = new LoomParser(new Lexer("workflow Main() { note \"x\" }").tokenize()).parseScript();
        assertThat(AutonomyView.of(plain, null)).isEmpty();
    }
}
