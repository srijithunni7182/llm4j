package io.github.llm4j.fairness;

import static org.junit.jupiter.api.Assertions.*;

import io.github.llm4j.LLMClient;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class LLMBiasMonitorTest {

    private static LLMClient replying(String reply, List<LLMRequest> seen) {
        return new LLMClient() {
            @Override
            public LLMResponse chat(LLMRequest request) {
                seen.add(request);
                return LLMResponse.builder().content(reply).build();
            }

            @Override
            public Stream<LLMResponse> chatStream(LLMRequest request) {
                throw new UnsupportedOperationException();
            }
        };
    }

    @Test
    void readsTheJudgesFindings() {
        List<LLMRequest> seen = new ArrayList<>();
        LLMBiasMonitor monitor = new LLMBiasMonitor(replying("""
                Here you go:
                {"findings": [{"type": "gender", "severity": "HIGH", "text": "women can't code", "explanation": "stereotype"},
                              {"type": "weird", "severity": "extreme", "text": "x", "explanation": "y"}]}""", seen));
        List<BiasEvent> events = monitor.detectBias("Women can't code.", BiasContext.empty());
        assertEquals(2, events.size());
        assertEquals(BiasType.GENDER, events.get(0).getType());
        assertEquals(BiasSeverity.HIGH, events.get(0).getSeverity());
        assertEquals("stereotype", events.get(0).getExplanation());
        assertEquals(BiasType.OTHER, events.get(1).getType());
        assertEquals(BiasSeverity.MEDIUM, events.get(1).getSeverity());
        assertTrue(monitor.shouldIntervene(events));
        assertTrue(seen.get(0).getMessages().get(1).getContent().contains("Women can't code."));
        assertEquals(0.0, seen.get(0).getTemperature());
    }

    @Test
    void noFindingsAndUnreadableRepliesMeanNoEvents() {
        List<LLMRequest> seen = new ArrayList<>();
        assertTrue(new LLMBiasMonitor(replying("{\"findings\": []}", seen)).detectBias("fine").isEmpty());
        assertTrue(new LLMBiasMonitor(replying("I think it's fine.", seen)).detectBias("fine").isEmpty());
        assertTrue(new LLMBiasMonitor(replying("{broken", seen)).detectBias("fine").isEmpty());
        int calls = seen.size();
        assertTrue(new LLMBiasMonitor(replying("x", seen)).detectBias("  ").isEmpty());
        assertEquals(calls, seen.size(), "blank text needs no judge");
    }
}
