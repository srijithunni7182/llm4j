package io.github.llm4j.loom.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.budget.BudgetExceeded;
import io.github.llm4j.classify.ClassifyRequest;
import io.github.llm4j.classify.ClassifyUsage;
import io.github.llm4j.classify.Question;
import io.github.llm4j.eval.testing.ScriptedClassifier;
import io.github.llm4j.exception.RateLimitException;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.loom.runtime.HumanInterface;
import io.github.llm4j.loom.runtime.RunJournal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@code intentclassify} at run time: the bound answers, the confidence rule, the budget, the trace, and resuming (acceptance A8, A9, A11, A12; R7, R9, R12). */
class IntentClassifyExecutionTest {

    private static final String MESSAGE = "my parcel 8812 never arrived and I want my money back";

    private static final String DECL = """
            intentclassifier Intent {
                model: "Jev/jev-latest"
                choose kind: refund | order_status | complaint | other
                ask urgent: "Is the customer threatening to leave?"
                rate tone: calm | annoyed | angry
                confidence at least 0.8 else escalate
            }
            """;

    private static final String TRIAGE = DECL + """
            workflow Triage(message) {
                intentclassify Intent on message -> intent
                alt (intent.kind == "refund") { note "route:refund" } else { note "route:{intent.kind}" }
            }
            """;

    private record Run(List<TraceEvent> events, Throwable error, Map<String, Object> variables, HarnessExecutor executor) {
        List<String> notes() {
            return events.stream().filter(e -> e.type().equals(TraceEvent.NOTE)).map(TraceEvent::text).toList();
        }

        TraceEvent classify() {
            return events.stream().filter(e -> e.type().equals("classify")).findFirst().orElseThrow();
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> intent() {
            return (Map<String, Object>) variables.get("intent");
        }
    }

    private static ScriptedClassifier.Builder jev() {
        return ScriptedClassifier.builder().calibrated(true).usage(new ClassifyUsage(100, 10, 150, "jev", "jev-1.13.0"));
    }

    private static ScriptedClassifier answers(double kindConfidence) {
        return jev().choice("kind", "refund", kindConfidence).yesNo("urgent", 0.12).score("tone", 0.5).build();
    }

    private Run run(String script, ScriptedClassifier classifier) {
        return run(script, classifier, null, null, Map.of("message", MESSAGE));
    }

    private Run run(String script, ScriptedClassifier classifier, HumanInterface human, RunJournal journal, Map<String, String> inputs) {
        LoomScript parsed = new LoomParser(new Lexer(script).tokenize()).parseScript();
        HarnessExecutor executor = new HarnessExecutor(parsed, new ToolRegistry(), model -> { throw new IllegalStateException("no chat model is used"); });
        executor.setClassifierFactory((model, samples) -> classifier);
        executor.setEnvLookup(name -> name.equals("JEV_API_KEY") ? "test-key" : null);
        if (human != null) executor.setHumanInterface(human);
        if (journal != null) executor.setJournal(journal);
        List<TraceEvent> events = Collections.synchronizedList(new ArrayList<>());
        executor.addTraceListener(events::add);
        executor.initialize();
        Throwable error = null;
        try {
            executor.executeWorkflow(parsed.getWorkflows().get(0).getName(), inputs);
        } catch (Throwable t) {
            error = t;
        }
        return new Run(events, error, executor.getContext().getAll(), executor);
    }

    // ---- binding and routing (A8)

    @Test
    void theAnswersAreBoundAsARecordTheScriptCanBranchOn() {
        Run r = run(TRIAGE, answers(0.93));

        assertThat(r.error()).isNull();
        Map<String, Object> intent = r.intent();
        assertThat(intent).containsEntry("kind", "refund").containsEntry("kind_confidence", 0.93).containsEntry("urgent", "no")
                .containsEntry("urgent_probability", 0.12).containsEntry("tone", "annoyed").containsEntry("tone_position", 0.5)
                .containsEntry("confidence", 0.93).containsEntry("calibrated", true).containsEntry("backend", "jev").containsEntry("model", "jev-1.13.0");
        assertThat(r.notes()).containsExactly("route:refund");
    }

