package io.github.llm4j.loom.inspect.map;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SignalsTest {

    @Test
    void figuresAreTheNumbersThatMeanSomething_notStepCountsOrOneDigitNumbers() {
        assertThat(Signals.figuresIn("3 findings in step 2: 87%, 1,200 users, 4.5 stars, 2019, 7"))
                .containsExactlyInAnyOrder("87%", "1200", "4.5", "2019");
        assertThat(Signals.figuresIn(null)).isEmpty();
        assertThat(Signals.figuresIn("version v2.10 and id a123b"))
                .isEmpty(); // glued to words: not a figure
    }

    @Test
    void aFigureInTheTaskOrInAToolResultIsGrounded_aFigureFoundNowhereIsNot() {
        Signals.Corpus corpus = new Signals.Corpus();
        corpus.add("Write about video hooks. Sample: 1,200 viewers");
        corpus.add("{\"hits\": 340, \"share\": 12.5%}");
        Signals.Grounding g =
                Signals.ground(
                        "A study of 1200 viewers found 340 hits, 12.5% share, and 87% retention in 2019.",
                        corpus);
        assertThat(g.figures()).isEqualTo(5);
        assertThat(g.ungrounded()).isEqualTo(2);
        assertThat(g.examples()).containsExactlyInAnyOrder("87%", "2019");
    }

    @Test
    void anAnswerWithNoFiguresHasNothingToCheck() {
        Signals.Grounding g = Signals.ground("A fine script about hooks.", new Signals.Corpus());
        assertThat(g.figures()).isZero();
        assertThat(g.ungrounded()).isZero();
    }

    @Test
    void hashesIgnoreWhitespaceAndTellDifferentThingsApart_andRevealNothing() {
        assertThat(Signals.actionHash("Wiki", "{\"q\":  \"hooks\"}"))
                .isEqualTo(Signals.actionHash("Wiki", " {\"q\": \"hooks\"} "));
        assertThat(Signals.actionHash("Wiki", "{\"q\":\"hooks\"}"))
                .isNotEqualTo(Signals.actionHash("Fetch", "{\"q\":\"hooks\"}"));
        assertThat(Signals.outputHash("score 5\nfix it"))
                .isEqualTo(Signals.outputHash("score 5 fix it"))
                .hasSize(12)
                .matches("[0-9a-f]+");
    }

    @Test
    void theCorpusIsBounded() {
        Signals.Corpus c = new Signals.Corpus();
        for (int i = 0; i < 50; i++) c.add("x".repeat(100_000));
        c.add("99999");
        assertThat(Signals.ground("99999 here", c).ungrounded())
                .isEqualTo(1); // past the bound, so never seen
    }
}
