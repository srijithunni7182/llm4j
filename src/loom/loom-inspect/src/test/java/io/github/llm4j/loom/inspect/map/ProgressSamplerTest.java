package io.github.llm4j.loom.inspect.map;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.execution.TraceEvent;
import io.github.llm4j.loom.inspect.state.InspectRules;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProgressSamplerTest {

    private static final Map<String, InspectRules> RULES =
            Map.of("Lead", new InspectRules(null, null, null, null, null, null, 3, "review.score"));

    private static TraceEvent ev(String step) {
        return new TraceEvent(TraceEvent.NOTE, "Lead", step, "x", Map.of(), Instant.EPOCH);
    }

    private final Map<String, Object> vars = new LinkedHashMap<>();
    private final ProgressSampler sampler =
            new ProgressSampler(RULES, () -> new LinkedHashMap<>(vars));

    @Test
    void theVariableIsReadOnceAtEachRoundBoundary_withTheValueTheRoundEndedWith() {
        vars.put("review", Map.of("score", 5));
        assertThat(sampler.samples(ev("W/s1/r1.0")))
                .isEmpty(); // the first round: nothing finished yet
        assertThat(sampler.samples(ev("W/s1/r1.1"))).isEmpty(); // still round one
        vars.put("review", Map.of("score", 6));
        List<TraceEvent> first =
                sampler.samples(ev("W/s1/r2.0")); // round two begins: round one's result is stored
        assertThat(first).hasSize(1);
        assertThat(first.get(0).type()).isEqualTo(ProgressSampler.SAMPLE);
        assertThat(first.get(0).data())
                .containsEntry("agent", "Lead")
                .containsEntry("variable", "review.score")
                .containsEntry("loop", "W/s1");
        assertThat(first.get(0).data().get("hash")).isEqualTo(Signals.hash("6"));
        assertThat(sampler.samples(ev("W/s1/r2.1"))).isEmpty();
    }

    @Test
    void theValueItselfIsNotInTheSample_onlyAHash() {
        vars.put("review", Map.of("score", "a secret draft"));
        sampler.samples(ev("W/s1/r1.0"));
        TraceEvent s = sampler.samples(ev("W/s1/r2.0")).get(0);
        assertThat(String.valueOf(s.data())).doesNotContain("secret");
    }

    @Test
    void noRoundNoSample_aMissingVariableNoSample_andNoRuleNoReading() {
        vars.put("other", 1);
        assertThat(sampler.samples(ev("W/s1"))).isEmpty(); // not in a loop
        sampler.samples(ev("W/s1/r1.0"));
        assertThat(sampler.samples(ev("W/s1/r2.0"))).isEmpty(); // the variable does not exist yet
        boolean[] read = {false};
        ProgressSampler none =
                new ProgressSampler(
                        Map.of(
                                "Lead",
                                new InspectRules(2, null, null, null, null, null, null, null)),
                        () -> {
                            read[0] = true;
                            return vars;
                        });
        none.samples(ev("W/s1/r1.0"));
        none.samples(ev("W/s1/r2.0"));
        assertThat(none.active()).isFalse();
        assertThat(read[0]).isFalse();
    }

    @Test
    void aFailingReadNeverReachesTheRun() {
        ProgressSampler broken =
                new ProgressSampler(
                        RULES,
                        () -> {
                            throw new IllegalStateException("gone");
                        });
        broken.samples(ev("W/s1/r1.0"));
        assertThat(broken.samples(ev("W/s1/r2.0"))).isEmpty();
    }

    @Test
    void aPlainVariableAndANestedFieldBothResolve() {
        assertThat(ProgressSampler.path(Map.of("final", "ok"), "final")).isEqualTo("ok");
        assertThat(ProgressSampler.path(Map.of("a", Map.of("b", Map.of("c", 3))), "a.b.c"))
                .isEqualTo(3);
        assertThat(ProgressSampler.path(Map.of("a", "text"), "a.b")).isNull();
    }
}
