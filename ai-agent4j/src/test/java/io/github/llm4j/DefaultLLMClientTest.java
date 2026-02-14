package io.github.llm4j;

import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.provider.LLMProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DefaultLLMClientTest {

    @Mock
    private LLMProvider mockProvider;

    private DefaultLLMClient client;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(mockProvider.getProviderName()).thenReturn("test-provider");
        doNothing().when(mockProvider).validate();

        client = new DefaultLLMClient(mockProvider);
    }

    @Test
    void testChatDelegatesToProvider() {
        LLMRequest request = LLMRequest.builder()
                .addUserMessage("Hello")
                .build();

        LLMResponse expectedResponse = LLMResponse.builder()
                .content("Hi there!")
                .build();

        when(mockProvider.chat(any(LLMRequest.class))).thenReturn(expectedResponse);

        LLMResponse response = client.chat(request);

        assertThat(response).isEqualTo(expectedResponse);
        verify(mockProvider).chat(request);
    }

    @Test
    void testNullProviderThrows() {
        assertThatThrownBy(() -> new DefaultLLMClient(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void testNullRequestThrows() {
        assertThatThrownBy(() -> client.chat(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void testValidateCalledOnConstruction() {
        verify(mockProvider).validate();
    }

    @Test
    void testGetProviderName() {
        assertThat(client.getProviderName()).isEqualTo("test-provider");
    }
}