    @Test
    void theOtherBranchIsTakenForAnotherLabel() {
        Run r = run(TRIAGE, jev().choice("kind", "complaint", 0.9).yesNo("urgent", 0.9).score("tone", 1.0).build());

        assertThat(r.notes()).containsExactly("route:complaint");
        assertThat(r.intent()).containsEntry("urgent", "yes").containsEntry("tone", "angry");
    }

    @Test
    void theClassifierIsAskedExactlyTheDeclaredQuestions() {
        ScriptedClassifier c = answers(0.93);

        run(TRIAGE, c);

        ClassifyRequest sent = c.requests().get(0);
        assertThat(sent.questions()).hasSize(3);
        assertThat(sent.questions().get(0)).isInstanceOfSatisfying(Question.Choice.class, q -> {
            assertThat(q.name()).isEqualTo("kind");
            assertThat(q.labels()).containsExactly("refund", "order_status", "complaint", "other");   // no unsure: a backend cannot produce it
            assertThat(q.instructions()).isEqualTo("Which kind fits best?");
        });
        assertThat(sent.questions().get(1)).isInstanceOfSatisfying(Question.YesNo.class, q -> assertThat(q.instructions()).isEqualTo("Is the customer threatening to leave?"));
        assertThat(sent.questions().get(2)).isInstanceOfSatisfying(Question.Score.class, q -> assertThat(q.scale().points()).containsExactly("calm", "annoyed", "angry"));
    }

    @Test
    void theStateIsAVariableAQuotedTextWithPlaceholdersOrNamedFields() {
        ScriptedClassifier plain = answers(0.9);
        ScriptedClassifier text = answers(0.9);
        ScriptedClassifier fields = answers(0.9);

        run(DECL + "workflow W(message) { intentclassify Intent on message -> i\n note \"{i.kind}\" }", plain);
        run(DECL + "workflow W(message) { intentclassify Intent on \"Customer said: {message}\" -> i\n note \"{i.kind}\" }", text);
        run(DECL + "workflow W(message) { intentclassify Intent on { message: message, history: \"none\" } -> i\n note \"{i.kind}\" }", fields);

        assertThat(plain.requests().get(0).plainText()).contains(MESSAGE);
        assertThat(text.requests().get(0).plainText()).contains("Customer said: " + MESSAGE);
        assertThat(fields.requests().get(0).state()).containsEntry("message", MESSAGE).containsEntry("history", "none");
    }

    @Test
    void stateThatHasNoValueFailsTheStepInsteadOfClassifyingNothing() {
        ScriptedClassifier c = answers(0.9);

        Run r = run(DECL + "workflow W(request) { intentclassify Intent on request.text -> i\n note \"{i.kind}\" }", c, null, null, Map.of("request", "plain text, not a record"));

        assertThat(r.error()).hasMessageContaining("request.text").hasMessageContaining("no value");
        assertThat(c.requests()).isEmpty();
    }

    // ---- the confidence rule at, just under and just over the threshold (A8)

    @Test
    void escalateTurnsALowConfidenceLabelIntoUnsureAndOnlyThen() {
        Run under = run(TRIAGE, answers(0.79));
        Run at = run(TRIAGE, answers(0.8));
        Run over = run(TRIAGE, answers(0.81));

        assertThat(under.intent()).containsEntry("kind", "unsure").containsEntry("kind_confidence", 0.79);
        assertThat(under.notes()).containsExactly("route:unsure");
        assertThat(at.intent()).containsEntry("kind", "refund");
        assertThat(over.intent()).containsEntry("kind", "refund");
    }

    @Test
    void escalatingAffectsOnlyTheChooseQuestionsThatAreBelowTheThreshold() {
        String script = """
                intentclassifier Intent {
                    model: "Jev/jev-latest"
                    choose kind: refund | other
                    choose region: north | south
                    confidence at least 0.8 else escalate
                }
                workflow W(message) { intentclassify Intent on message -> intent\n note "{intent.kind}/{intent.region}" }
                """;
        ScriptedClassifier c = jev().choice("kind", "refund", 0.5).choice("region", "north", 0.95).build();

        Run r = run(script, c);

        assertThat(r.intent()).containsEntry("kind", "unsure").containsEntry("region", "north").containsEntry("confidence", 0.5);
    }

