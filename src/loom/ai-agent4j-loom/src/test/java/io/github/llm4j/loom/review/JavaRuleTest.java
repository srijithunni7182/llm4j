package io.github.llm4j.loom.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.review.engine.ReviewEngine;
import io.github.llm4j.loom.review.engine.ReviewReport;
import io.github.llm4j.loom.review.engine.ReviewRule;
import io.github.llm4j.loom.review.engine.RuleInfo;
import io.github.llm4j.loom.review.model.Note;
import io.github.llm4j.loom.review.pack.JavaRules;
import io.github.llm4j.loom.review.pack.PackException;
import io.github.llm4j.loom.review.pack.PackSet;
import io.github.llm4j.loom.review.settings.ReviewSettings;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R21, R73 of spec 09: Java rules from a named jar work beside data rules. */
class JavaRuleTest {

    @TempDir Path dir;

    static final String SCRIPT =
            "agent Intern { model: \"m\"  system: \"s\" }\nworkflow Main(q) { delegate \"{q}\" to Intern -> r  note \"{r}\" }\n";

    Path jar(boolean withServiceFile) throws Exception {
        Path jar = dir.resolve("org-rules.jar");
        String cls = JavaRuleFixture.class.getName().replace('.', '/') + ".class";
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar));
                InputStream in = JavaRuleFixture.class.getClassLoader().getResourceAsStream(cls)) {
            out.putNextEntry(new JarEntry(cls));
            out.write(in.readAllBytes());
            out.closeEntry();
            if (withServiceFile) {
                out.putNextEntry(new JarEntry("META-INF/services/" + ReviewRule.class.getName()));
                out.write((JavaRuleFixture.class.getName() + "\n").getBytes());
                out.closeEntry();
            }
        }
        return jar;
    }

    @Test
    void aNamedJarProvidesItsRulesThroughServiceLoader() throws Exception {
        List<ReviewRule> rules = JavaRules.fromJars(List.of(jar(true)));
        assertThat(rules).hasSize(1);
        assertThat(rules.get(0).rules()).extracting(RuleInfo::id).containsExactly("ORG-001");
    }

    @Test
    void aJavaRuleRunsBesideTheCoreRulesAndItsFindingNamesItsSource() throws Exception {
        ReviewReport r =
                Reviews.engine(
                                PackSet.coreOnly(Reviews.corePack()),
                                JavaRules.fromJars(List.of(jar(true))))
                        .review(
                                ReviewFixtures.input(dir, SCRIPT),
                                ReviewSettings.defaults().withLenses("all"));
        var f =
                r.findings().stream()
                        .filter(x -> x.rule().equals("ORG-001"))
                        .findFirst()
                        .orElseThrow();
        assertThat(f.source()).isEqualTo("java org-rules");
        assertThat(r.findings()).extracting(x -> x.rule()).contains("ORG-001", "LA03");
    }

    @Test
    void theCatalogExplainsAJavaRuleLikeAnyOther() throws Exception {
        var catalog =
                Reviews.catalog(
                        PackSet.coreOnly(Reviews.corePack()),
                        JavaRules.fromJars(List.of(jar(true))));
        assertThat(catalog.find("ORG-001")).isPresent();
    }

    @Test
    void aJarWithNoServiceEntryAndAMissingJarAreErrorsThatSayWhat() throws Exception {
        assertThatThrownBy(() -> JavaRules.fromJars(List.of(jar(false))))
                .isInstanceOf(PackException.class)
                .hasMessageContaining("provides no rules")
                .hasMessageContaining("META-INF/services");
        assertThatThrownBy(() -> JavaRules.fromJars(List.of(dir.resolve("nope.jar"))))
                .hasMessageContaining("does not exist");
    }

    @Test
    void aJavaRuleThatThrowsBecomesANoteAndTheOtherRulesStillRun() throws Exception {
        ReviewRule boom =
                new ReviewRule() {
                    @Override
                    public String name() {
                        return "boom-rules";
                    }

                    @Override
                    public List<RuleInfo> rules() {
                        return List.of();
                    }

                    @Override
                    public void collect(
                            io.github.llm4j.loom.review.engine.ReviewInput input,
                            ReviewSettings settings,
                            Sink sink) {
                        throw new IllegalStateException("bug in the rule");
                    }
                };
        ReviewReport r =
                Reviews.engine(
                                PackSet.coreOnly(Reviews.corePack()),
                                List.of(boom, new JavaRuleFixture()))
                        .review(
                                ReviewFixtures.input(dir, SCRIPT),
                                ReviewSettings.defaults().withLenses("all"));
        assertThat(r.notes())
                .anyMatch(
                        n ->
                                n.kind() == Note.Kind.SOURCE_FAILED
                                        && n.message()
                                                .contains(
                                                        "boom-rules could not run: bug in the rule"));
        assertThat(r.findings()).extracting(x -> x.rule()).contains("ORG-001");
    }

    @Test
    void theEngineGivesTheSameFindingsWhateverTheClockOrEnvironment() throws Exception {
        var input = ReviewFixtures.input(dir, SCRIPT);
        ReviewEngine engine = Reviews.engine(PackSet.coreOnly(Reviews.corePack()));
        String first =
                io.github.llm4j.loom.review.report.JsonReport.write(
                        engine.review(input, ReviewSettings.defaults()));
        Thread.sleep(5);
        String second =
                io.github.llm4j.loom.review.report.JsonReport.write(
                        engine.review(input, ReviewSettings.defaults()));
        assertThat(second).isEqualTo(first);
    }
}
