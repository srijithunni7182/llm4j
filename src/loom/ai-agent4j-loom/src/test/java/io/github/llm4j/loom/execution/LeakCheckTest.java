package io.github.llm4j.loom.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Phase 26d, R9: the deterministic leak guard. */
class LeakCheckTest {

    static final String PROMPT =
            "You are a careful support assistant for Acme and you must always greet the customer by name before anything else";

    static LeakCheck guard(int words, Pattern... refs) {
        return new LeakCheck(List.of(PROMPT), List.of(refs), words, System::nanoTime);
    }

    static String run(int n) {
        String[] w = PROMPT.split(" ");
        return String.join(" ", java.util.Arrays.copyOfRange(w, 3, 3 + n));
    }

    @Test
    void elevenSharedWordsPassAndTwelveAreFound() {
        LeakCheck g = guard(12);
        assertThat(g.find("As I said, " + run(11) + " and that is all.")).isNull();
        var found = g.find("As I said, " + run(12) + " and that is all.");
        assertThat(found).isNotNull();
        assertThat(found.kind()).isEqualTo("repeated_instructions");
        assertThat(found.detail()).isEqualTo(12);
    }

    @Test
    void caseAndPunctuationAndSpacingAreFoldedAway() {
        String loud = run(12).toUpperCase().replace(" ", " \n  - ") + "!!!";
        assertThat(guard(12).find(loud)).isNotNull();
    }

    @Test
    void everyKindOfSourceCounts() {
        for (String source : new String[] {"The reply promises a refund a credit or any compensation to the customer in this particular case today"}) {
            LeakCheck g = new LeakCheck(List.of(source), List.of(), 12, System::nanoTime);
            assertThat(g.find("Sure: " + source.toLowerCase())).isNotNull();
        }
    }

    @Test
    void anAnswerShorterThanTheRunIsNeverFound() {
        assertThat(guard(12).find("you are a careful support assistant")).isNull();
        assertThat(guard(12).find("")).isNull();
        assertThat(guard(12).find(null)).isNull();
    }

    @Test
    void anInternalReferenceIsFoundAndANearMissIsNot() {
        LeakCheck g = guard(12, Pattern.compile("Section \\d+(\\.\\d+)*"), Pattern.compile("POL-\\d+"));
        assertThat(g.find("Per Section 4.6 we will help").kind()).isEqualTo("internal_reference");
        assertThat(g.find("see POL-17").detail()).isEqualTo(1);
        assertThat(g.find("Our sections are clear, see policy for details")).isNull();
    }

    @Test
    void aPatternThatCannotFinishCountsAsAMatchAndTheAnswerIsNotHungOn() {
        AtomicLong now = new AtomicLong();
        // each read of the clock moves it on 1 ms: a pattern that reads the answer 200 times is out of time
        LeakCheck g =
                new LeakCheck(List.of(), List.of(Pattern.compile("(a+)+$")), 12, () -> now.addAndGet(1_000_000));
        String answer = "a".repeat(40) + "!";
        assertThat(g.find(answer)).as("timed out, so treated as a match").isNotNull();
    }

    @Test
    void theFindingNeverCarriesTheText() {
        var found = guard(12).find(run(12));
        assertThat(found.toString()).doesNotContain("careful");
    }
}