    @Test
    void askPersonPutsALowConfidenceLabelToAPersonWhoseAnswerIsTheLabel() {
        List<String> asked = new ArrayList<>();
        List<List<String>> offered = new ArrayList<>();
        HumanInterface person = new HumanInterface() {
            @Override public String promptHuman(String message) { return "x"; }
            @Override public String promptHuman(String stepId, String message, Hints hints) {
                asked.add(message);
                offered.add(hints.choices());
                return "Complaint";
            }
        };
        String script = TRIAGE.replace("else escalate", "else ask person");

        Run low = run(script, answers(0.79), person, null, Map.of("message", MESSAGE));
        Run high = run(script, answers(0.9), person, null, Map.of("message", MESSAGE));

        assertThat(low.intent()).containsEntry("kind", "complaint");
        assertThat(asked).hasSize(1);                                  // not asked when sure
        assertThat(asked.get(0)).contains("kind").contains("0.79");
        assertThat(offered.get(0)).containsExactly("refund", "order_status", "complaint", "other");
        assertThat(high.intent()).containsEntry("kind", "refund");
    }

    @Test
    void askPersonAsksAgainOnceAndThenFailsIfTheAnswerIsNotALabel() {
        int[] asks = {0};
        HumanInterface person = msg -> { asks[0]++; return "maybe"; };

        Run r = run(TRIAGE.replace("else escalate", "else ask person"), answers(0.5), person, null, Map.of("message", MESSAGE));

        assertThat(asks[0]).isEqualTo(2);
        assertThat(r.error()).hasMessageContaining("not one of").hasMessageContaining("refund");
    }

    @Test
    void askPersonWithNobodyToAskIsAClearError() {
        Run r = run(TRIAGE.replace("else escalate", "else ask person"), answers(0.5));

        assertThat(r.error()).hasMessageContaining("Intent").hasMessageContaining("no person");
    }

    @Test
    void failMakesALowConfidenceStepFailLikeAFailingTask() {
        Run r = run(TRIAGE.replace("else escalate", "else fail"), answers(0.79));

        assertThat(r.error()).hasMessageContaining("Intent").hasMessageContaining("0.79").hasMessageContaining("0.8");
        assertThat(run(TRIAGE.replace("else escalate", "else fail"), answers(0.8)).error()).isNull();
    }

    @Test
    void onFailureHandlesTheFailureAndTheWorkflowGoesOn() {
        String script = DECL.replace("else escalate", "else fail") + """
                workflow W(message) {
                    intentclassify Intent on message -> intent on_failure { note "handled: {_error}" }
                    note "after"
                }
                """;

        Run r = run(script, answers(0.5));

        assertThat(r.error()).isNull();
        assertThat(r.notes()).hasSize(2);
        assertThat(r.notes().get(0)).startsWith("handled: ").contains("0.5");
        assertThat(r.notes().get(1)).isEqualTo("after");
        assertThat(r.variables()).doesNotContainKey("intent");
    }

    // ---- unsure comes only from the rule (R7, A9)

    @Test
    void aBackendCannotProduceUnsure() {
        ScriptedClassifier c = jev().choice("kind", "unsure", 0.99).yesNo("urgent", 0.1).score("tone", 0).build();

        Run r = run(TRIAGE, c);

        assertThat(r.error()).hasMessageContaining("unsure");
        assertThat(r.variables()).doesNotContainKey("intent");
    }

    @Test
    void withoutARuleALowConfidenceLabelIsUsedAsGiven() {
        Run r = run(TRIAGE.replace("    confidence at least 0.8 else escalate\n", ""), answers(0.1));

        assertThat(r.intent()).containsEntry("kind", "refund");
        assertThat(r.intent()).doesNotContainValue("unsure");
    }

