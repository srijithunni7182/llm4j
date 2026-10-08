package io.github.llm4j.agent.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

public class RemoteSkillLoaderTest {

    @Mock
    private HttpClient httpClient;

    @Mock
    private HttpResponse<String> httpResponse;

    private RemoteSkillLoader loader;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        loader = new RemoteSkillLoader(httpClient);
    }

    @Test
    void testLoadSkill() throws Exception {
        String content = "# My Skill\\nThis is a test skill.";
        Mockito.when(httpResponse.statusCode()).thenReturn(200);
        Mockito.when(httpResponse.body()).thenReturn(content);
        
        Mockito.when(httpClient.send(Mockito.any(HttpRequest.class), Mockito.<HttpResponse.BodyHandler<String>>any()))
               .thenReturn(httpResponse);

        AgentSkill skill = loader.load("https://raw.githubusercontent.com/test/repo/main/my-skill.md");
        
        assertNotNull(skill);
        assertEquals("My Skill", skill.getName());
        assertEquals(content, skill.getContent());
    }
}
