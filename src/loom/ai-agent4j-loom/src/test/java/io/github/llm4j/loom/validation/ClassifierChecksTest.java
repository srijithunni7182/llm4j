package io.github.llm4j.loom.validation;

import static org.junit.jupiter.api.Assertions.*;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.execution.ScriptValidator;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The {@code weave check} rules of DESIGN 5.5 for {@code intentclassifier} and {@code intentclassify} (acceptance A10, requirement R8). */
class ClassifierChecksTest {

    private static final String CLASSIFIER = """
            intentclassifier Intent {
                model: "gemini-2.5-flash"
                samples: 3
                choose kind: refund | order_status | complaint | other
                ask urgent: "Is the customer threatening to leave?"
                rate tone: calm | annoyed | angry
                confidence at least 0.8 else escalate
            }
            """;

    private static List<ScriptValidator.Problem> problems(String source) {
        LoomScript script = new LoomParser(new Lexer(source).tokenize()).parseScript();
        return new ScriptValidator().validate(script, new ScriptValidator.Context());
    }

    private static List<ScriptValidator.Problem> errors(String source) {
        return problems(source).stream().filter(p -> p.severity() == ScriptValidator.Severity.ERROR).toList();
    }

    private static String text(List<ScriptValidator.Problem> ps) {
        return ps.stream().map(Object::toString).reduce("", (a, b) -> a + b + "\n");
    }

    private static String workflow(String body) {
        return "workflow Triage(message) {\n" + body + "\n}\n";
    }

    private static final String USE = "    intentclassify Intent on message -> intent\n";

    // ---- a good script is quiet

    @Test
    void aWellFormedClassifierAndItsUseHaveNoProblems() {
        assertEquals(List.of(), problems(CLASSIFIER + workflow(USE + "    alt (intent.kind == \"refund\") { note \"{intent.kind}\" }")));
    }

    // ---- the statement

    @Test
    void aStatementMustNameADeclaredClassifierAndSuggestsTheNearest() {
        String t = text(errors(CLASSIFIER + workflow("    intentclassify Intnt on message -> intent\n    note \"{intent.kind}\"")));

        assertTrue(t.contains("there is no intentclassifier named Intnt"), t);
        assertTrue(t.contains("did you mean Intent"), t);
        assertTrue(t.contains("line "), t);
    }

    @Test
    void aStatementWithNoClassifiersDeclaredSaysSo() {
        String t = text(errors(workflow("    intentclassify Intent on message -> intent")));

        assertTrue(t.contains("no intentclassifier is declared"), t);
    }

    @Test
    void whatIsClassifiedMustBeBound() {
        String t = text(errors(CLASSIFIER + workflow("    intentclassify Intent on mesage -> intent\n    note \"{intent.kind}\"")));

        assertTrue(t.contains("mesage"), t);
        assertTrue(t.contains("not defined"), t);
        assertTrue(t.contains("message"), "it lists what is defined: " + t);
    }

    @Test
    void anythingARunDelegateHumanPromptOrLoopBindsCountsAsBound() {
        String script = CLASSIFIER + """
                agent A { model: "gemini-2.5-flash" system: "x" }
                workflow Triage(message) {
                    delegate "{message}" to A -> reply
                    intentclassify Intent on reply -> first
                    human_prompt "More?" -> more
                    intentclassify Intent on more -> second
                    for each item in message.items {
                        intentclassify Intent on item -> third
                        note "{third.kind}"
                    }
                    intentclassify Intent on first.kind -> fourth
                    intentclassify Intent on { message: message, history: more } -> fifth
                    note "{first.kind} {second.kind} {fourth.kind} {fifth.kind}"
                }
                """;

        assertEquals(List.of(), errors(script));
    }

    @Test
    void inputsGivenAsFieldsAreEachChecked() {
        String t = text(errors(CLASSIFIER + workflow("    intentclassify Intent on { message: message, history: nothing } -> intent\n    note \"{intent.kind}\"")));

        assertTrue(t.contains("nothing"), t);
    }

    @Test
    void aClassificationThatIsNeverReadIsReportedLikeAnyUnusedResult() {
        List<ScriptValidator.Problem> all = problems(CLASSIFIER + workflow(USE));

        assertTrue(text(all).contains("intent is set here and never used"), text(all));
    }

    // ---- the declaration

    @Test
    void classifierNamesAreUnique() {
        String t = text(errors(CLASSIFIER + CLASSIFIER + workflow(USE)));

        assertTrue(t.contains("intentclassifier Intent") && t.contains("declared twice"), t);
    }

