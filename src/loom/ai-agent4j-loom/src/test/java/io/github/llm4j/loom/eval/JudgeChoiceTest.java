package io.github.llm4j.loom.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R12 of docs/specs/28-evaluation-first: the doer does not grade itself unless it is told to. */
class JudgeChoiceTest {

    @TempDir Path dir;

    private static final List<String> ALL = List.of("gemini-2.5-pro", "claude-haiku-5-5", "gemini-2.5-flash");

    private static JudgeChoice choose(String flag, String dataset, String project, String judged, List<String> candidates) {
        return JudgeChoice.choose(flag, dataset, project, judged, candidates, m -> true);
    }

    @Test
    void theFlagComesFirst() {
        var c = choose("claude-sonnet-5-5", "gemini-2.5-flash", "claude-haiku-5-5", "gemini-2.5-pro", ALL);
        assertThat(c.model()).isEqualTo("claude-sonnet-5-5");
        assertThat(c.source()).contains("--judge");
        assertThat(c.selfJudging()).isFalse();
    }

    @Test
    void thenTheDatasetThenTheProject() {
        assertThat(choose(null, "gemini-2.5-flash", "claude-haiku-5-5", "gemini-2.5-pro", ALL).source()).contains("dataset.yaml");
        var project = choose(null, null, "claude-haiku-5-5", "gemini-2.5-pro", ALL);
        assertThat(project.model()).isEqualTo("claude-haiku-5-5");
        assertThat(project.source()).contains("eval.properties");
    }

    @Test
    void otherwiseItIsTheCheapestModelThatIsNotTheOneBeingJudgedPreferringAnotherProvider() {
        var c = choose(null, null, null, "gemini-2.5-pro", ALL);
        assertThat(c.model()).isEqualTo("claude-haiku-5-5");
        assertThat(c.source()).contains("another provider");
        assertThat(c.selfJudging()).isFalse();
    }

    @Test
    void withNoOtherProviderTheCheapestOtherModelOfTheSameOneIsUsed() {
        var c = choose(null, null, null, "gemini-2.5-pro", List.of("gemini-2.5-pro", "gemini-2.5-flash"));
        assertThat(c.model()).isEqualTo("gemini-2.5-flash");
        assertThat(c.selfJudging()).isFalse();
    }

    @Test
    void geminiIsNotMiniAndProIsNotCheap() {
        assertThat(JudgeChoice.cost("gemini-2.5-pro")).isGreaterThan(JudgeChoice.cost("gemini-2.5-flash"));
        assertThat(JudgeChoice.cost("openai/gpt-5-mini")).isEqualTo(JudgeChoice.cost("claude-haiku-5-5"));
        assertThat(JudgeChoice.cost("some-nano-thing")).isLessThan(JudgeChoice.cost("claude-haiku-5-5"));
        assertThat(JudgeChoice.cost("claude-opus-5")).isEqualTo(JudgeChoice.cost("gemini-2.5-pro"));
        assertThat(JudgeChoice.cost("plain")).isEqualTo(2);
    }

    @Test
    void providersComeFromTheSlashOrTheLeadingLetters() {
        assertThat(JudgeChoice.provider("openai/gpt-5")).isEqualTo("openai");
        assertThat(JudgeChoice.provider("claude-haiku-5-5")).isEqualTo("claude");
        assertThat(JudgeChoice.provider("gemini-2.5-pro")).isEqualTo("gemini");
    }

    @Test
    void aModelWithNoKeyIsNotReachable() {
        var c = JudgeChoice.choose(null, null, null, "gemini-2.5-pro", ALL, m -> !m.startsWith("claude"));
        assertThat(c.model()).isEqualTo("gemini-2.5-flash");
    }

    @Test
    void sameIsDeliberateAndCautioned() {
        var c = choose("same", null, null, "gemini-2.5-pro", ALL);
        assertThat(c.model()).isEqualTo("gemini-2.5-pro");
        assertThat(c.selfJudging()).isTrue();
        assertThat(c.caution()).contains("self-judging").contains("biased");
    }

    @Test
    void whenOnlyOneModelIsReachableItJudgesItselfAndSaysSoAndNeverRefuses() {
        var c = choose(null, null, null, "gemini-2.5-pro", List.of("gemini-2.5-pro"));
        assertThat(c.model()).isEqualTo("gemini-2.5-pro");
        assertThat(c.selfJudging()).isTrue();
        assertThat(c.caution()).contains("only one model");
    }

    @Test
    void anExplicitChoiceThatIsTheJudgedModelIsStillSelfJudgingAndSaysSo() {
        var c = choose("gemini-2.5-pro", null, null, "gemini-2.5-pro", ALL);
        assertThat(c.selfJudging()).isTrue();
        assertThat(c.caution()).contains("self-judging");
    }

    @Test
    void theChoiceAndWhyAreOneReadableLine() {
        var c = choose(null, null, null, "gemini-2.5-pro", ALL);
        assertThat(c.describe("gemini-2.5-pro")).contains("claude-haiku-5-5").contains("gemini-2.5-pro").contains("another provider");
    }

    @Test
    void aMissingJudgedModelFallsBackToTheCheapestReachable() {
        var c = choose(null, null, null, null, ALL);
        assertThat(c.model()).isEqualTo("claude-haiku-5-5"); // cheapest; the first named wins a tie
    }

    // ── reading the settings ─────────────────────────────────────────────────────────────────

    @Test
    void theDatasetAndTheProjectSettingsAreReadFromTheirFiles() throws Exception {
        Path golden = Files.createDirectories(dir.resolve("eval/golden"));
        Files.writeString(golden.resolve("dataset.yaml"), "judge: \"gemini-2.5-flash\"\ndimensions:\n  a: b\n");
        Files.writeString(dir.resolve("eval.properties"), "judge = claude-haiku-5-5\n");
        assertThat(JudgeChoice.datasetJudge(golden)).isEqualTo("gemini-2.5-flash");
        assertThat(JudgeChoice.projectJudge(dir)).isEqualTo("claude-haiku-5-5");
    }

    @Test
    void noFileMeansNoSetting() {
        assertThat(JudgeChoice.datasetJudge(dir.resolve("nope"))).isNull();
        assertThat(JudgeChoice.projectJudge(dir)).isNull();
    }
}