    // ---- retry, and a failing classifier

    @Test
    void aRetryTriesAgainAfterAFailureAndTheAttemptsAreInTheTrace() {
        ScriptedClassifier c = jev().failWith(new RateLimitException("slow down")).next().choice("kind", "refund", 0.9).yesNo("urgent", 0.1).score("tone", 0).build();
        String script = DECL + "workflow W(message) { intentclassify Intent on message -> intent retry 1\n note \"{intent.kind}\" }";

        Run r = run(script, c);

        assertThat(r.error()).isNull();
        assertThat(c.requests()).hasSize(2);
        assertThat(r.notes()).containsExactly("refund");
        assertThat(r.classify().data()).containsEntry("attempts", 2);
    }

    @Test
    void aClassifierThatKeepsFailingFailsTheRunWithTheCause() {
        ScriptedClassifier c = jev().failWith(new RateLimitException("slow down")).build();

        Run r = run(TRIAGE, c);

        assertThat(r.error()).hasMessageContaining("Intent").hasMessageContaining("slow down");
        assertThat(r.events).anyMatch(e -> e.type().equals(TraceEvent.FAILURE) && "classify".equals(e.data().get("kind")));
    }

    // ---- the trace (A12, R12)

    @Test
    void theTraceCarriesBackendModelTokensLatencyAndLabelsButNeverTheState() {
        Run r = run(TRIAGE, answers(0.93));

        TraceEvent e = r.classify();
        assertThat(e.data()).containsEntry("classifier", "Intent").containsEntry("backend", "jev").containsEntry("model", "jev-1.13.0")
                .containsEntry("calibrated", true).containsEntry("input_tokens", 100).containsEntry("output_tokens", 10).containsEntry("outcome", "ok")
                .containsEntry("questions", 3).containsKey("duration_ms");
        assertThat(e.data().get("labels")).isEqualTo(Map.of("kind", "refund", "urgent", "no", "tone", "annoyed"));
        assertThat(e.data().get("confidence")).isEqualTo(0.93);
        assertThat(e.toString()).doesNotContain(MESSAGE).doesNotContain("never arrived").doesNotContain("threatening");
        assertThat(r.events.stream().map(Object::toString).toList()).noneMatch(s -> s.contains("8812"));
    }

    @Test
    void anEscalationIsInTheTraceWithoutTheState() {
        TraceEvent e = run(TRIAGE, answers(0.5)).classify();

        assertThat(e.data()).containsEntry("outcome", "unsure");
        assertThat(e.data().get("escalated")).isEqualTo(List.of("kind"));
        assertThat(e.data().get("labels")).isEqualTo(Map.of("kind", "unsure", "urgent", "no", "tone", "annoyed"));
    }

    // ---- budget (A11, R9)

    @Test
    void aClassifyCallIsChargedToTheBudgetAndABreachHalts() {
        ScriptedClassifier c = jev().usage(new ClassifyUsage(300, 10, 100, "jev", "jev-1.13.0")).choice("kind", "refund", 0.9).yesNo("urgent", 0.1).score("tone", 0).build();
        String script = "budget { tokens: 500 }\n" + DECL
                + "workflow W(message) {\n intentclassify Intent on message -> a\n intentclassify Intent on message -> b\n intentclassify Intent on message -> c\n note \"{a.kind}{b.kind}{c.kind}\"\n}\n";

        Run r = run(script, c);

        assertThat(r.error()).isInstanceOf(BudgetExceeded.class);
        assertThat(c.requests().size()).isBetween(1, 2);                                   // the refused call never reached the backend
        assertThat(r.executor().spend().total().tokens()).isEqualTo(310L * c.requests().size());
        assertThat(r.notes()).isEmpty();
    }

    @Test
    void theSpendIsReportedWithTheClassifierAsTheSpender() {
        Run r = run("budget { tokens: 100000 }\n" + TRIAGE, answers(0.9));

        assertThat(r.error()).isNull();
        assertThat(r.executor().spend().total().tokens()).isEqualTo(110L);
        assertThat(r.executor().spend().lines()).anyMatch(l -> l.agent().contains("Intent"));
    }