    @Test
    void aChoiceNeedsAtLeastTwoDistinctLabels() {
        String one = text(errors("intentclassifier C { model: \"gemini-2.5-flash\" choose kind: only }\n"));
        String dup = text(errors("intentclassifier C { model: \"gemini-2.5-flash\" choose kind: a | b | a }\n"));

        assertTrue(one.contains("kind") && one.contains("at least two labels"), one);
        assertTrue(dup.contains("kind") && dup.contains("a is listed twice"), dup);
    }

    @Test
    void aScaleHasTwoToTenPoints() {
        String one = text(errors("intentclassifier C { model: \"gemini-2.5-flash\" rate tone: only }\n"));
        String many = text(errors("intentclassifier C { model: \"gemini-2.5-flash\" rate tone: a | b | c | d | e | f | g | h | i | j | k }\n"));

        assertTrue(one.contains("tone") && one.contains("2 to 10"), one);
        assertTrue(many.contains("tone") && many.contains("2 to 10"), many);
    }

    @Test
    void unsureCannotBeDeclaredInAnyCase() {
        String t = text(errors("intentclassifier C { model: \"gemini-2.5-flash\" choose kind: refund | Unsure | other }\n"));

        assertTrue(t.contains("unsure is reserved"), t);
        assertTrue(t.contains("confidence"), "it says what produces unsure: " + t);
    }

    @Test
    void aClassifierNeedsAQuestion() {
        String t = text(errors("intentclassifier C { model: \"gemini-2.5-flash\" }\n"));

        assertTrue(t.contains("needs at least one question"), t);
    }

    @Test
    void questionNamesAreUniquePlainNamesThatDoNotShadowTheRecordsOwnFields() {
        assertTrue(text(errors("intentclassifier C { model: \"m\" choose k: a | b ask k: \"k?\" }\n")).contains("k is declared twice"));
        assertTrue(text(errors("intentclassifier C { model: \"m\" choose confidence: a | b }\n")).contains("confidence is a field of the answer"));
        assertTrue(text(errors("intentclassifier C { model: \"m\" ask calibrated: \"x?\" }\n")).contains("calibrated is a field of the answer"));
        assertTrue(text(errors("intentclassifier C { model: \"m\" ask a.b: \"x?\" }\n")).contains("plain name"));
        assertTrue(text(errors("intentclassifier C { model: \"m\" ask a: \"x?\" ask a_probability: \"y?\" }\n")).contains("a_probability"));
    }

    @Test
    void piiTakesMaskBlockOrWarn() {
        assertEquals(List.of(), errors("intentclassifier C { model: \"m\" pii: mask choose k: a | b }\n"));
        assertTrue(text(errors("intentclassifier C { model: \"m\" pii: scrub choose k: a | b }\n")).contains("pii: use mask, block or warn"));
    }

    // ---- the confidence rule

    @Test
    void aConfidenceRuleNeedsAChooseQuestionToMeasure() {
        String t = text(errors("intentclassifier C { model: \"m\" ask urgent: \"u?\" confidence at least 0.8 }\n"));

        assertTrue(t.contains("confidence") && t.contains("choose"), t);
    }

    @Test
    void aChatBackendWithoutSamplesIsMarkedUncalibratedAndToldToSetSamples() {
        List<ScriptValidator.Problem> all = problems("intentclassifier C { model: \"gemini-2.5-flash\" choose k: a | b confidence at least 0.8 }\nworkflow W(m) { intentclassify C on m -> r\n note \"{r.k}\" }\n");

        String t = text(all);
        assertTrue(t.contains("uncalibrated") && t.contains("samples"), t);
        assertTrue(all.stream().noneMatch(p -> p.severity() == ScriptValidator.Severity.ERROR), t);
    }

    @Test
    void jevNeedsNoSamplesAndSamplesAreWastedOnIt() {
        String ok = text(problems("intentclassifier C { model: \"Jev/jev-latest\" choose k: a | b confidence at least 0.8 }\nworkflow W(m) { intentclassify C on m -> r\n note \"{r.k}\" }\n"));
        String wasted = text(problems("intentclassifier C { model: \"Jev/jev-latest\" samples: 3 choose k: a | b }\nworkflow W(m) { intentclassify C on m -> r\n note \"{r.k}\" }\n"));

        assertFalse(ok.contains("uncalibrated"), ok);
        assertTrue(wasted.contains("samples") && wasted.contains("same answer"), wasted);
    }

