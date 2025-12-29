package io.github.llm4j.multiagent.controller;

import io.github.llm4j.multiagent.model.CollaborationSession;
import io.github.llm4j.multiagent.service.MultiAgentOrchestrator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CollaborationController.class)
class CollaborationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MultiAgentOrchestrator orchestrator;

    @Test
    void testSubmitProblem() throws Exception {
        when(orchestrator.startCollaboration(anyString())).thenReturn("session-123");

        String json = "{\"problem\": \"Solve X\"}";

        mockMvc.perform(post("/api/problems")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("session-123"))
                .andExpect(jsonPath("$.message").value("Collaboration started"));

        verify(orchestrator).startCollaboration("Solve X");
    }

    @Test
    void testGetSessionFound() throws Exception {
        CollaborationSession session = CollaborationSession.builder()
                .sessionId("session-123")
                .status(CollaborationSession.SessionStatus.CREATED)
                .build();
        when(orchestrator.getSession("session-123")).thenReturn(session);

        mockMvc.perform(get("/api/sessions/session-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("session-123"))
                .andExpect(jsonPath("$.status").value("CREATED"));
    }

    @Test
    void testGetSessionNotFound() throws Exception {
        when(orchestrator.getSession("unknown")).thenReturn(null);

        mockMvc.perform(get("/api/sessions/unknown"))
                .andExpect(status().isNotFound());
    }

    @Test
    void testSubmitFeedback() throws Exception {
        String json = "{\"feedback\": \"Good job\"}";

        mockMvc.perform(post("/api/sessions/session-123/feedback")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
                .andExpect(status().isAccepted());

        verify(orchestrator).processFeedback("session-123", "Good job");
    }

    // ===== NEW COMPREHENSIVE TESTS =====

    @Test
    void testSubmitProblemWithEmptyString() throws Exception {
        when(orchestrator.startCollaboration(anyString())).thenReturn("session-empty");

        String json = "{\"problem\": \"\"}";

        mockMvc.perform(post("/api/problems")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("session-empty"));

        verify(orchestrator).startCollaboration("");
    }

    @Test
    void testSubmitProblemWithNullValue() throws Exception {
        when(orchestrator.startCollaboration(null)).thenReturn("session-null");

        String json = "{\"problem\": null}";

        mockMvc.perform(post("/api/problems")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
                .andExpect(status().isOk());

        verify(orchestrator).startCollaboration(null);
    }

    @Test
    void testSubmitProblemWithMalformedJson() throws Exception {
        String malformedJson = "{\"problem\": \"Test\" invalid}";

        mockMvc.perform(post("/api/problems")
                .contentType(MediaType.APPLICATION_JSON)
                .content(malformedJson))
                .andExpect(status().isBadRequest());

        verify(orchestrator, never()).startCollaboration(anyString());
    }

    @Test
    void testSubmitProblemWithVeryLongString() throws Exception {
        when(orchestrator.startCollaboration(anyString())).thenReturn("session-long");

        String longProblem = "A".repeat(10000);
        String json = String.format("{\"problem\": \"%s\"}", longProblem);

        mockMvc.perform(post("/api/problems")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("session-long"));

        verify(orchestrator).startCollaboration(longProblem);
    }

    @Test
    void testSubmitProblemWithSpecialCharacters() throws Exception {
        when(orchestrator.startCollaboration(anyString())).thenReturn("session-special");

        String json = "{\"problem\": \"Test with \\\"quotes\\\" and \\n newlines \\t tabs\"}";

        mockMvc.perform(post("/api/problems")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
                .andExpect(status().isOk());

        verify(orchestrator).startCollaboration(anyString());
    }

    @Test
    void testSubmitFeedbackWithEmptyString() throws Exception {
        String json = "{\"feedback\": \"\"}";

        mockMvc.perform(post("/api/sessions/session-123/feedback")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
                .andExpect(status().isAccepted());

        verify(orchestrator).processFeedback("session-123", "");
    }

    @Test
    void testSubmitFeedbackWithNullValue() throws Exception {
        String json = "{\"feedback\": null}";

        mockMvc.perform(post("/api/sessions/session-123/feedback")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
                .andExpect(status().isAccepted());

        verify(orchestrator).processFeedback("session-123", null);
    }

    @Test
    void testSubmitFeedbackWithMalformedJson() throws Exception {
        String malformedJson = "{\"feedback\": invalid}";

        mockMvc.perform(post("/api/sessions/session-123/feedback")
                .contentType(MediaType.APPLICATION_JSON)
                .content(malformedJson))
                .andExpect(status().isBadRequest());

        verify(orchestrator, never()).processFeedback(anyString(), anyString());
    }

    @Test
    void testGetSessionWithSpecialCharactersInId() throws Exception {
        String specialId = "session-with-special-chars-!@#$%";
        when(orchestrator.getSession(specialId)).thenReturn(null);

        mockMvc.perform(get("/api/sessions/" + specialId))
                .andExpect(status().isNotFound());
    }

    @Test
    void testGetSessionWithCompletedStatus() throws Exception {
        CollaborationSession session = CollaborationSession.builder()
                .sessionId("session-completed")
                .status(CollaborationSession.SessionStatus.COMPLETED)
                .problem("Test problem")
                .build();
        when(orchestrator.getSession("session-completed")).thenReturn(session);

        mockMvc.perform(get("/api/sessions/session-completed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("session-completed"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.problem").value("Test problem"));
    }

    @Test
    void testGetSessionWithFailedStatus() throws Exception {
        CollaborationSession session = CollaborationSession.builder()
                .sessionId("session-failed")
                .status(CollaborationSession.SessionStatus.FAILED)
                .build();
        when(orchestrator.getSession("session-failed")).thenReturn(session);

        mockMvc.perform(get("/api/sessions/session-failed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));
    }

    @Test
    void testCorsHeaders() throws Exception {
        when(orchestrator.startCollaboration(anyString())).thenReturn("session-cors");

        String json = "{\"problem\": \"Test CORS\"}";

        mockMvc.perform(post("/api/problems")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .header("Origin", "http://localhost:3000"))
                .andExpect(status().isOk());
    }

    @Test
    void testMissingContentType() throws Exception {
        String json = "{\"problem\": \"Test\"}";

        mockMvc.perform(post("/api/problems")
                .content(json))
                .andExpect(status().isUnsupportedMediaType());

        verify(orchestrator, never()).startCollaboration(anyString());
    }

    @Test
    void testEmptyRequestBody() throws Exception {
        mockMvc.perform(post("/api/problems")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isOk());

        verify(orchestrator).startCollaboration(null);
    }
}
