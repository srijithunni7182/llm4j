package io.github.llm4j.loom.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.llm4j.loom.ast.AltStmt;
import io.github.llm4j.loom.ast.BroadcastStmt;
import io.github.llm4j.loom.ast.CallStmt;
import io.github.llm4j.loom.ast.CheckpointStmt;
import io.github.llm4j.loom.ast.DecideStmt;
import io.github.llm4j.loom.ast.DelegateStmt;
import io.github.llm4j.loom.ast.ForEachStmt;
import io.github.llm4j.loom.ast.GuardrailStmt;
import io.github.llm4j.loom.ast.HandoffStmt;
import io.github.llm4j.loom.ast.HumanPromptStmt;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.LoopStmt;
import io.github.llm4j.loom.ast.NoteStmt;
import io.github.llm4j.loom.ast.ObserveStmt;
import io.github.llm4j.loom.ast.ParallelStmt;
import io.github.llm4j.loom.ast.RewindStmt;
import io.github.llm4j.loom.ast.RunStmt;
import io.github.llm4j.loom.ast.Statement;
import io.github.llm4j.loom.ast.StatementWalker;
import io.github.llm4j.loom.ast.WorkflowDef;
import io.github.llm4j.loom.lexer.Lexer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** V1.6: every statement and workflow remembers the line it starts on. */
class ParserLinesTest {

    private static final Map<Class<?>, List<String>> KEYWORDS = Map.ofEntries(
            Map.entry(DelegateStmt.class, List.of("delegate")),
            Map.entry(RunStmt.class, List.of("run")),
            Map.entry(BroadcastStmt.class, List.of("broadcast")),
            Map.entry(ParallelStmt.class, List.of("parallel")),
            Map.entry(HumanPromptStmt.class, List.of("human_prompt")),
            Map.entry(AltStmt.class, List.of("alt")),
            Map.entry(NoteStmt.class, List.of("note")),
            Map.entry(ObserveStmt.class, List.of("observe")),
            Map.entry(LoopStmt.class, List.of("loop")),
            Map.entry(ForEachStmt.class, List.of("for each", "parallel for each")),
            Map.entry(GuardrailStmt.class, List.of("guardrail")),
            Map.entry(DecideStmt.class, List.of("decide")),
            Map.entry(CallStmt.class, List.of("call")),
            Map.entry(HandoffStmt.class, List.of("handoff")),
            Map.entry(RewindStmt.class, List.of("rewind")),
            Map.entry(CheckpointStmt.class, List.of("checkpoint")));

    private static LoomScript parse(String source) {
        return new LoomParser(new Lexer(source).tokenize()).parseScript();
    }

    @Test
    void everyStatementKindStartsOnTheLineItIsWrittenOn() throws IOException {
        List<String> lines = Files.readAllLines(Path.of("src/test/resources/graph/all_statements.loom"));
        LoomScript script = parse(String.join("\n", lines));
        Set<Class<?>> seen = new HashSet<>();

        for (WorkflowDef workflow : script.getWorkflows()) {
            StatementWalker.walk(workflow.getStatements(), statement -> {
                seen.add(statement.getClass());
                assertTrue(statement.getLine() > 0, statement.getClass().getSimpleName() + " has no line");
                String text = lines.get(statement.getLine() - 1).trim();
                List<String> accepted = KEYWORDS.get(statement.getClass());
                assertTrue(
                        accepted.stream().anyMatch(text::startsWith),
                        statement.getClass().getSimpleName() + " line " + statement.getLine() + " is: " + text);
            });
        }
        assertEquals(KEYWORDS.keySet(), seen, "the fixture must use every statement kind");
    }

    @Test
    void aWorkflowRemembersTheLineOfItsName() {
        LoomScript script = parse("agent A { model: \"m\" }\n\nworkflow First() { note \"x\" }\nworkflow Second() { note \"y\" }\n");
        assertEquals(3, script.getWorkflows().get(0).getLine());
        assertEquals(4, script.getWorkflows().get(1).getLine());
    }

    @Test
    void aStatementOnTheSameLineAsItsBlockKeepsItsOwnLine() {
        LoomScript script = parse("workflow W() {\n  alt (a == \"1\") { note \"one\" } else { note \"two\" }\n}\n");
        AltStmt alt = (AltStmt) script.getWorkflows().get(0).getStatements().get(0);
        assertEquals(2, alt.getLine());
        assertEquals(2, alt.getIfBranch().get(0).getLine());
    }

    @Test
    void aStatementBuiltByHandHasLineZero() {
        Statement statement = new NoteStmt("x");
        assertEquals(0, statement.getLine());
    }
}
