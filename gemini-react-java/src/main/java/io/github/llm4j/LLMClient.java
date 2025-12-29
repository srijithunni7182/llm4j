package io.github.llm4j;

import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;

import java.util.stream.Stream;

/**
 * Main interface for interacting with LLM providers.
 * Implementations of this interface provide a unified API for making requests
 * to different LLM services.
 */
public interface LLMClient {
    
    /**
     * Sends a chat request to the LLM and returns the complete response.
     * This method blocks until the full response is received.
     *
     * @param request the LLM request containing messages and configuration
     * @return the LLM response containing the generated content
     * @throws io.github.llm4j.exception.LLMException if an error occurs during the request
     */
    LLMResponse chat(LLMRequest request);
    
    /**
     * Sends a chat request to the LLM and returns a stream of response chunks.
     * This enables streaming responses for real-time output.
     *
     * @param request the LLM request containing messages and configuration
     * @return a stream of LLM responses, each containing a chunk of generated content
     * @throws io.github.llm4j.exception.LLMException if an error occurs during the request
     */
    Stream<LLMResponse> chatStream(LLMRequest request);
}
