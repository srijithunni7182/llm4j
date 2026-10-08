package io.github.llm4j.loom.parser;

import static org.junit.jupiter.api.Assertions.*;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.SchemaDef;
import io.github.llm4j.loom.lexer.Lexer;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** The examples shown in the README and the guide must stay valid Loom. */
class DocumentedExamplesTest {

    @Test
    void theNewFeatureExamplesParse() throws Exception {
        String source;
        try (var in = getClass().getResourceAsStream("/docs/readme_examples.loom")) {
            source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        LoomScript script = new LoomParser(new Lexer(source).tokenize()).parseScript();
        assertEquals(1, script.getWorkflows().size());
        assertEquals(200_000L, script.getBudget().getTokens());
        assertEquals(2000, script.getAgents().stream().filter(a -> a.getName().equals("Writer"))
                .findFirst().orElseThrow().getBudget().getPerCall());
    }

    @Test
    void thePauseAndScheduleExamplesParse() throws Exception {
        String source;
        try (var in = getClass().getResourceAsStream("/docs/resume_examples.loom")) {
            source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        LoomScript script = new LoomParser(new Lexer(source).tokenize()).parseScript();
        assertEquals(io.github.llm4j.loom.ast.RateLimitDef.OnLimit.SUSPEND, script.getRateLimits().getOnLimit());
        assertEquals(io.github.llm4j.budget.Window.DAY, script.getBudget().getWindow());
        assertEquals(2, script.getSchedules().size());
        assertEquals("0 7 * * *", script.getSchedules().get(0).getCron());
        // and the one-line README variants
        new LoomParser(new Lexer("""
                rate_limits { on_limit: suspend  max_wait: 24h }
                budget { tokens: 100000 per day  when_exhausted: suspend }
                workflow DailyDigest(topic) { note "x" }
                schedule MorningDigest { cron: "0 7 * * *"  timezone: "Asia/Kolkata"  run: DailyDigest(topic="AI agents") }
                """).tokenize()).parseScript();
    }

    @Test
    void theBudgetsAndSchedulingWalkthroughsParse() {
        for (String source : new String[] {
                """
                rate_limits { on_limit: suspend }
                agent Researcher { model: "gemini/gemini-2.5-flash" system: "You are Researcher." tools: [WebSearch] }
                workflow Main(topic) {
                    delegate "Research the history of {topic}" to Researcher -> history
                    delegate "Research the state of the art of {topic}" to Researcher -> current
                    delegate "Research the open problems of {topic}" to Researcher -> problems
                    delegate "Write a briefing from {history} {current} {problems}" to Researcher -> briefing
                }
                """,
                """
                budget { tokens: 200000 per day  when_exhausted: suspend }
                agent Triage { model: "gemini/gemini-2.5-flash" system: "You are Triage." }
                workflow Main() {
                    for each ticket in backlog {
                        delegate "Triage {ticket}" to Triage -> {ticket.id}
                    }
                }
                """,
                """
                budget { tokens: 100000 per day  calls: 500 per day  when_exhausted: suspend }
                workflow Sweep() { note "x" }
                schedule Hourly { every: 1h  run: Sweep() }
                agent Critic { model: "m" }
                workflow Main(topic) {
                    delegate "Draft {topic}" to Critic -> draft budget 5000 tokens
                        on_failure { note "Out of budget: {_error}" }
                    broadcast "Review {draft}" to [Critic] -> reviews budget 3000 tokens
                    for each i in items budget 300 tokens { delegate "{i}" to Critic -> out } on_exhausted { note "x" }
                }
                """}) {
            assertNotNull(new LoomParser(new Lexer(source).tokenize()).parseScript());
        }
    }

    @Test
    void theToolsKnowledgeAndApprovalExamplesParse() throws Exception {
        String source;
        try (var in = getClass().getResourceAsStream("/docs/tools_examples.loom")) {
            source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        LoomScript script = new LoomParser(new Lexer(source).tokenize()).parseScript();
        assertEquals(5, script.getTools().size());
        assertEquals("serpapi", script.getTools().get(0).getKind());
        assertTrue(script.getTools().get(0).getOptions().get("api_key").fromEnv());
        assertEquals(io.github.llm4j.loom.ast.KnowledgeDef.Mode.CONTEXT, script.getKnowledgeBases().get(0).getMode());
        assertEquals(8, script.getAgents().get(0).getMaxIterations());
        assertEquals(java.util.List.of("Instagram"), script.getAgents().get(1).getApprove());
        // and the README one-liners
        new LoomParser(new Lexer("""
                tool Search { use: serpapi api_key: env.SERPAPI_KEY }
                knowledge Handbook { source: "docs/" embedding: "gemini/text-embedding-004" }
                agent P { model: "m" tools: [Search] approve: [Search] }
                """).tokenize()).parseScript();
    }

    @Test
    void theMemoryVoiceProviderPersonaGraphAndGuardExamplesParse() throws Exception {
        String source;
        try (var in = getClass().getResourceAsStream("/docs/depth_examples.loom")) {
            source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        LoomScript script = new LoomParser(new Lexer(source).tokenize()).parseScript();
        assertEquals(2, script.getProviders().size());
        assertEquals("ollama", script.getProviders().get(0).getKind());
        assertTrue(script.getProviders().get(1).getOptions().get("api_key").fromEnv());
        assertEquals("Mentor", script.getPersonas().get(0).getName());
        assertEquals(2, script.getPersonas().get(0).getConstraints().size());
        var concierge = script.getAgents().get(0).getMemory();
        assertEquals("chats", concierge.getConversation());
        assertEquals("{user_id}", concierge.getSession());
        assertEquals(0.7, concierge.getMinSimilarity());
        assertEquals("sarvam/bulbul:v2", script.getAgents().get(1).getVoice().getSpeak());
        assertEquals("replies", script.getAgents().get(1).getVoice().getOut());
        assertEquals("Mentor", script.getAgents().get(4).getPersona());
        assertEquals("mask", script.getAgents().get(5).getGuard().getPii());
        assertEquals("fallback", script.getRoutingPolicies().get(0).getStrategy());
        assertEquals(java.util.List.of("gemini-2.5-flash", "Box/gemma3"), script.getRoutingPolicies().get(0).getFallbackModels());
        // and the guide's one-liners
        new LoomParser(new Lexer("""
                agent A { model: "m" memory { conversation: "chats"  session: "{user_id}" }
                          voice  { speak: "sarvam/bulbul:v2"  language: "hi-IN" }
                          guard  { pii: mask  bias: warn } }
                agent B { model: "m" skills: ["https://skills.example.com/refunds.md"] }
                """).tokenize()).parseScript();
    }

    @Test
    void aBareListHoldsAnything() {
        LoomScript script = new LoomParser(new Lexer("""
                agent A { model: "m" output_schema: { items: list, names: list<string> } }
                """).tokenize()).parseScript();
        SchemaDef schema = script.getAgents().get(0).getOutputSchema();
        assertEquals(SchemaDef.Type.LIST, schema.getFields().get("items").getType());
        assertNull(schema.getFields().get("items").getElementType());
        assertEquals(SchemaDef.Type.STRING, schema.getFields().get("names").getElementType().getType());
    }
}