    // ---- resume: the recorded answer is reused, the backend is not asked twice

    @Test
    void aResumedRunReusesTheRecordedAnswerAndDoesNotAskAgain() {
        RunJournal journal = RunJournal.inMemory();
        ScriptedClassifier first = answers(0.93);
        ScriptedClassifier second = answers(0.1);   // would give a different answer if it were asked

        run(TRIAGE, first, null, journal, Map.of("message", MESSAGE));
        Run resumed = run(TRIAGE, second, null, journal, Map.of("message", MESSAGE));

        assertThat(first.requests()).hasSize(1);
        assertThat(second.requests()).isEmpty();
        assertThat(resumed.intent()).containsEntry("kind", "refund");
        assertThat(resumed.classify().data()).containsEntry("replayed", true);
    }

    @Test
    void aRecordedFailureGoesStraightToOnFailureOnResume() {
        RunJournal journal = RunJournal.inMemory();
        String script = DECL.replace("else escalate", "else fail") + "workflow W(message) {\n intentclassify Intent on message -> intent on_failure { note \"handled\" }\n}\n";
        ScriptedClassifier first = answers(0.5);
        ScriptedClassifier second = answers(0.99);

        run(script, first, null, journal, Map.of("message", MESSAGE));
        Run resumed = run(script, second, null, journal, Map.of("message", MESSAGE));

        assertThat(second.requests()).isEmpty();
        assertThat(resumed.notes()).containsExactly("handled");
    }

    // ---- personal data in the state (DESIGN 8): the same masker an agent's guard uses

    private static final String WITH_EMAIL = "please refund me, write to jo.smith@example.com";

    private static String piiScript(String mode) {
        return DECL.replace("    confidence at least 0.8 else escalate\n", "    pii: " + mode + "\n") + "workflow W(message) { intentclassify Intent on message -> intent\n note \"{intent.kind}\" }\n";
    }

    @Test
    void piiMaskKeepsPersonalDataFromTheBackendAndTheTraceNamesTypesNotValues() {
        ScriptedClassifier c = answers(0.9);

        Run r = run(piiScript("mask"), c, null, null, Map.of("message", WITH_EMAIL));

        assertThat(r.error()).isNull();
        assertThat(c.requests().get(0).plainText().orElseThrow()).doesNotContain("jo.smith@example.com").contains("please refund me");
        TraceEvent guard = r.events().stream().filter(e -> e.type().equals(TraceEvent.GUARD)).findFirst().orElseThrow();
        assertThat(guard.text()).contains("EMAIL").doesNotContain("jo.smith");
        assertThat(guard.data().toString()).doesNotContain("jo.smith");
    }

    @Test
    void piiBlockRefusesAStateThatCarriesPersonalData() {
        ScriptedClassifier c = answers(0.9);

        Run r = run(piiScript("block"), c, null, null, Map.of("message", WITH_EMAIL));

        assertThat(r.error()).hasMessageContaining("personal data").hasMessageContaining("EMAIL").hasMessageNotContaining("jo.smith");
        assertThat(c.requests()).isEmpty();
    }

    @Test
    void piiWarnSendsTheStateAsItIsAndRecordsTheFinding() {
        ScriptedClassifier c = answers(0.9);

        Run r = run(piiScript("warn"), c, null, null, Map.of("message", WITH_EMAIL));

        assertThat(r.error()).isNull();
        assertThat(c.requests().get(0).plainText().orElseThrow()).contains("jo.smith@example.com");
        assertThat(r.events()).anyMatch(e -> e.type().equals(TraceEvent.GUARD) && e.text().contains("EMAIL") && !e.text().contains("jo.smith"));
    }

    @Test
    void withoutThePiiSettingTheStateIsSentUntouched() {
        ScriptedClassifier c = answers(0.9);

        run(TRIAGE, c, null, null, Map.of("message", WITH_EMAIL));

        assertThat(c.requests().get(0).plainText().orElseThrow()).contains("jo.smith@example.com");
    }
}
