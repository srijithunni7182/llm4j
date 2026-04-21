package io.github.llm4j.multiagent.integration;

import io.github.llm4j.LLMClient;
import io.github.llm4j.agent.rag.embedding.EmbeddingProvider;
import io.github.llm4j.hexamind.model.Session;
import io.github.llm4j.hexamind.model.Turn;
import io.github.llm4j.hexamind.model.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "GOOGLE_API_KEY=test-api-key",
        "GOOGLE_SEARCH_CX=test-search-cx",
        "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class UserStatsIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private io.github.llm4j.hexamind.repository.UserRepository userRepository;

    @Autowired
    private io.github.llm4j.hexamind.repository.SessionRepository sessionRepository;

    @Autowired
    private io.github.llm4j.hexamind.repository.TurnRepository turnRepository;

    @MockBean
    private LLMClient llmClient;

    @MockBean
    private EmbeddingProvider embeddingProvider;

    @org.junit.jupiter.api.BeforeEach
    void setup() {
        turnRepository.deleteAll();
        sessionRepository.deleteAll();
        userRepository.deleteAll();

        // Create User
        User user = io.github.llm4j.hexamind.model.User.builder()
                .username("statuser")
                .email("stats@example.com")
                .name("Stats User")
                .password("password")
                .avatarUrl("http://example.com/avatar.png")
                .build();
        userRepository.save(user);
    }

    @Test
    @org.springframework.security.test.context.support.WithMockUser(username = "statuser")
    void testGetUserStatsAggregated() throws Exception {
        User user = userRepository.findByUsername("statuser").orElseThrow();

        // Create Session 1
        Session s1 = Session.builder()
                .sessionId(UUID.randomUUID().toString())
                .user(user)
                .topic("Topic 1")
                .status("COMPLETED")
                .build();
        s1 = sessionRepository.save(s1);

        // Add 2 turns to Session 1
        turnRepository.save(Turn.builder().session(s1).speaker("Agent1").content("Thought 1").build());
        turnRepository.save(Turn.builder().session(s1).speaker("Agent2").content("Thought 2").build());

        // Create Session 2
        Session s2 = Session.builder()
                .sessionId(UUID.randomUUID().toString())
                .user(user)
                .topic("Topic 2")
                .status("COMPLETED")
                .build();
        s2 = sessionRepository.save(s2);

        // Add 3 turns to Session 2
        turnRepository.save(Turn.builder().session(s2).speaker("Agent3").content("Thought 3").build());
        turnRepository.save(Turn.builder().session(s2).speaker("Agent4").content("Thought 4").build());
        turnRepository.save(Turn.builder().session(s2).speaker("Agent5").content("Thought 5").build());

        // Perform request
        // We expect:
        // - cognitiveSteps (turns) = 2 + 3 = 5
        // - knowledgeNodes = 0 (we haven't mocked triples yet, but repo method will
        // return 0 which is valid)
        // - memoryVectors = 0

        mockMvc.perform(get("/api/user/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cognitiveSteps").value(5))
                .andExpect(jsonPath("$.knowledgeNodes").value(0)) // Will be 0 until we implement triple saving in test
                .andExpect(jsonPath("$.memoryVectors").value(0));
    }
}
