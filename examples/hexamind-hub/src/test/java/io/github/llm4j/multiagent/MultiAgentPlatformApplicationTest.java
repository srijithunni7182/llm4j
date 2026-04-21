package io.github.llm4j.multiagent;

import io.github.llm4j.LLMClient;
import io.github.llm4j.multiagent.config.AgentConfiguration;
import io.github.llm4j.multiagent.config.WebSocketConfiguration;
import io.github.llm4j.multiagent.controller.CollaborationController;
import io.github.llm4j.multiagent.model.AgentParticipant;
import io.github.llm4j.multiagent.service.MultiAgentOrchestrator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
        "GOOGLE_API_KEY=test-api-key",
        "GOOGLE_SEARCH_CX=test-search-cx"
})
class MultiAgentPlatformApplicationTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void contextLoads() {
        assertThat(applicationContext).isNotNull();
    }

    @Test
    void testLLMClientBeanExists() {
        assertThat(applicationContext.containsBean("llmClient")).isTrue();

        LLMClient llmClient = applicationContext.getBean(LLMClient.class);
        assertThat(llmClient).isNotNull();
    }

    @Test
    void testAgentsBeanExists() {
        assertThat(applicationContext.containsBean("agents")).isTrue();

        @SuppressWarnings("unchecked")
        List<AgentParticipant> agents = (List<AgentParticipant>) applicationContext.getBean("agents");
        assertThat(agents).isNotNull();
        assertThat(agents).isNotEmpty();
    }

    @Test
    void testMultiAgentOrchestratorBeanExists() {
        MultiAgentOrchestrator orchestrator = applicationContext.getBean(MultiAgentOrchestrator.class);
        assertThat(orchestrator).isNotNull();
    }

    @Test
    void testCollaborationControllerBeanExists() {
        CollaborationController controller = applicationContext.getBean(CollaborationController.class);
        assertThat(controller).isNotNull();
    }

    @Test
    void testAgentConfigurationBeanExists() {
        AgentConfiguration config = applicationContext.getBean(AgentConfiguration.class);
        assertThat(config).isNotNull();
    }

    @Test
    void testWebSocketConfigurationBeanExists() {
        WebSocketConfiguration config = applicationContext.getBean(WebSocketConfiguration.class);
        assertThat(config).isNotNull();
    }

    @Test
    void testAllRequiredBeansArePresent() {
        String[] requiredBeans = {
                "llmClient",
                "agents",
                "multiAgentOrchestrator",
                "collaborationController",
                "agentConfiguration",
                "webSocketConfiguration"
        };

        for (String beanName : requiredBeans) {
            assertThat(applicationContext.containsBean(beanName))
                    .as("Bean '%s' should exist", beanName)
                    .isTrue();
        }
    }

    @Test
    void testApplicationStartsSuccessfully() {
        // If we reach this point, the application context loaded successfully
        assertThat(applicationContext).isNotNull();
    }
}
