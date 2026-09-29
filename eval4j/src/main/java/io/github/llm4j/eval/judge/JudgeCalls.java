package io.github.llm4j.eval.judge;

import io.github.llm4j.LLMClient;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared machinery for the multi-call metrics (RAG chunk judging, conversation turns, pairwise
 * comparison, dataset synthesis): runs rubric-rated judge calls and raw judge/generator calls with
 * the same caching, self-consistency sampling and untrusted-data handling as {@link
 * LlmJudgeCondition}. Instances are immutable apart from a call counter and safe to share across
 * threads.
 */
public final class JudgeCalls {

    private final LLMClient client;
    private final JudgeCache cache;
    private final int samples;
    private final String judgeIdentifier;
    private final AtomicInteger llmCallCount;

    private JudgeCalls(
            LLMClient client,
            JudgeCache cache,
            int samples,
            String judgeIdentifier,
            AtomicInteger llmCallCount) {
        this.client = client;
        this.cache = cache;
        this.samples = samples;
        this.judgeIdentifier = judgeIdentifier;
        this.llmCallCount = llmCallCount;
    }

    public static JudgeCalls using(LLMClient client) {
        return new JudgeCalls(
                Objects.requireNonNull(client, "judge cannot be null"),
                null,
                1,
                null,
                new AtomicInteger());
    }

    public JudgeCalls cache(JudgeCache cache) {
        return new JudgeCalls(client, cache, samples, judgeIdentifier, llmCallCount);
    }

    public JudgeCalls samples(int samples) {
        if (samples < 1) {
            throw new IllegalArgumentException("samples must be at least 1, got: " + samples);
        }
        return new JudgeCalls(client, cache, samples, judgeIdentifier, llmCallCount);
    }

    public JudgeCalls judgeIdentifier(String judgeIdentifier) {
        return new JudgeCalls(client, cache, samples, judgeIdentifier, llmCallCount);
    }

    public String judgeIdentifier() {
        return judgeIdentifier;
    }

    /** Number of real (non-cached) LLM calls made through this instance and its copies. */
    public int llmCallCount() {
        return llmCallCount.get();
    }

    /**
     * One rubric-rated judgment (1-5 → 0.0-1.0), averaged over {@code samples} draws.
     *
     * @param subject discriminates otherwise-identical sub-calls (e.g. {@code "chunk:3"}) in the
     *     cache key
     * @param sections named data sections shown to the judge, each wrapped in BEGIN/END delimiters
     */
    public JudgeVerdict rate(
            String metric, String criteria, String subject, Map<String, String> sections) {
        double temperature = samples > 1 ? LlmJudgeCondition.MULTI_SAMPLE_TEMPERATURE : 0.0;
        String userMessage = JudgePrompt.buildSectionsMessage(metric, criteria, sections);
        String baseKey =
                cache == null
                        ? null
                        : hash(
                                JudgePrompt.SYSTEM_PROMPT,
                                metric,
                                criteria,
                                subject,
                                userMessage,
                                String.valueOf(temperature),
                                judgeIdentifier);
        List<JudgeVerdict> verdicts = new ArrayList<>(samples);
        for (int i = 0; i < samples; i++) {
            String key = baseKey == null ? null : baseKey + "#" + i;
            JudgeVerdict verdict = key == null ? null : cache.get(key).orElse(null);
            if (verdict == null) {
                String content = callLlm(JudgePrompt.SYSTEM_PROMPT, userMessage, temperature, metric);
                verdict = JudgeResponseParser.parse(content);
                if (key != null) {
                    cache.put(key, verdict);
                }
            }
            verdicts.add(verdict);
        }
        return combine(verdicts);
    }

    /**
     * One raw call returning the model's text (single draw, cached). The user message must already
     * have been built from sanitized sections — see {@link #sanitize(String)}.
     */
    public String ask(
            String purpose, String subject, String system, String user, double temperature) {
        String key =
                cache == null
                        ? null
                        : hash(
                                "raw",
                                system,
                                purpose,
                                subject,
                                user,
                                String.valueOf(temperature),
                                judgeIdentifier);
        if (key != null) {
            JudgeVerdict hit = cache.get(key).orElse(null);
            if (hit != null) {
                return hit.reason();
            }
        }
        String content = callLlm(system, user, temperature, purpose);
        if (key != null) {
            cache.put(key, new JudgeVerdict(0.0, content));
        }
        return content;
    }

    /** Wraps text as a delimited data block and neutralizes forged delimiters inside it. */
    public static String delimited(String label, String content) {
        return "<<<BEGIN " + label + ">>>\n" + sanitize(content) + "\n<<<END " + label + ">>>\n\n";
    }

    public static String sanitize(String text) {
        return JudgePrompt.sanitize(text);
    }

    /** The shared judge system prompt (data-only notice + 1-5 rubric). */
    public static String judgeSystemPrompt() {
        return JudgePrompt.SYSTEM_PROMPT;
    }

    private String callLlm(String system, String user, double temperature, String what) {
        LLMRequest request =
                LLMRequest.builder()
                        .addSystemMessage(system)
                        .addUserMessage(user)
                        .temperature(temperature)
                        .build();
        try {
            llmCallCount.incrementAndGet();
            LLMResponse response = client.chat(request);
            return response.getContent();
        } catch (JudgeEvaluationException e) {
            throw e;
        } catch (Exception e) {
            throw new JudgeEvaluationException("Judge call failed for \"" + what + "\"", e);
        }
    }

    private static JudgeVerdict combine(List<JudgeVerdict> verdicts) {
        if (verdicts.size() == 1) {
            return verdicts.get(0);
        }
        double avg = verdicts.stream().mapToDouble(JudgeVerdict::score).average().orElseThrow();
        StringBuilder reasons = new StringBuilder();
        for (int i = 0; i < verdicts.size(); i++) {
            reasons.append('(').append(i + 1).append(") ").append(verdicts.get(i).reason());
            if (i < verdicts.size() - 1) {
                reasons.append(' ');
            }
        }
        return new JudgeVerdict(
                avg, "Averaged over " + verdicts.size() + " samples. " + reasons);
    }

    static String hash(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                digest.update((part == null ? "" : part).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 1);
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) {
                hex.append(String.format(java.util.Locale.ROOT, "%02x", b));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new JudgeEvaluationException("SHA-256 not available", e);
        }
    }
}
