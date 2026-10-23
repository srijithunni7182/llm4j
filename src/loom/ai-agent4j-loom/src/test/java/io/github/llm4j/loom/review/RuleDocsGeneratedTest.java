package io.github.llm4j.loom.review;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.review.tools.RuleDocs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * R69 of spec 09: the rule reference is generated from the rules, and the committed pages are
 * exactly what the generator writes, so they cannot drift. Run with {@code
 * -Dregenerate.rule.docs=true} to rewrite them after changing a rule.
 */
class RuleDocsGeneratedTest {

    static final Path PAGES = Path.of("../../../docs/review/rules");

    Map<String, String> generated() {
        return RuleDocs.generate(Reviews.corePack(), Reviews.catalog());
    }

    @Test
    void everyRuleHasAPageAndTheIndexLinksToIt() {
        Map<String, String> pages = generated();
        for (var info : Reviews.catalog().all()) {
            String file = info.id().toLowerCase() + ".md";
            assertThat(pages).as(info.id()).containsKey(file);
            assertThat(pages.get("index.md")).contains("[" + info.id() + "](" + file + ")");
        }
    }

    @Test
    void everyRuleHasAnExplanationAndATitle() {
        for (var info : Reviews.catalog().all()) {
            assertThat(info.title()).as(info.id() + " title").isNotBlank();
            assertThat(info.explain()).as(info.id() + " explanation").isNotBlank();
            assertThat(info.lens()).as(info.id()).isNotNull();
        }
    }

    @Test
    void aPackRulePageSaysWhenItFiresAndWhatItOffers() {
        String lr01 = generated().get("lr01.md");
        assertThat(lr01)
                .startsWith("# LR01: `label` has a tool")
                .contains("## When it fires")
                .contains("kind is delegate")
                .contains("## Options")
                .contains("**timeout-only**")
                .contains("--accept FINGERPRINT");
        assertThat(generated().get("lg02.md")).contains("data.deprecated-models");
        assertThat(generated().get("lh03.md")).contains("Human oversight");
    }

    @Test
    void anOptionalLensIsMarkedOffInTheIndex() {
        assertThat(generated().get("index.md")).contains("## Model governance *(off)*");
        assertThat(generated().get("index.md")).doesNotContain("## Reliability *(off)*");
    }

    @Test
    void theCommittedPagesAreWhatTheGeneratorWrites() throws IOException {
        Map<String, String> pages = generated();
        if (Boolean.getBoolean("regenerate.rule.docs")) {
            Files.createDirectories(PAGES);
            for (var e : pages.entrySet()) Files.writeString(PAGES.resolve(e.getKey()), e.getValue());
        }
        assertThat(PAGES).as("run once with -Dregenerate.rule.docs=true").isDirectory();
        for (var e : pages.entrySet())
            assertThat(Files.readString(PAGES.resolve(e.getKey())))
                    .as(e.getKey())
                    .isEqualTo(e.getValue());
        try (var files = Files.list(PAGES)) {
            assertThat(files.map(p -> p.getFileName().toString()).sorted().toList())
                    .as("no page without a rule")
                    .containsExactlyElementsOf(pages.keySet().stream().sorted().toList());
        }
    }
}
