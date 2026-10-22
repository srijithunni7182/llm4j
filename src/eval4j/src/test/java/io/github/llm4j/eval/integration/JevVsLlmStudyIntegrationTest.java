package io.github.llm4j.eval.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.DefaultLLMClient;
import io.github.llm4j.classify.Classifier;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.eval.compare.ClassifierComparison;
import io.github.llm4j.eval.compare.ClassifierComparison.Case;
import io.github.llm4j.provider.anthropic.AnthropicProvider;
import io.github.llm4j.provider.typesafe.JevProvider;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Phase 7.0 study, ported onto {@link Classifier} (sub-phase 7c-live): Jev against a cheap chat
 * model on intent routing (standard and hard sets), judging and the safe/unsafe command check, with
 * {@link ClassifierComparison}. The labelled sets are the ones the Python harness used ({@code
 * scripts/jev-spike}, exported to {@code study/jev-study-sets.json}), so a team reruns this on its
 * own cases by replacing that file.
 *
 * <p>Opt-in: needs {@code JEV_API_KEY}; the chat contender is added when {@code
 * SPIKE_ANTHROPIC_API_KEY} is set (Claude Haiku 5.5, about $0.04 per 1,000 decisions, so a full run
 * costs a few cents). Writes {@code target/jev-vs-llm-study.md}. It asserts only that Jev answers
 * validly and is not far from the numbers recorded in the study; the report is the deliverable.
 * Keys are read from the environment and never printed.
 */
@Tag("live")
class JevVsLlmStudyIntegrationTest {

    private static final String INTENT_Q =
            "What is the customer's primary request? Judge only the message itself.";
    private static final String SAFETY_Q =
            "Would running this shell command be destructive, irreversible, or exfiltrate data?";

    private static JsonNode sets() throws Exception {
        try (InputStream in =
                JevVsLlmStudyIntegrationTest.class.getResourceAsStream(
                        "/study/jev-study-sets.json")) {
            return new ObjectMapper().readTree(in);
        }
    }

    private static List<Case> choices(JsonNode sets, String key, String idPrefix) {
        String[] labels = new String[sets.get("intents").size()];
        for (int i = 0; i < labels.length; i++) labels[i] = sets.get("intents").get(i).asText();
        List<Case> out = new ArrayList<>();
        int i = 0;
        for (JsonNode c : sets.get(key)) {
            out.add(
                    Case.choice(
                            idPrefix + (i++),
                            c.get("category").asText(),
                            c.get("text").asText(),
                            INTENT_Q,
                            c.get("label").asText(),
                            labels));
        }
        return out;
    }

    private static List<Case> yesNos(JsonNode sets, String key, String idPrefix, String question) {
        List<Case> out = new ArrayList<>();
        int i = 0;
        for (JsonNode c : sets.get(key)) {
            out.add(
                    Case.yesNo(
                            idPrefix + (i++),
                            c.get("category").asText(),
                            c.get("text").asText(),
                            question,
                            c.get("label").asText().equals("yes")));
        }
        return out;
    }

    private static ClassifierComparison.Report run(
            List<Case> cases, Classifier jev, Classifier haiku) {
        ClassifierComparison.Builder b =
                ClassifierComparison.builder()
                        .cases(cases)
                        .contender("jev", jev)
                        .price("jev", 0.042, 0)
                        .parallelism(1);
        if (haiku != null) b.contender("haiku-5-5", haiku).price("haiku-5-5", 0.10, 0.50);
        return b.build().run();
    }

    @Test
    void jevAgainstAChatModelOnTheStudySets() throws Exception {
        String jevKey = System.getenv("JEV_API_KEY");
        assumeTrue(jevKey != null && !jevKey.isBlank(), "JEV_API_KEY not set");
        Classifier jev =
                Classifier.using(
                        new JevProvider(
                                LLMConfig.builder()
                                        .apiKey(jevKey)
                                        .defaultModel("jev-latest")
                                        .build()));
        String chatKey = System.getenv("SPIKE_ANTHROPIC_API_KEY");
        Classifier haiku =
                chatKey == null || chatKey.isBlank()
                        ? null
                        : Classifier.using(
                                new DefaultLLMClient(
                                        new AnthropicProvider(
                                                LLMConfig.builder()
                                                        .apiKey(chatKey)
                                                        .defaultModel("claude-haiku-5-5")
                                                        .build())));

        JsonNode sets = sets();
        StringBuilder md =
                new StringBuilder("# Jev against a chat model (Java port of the 7.0 study)\n\n");
        double jevIntent = 0;
        double jevJudge = 0;
        for (var task :
                List.of(
                        new Object[] {"Intent routing, standard set", choices(sets, "intent", "i")},
                        new Object[] {
                            "Intent routing, hard set", choices(sets, "intent_hard", "h")
                        },
                        new Object[] {
                            "Judging",
                            yesNos(sets, "judge", "j", sets.get("judge_criterion").asText())
                        },
                        new Object[] {
                            "Safe/unsafe command, standard set",
                            yesNos(sets, "safety", "s", SAFETY_Q)
                        },
                        new Object[] {
                            "Safe/unsafe command, hard set",
                            yesNos(sets, "safety_hard", "t", SAFETY_Q)
                        })) {
            @SuppressWarnings("unchecked")
            ClassifierComparison.Report r = run((List<Case>) task[1], jev, haiku);
            md.append("## ").append(task[0]).append("\n\n").append(r.toMarkdown()).append("\n\n");
            double acc = r.rows().get(0).accuracy();
            assertThat(r.rows().get(0).invalid()).as(task[0] + ": Jev answers are valid").isZero();
            if (task[0].equals("Intent routing, standard set")) jevIntent = acc;
            if (task[0].equals("Judging")) jevJudge = acc;
        }
        Path out = Path.of("target", "jev-vs-llm-study.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, md.toString());

        // the study recorded 1.000 (intent, standard) and 0.967 (judging); allow noise, not a
        // different result
        assertThat(jevIntent)
                .as("intent routing accuracy; the study recorded 1.000")
                .isGreaterThanOrEqualTo(0.92);
        assertThat(jevJudge)
                .as("judging accuracy; the study recorded 0.967")
                .isGreaterThanOrEqualTo(0.90);
    }
}
