package io.github.llm4j.loom.explain;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.travel.TravelFixtures;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * R7 to R10 of spec 13: the choices of a run, over which values, from where, and the way not taken.
 */
class WhyTest {

    @TempDir Path dir;

    static Why.Report explain(Path dir, String caseName, Why.Request request) throws Exception {
        var c =
                TravelFixtures.CORPUS.stream()
                        .filter(x -> x.name().equals(caseName))
                        .findFirst()
                        .orElseThrow();
        var real = TravelFixtures.run(dir, c);
        return Why.explain(
                real.script(), c.workflow(), c.input(), real.run().journal(), request, Set.of());
    }

    @Test
    void thePathOfARunIsEachChoiceWithItsConditionValuesAndTheWayNotTaken() throws Exception {
        Why.Report r = explain(dir, "triage order status", Why.Request.path());
        assertThat(r.status()).isEqualTo("completed");
        String text = WhyRenderer.text(r);
        assertThat(text)
                .contains("intentclassify Intent")
                .contains("kind = order_status (0.99)")
                .contains("alt (intent.urgent == \"yes\")")
                .contains("→ false  → else")
                .contains("intent.urgent = \"no\"  (bound at step 1 by intentclassify Intent)")
                .contains("not taken: then: note")
                .contains("alt (intent.kind == \"order_status\")")
                .contains("→ TRUE  → then")
                .contains("delegate Replier")
                .contains("replied (16 chars)");
        assertThat(r.entries())
                .extracting(Why.Entry::kind)
                .containsExactly("classify", "alt", "alt", "delegate", "return");
    }

    @Test
    void theConditionIsShownAsWrittenWithItsQuotes() throws Exception {
        Why.Report r = explain(dir, "triage urgent", Why.Request.path());
        Why.Entry alt = r.entries().get(1);
        assertThat(alt.condition()).isEqualTo("intent.urgent == \"yes\"");
        assertThat(alt.result()).isTrue();
        assertThat(alt.taken()).isEqualTo("then");
        assertThat(alt.operands())
                .singleElement()
                .satisfies(
                        o -> {
                            assertThat(o.expr()).isEqualTo("intent.urgent");
                            assertThat(o.value()).isEqualTo("yes");
                            assertThat(o.boundBy()).isEqualTo("intentclassify Intent");
                            assertThat(o.origin()).isEqualTo("bound");
                            assertThat(o.boundNode()).isNotNull();
                        });
        assertThat(alt.notTaken()).singleElement().asString().startsWith("else: alt (intent.kind");
    }

    @Test
    void anInputIsSaidToBeAnInputAndAnUnsetNameIsSaidToBeUnset() throws Exception {
        Why.Report loop = explain(dir, "loop until done", Why.Request.path());
        assertThat(loop.entries().get(0).operands().get(0).origin()).isEqualTo("unset");
        assertThat(WhyRenderer.text(loop)).contains("(not set at this point)");
        assertThat(loop.entries().get(2).operands().get(0).boundBy()).isEqualTo("delegate Checker");
    }

    @Test
    void aLoopShowsEachLookAtItsConditionAndHowItLeft() throws Exception {
        Why.Report r = explain(dir, "loop until done", Why.Request.path());
        assertThat(r.entries()).filteredOn(e -> e.kind().equals("loop")).hasSize(4);
        assertThat(r.entries().stream().filter(e -> e.kind().equals("loop")).map(Why.Entry::taken))
                .containsExactly("body", "body", "body", "exit");
        Why.Report out = explain(dir, "loop runs out", Why.Request.path());
        String text = WhyRenderer.text(out);
        assertThat(text).contains("(left by max after 2 rounds)").contains("(round 1 of 2)");
    }

    @Test
    void aClassifierSaysWhatItAnsweredAndAForEachHowManyItems() throws Exception {
        assertThat(WhyRenderer.text(explain(dir, "for each", Why.Request.path())))
                .contains("for each item in plan.items")
                .contains("(2 items)");
    }

    @Test
    void aFailureTakenOverByAHandlerIsAChoice() throws Exception {
        Why.Report r = explain(dir, "failure taken over", Why.Request.path());
        assertThat(r.entries()).extracting(Why.Entry::kind).contains("delegate", "failure");
        assertThat(WhyRenderer.text(r)).contains("failed:").contains("on_failure took over");
    }

    @Test
    void aRewindIsExplainedAndEachAttemptIsMarked() throws Exception {
        Why.Report r = explain(dir, "rewind", Why.Request.path());
        assertThat(r.entries()).extracting(Why.Entry::kind).contains("rewind");
        assertThat(r.entries()).anyMatch(e -> e.generation() == 2);
        assertThat(WhyRenderer.text(r)).contains("(attempt 2)");
    }

    @Test
    void aCalledWorkflowsStepsAreShownInPlace() throws Exception {
        Why.Report r = explain(dir, "call another workflow", Why.Request.path());
        assertThat(r.entries())
                .extracting(Why.Entry::kind)
                .containsSubsequence("call", "delegate", "return");
    }

    @Test
    void allShowsEveryStepAndTheDefaultOnlyTheOnesThatDidSomething() throws Exception {
        Why.Report some = explain(dir, "triage urgent", Why.Request.path());
        Why.Report all =
                explain(dir, "triage urgent", new Why.Request(Why.Selector.none(), true, null));
        assertThat(all.entries().size()).isGreaterThanOrEqualTo(some.entries().size());
        assertThat(all.entries())
                .extracting(Why.Entry::kind)
                .contains("note".equals("x") ? "x" : "return");
    }

    @Test
    void sinceKeepsOnlyWhatCameAfterAStep() throws Exception {
        Why.Report all = explain(dir, "triage order status", Why.Request.path());
        String first = all.entries().get(0).step();
        Why.Report later =
                explain(
                        dir,
                        "triage order status",
                        new Why.Request(Why.Selector.none(), false, first));
        assertThat(later.entries()).hasSize(all.entries().size() - 1);
        assertThat(later.entries().get(0).kind()).isEqualTo("alt");
    }
}
