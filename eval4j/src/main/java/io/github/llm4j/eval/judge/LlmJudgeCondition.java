package io.github.llm4j.eval.judge;

import io.github.llm4j.LLMClient;
import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;
import org.assertj.core.api.Condition;
import org.assertj.core.description.Description;
import org.assertj.core.description.TextDescription;

/**
 * An AssertJ {@link Condition} that asks an LLM to grade something against a natural-language
 * criterion, rather than checking it deterministically. Build one with {@link #llmJudged(String)}
 * and use it anywhere AssertJ accepts a {@code Condition}, e.g. {@code
 * assertThat(result).is(llmJudged("Correctness").criteria(...).judge(judgeClient).threshold(0.7))}.
 *
 * <p>This condition is deliberately typed as {@code Condition<Object>} rather than {@code
 * Condition<AgentResult>}: AssertJ's {@code is(Condition<? super ACTUAL>)} accepts it against any
 * actual type as a result, so the same condition works against an {@link AgentResult}, a raw {@link
 * LLMResponse}, or a plain {@link String} without forcing a generic type witness at every call
 * site. {@link #matches(Object)} resolves which of those it received.
 *
 * <p>By default this makes one judge call at temperature 0 (deterministic). Setting {@link
 * Builder#samples(int)} above 1 switches to temperature {@value #MULTI_SAMPLE_TEMPERATURE} and
 * averages that many independent judge calls (self-consistency), trading judge-call cost for a less
 * noise-sensitive score. Setting {@link Builder#cache(JudgeCache)} avoids repeating identical judge
 * calls — each of the {@code samples} draws is cached under its own key, so a rerun with an
 * unchanged input replays the same draws instead of re-spending judge calls, while a genuinely new
 * input still gets fresh ones.
 *
 * <p><b>Thread-safety of a shared/reused instance:</b> the failure-message description is stored
 * per-thread, so the same built condition can safely be reused across parallel-running tests
 * without one test's failure message showing another's score/reason. It is not, however, safe to
 * call {@link #matches(Object)} concurrently from the *same* thread expecting independent verdicts
 * — one thread only ever sees its own most recent call.
 */
public final class LlmJudgeCondition extends Condition<Object> {

    /** Temperature used when {@code samples == 1}, favoring a single reproducible judge call. */
    static final double SINGLE_SAMPLE_TEMPERATURE = 0.0;

    /**
     * Temperature used when {@code samples > 1}: self-consistency only reduces noise if the sampled
     * judge calls can actually disagree with each other, which a temperature-0 judge call mostly
     * won't.
     */
    static final double MULTI_SAMPLE_TEMPERATURE = 0.7;

    private final String name;
    private final String criteria;
    private final LLMClient judge;
    private final double threshold;
    private final String input;
    private final String expectedOutput;
    private final List<String> context;
    private final List<String> retrievalContext;
    private final JudgeCache cache;
    private final int samples;
    private final boolean includeTrajectory;
    private final String judgeIdentifier;

    /**
     * Per-thread override of the failure description, so a shared/reused condition instance is safe
     * under parallel test execution — see the class Javadoc.
     */
    private final ThreadLocal<Description> perThreadDescription = new ThreadLocal<>();

    private LlmJudgeCondition(Builder builder) {
        super("llm-judged \"" + builder.name + "\" (threshold=" + builder.threshold + ")");
        this.name = builder.name;
        this.criteria = Objects.requireNonNull(builder.criteria, "criteria cannot be null");
        this.judge = Objects.requireNonNull(builder.judge, "judge cannot be null");
        this.threshold = builder.threshold;
        this.input = builder.input;
        this.expectedOutput = builder.expectedOutput;
        this.context = builder.context;
        this.retrievalContext = builder.retrievalContext;
        this.cache = builder.cache;
        this.samples = builder.samples;
        this.includeTrajectory = builder.includeTrajectory;
        this.judgeIdentifier = builder.judgeIdentifier;
    }

    @Override
    public Description description() {
        Description perThread = perThreadDescription.get();
        return perThread != null ? perThread : super.description();
    }

    @Override
    public boolean matches(Object actual) {
        String actualOutput = OutputExtractor.extract(actual);
        String trajectory = includeTrajectory ? OutputExtractor.extractTrajectory(actual) : null;
        double temperature = samples > 1 ? MULTI_SAMPLE_TEMPERATURE : SINGLE_SAMPLE_TEMPERATURE;
        String baseKey =
                cache != null
                        ? JudgeCacheKey.compute(
                                name,
                                criteria,
                                input,
                                expectedOutput,
                                context,
                                retrievalContext,
                                actualOutput,
                                trajectory,
                                temperature,
                                judgeIdentifier)
                        : null;

        List<JudgeVerdict> verdicts = new ArrayList<>(samples);
        for (int i = 0; i < samples; i++) {
            String sampleKey = baseKey != null ? baseKey + "#" + i : null;
            JudgeVerdict verdict = sampleKey != null ? cache.get(sampleKey).orElse(null) : null;
            if (verdict == null) {
                verdict = callJudge(actualOutput, trajectory, temperature);
                if (sampleKey != null) {
                    cache.put(sampleKey, verdict);
                }
            }
            verdicts.add(verdict);
        }

        JudgeVerdict combined = combine(verdicts);
        perThreadDescription.set(
                new TextDescription(
                        "llm-judged \"%s\" (score=%.2f, threshold=%.2f): %s",
                        name, combined.score(), threshold, combined.reason()));
        return combined.score() >= threshold;
    }

