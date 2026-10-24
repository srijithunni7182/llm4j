package io.github.llm4j.loom.explain;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.travel.TravelFixtures;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R8 of spec 13: why a step ran, and why a step that never ran did not. */
class WhyNotRanTest {

    @TempDir Path dir;

    private Why.Report ask(String caseName, Why.Selector selector) throws Exception {
        var c =
                TravelFixtures.CORPUS.stream()
                        .filter(x -> x.name().equals(caseName))
                        .findFirst()
                        .orElseThrow();
        var real = TravelFixtures.run(dir, c);
        return Why.explain(
                real.script(),
                c.workflow(),
                c.input(),
                real.run().journal(),
                new Why.Request(selector, false, null),
                Set.of());
    }

    @Test
    void aStepThatRanSaysWhichChoiceLetItRun() throws Exception {
        // line 14 is `delegate Replier` in the order-status branch
        Why.Answer a = ask("triage order status", new Why.Selector(null, null, 14)).answer();
        assertThat(a.text())
                .contains("delegate Replier ran because")
                .contains("alt (intent.kind == \"order_status\")")
                .contains("was true")
                .contains("intent.kind was \"order_status\"");
        assertThat(a.because().kind()).isEqualTo("alt");
    }

    @Test
    void aStepThatNeverRanNamesTheChoiceThatKeptItOutAndWhatWouldHaveLetItIn() throws Exception {
        // line 10 is `note "urgent"` in the branch the urgent test did not take
        Why.Answer a = ask("triage order status", new Why.Selector(null, null, 10)).answer();
        assertThat(a.text())
                .contains("did not run")
                .contains("alt (intent.urgent == \"yes\")")
                .contains("was false")
                .contains("intent.urgent was \"no\"")
                .contains("It would have run if intent.urgent == \"yes\"");
        assertThat(a.wouldNeed()).containsExactly("intent.urgent == \"yes\"");
    }

    @Test
    void theOtherWayRoundForAnElseBranch() throws Exception {
        // the else branch of the urgent test did not run in the urgent run
        Why.Answer a = ask("triage urgent", new Why.Selector(null, null, 13)).answer();
        assertThat(a.text()).contains("did not run").contains("was true");
        assertThat(a.wouldNeed()).containsExactly("intent.urgent != \"yes\"");
    }

    @Test
    void aStepDeepInAnExcludedBranchIsExplainedByTheOutermostChoice() throws Exception {
        // `delegate Replier` is inside the else branch, which the urgent run never entered
        Why.Answer a = ask("triage urgent", new Why.Selector(null, null, 14)).answer();
        assertThat(a.text()).contains("did not run").contains("alt (intent.urgent == \"yes\")");
    }

    @Test
    void aStepIsFoundByNodeAndByStepIdToo() throws Exception {
        Why.Report byLine = ask("triage order status", new Why.Selector(null, null, 14));
        String node = byLine.answer().because().node();
        assertThat(node).isNotNull();
        var real = ask("triage order status", new Why.Selector("Main/s1/b0/a0", null, null));
        assertThat(real.answer().text()).contains("ran because");
    }

    @Test
    void aStatementTheScriptDoesNotHaveIsSaidSo() throws Exception {
        Why.Answer a = ask("straight", new Why.Selector(null, null, 99)).answer();
        assertThat(a.text()).contains("There is no such step");
    }

    @Test
    void aTopLevelStepThatNeverRanSaysHowTheRunEnded() throws Exception {
        // the urgent run returns before the end of the script: its other return never ran
        Why.Answer a = ask("triage urgent", new Why.Selector(null, null, 17)).answer();
        assertThat(a.text()).contains("did not run");
    }
}
