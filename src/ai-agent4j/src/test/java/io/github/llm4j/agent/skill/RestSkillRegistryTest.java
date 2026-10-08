package io.github.llm4j.agent.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

public class RestSkillRegistryTest {

    @Mock
    private HttpClient httpClient;

    @Mock
    private HttpResponse<String> searchResponse;
    
    @Mock
    private HttpResponse<String> skillResponse;

    private RestSkillRegistry registry;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        registry = RestSkillRegistry.builder()
                .baseUrl("http://localhost:8080/api/skills")
                .apiKey("test-key")
                .httpClient(httpClient)
                .objectMapper(new ObjectMapper())
                .build();
    }

    @Test
    void testSearchSkills() throws Exception {
        String jsonBody = """
            {
               "data": [
                 {
                   "id": "test-skill-1",
                   "name": "Test Skill 1",
                   "description": "A test skill",
                   "author": "tester",
                   "tags": ["test"]
                 }
               ]
            }
            """;
        Mockito.when(searchResponse.statusCode()).thenReturn(200);
        Mockito.when(searchResponse.body()).thenReturn(jsonBody);
        
        Mockito.when(httpClient.send(Mockito.any(HttpRequest.class), Mockito.<HttpResponse.BodyHandler<String>>any()))
               .thenReturn(searchResponse);

        List<SkillMetadata> results = registry.searchSkills("test");
        assertEquals(1, results.size());
        assertEquals("test-skill-1", results.get(0).id());
        assertEquals("Test Skill 1", results.get(0).name());
        assertEquals("A test skill", results.get(0).description());
        assertEquals("tester", results.get(0).author());
        assertEquals(1, results.get(0).tags().size());
        assertEquals("test", results.get(0).tags().get(0));
    }

    @Test
    void testGetSkill() throws Exception {
        String jsonBody = """
            {
               "id": "test-skill-1",
               "name": "Test Skill 1",
               "content": "This is the markdown content of test skill 1."
            }
            """;
        Mockito.when(skillResponse.statusCode()).thenReturn(200);
        Mockito.when(skillResponse.body()).thenReturn(jsonBody);

        Mockito.when(httpClient.send(Mockito.any(HttpRequest.class), Mockito.<HttpResponse.BodyHandler<String>>any()))
               .thenReturn(skillResponse);

        AgentSkill skill = registry.getSkill("test-skill-1");
        assertNotNull(skill);
        assertEquals("Test Skill 1", skill.getName());
        assertEquals("This is the markdown content of test skill 1.", skill.getContent());
    }
}
