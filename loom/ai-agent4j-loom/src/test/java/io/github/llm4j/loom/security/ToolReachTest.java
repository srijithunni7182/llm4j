package io.github.llm4j.loom.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.ast.ToolDef;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.tools.ToolFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** An author says what their own tool reaches with {@code reach:}; the audit believes it (it never loads the class) and says whose word it is. */
class ToolReachTest {

    @TempDir
    Path dir;

    static final String WORKFLOW = "\nworkflow Main(q) {\n    delegate \"{q}\" to A -> r\n}\n";

    ToolDef tool(String source) throws Exception {
        Path f = dir.resolve("t" + System.nanoTime() + ".loom");
        Files.writeString(f, source + "agent A { model: \"m\" system: \"s\" tools: [Mine] }" + WORKFLOW);
        return new LoomLoader().load(f.toString()).getTools().get(0);
    }

    AuditReport audit(String declaration) throws Exception {
        Path f = dir.resolve("s" + System.nanoTime() + ".loom");
        Files.writeString(f, declaration + "\nagent A { model: \"m\" system: \"s\" tools: [web_search, Mine] }" + WORKFLOW);
        return SecurityAudit.audit(new LoomLoader().load(f.toString()), f.getFileName().toString());
    }

    static List<Finding> rule(AuditReport r, String id) {
        return r.findings().stream().filter(f -> f.rule().equals(id)).toList();
    }

    @Test
    void aToolWithNoDeclarationIsStillAssumedToDoEverythingAndTheMessageSaysHowToDeclare() throws Exception {
        AuditReport r = audit("tool Mine { use: class  class: \"com.acme.Mine\" }");
        assertThat(rule(r, "LA14")).singleElement().satisfies(f -> assertThat(f.risk()).contains("reach: none | reads | fetches | writes | sends"));
        assertThat(rule(r, "LA01")).as("untrusted + private + outward on one agent").isNotEmpty();
    }

    @Test
    void aToolThatOnlyReadsItsOwnDataDoesNotCompleteTheTrifectaAndIsMarkedAsTheAuthorsWord() throws Exception {
        AuditReport r = audit("tool Mine { use: class  class: \"com.acme.Mine\"  reach: reads }");
        assertThat(rule(r, "LA14")).isEmpty();
        assertThat(rule(r, "LA01")).as("web search is untrusted and the tool reads private data, but nothing can send").isEmpty();
        assertThat(r.agents().get(0).privateData()).contains("Mine");
        assertThat(r.agents().get(0).outward()).doesNotContain("Mine");
    }

    @Test
    void theNoteSaysTheDeclarationIsNotChecked() throws Exception {
        assertThat(Capabilities.of(tool("tool Mine { use: class  class: \"com.acme.Mine\"  reach: none }")).note())
                .contains("declared reach: none").contains("author's word").contains("does not check");
    }

    @Test
    void eachWordMeansWhatItSays() throws Exception {
        record Row(String word, boolean untrusted, boolean privateData, boolean outward, boolean effect) { }
        for (Row w : List.of(new Row("none", false, false, false, false), new Row("reads", false, true, false, false),
                new Row("fetches", true, false, false, false), new Row("writes", false, true, false, true), new Row("sends", false, true, true, true))) {
            Capabilities c = Capabilities.of(tool("tool Mine { use: class  class: \"com.acme.Mine\"  reach: " + w.word() + " }"));
            assertThat(c.known()).as(w.word()).isTrue();
            assertThat(new Row(w.word(), c.untrusted(), c.privateData(), c.outward(), c.effect())).as(w.word()).isEqualTo(w);
        }
    }

    @Test
    void aSendingToolWithNoApprovalIsStillReported() throws Exception {
        AuditReport r = audit("tool Mine { use: class  class: \"com.acme.Mine\"  reach: sends }");
        assertThat(rule(r, "LA02")).as("an outward effect with no approval").isNotEmpty();
        assertThat(rule(r, "LA01")).isNotEmpty();
    }

    @Test
    void aWordThatIsNotOneOfTheFiveIsRefusedByCheckAndTreatedAsUnknownByTheAudit() throws Exception {
        ToolDef def = tool("tool Mine { use: class  class: \"com.acme.Mine\"  reach: harmless }");
        assertThat(new ToolFactory().problems(def, k -> null)).anyMatch(p -> p.contains("reach: must be one of") && p.contains("harmless"));
        assertThat(Capabilities.of(def).known()).isFalse();
    }

    @Test
    void reachOnABuiltInKindIsRefusedBecauseItAlreadyKnowsItsReach() throws Exception {
        ToolDef def = tool("tool Mine { use: calculator  reach: none }");
        assertThat(new ToolFactory().problems(def, k -> null)).anyMatch(p -> p.contains("reach: is for your own tools"));
    }

    @Test
    void aValidDeclarationIsNotAProblemAndIsNotPassedToTheTool() throws Exception {
        ToolDef def = tool("tool Mine { use: class  class: \"io.github.llm4j.loom.security.ToolReachTest$Probe\"  reach: reads }");
        assertThat(new ToolFactory().problems(def, k -> null)).isEmpty();
    }

    public static final class Probe implements io.github.llm4j.agent.Tool {
        public String getName() { return "probe"; }
        public String getDescription() { return "d"; }
        public String execute(java.util.Map<String, Object> args) { return "ok"; }
    }
}
