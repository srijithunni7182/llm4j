package io.github.llm4j.loom.review;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.review.pack.Pack;
import io.github.llm4j.loom.review.pack.PackTester;
import io.github.llm4j.loom.review.rule.RuleDoc;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * R57, R68 of spec 09: the bundled pack loads, and every rule in it passes the examples it carries.
 */
class CorePackTest {

    @Test
    void theBundledPackLoadsAndIsMarkedWithItsVersion() {
        Pack core = Reviews.corePack();
        assertThat(core.manifest().core()).isTrue();
        assertThat(core.label()).isEqualTo("core@1.5.0");
        assertThat(core.rules())
                .extracting(RuleDoc::id)
                .contains("LR01", "LR03", "LD02", "LY02", "LM01", "LG04");
        assertThat(core.rules()).allMatch(r -> r.pack().equals("core@1.5.0"));
    }

    @Test
    void everyRuleOfTheCorePackPassesItsOwnExamples() {
        List<PackTester.Result> results = PackTester.run(Reviews.corePack());
        assertThat(results).isNotEmpty();
        for (PackTester.Result r : results)
            assertThat(r.passed())
                    .as("%s %s %s: %s", r.rule(), r.kind(), r.example(), r.message())
                    .isTrue();
    }

    @Test
    void everyRuleCarriesAtLeastOneFiringAndOneQuietExample() {
        for (RuleDoc r : Reviews.corePack().rules()) {
            assertThat(r.fires()).as(r.id() + " fires").isNotEmpty();
            assertThat(r.quiet()).as(r.id() + " quiet").isNotEmpty();
        }
    }

    @Test
    void ruleIdsAreUniqueAndNeverUseTheJavaOnlyPrefixes() {
        List<String> ids = Reviews.corePack().rules().stream().map(RuleDoc::id).toList();
        assertThat(ids).doesNotHaveDuplicates();
        assertThat(ids).noneMatch(i -> i.startsWith("LA") || i.startsWith("LV"));
    }

    @Test
    void everyRuleAnswersWhyAndWhatToDoAndOptionalLensesAreMarked() {
        for (RuleDoc r : Reviews.corePack().rules()) {
            assertThat(r.title()).isNotBlank();
            assertThat(r.fixHint()).isNotBlank();
            assertThat(r.why()).as(r.id() + " says why").isNotBlank();
        }
        assertThat(
                        Reviews.corePack().rules().stream()
                                .filter(r -> r.lens().optional())
                                .map(RuleDoc::id))
                .contains("LM01", "LG04");
    }
}
