package io.github.llm4j.privacy;

import static org.junit.jupiter.api.Assertions.*;

import io.github.llm4j.LLMClient;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class MaskingLLMClientTest {

    static class Recording implements LLMClient {
        final List<LLMRequest> requests = new ArrayList<>();

        @Override
        public LLMResponse chat(LLMRequest request) {
            requests.add(request);
            return LLMResponse.builder().content("ok").build();
        }

        @Override
        public Stream<LLMResponse> chatStream(LLMRequest request) {
            requests.add(request);
            return Stream.of(LLMResponse.builder().content("ok").build());
        }
    }

    @Test
    void everyMessageIsMaskedAndTheRestOfTheRequestKept() {
        Recording inner = new Recording();
        List<Map<PIIType, Integer>> reports = new ArrayList<>();
        MaskingLLMClient client = new MaskingLLMClient(inner, new RegexPIIDetector(), MaskingLLMClient.PERSONAL, reports::add);

        client.chat(LLMRequest.builder()
                .addSystemMessage("Reply to asha@example.com")
                .addUserMessage("Call me on 555-123-4567, docs at https://example.com/help")
                .addAssistantMessage("noted")
                .model("m1").temperature(0.3).maxTokens(50)
                .build());

        LLMRequest sent = inner.requests.get(0);
        assertEquals("Reply to [EMAIL]", sent.getMessages().get(0).getContent());
        assertTrue(sent.getMessages().get(1).getContent().startsWith("Call me on [PHONE]"), sent.getMessages().get(1).getContent());
        assertTrue(sent.getMessages().get(1).getContent().contains("https://example.com/help"), "URLs are not personal: kept");
        assertEquals("noted", sent.getMessages().get(2).getContent());
        assertEquals("m1", sent.getModel());
        assertEquals(0.3, sent.getTemperature());
        assertEquals(50, sent.getMaxTokens());
        assertEquals(Map.of(PIIType.EMAIL, 1, PIIType.PHONE, 1), reports.get(0));
    }

    @Test
    void nothingToMaskReportsNothing() {
        Recording inner = new Recording();
        List<Map<PIIType, Integer>> reports = new ArrayList<>();
        MaskingLLMClient client = new MaskingLLMClient(inner, new RegexPIIDetector(), MaskingLLMClient.PERSONAL, reports::add);
        client.chatStream(LLMRequest.builder().addUserMessage("hello there").build()).toList();
        assertEquals("hello there", inner.requests.get(0).getMessages().get(0).getContent());
        assertTrue(reports.isEmpty());
    }

    @Test
    void theDefaultConstructorMasksPersonalData() {
        Recording inner = new Recording();
        new MaskingLLMClient(inner).chat(LLMRequest.builder().addUserMessage("ssn 123-45-6789").build());
        assertEquals("ssn [SSN]", inner.requests.get(0).getMessages().get(0).getContent());
    }

    @Test
    void maskHandlesChosenTypesAndEmptyText() {
        assertNull(MaskingLLMClient.mask(null, new RegexPIIDetector(), MaskingLLMClient.PERSONAL, null));
        assertEquals("", MaskingLLMClient.mask("", new RegexPIIDetector(), MaskingLLMClient.PERSONAL, null));
        assertEquals("see [URL]", MaskingLLMClient.mask("see https://x.org/a", new RegexPIIDetector(), EnumSet.of(PIIType.URL), null));
        assertEquals("mail a@b.io", MaskingLLMClient.mask("mail a@b.io", new RegexPIIDetector(), EnumSet.of(PIIType.PHONE), null));
    }
}
