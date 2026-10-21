package io.github.llm4j.loom.parser;

import static org.junit.jupiter.api.Assertions.*;

import io.github.llm4j.loom.ast.IntentClassifierDef;
import io.github.llm4j.loom.ast.IntentClassifyStmt;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.RunStmt;
import io.github.llm4j.loom.ast.Statement;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.lexer.TokenType;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The grammar of {@code intentclassifier} and {@code intentclassify} (docs/specs/07-classifier DESIGN 5.1 to 5.3). */
class IntentClassifierParserTest {

    private static LoomScript parse(String source) {
        return new LoomParser(new Lexer(source).tokenize()).parseScript();
    }

    private static final String FULL = """
            provider Jev { use: jev  api_key: env.JEV_API_KEY }

            intentclassifier Intent {
                model: "Jev/jev-latest"
                choose kind: refund | order_status | complaint | other
                ask urgent: "Is the customer threatening to leave?"
                rate tone: calm | annoyed | angry
                confidence at least 0.8 else escalate
            }

            workflow Triage(message) {
                intentclassify Intent on message -> intent
            }
            """;

    // ---- the lexer

    @Test
    void aSinglePipeIsATokenAndADoublePipeIsStillRefusedWithItsHint() {
        assertEquals(TokenType.PIPE, new Lexer("a | b").tokenize().get(1).getType());
        RuntimeException e = assertThrows(RuntimeException.class, () -> new Lexer("a || b").tokenize());
        assertTrue(e.getMessage().contains("no && or ||"), e.getMessage());
    }

    // ---- the declaration

    @Test
    void aFullClassifierParsesWithItsQuestionsAndConfidenceRule() {
        IntentClassifierDef def = parse(FULL).getIntentClassifiers().get(0);

        assertEquals("Intent", def.getName());
        assertEquals("Jev/jev-latest", def.getModel());
        assertEquals(1, def.getSamples());
        assertEquals(3, def.getQuestions().size());
        IntentClassifierDef.Question kind = def.getQuestions().get(0);
        assertEquals(IntentClassifierDef.Kind.CHOOSE, kind.kind());
        assertEquals("kind", kind.name());
        assertEquals(List.of("refund", "order_status", "complaint", "other"), kind.labels());
        assertEquals(IntentClassifierDef.Kind.ASK, def.getQuestions().get(1).kind());
        assertEquals("Is the customer threatening to leave?", def.getQuestions().get(1).instructions());
        assertEquals(IntentClassifierDef.Kind.RATE, def.getQuestions().get(2).kind());
        assertEquals(List.of("calm", "annoyed", "angry"), def.getQuestions().get(2).labels());
        assertEquals(0.8, def.getConfidence().threshold());
        assertEquals(IntentClassifierDef.Action.ESCALATE, def.getConfidence().action());
    }

    @Test
    void instructionsMayBeWrittenOutAndOtherwiseDefaultToASentenceFromTheName() {
        IntentClassifierDef def = parse("""
                intentclassifier C {
                    model: "gemini-2.5-flash"
                    choose kind "What does the customer want?": refund | other
                    choose sector: retail | other
                    ask angry
                    rate tone "How hostile is it?": calm | angry
                }
                """).getIntentClassifiers().get(0);

        assertEquals("What does the customer want?", def.getQuestions().get(0).instructions());
        assertNull(def.getQuestions().get(1).instructions());
        assertEquals("Which sector fits best?", def.effectiveInstructions(def.getQuestions().get(1)));
        assertEquals("Is it angry? Answer yes or no.", def.effectiveInstructions(def.getQuestions().get(2)));
        assertEquals("How hostile is it?", def.effectiveInstructions(def.getQuestions().get(3)));
    }

    @Test
    void theThreeConfidenceActionsAndTheDefault() {
        String head = "intentclassifier C { model: \"m\" choose k: a | b ";

        assertEquals(IntentClassifierDef.Action.ESCALATE, action(head + "confidence at least 0.5 else escalate }"));
        assertEquals(IntentClassifierDef.Action.ASK_PERSON, action(head + "confidence at least 0.5 else ask person }"));
        assertEquals(IntentClassifierDef.Action.FAIL, action(head + "confidence at least 0.5 else fail }"));
        assertEquals(IntentClassifierDef.Action.ESCALATE, action(head + "confidence at least 0.5 }"));
    }

    private static IntentClassifierDef.Action action(String source) {
        return parse(source).getIntentClassifiers().get(0).getConfidence().action();
    }

    @Test
    void piiIsReadAndAbsentByDefault() {
        assertEquals("mask", parse("intentclassifier C { model: \"m\" pii: mask choose k: a | b }").getIntentClassifiers().get(0).getPii());
        assertNull(parse("intentclassifier C { model: \"m\" choose k: a | b }").getIntentClassifiers().get(0).getPii());
    }

    @Test
    void samplesAreRead() {
        assertEquals(5, parse("intentclassifier C { model: \"m\" samples: 5 choose k: a | b }").getIntentClassifiers().get(0).getSamples());
    }

    @Test
    void labelsMayBeQuotedAndMayBeWordsThatAreKeywordsElsewhere() {
        IntentClassifierDef def = parse("intentclassifier C { model: \"m\" choose k: type | model | \"order status\" }").getIntentClassifiers().get(0);

        assertEquals(List.of("type", "model", "order status"), def.getQuestions().get(0).labels());
    }