    @Test
    void aProviderDeclaredWithUseJevIsJevAndAnotherKindIsNot() {
        String decl = "intentclassifier C { model: \"%s/m\" %s choose k: a | b confidence at least 0.8 }\nworkflow W(m) { intentclassify C on m -> r\n note \"{r.k}\" }\n";
        String jev = text(problems("provider P { use: jev api_key: env.JEV_API_KEY }\n" + String.format(decl, "P", "samples: 3")));
        String chat = text(problems("provider P { use: ollama base_url: \"http://h:1\" }\n" + String.format(decl, "P", "")));
        String jevNoSamples = text(problems("provider P { use: jev api_key: env.JEV_API_KEY }\n" + String.format(decl, "P", "")));

        assertTrue(jev.contains("same answer"), jev);
        assertTrue(chat.contains("uncalibrated"), chat);
        assertFalse(jevNoSamples.contains("uncalibrated"), jevNoSamples);
        assertFalse(text(problems(String.format(decl, "Other", "").replace("\"Other/m\"", "\"jev-latest\""))).contains("uncalibrated"));
    }

    // ---- an alt on a label that can never be produced (R8)

    private static String alt(String condition) {
        return CLASSIFIER + workflow(USE + "    alt (" + condition + ") { note \"x\" }");
    }

    @Test
    void aLabelThatIsNeverProducedIsAnErrorThatListsWhatCanBe() {
        String t = text(errors(alt("intent.kind == \"refunds\"")));

        assertTrue(t.contains("intent.kind can be refund, order_status, complaint, other, unsure"), t);
        assertTrue(t.contains("\"refunds\" is never produced"), t);
        assertTrue(t.contains("did you mean refund"), t);
        assertTrue(t.contains("line "), t);
    }

    @Test
    void notEqualIsCheckedToo() {
        assertTrue(text(errors(alt("intent.kind != \"refunds\""))).contains("never produced"));
    }

    @Test
    void everyProducibleLabelIsAcceptedIncludingUnsureWhenARuleEscalates() {
        for (String label : List.of("refund", "order_status", "complaint", "other", "unsure")) {
            assertEquals(List.of(), errors(alt("intent.kind == \"" + label + "\"")), label);
        }
    }

    @Test
    void unsureIsNeverProducedWithoutAnEscalatingRule() {
        String noRule = "intentclassifier Intent { model: \"Jev/jev-latest\" choose kind: refund | other }\n";
        String failing = "intentclassifier Intent { model: \"Jev/jev-latest\" choose kind: refund | other confidence at least 0.8 else fail }\n";
        String body = workflow(USE + "    alt (intent.kind == \"unsure\") { note \"x\" }");

        assertTrue(text(errors(noRule + body)).contains("\"unsure\" is never produced"));
        assertTrue(text(errors(failing + body)).contains("\"unsure\" is never produced"));
    }

    @Test
    void anAskIsYesOrNoAndARateIsOneOfItsPoints() {
        assertEquals(List.of(), errors(alt("intent.urgent == \"yes\"")));
        assertEquals(List.of(), errors(alt("intent.tone == \"angry\"")));
        assertTrue(text(errors(alt("intent.urgent == \"true\""))).contains("intent.urgent can be yes, no"));
        assertTrue(text(errors(alt("intent.tone == \"furious\""))).contains("intent.tone can be calm, annoyed, angry"));
    }

    @Test
    void numbersAreNotLabels() {
        assertEquals(List.of(), errors(alt("intent.confidence >= 0.8")));
        assertEquals(List.of(), errors(alt("intent.urgent_probability > 0.5")));
        assertEquals(List.of(), errors(alt("intent.tone_position >= 0.5")));
        assertEquals(List.of(), errors(alt("intent.calibrated == true")));
    }

    @Test
    void aFieldTheAnswerDoesNotHaveIsAnErrorThatListsThem() {
        String t = text(errors(alt("intent.kindd == \"refund\"")));

        assertTrue(t.contains("intent has no field kindd"), t);
        assertTrue(t.contains("kind") && t.contains("confidence") && t.contains("urgent_probability"), t);
    }

    @Test
    void aConditionOnSomethingElseIsLeftAlone() {
        assertEquals(List.of(), errors(CLASSIFIER + workflow(USE + "    set x = \"a\"\n    alt (x == \"zzz\") { note \"{intent.kind}\" }")));
    }

    @Test
    void anAltInsideABranchOrAFailureHandlerIsCheckedToo() {
        String t = text(errors(CLASSIFIER + workflow(USE + "    alt (intent.urgent == \"yes\") { alt (intent.kind == \"nope\") { note \"x\" } }")));

        assertTrue(t.contains("\"nope\" is never produced"), t);
    }
}