    private JudgeVerdict callJudge(String actualOutput, String trajectory, double temperature) {
        String userMessage =
                JudgePrompt.buildUserMessage(
                        name,
                        criteria,
                        input,
                        expectedOutput,
                        context,
                        retrievalContext,
                        actualOutput,
                        trajectory);
        LLMRequest request =
                LLMRequest.builder()
                        .addSystemMessage(JudgePrompt.SYSTEM_PROMPT)
                        .addUserMessage(userMessage)
                        .temperature(temperature)
                        .build();
        try {
            LLMResponse response = judge.chat(request);
            return JudgeResponseParser.parse(response.getContent());
        } catch (JudgeEvaluationException e) {
            throw e;
        } catch (Exception e) {
            throw new JudgeEvaluationException(
                    "Judge call failed for criterion \"" + name + "\"", e);
        }
    }

    private static JudgeVerdict combine(List<JudgeVerdict> verdicts) {
        if (verdicts.size() == 1) {
            return verdicts.get(0);
        }
        double averageScore =
                verdicts.stream().mapToDouble(JudgeVerdict::score).average().orElseThrow();
        String reasons =
                IntStream.range(0, verdicts.size())
                        .mapToObj(i -> "(" + (i + 1) + ") " + verdicts.get(i).reason())
                        .reduce((a, b) -> a + " " + b)
                        .orElse("");
        return new JudgeVerdict(
                averageScore, "Averaged over " + verdicts.size() + " samples. " + reasons);
    }

    public static Builder llmJudged(String name) {
        return new Builder(Objects.requireNonNull(name, "name cannot be null"));
    }

    public static final class Builder {
        private final String name;
        private String criteria;
        private LLMClient judge;
        private double threshold = 0.5;
        private String input;
        private String expectedOutput;
        private List<String> context;
        private List<String> retrievalContext;
        private JudgeCache cache;
        private int samples = 1;
        private boolean includeTrajectory;
        private String judgeIdentifier;

        private Builder(String name) {
            this.name = name;
        }

        public Builder criteria(String criteria) {
            this.criteria = criteria;
            return this;
        }

        public Builder judge(LLMClient judge) {
            this.judge = judge;
            return this;
        }

        public Builder threshold(double threshold) {
            this.threshold = threshold;
            return this;
        }

        public Builder input(String input) {
            this.input = input;
            return this;
        }

        public Builder expectedOutput(String expectedOutput) {
            this.expectedOutput = expectedOutput;
            return this;
        }

        public Builder context(List<String> context) {
            this.context =
                    context == null ? null : Collections.unmodifiableList(new ArrayList<>(context));
            return this;
        }

        public Builder retrievalContext(List<String> retrievalContext) {
            this.retrievalContext =
                    retrievalContext == null
                            ? null
                            : Collections.unmodifiableList(new ArrayList<>(retrievalContext));
            return this;
        }

        /**
         * Caches each judge call this condition makes, keyed by its exact content. Not applied by
         * default. See {@link InMemoryJudgeCache} (per-run) and {@link FileSystemJudgeCache}
         * (persists across runs, e.g. for CI).
         */
        public Builder cache(JudgeCache cache) {
            this.cache = cache;
            return this;
        }

        /**
         * Number of independent judge calls to average (self-consistency). Defaults to 1
         * (deterministic, temperature 0). Values above 1 switch to temperature {@value
         * LlmJudgeCondition#MULTI_SAMPLE_TEMPERATURE} so the samples can actually differ.
         */
        public Builder samples(int samples) {
            if (samples < 1) {
                throw new IllegalArgumentException("samples must be at least 1, got: " + samples);
            }
            this.samples = samples;
            return this;
        }

        /**
         * When {@code actual} is an {@link AgentResult}, includes the full step trajectory
         * (thought/action/input/outcome/observation per step) in the judge prompt, not just the
         * final answer. Off by default. Presets that need to judge the whole run (e.g. task
         * completion) turn this on; presets grading just the final answer's content (e.g.
         * correctness) should leave it off to avoid diluting the judge's focus.
         */
        public Builder includeTrajectory(boolean includeTrajectory) {
            this.includeTrajectory = includeTrajectory;
            return this;
        }

        /**
         * Optional free-form identifier for the judge model in use (e.g. {@code "gemini-2.5-pro"}),
         * folded into the {@link JudgeCache} key. {@code LLMClient} doesn't expose which model or
         * provider it wraps, so without this, switching judge models while reusing the same cache
         * would silently replay verdicts graded by the old model. Only matters when a cache is set.
         */
        public Builder judgeIdentifier(String judgeIdentifier) {
            this.judgeIdentifier = judgeIdentifier;
            return this;
        }

        public LlmJudgeCondition build() {
            return new LlmJudgeCondition(this);
        }
    }
}
