package io.github.llm4j.multiagent.integration;

import io.github.llm4j.LLMClient;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.multiagent.model.CollaborationSession;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * End-to-end integration test for the collaboration REST API.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "google.api.key=test-api-key",
        "google.search.cx=test-search-cx"
})
class CollaborationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private LLMClient llmClient;

    @Test
    void testCompleteCollaborationFlow() throws Exception {
        // Mock LLM responses
        LLMResponse mockResponse = LLMResponse.builder()
                .content("Comprehensive consensus recommendation")
                .build();
        when(llmClient.chat(any(LLMRequest.class))).thenReturn(mockResponse);

        // Step 1: Submit a problem
        String problemJson = "{\"problem\": \"Should we adopt microservices architecture?\"}";

        MvcResult submitResult = mockMvc.perform(post("/api/problems")
                .contentType(MediaType.APPLICATION_JSON)
                .content(problemJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").exists())
                .andExpect(jsonPath("$.message").value("Collaboration started"))
                .andReturn();

        String response = submitResult.getResponse().getContentAsString();
        String sessionId = extractSessionId(response);

        assertThat(sessionId).isNotNull();

        // Step 2: Wait a bit for collaboration to progress
        Thread.sleep(2000);

        // Step 3: Get session status
        mockMvc.perform(get("/api/sessions/" + sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(sessionId))
                .andExpect(jsonPath("$.problem").value("Should we adopt microservices architecture?"))
                .andExpect(jsonPath("$.status").value(isOneOf("CREATED", "ANALYZING", "DEBATING", "BUILDING_CONSENSUS", "REFINING", "COMPLETED")));

        // Step 4: Wait for completion
        await().atMost(30, TimeUnit.SECONDS)
                .pollInterval(1, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    mockMvc.perform(get("/api/sessions/" + sessionId))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.status").value("COMPLETED"));
                });

        // Step 5: Verify final state
        mockMvc.perform(get("/api/sessions/" + sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.consensus").exists())
                .andExpect(jsonPath("$.consensus.recommendation").exists())
                .andExpect(jsonPath("$.thoughts").isArray());

        // Step 6: Submit feedback
        String feedbackJson = "{\"feedback\": \"Please consider cloud-native aspects\"}";

        mockMvc.perform(post("/api/sessions/" + sessionId + "/feedback")
                .contentType(MediaType.APPLICATION_JSON)
                .content(feedbackJson))
                .andExpect(status().isAccepted());

        // Step 7: Wait for refinement
        Thread.sleep(2000);

        // Step 8: Verify refinement completed
        await().atMost(30, TimeUnit.SECONDS)
                .pollInterval(1, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    mockMvc.perform(get("/api/sessions/" + sessionId))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.status").value("COMPLETED"))
                            .andExpect(jsonPath("$.currentRound").value(greaterThan(5)));
                });
    }

    @Test
    void testSubmitProblemAndRetrieve() throws Exception {
        LLMResponse mockResponse = LLMResponse.builder()
                .content("Test consensus")
                .build();
        when(llmClient.chat(any(LLMRequest.class))).thenReturn(mockResponse);

        String problemJson = "{\"problem\": \"Test problem for retrieval\"}";

        MvcResult result = mockMvc.perform(post("/api/problems")
                .contentType(MediaType.APPLICATION_JSON)
                .content(problemJson))
                .andExpect(status().isOk())
                .andReturn();

        String sessionId = extractSessionId(result.getResponse().getContentAsString());

        mockMvc.perform(get("/api/sessions/" + sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(sessionId))
                .andExpect(jsonPath("$.problem").value("Test problem for retrieval"));
    }

    @Test
    void testGetNonExistentSession() throws Exception {
        mockMvc.perform(get("/api/sessions/non-existent-session-id"))
                .andExpect(status().isNotFound());
    }

    @Test
    void testSubmitFeedbackToNonExistentSession() throws Exception {
        String feedbackJson = "{\"feedback\": \"Some feedback\"}";

        mockMvc.perform(post("/api/sessions/non-existent/feedback")
                .contentType(MediaType.APPLICATION_JSON)
                .content(feedbackJson))
                .andExpect(status().isAccepted()); // Should accept but do nothing
    }

    @Test
    void testMultipleConcurrentProblems() throws Exception {
        LLMResponse mockResponse = LLMResponse.builder()
                .content("Concurrent consensus")
                .build();
        when(llmClient.chat(any(LLMRequest.class))).thenReturn(mockResponse);

        // Submit multiple problems
        String problem1 = "{\"problem\": \"Problem 1\"}";
        String problem2 = "{\"problem\": \"Problem 2\"}";
        String problem3 = "{\"problem\": \"Problem 3\"}";

        MvcResult result1 = mockMvc.perform(post("/api/problems")
                .contentType(MediaType.APPLICATION_JSON)
                .content(problem1))
                .andExpect(status().isOk())
                .andReturn();

        MvcResult result2 = mockMvc.perform(post("/api/problems")
                .contentType(MediaType.APPLICATION_JSON)
                .content(problem2))
                .andExpect(status().isOk())
                .andReturn();

        MvcResult result3 = mockMvc.perform(post("/api/problems")
                .contentType(MediaType.APPLICATION_JSON)
                .content(problem3))
                .andExpect(status().isOk())
                .andReturn();

        String sessionId1 = extractSessionId(result1.getResponse().getContentAsString());
        String sessionId2 = extractSessionId(result2.getResponse().getContentAsString());
        String sessionId3 = extractSessionId(result3.getResponse().getContentAsString());

        // Verify all sessions are different
        assertThat(sessionId1).isNotEqualTo(sessionId2);
        assertThat(sessionId2).isNotEqualTo(sessionId3);
        assertThat(sessionId1).isNotEqualTo(sessionId3);

        // Verify all sessions exist
        mockMvc.perform(get("/api/sessions/" + sessionId1))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/sessions/" + sessionId2))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/sessions/" + sessionId3))
                .andExpect(status().isOk());
    }

    @Test
    void testInvalidJsonRequest() throws Exception {
        String invalidJson = "{invalid json}";

        mockMvc.perform(post("/api/problems")
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidJson))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testEmptyProblemSubmission() throws Exception {
        LLMResponse mockResponse = LLMResponse.builder()
                .content("Empty problem consensus")
                .build();
        when(llmClient.chat(any(LLMRequest.class))).thenReturn(mockResponse);

        String emptyProblem = "{\"problem\": \"\"}";

        mockMvc.perform(post("/api/problems")
                .contentType(MediaType.APPLICATION_JSON)
                .content(emptyProblem))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").exists());
    }

    @Test
    void testNullProblemSubmission() throws Exception {
        LLMResponse mockResponse = LLMResponse.builder()
                .content("Null problem consensus")
                .build();
        when(llmClient.chat(any(LLMRequest.class))).thenReturn(mockResponse);

        String nullProblem = "{\"problem\": null}";

        mockMvc.perform(post("/api/problems")
                .contentType(MediaType.APPLICATION_JSON)
                .content(nullProblem))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").exists());
    }

    @Test
    void testCorsSupport() throws Exception {
        LLMResponse mockResponse = LLMResponse.builder()
                .content("CORS test consensus")
                .build();
        when(llmClient.chat(any(LLMRequest.class))).thenReturn(mockResponse);

        String problemJson = "{\"problem\": \"CORS test\"}";

        mockMvc.perform(post("/api/problems")
                .contentType(MediaType.APPLICATION_JSON)
                .content(problemJson)
                .header("Origin", "http://localhost:3000"))
                .andExpect(status().isOk());
    }

    /**
     * Helper method to extract session ID from JSON response
     */
    private String extractSessionId(String jsonResponse) {
        // Simple extraction - in production would use JSON parser
        int start = jsonResponse.indexOf("\"sessionId\":\"") + 13;
        int end = jsonResponse.indexOf("\"", start);
        return jsonResponse.substring(start, end);
    }
}
