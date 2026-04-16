package io.github.llm4j.loom.parser;

import io.github.llm4j.loom.ast.*;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.lexer.Token;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class LoomParserTest {

    @Test
    public void testParseAgent() {
        String input = """
            agent Researcher {
                model: "gpt-4-turbo"
                system: "Find facts"
                tools: [WebSearch]
            }
        """;

        Lexer lexer = new Lexer(input);
        List<Token> tokens = lexer.tokenize();

        LoomParser parser = new LoomParser(tokens);
        LoomScript script = parser.parseScript();

        assertEquals(1, script.getAgents().size());
        AgentDef agent = script.getAgents().get(0);
        assertEquals("Researcher", agent.getName());
        assertEquals("gpt-4-turbo", agent.getModel());
        assertEquals("Find facts", agent.getSystemPrompt());
        assertEquals(1, agent.getTools().size());
        assertEquals("WebSearch", agent.getTools().get(0));
    }

    @Test
    public void testParseWorkflow() {
        String input = """
            workflow FactCheck(topic) {
                note "Starting fact check"
                handoff topic to Researcher
            }
        """;

        Lexer lexer = new Lexer(input);
        List<Token> tokens = lexer.tokenize();

        LoomParser parser = new LoomParser(tokens);
        LoomScript script = parser.parseScript();

        assertEquals(1, script.getWorkflows().size());
        WorkflowDef workflow = script.getWorkflows().get(0);
        assertEquals("FactCheck", workflow.getName());
        assertEquals(1, workflow.getParameters().size());
        assertEquals("topic", workflow.getParameters().get(0));

        assertEquals(2, workflow.getStatements().size());

        assertTrue(workflow.getStatements().get(0) instanceof NoteStmt);
        assertEquals("Starting fact check", ((NoteStmt) workflow.getStatements().get(0)).getMessage());

        assertTrue(workflow.getStatements().get(1) instanceof HandoffStmt);
        HandoffStmt handoff = (HandoffStmt) workflow.getStatements().get(1);
        assertEquals("topic", handoff.getPayload());
        assertEquals("Researcher", handoff.getTargetAgent());
    }
}