    @Test
    void theLinesOfTheDeclarationItsQuestionsAndTheStatementAreKept() {
        LoomScript script = parse("\n\nintentclassifier C {\n  model: \"m\"\n  choose k: a | b\n  ask u: \"u?\"\n  confidence at least 0.9\n}\nworkflow W(x) {\n  intentclassify C on x -> r\n}\n");

        IntentClassifierDef def = script.getIntentClassifiers().get(0);
        assertEquals(3, def.getLine());
        assertEquals(4, def.getModelLine());
        assertEquals(5, def.getQuestions().get(0).line());
        assertEquals(6, def.getQuestions().get(1).line());
        assertEquals(7, def.getConfidence().line());
        assertEquals(10, script.getWorkflows().get(0).getStatements().get(0).getLine());
    }

    // ---- the statement

    private static IntentClassifyStmt statement(String stmt) {
        LoomScript s = parse("workflow W(x, y) { " + stmt + " }");
        Statement st = s.getWorkflows().get(0).getStatements().get(0);
        return assertInstanceOf(IntentClassifyStmt.class, st);
    }

    @Test
    void theStateIsAVariableAPathAQuotedTextOrNamedFields() {
        IntentClassifyStmt byName = statement("intentclassify C on message -> intent");
        assertEquals("C", byName.getClassifier());
        assertEquals("intent", byName.getVariableName());
        assertEquals(1, byName.getInputs().size());
        assertEquals("", byName.getInputs().get(0).name());
        assertEquals(RunStmt.Kind.REFERENCE, byName.getInputs().get(0).kind());
        assertEquals("message", byName.getInputs().get(0).text());

        assertEquals("request.text", statement("intentclassify C on request.text -> r").getInputs().get(0).text());

        IntentClassifyStmt literal = statement("intentclassify C on \"Customer said: {x}\" -> r");
        assertEquals(RunStmt.Kind.STRING, literal.getInputs().get(0).kind());
        assertEquals("Customer said: {x}", literal.getInputs().get(0).text());

        IntentClassifyStmt fields = statement("intentclassify C on { message: x, history: \"none\" } -> r");
        assertEquals(2, fields.getInputs().size());
        assertEquals("message", fields.getInputs().get(0).name());
        assertEquals(RunStmt.Kind.REFERENCE, fields.getInputs().get(0).kind());
        assertEquals("history", fields.getInputs().get(1).name());
        assertEquals(RunStmt.Kind.STRING, fields.getInputs().get(1).kind());
    }

    @Test
    void retryBackoffTimeoutAndOnFailureAreTheSameAsForRun() {
        IntentClassifyStmt s = statement("intentclassify C on x -> r retry 2 backoff 2s timeout 30s on_failure { note \"classifier down\" }");

        assertEquals(2, s.getRetryCount());
        assertEquals(2000, s.getBackoffMillis());
        assertEquals(30_000, s.getTimeoutMillis());
        assertEquals(1, s.getOnFailure().size());
    }

    // ---- words that are reserved only where they mean something

    @Test
    void theNewWordsStayUsableAsNamesEverywhereElse() {
        LoomScript s = parse("""
                agent choose { model: "gemini-2.5-flash" system: "x" }
                workflow W(rate) { note "{rate}" }
                """);

        assertEquals("choose", s.getAgents().get(0).getName());
    }

    // ---- errors that say what to do

    private static String error(String source) {
        return assertThrows(RuntimeException.class, () -> parse(source)).getMessage();
    }

    @Test
    void aClassifierNeedsAModel() {
        assertTrue(error("intentclassifier C { choose k: a | b }").contains("model"));
    }

    @Test
    void anUnknownSettingListsWhatAClassifierCanHaveAndGuessesTheNearest() {
        String m = error("intentclassifier C { model: \"m\" chose k: a | b }");

        assertTrue(m.contains("chose"), m);
        assertTrue(m.contains("choose") && m.contains("ask") && m.contains("rate") && m.contains("confidence") && m.contains("samples") && m.contains("pii"), m);
        assertTrue(m.contains("Did you mean choose"), m);
    }

    @Test
    void repeatedSingletonSettingsAreRefused() {
        assertTrue(error("intentclassifier C { model: \"a\" model: \"b\" }").contains("model is given twice"));
        assertTrue(error("intentclassifier C { model: \"a\" samples: 2 samples: 3 }").contains("samples is given twice"));
        assertTrue(error("intentclassifier C { model: \"a\" confidence at least 0.5 confidence at least 0.6 }").contains("one confidence"));
    }

    @Test
    void badValuesAreExplained() {
        assertTrue(error("intentclassifier C { model: \"a\" samples: 0 }").contains("samples"));
        assertTrue(error("intentclassifier C { model: \"a\" confidence at least 1.5 }").contains("0 to 1"));
        assertTrue(error("intentclassifier C { model: \"a\" confidence at least 0.5 else shrug }").contains("escalate"));
        assertTrue(error("intentclassifier C { model: \"a\" choose k }").contains("labels"));
        assertTrue(error("intentclassifier C { model: \"a\" choose k: a | }").contains("label"));
    }

    @Test
    void theStatementNeedsTheArrowAndAVariable() {
        assertTrue(error("workflow W(x) { intentclassify C on x }").contains("->"));
        assertTrue(error("workflow W(x) { intentclassify C x -> r }").contains("on"));
    }

    @Test
    void aProviderCanBeOfKindJev() {
        assertEquals("jev", parse(FULL).getProviders().get(0).getKind());
    }

    @Test
    void classifiersFromAnImportedFileAreMergedLikeAgentsAre() {
        LoomScript lib = parse("intentclassifier Shared { model: \"m\" choose k: a | b }");
        LoomScript main = parse("workflow W(x) { intentclassify Shared on x -> r }");

        main.merge(lib);

        assertEquals("Shared", main.findIntentClassifier("Shared").getName());
        assertNull(main.findIntentClassifier("Nope"));
    }
}
