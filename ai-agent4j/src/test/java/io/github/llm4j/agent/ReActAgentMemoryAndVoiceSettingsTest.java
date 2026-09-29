package io.github.llm4j.agent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import io.github.llm4j.LLMClient;
import io.github.llm4j.agent.memory.SemanticMemoryService;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.model.TextToSpeechRequest;
import io.github.llm4j.model.TextToSpeechResponse;
import io.github.llm4j.provider.TextToSpeechProvider;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ReActAgentMemoryAndVoiceSettingsTest {

    private static final LLMClient ANSWERS = new LLMClient() {
        @Override
        public LLMResponse chat(LLMRequest request) {
            return LLMResponse.builder().content("{\"final_answer\": \"done\"}").build();
        }

        @Override
        public Stream<LLMResponse> chatStream(LLMRequest request) {
            throw new UnsupportedOperationException();
        }
    };

    @Test
    void recallUsesTheConfiguredTopKAndSimilarity() {
        SemanticMemoryService memory = mock(SemanticMemoryService.class);
        when(memory.recallRelevantFacts(anyString(), anyInt(), anyFloat())).thenReturn(List.of());
        ReActAgent agent = ReActAgent.builder().llmClient(ANSWERS).semanticMemory(memory).semanticRecall(3, 0.5f).build();
        agent.run("what do I like?");
        verify(memory).recallRelevantFacts(anyString(), eq(3), eq(0.5f));

        agent.toBuilder().build().run("again");
        verify(memory, times(2)).recallRelevantFacts(anyString(), eq(3), eq(0.5f));
    }

    @Test
    void recallDefaultsAndValidation() {
        SemanticMemoryService memory = mock(SemanticMemoryService.class);
        when(memory.recallRelevantFacts(anyString(), anyInt(), anyFloat())).thenReturn(List.of());
        ReActAgent.builder().llmClient(ANSWERS).semanticMemory(memory).build().run("q");
        verify(memory).recallRelevantFacts(anyString(), eq(5), eq(0.7f));
        assertThrows(IllegalArgumentException.class, () -> ReActAgent.builder().semanticRecall(0, 0.5f));
        assertThrows(IllegalArgumentException.class, () -> ReActAgent.builder().semanticRecall(1, 1.5f));
    }

    @Test
    void toBuilderKeepsTheSpeechLanguageAndModel() {
        TextToSpeechProvider tts = mock(TextToSpeechProvider.class);
        when(tts.generateSpeech(any())).thenReturn(new TextToSpeechResponse(new byte[] {1}, "audio/wav"));
        ReActAgent agent = ReActAgent.builder().llmClient(ANSWERS).ttsProvider(tts)
                .ttsLanguage("Hindi").ttsModel("bulbul:v2").autoPlayAudio(false).build();
        agent.toBuilder().build().speak("namaste");

        ArgumentCaptor<TextToSpeechRequest> request = ArgumentCaptor.forClass(TextToSpeechRequest.class);
        verify(tts).generateSpeech(request.capture());
        assertEquals("bulbul:v2", request.getValue().getModel().orElseThrow());
        assertEquals("hi-IN", request.getValue().getTargetLanguageCode().orElseThrow());
    }

    @Test
    void agentResultToBuilderCopiesEverything() {
        AgentResult original = AgentResult.builder().finalAnswer("secret a@b.io").iterations(3).completed(true)
                .uncertaintyDetected(true).uncertaintyReason("why").redundantActionCount(2).protocolFollowed(false)
                .usage(new AgentResult.Usage(2, 10, 5, 15, false, null))
                .addStep(new AgentResult.AgentStep("t", "a", "i", "o", AgentResult.StepOutcome.EXECUTED)).build();
        AgentResult copy = original.toBuilder().finalAnswer("secret [EMAIL]").build();
        assertEquals("secret [EMAIL]", copy.getFinalAnswer());
        assertEquals(3, copy.getIterations());
        assertTrue(copy.isCompleted());
        assertTrue(copy.isUncertaintyDetected());
        assertEquals("why", copy.getUncertaintyReason());
        assertEquals(2, copy.getRedundantActionCount());
        assertFalse(copy.isProtocolFollowed());
        assertEquals(15, copy.getUsage().getTotalTokens());
        assertEquals(1, copy.getSteps().size());
        assertEquals("secret a@b.io", original.getFinalAnswer());
    }
}
