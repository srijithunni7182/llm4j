package io.github.llm4j.eval.guide;

import static io.github.llm4j.eval.judge.LlmJudgeCondition.llmJudged;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.LLMClient;
import io.github.llm4j.classify.Classifier;
import io.github.llm4j.classify.LlmClassifier;
import io.github.llm4j.eval.compare.ClassifierComparison;
import io.github.llm4j.eval.compare.ClassifierComparison.Case;
import io.github.llm4j.eval.testing.ScriptedClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The code samples in docs/LLM-AS-JUDGE.md ("Judge with a classifier"), compiled and run. Change a sample here and in the page together. */
class ClassifierJudgeDocsTest {

    private static final String YES = "{\"answers\": {\"criterion\": {\"answer\": \"yes\", \"confidence\": 0.9}}}";
    private static final String NO = "{\"answers\": {\"criterion\": {\"answer\": \"no\", \"confidence\": 0.9}}}";

    @Test
    void judgeWithAClassifier() {
        LLMClient cheapChatClient = new ScriptedClient().otherwise(YES);
        String result = "Refunds are accepted within 30 days of delivery.";
        // --- sample: judge
        Classifier judge = Classifier.using(cheapChatClient);   // any chat model; a Jev-backed classifier plugs in the same way

        assertThat(result).is(llmJudged("Correctness")
                .criteria("The answer states the refund window as 30 days")
                .classifier(judge)                              // instead of .judge(chatClient)
                .threshold(0.7)
                .build());
        // --- end sample
    }

    @Test
    void gradedAndWithAReason() {
        LLMClient cheapChatClient = new ScriptedClient().otherwise(
                "{\"answers\": {\"criterion\": {\"point\": \"mostly satisfies\", \"confidence\": 0.8, \"reason\": \"gives 30 days but no start date\"}}}");
        // --- sample: graded
        Classifier judge = LlmClassifier.builder(cheapChatClient).withRationale(true).build();   // a reason costs output tokens

        var verdict = llmJudged("Correctness")
                .criteria("The answer states the refund window and when it starts")
                .classifier(judge)
                .graded(true)                                    // a five-point scale instead of yes/no; the score is the position on it
                .build()
                .evaluate("Refunds are accepted within 30 days.");
        // verdict.score() is 0.75 here; verdict.reason() is "" unless the backend gave a rationale
        // --- end sample
        assertThat(verdict.score()).isEqualTo(0.75);
        assertThat(verdict.reason()).isEqualTo("gives 30 days but no start date");
    }

    @Test
    void compareClassifiersOnYourOwnCases() {
        LLMClient cheapChatClient = new ScriptedClient().whenSeen("30 days", YES).otherwise(NO);
        LLMClient strongChatClient = new ScriptedClient().whenSeen("30 days", YES).otherwise(NO);
        // --- sample: compare
        List<Case> cases = List.of(
                Case.yesNo("c1", "clear", "Refunds are accepted within 30 days.", "Does the answer state a 30 day refund window?", true),
                Case.yesNo("c2", "wrong number", "Refunds are accepted within 14 days.", "Does the answer state a 30 day refund window?", false));

        ClassifierComparison.Report report = ClassifierComparison.builder()
                .cases(cases)
                .contender("cheap", Classifier.using(cheapChatClient))
                .contender("strong", Classifier.using(strongChatClient))
                .price("cheap", 0.10, 0.50)                      // dollars per million input and output tokens; optional
                .repeats(3)                                      // ask each case three times to see how often the answer flips
                .build()
                .run();

        System.out.println(report.toMarkdown());                 // accuracy, calibration, latency, cost, agreement
        // --- end sample
        assertThat(report.rows()).hasSize(2);
        assertThat(report.rows().get(0).accuracy()).isEqualTo(1.0);
        assertThat(report.agreement()).containsEntry("cheap vs strong", 1.0);
        assertThat(report.flipRate()).containsEntry("cheap", 0.0);
    }

    @Test
    void thePageShowsTheSamples() throws Exception {
        String source = Files.readString(Path.of("src/test/java/io/github/llm4j/eval/guide/ClassifierJudgeDocsTest.java"));
        String page = Files.readString(Path.of("docs/LLM-AS-JUDGE.md"));
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("// --- sample: ([^\\n]+)\\n(.*?)\\n\\s*// --- end sample", java.util.regex.Pattern.DOTALL).matcher(source);
        int found = 0;
        while (m.find()) {
            found++;
            assertThat(page).as("the page shows the '" + m.group(1) + "' sample").contains(m.group(2).stripIndent().strip());
        }
        assertThat(found).isEqualTo(3);
    }
}
