package io.github.llm4j.loom.review;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.review.engine.FindingSource;
import io.github.llm4j.loom.review.engine.ReviewEngine;
import io.github.llm4j.loom.review.engine.ReviewInput;
import io.github.llm4j.loom.review.engine.ReviewReport;
import io.github.llm4j.loom.review.model.Finding;
import io.github.llm4j.loom.review.model.Lens;
import io.github.llm4j.loom.review.model.Location;
import io.github.llm4j.loom.review.model.Note;
import io.github.llm4j.loom.review.model.Severity;
import io.github.llm4j.loom.review.settings.ReviewSettings;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * R22, R23 and the engine's duties in spec 09: collect, apply settings, de-duplicate, rank, isolate
 * failures.
 */
class ReviewEngineTest {

    static final ReviewInput INPUT =
            new ReviewInput("main.loom", Path.of("."), null, null, null, null);

    static Finding f(String rule, Lens lens, Severity sev, int line, String title) {
        return new Finding(
                rule,
                lens,
                sev,
                Location.at("main.loom", line),
                title,
                "why",
                "fix",
                List.of(),
                Optional.empty(),
                List.of(),
                "test");
    }

    static FindingSource source(String name, Finding... findings) {
        return new FindingSource() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public void collect(ReviewInput input, ReviewSettings settings, Sink sink) {
                for (Finding x : findings) sink.finding(x);
            }
        };
    }

    ReviewReport run(ReviewSettings settings, FindingSource... sources) {
        return new ReviewEngine(List.of(sources)).review(INPUT, settings);
    }

    @Test
    void findingsAreRankedBySeverityThenLensThenLineThenRule() {
        ReviewReport r =
                run(
                        ReviewSettings.defaults().withLenses("all"),
                        source(
                                "s",
                                f("LP01", Lens.PROMPTS, Severity.MEDIUM, 3, "a"),
                                f("LR01", Lens.RELIABILITY, Severity.MEDIUM, 9, "b"),
                                f("LA01", Lens.SECURITY, Severity.HIGH, 50, "c"),
                                f("LR02", Lens.RELIABILITY, Severity.MEDIUM, 2, "d"),
                                f("LT01", Lens.TESTABILITY, Severity.LOW, 1, "e")));
        assertThat(r.findings())
                .extracting(Finding::rule)
                .containsExactly("LA01", "LR02", "LR01", "LP01", "LT01");
    }

    @Test
    void theSameInputGivesTheSameListWhateverOrderSourcesReportIn() {
        List<Finding> in = new ArrayList<>();
        for (int i = 0; i < 20; i++)
            in.add(f("LR0" + (i % 5), Lens.RELIABILITY, Severity.values()[i % 4], i % 7, "t" + i));
        ReviewReport a = run(ReviewSettings.defaults(), source("s", in.toArray(new Finding[0])));
        java.util.Collections.reverse(in);
        ReviewReport b = run(ReviewSettings.defaults(), source("s", in.toArray(new Finding[0])));
        assertThat(a.findings()).isEqualTo(b.findings());
    }

    @Test
    void exactDuplicatesFromTwoSourcesAppearOnce() {
        Finding x = f("LR01", Lens.RELIABILITY, Severity.MEDIUM, 4, "same");
        ReviewReport r = run(ReviewSettings.defaults(), source("one", x), source("two", x));
        assertThat(r.findings()).hasSize(1);
    }

    @Test
    void findingsInALensThatIsOffAreLeftOutAndTheLensIsListedAsOff() {
        ReviewReport r =
                run(
                        ReviewSettings.defaults(),
                        source(
                                "s",
                                f("LG01", Lens.GOVERNANCE, Severity.LOW, 1, "g"),
                                f("LR01", Lens.RELIABILITY, Severity.LOW, 1, "r")));
        assertThat(r.findings()).extracting(Finding::rule).containsExactly("LR01");
        assertThat(r.lensesOff()).contains(Lens.GOVERNANCE);
        assertThat(r.lensesRun()).hasSize(9);
    }

    @Test
    void aRuleTurnedOffIsLeftOutAndASeveritySettingIsApplied() {
        ReviewSettings s = ReviewSettings.parse("p", "LR01 = off\nLR02.severity = high\n");
        ReviewReport r =
                run(
                        s,
                        source(
                                "x",
                                f("LR01", Lens.RELIABILITY, Severity.MEDIUM, 1, "off"),
                                f("LR02", Lens.RELIABILITY, Severity.LOW, 1, "louder")));
        assertThat(r.findings()).hasSize(1);
        assertThat(r.findings().get(0).severity()).isEqualTo(Severity.HIGH);
    }

    @Test
    void aSourceThatFailsBecomesANoteAndTheOthersStillRun() {
        FindingSource broken =
                new FindingSource() {
                    @Override
                    public String name() {
                        return "broken";
                    }

                    @Override
                    public void collect(ReviewInput input, ReviewSettings settings, Sink sink) {
                        throw new IllegalStateException("boom");
                    }
                };
        ReviewReport r =
                run(
                        ReviewSettings.defaults(),
                        broken,
                        source("ok", f("LR01", Lens.RELIABILITY, Severity.LOW, 1, "t")));
        assertThat(r.findings()).hasSize(1);
        assertThat(r.notes()).hasSize(1);
        assertThat(r.notes().get(0).kind()).isEqualTo(Note.Kind.SOURCE_FAILED);
        assertThat(r.notes().get(0).message()).contains("broken").contains("boom");
    }

    @Test
    void notesFromSourcesAreKept() {
        FindingSource noting =
                new FindingSource() {
                    @Override
                    public String name() {
                        return "n";
                    }

                    @Override
                    public void collect(ReviewInput input, ReviewSettings settings, Sink sink) {
                        sink.note(Note.notEvaluated("LY03", "data.regions is not set"));
                    }
                };
        assertThat(run(ReviewSettings.defaults(), noting).notes())
                .extracting(Note::rule)
                .containsExactly("LY03");
    }

    @Test
    void countsAndTheFailThresholdUseOpenFindings() {
        ReviewReport r =
                run(
                        ReviewSettings.defaults(),
                        source(
                                "s",
                                f("A", Lens.SECURITY, Severity.HIGH, 1, "a"),
                                f("B", Lens.SECURITY, Severity.LOW, 1, "b")));
        assertThat(r.count(Severity.HIGH)).isEqualTo(1);
        assertThat(r.failsAt(Severity.HIGH)).isTrue();
        assertThat(run(ReviewSettings.defaults()).failsAt(Severity.INFO)).isFalse();
    }

    @Test
    void theMoreSpecificRuleAbsorbsAGeneralOneAboutTheSameCause() {
        Finding specific =
                f("LR07", Lens.RELIABILITY, Severity.MEDIUM, 9, "tool call has no timeout");
        Finding general = f("LA17", Lens.SECURITY, Severity.LOW, 9, "tool call is unbounded");
        Finding elsewhere =
                f("LA17", Lens.SECURITY, Severity.LOW, 20, "other tool call is unbounded");
        ReviewReport r =
                new ReviewEngine(
                                List.of(source("s", general, specific, elsewhere)),
                                List.of(
                                        new io.github.llm4j.loom.review.engine.Overlap(
                                                "LR07", "LA17")))
                        .review(INPUT, ReviewSettings.defaults());
        assertThat(r.findings()).extracting(Finding::rule).containsExactly("LR07", "LA17");
        assertThat(r.findings().get(0).related()).containsExactly("LA17");
        assertThat(r.findings().get(1).where().line()).isEqualTo(20);
    }

    @Test
    void overlapsFoldByGraphNodeToo() {
        Finding a = f("LR07", Lens.RELIABILITY, Severity.MEDIUM, 0, "x");
        Finding b = f("LA17", Lens.SECURITY, Severity.LOW, 0, "y");
        a =
                new Finding(
                        a.rule(),
                        a.lens(),
                        a.severity(),
                        a.where().withNode("n2"),
                        a.title(),
                        "w",
                        "f",
                        List.of(),
                        Optional.empty(),
                        List.of(),
                        "t");
        b =
                new Finding(
                        b.rule(),
                        b.lens(),
                        b.severity(),
                        b.where().withNode("n2"),
                        b.title(),
                        "w",
                        "f",
                        List.of(),
                        Optional.empty(),
                        List.of(),
                        "t");
        ReviewReport r =
                new ReviewEngine(
                                List.of(source("s", a, b)),
                                List.of(
                                        new io.github.llm4j.loom.review.engine.Overlap(
                                                "LR07", "LA17")))
                        .review(INPUT, ReviewSettings.defaults());
        assertThat(r.findings()).hasSize(1);
    }

    @Test
    void withoutBothRulesNothingIsFolded() {
        Finding only = f("LA17", Lens.SECURITY, Severity.LOW, 9, "y");
        ReviewReport r =
                new ReviewEngine(
                                List.of(source("s", only)),
                                List.of(
                                        new io.github.llm4j.loom.review.engine.Overlap(
                                                "LR07", "LA17")))
                        .review(INPUT, ReviewSettings.defaults());
        assertThat(r.findings()).containsExactly(only);
    }
}
