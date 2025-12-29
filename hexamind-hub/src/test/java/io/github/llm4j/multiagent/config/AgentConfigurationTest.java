package io.github.llm4j.multiagent.config;

import io.github.llm4j.LLMClient;
import io.github.llm4j.multiagent.model.AgentParticipant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
        "google.api.key=test-api-key",
        "google.search.cx=test-search-cx"
})
class AgentConfigurationTest {

    @Autowired(required = false)
    private LLMClient llmClient;

    @Autowired(required = false)
    private List<AgentParticipant> agents;

    @Test
    void testLLMClientBeanCreation() {
        assertThat(llmClient).isNotNull();
    }

    @Test
    void testAgentsBeanCreation() {
        assertThat(agents).isNotNull();
        assertThat(agents).isNotEmpty();
    }

    @Test
    void testCorrectNumberOfAgents() {
        // Should have 6 agents: Alex, Jordan, Sasha, Dr. Aris, Casey, and Rahul
        assertThat(agents).hasSize(6);
    }

    @Test
    void testAgentIds() {
        List<String> agentIds = agents.stream()
                .map(AgentParticipant::getId)
                .toList();

        assertThat(agentIds).contains("tech", "business", "creative", "research", "customer", "rahul");
    }

    @Test
    void testAgentNames() {
        List<String> agentNames = agents.stream()
                .map(AgentParticipant::getName)
                .toList();

        assertThat(agentNames).contains("Alex", "Jordan", "Sasha", "Dr. Aris", "Casey", "Rahul");
    }

    @Test
    void testAllAgentsHavePersonas() {
        for (AgentParticipant agent : agents) {
            assertThat(agent.getPersona()).isNotNull();
            assertThat(agent.getPersona().getName()).isNotNull();
            assertThat(agent.getPersona().getRole()).isNotNull();
        }
    }

    @Test
    void testAllAgentsHaveReActAgents() {
        for (AgentParticipant agent : agents) {
            assertThat(agent.getAgent()).isNotNull();
        }
    }

    @Test
    void testAllAgentsHaveAvatarUrls() {
        for (AgentParticipant agent : agents) {
            assertThat(agent.getAvatarUrl()).isNotNull();
            assertThat(agent.getAvatarUrl()).startsWith("/images/");
            assertThat(agent.getAvatarUrl()).endsWith(".png");
        }
    }

    @Test
    void testRahulAgentHasSpecialPersona() {
        AgentParticipant rahul = agents.stream()
                .filter(a -> "rahul".equals(a.getId()))
                .findFirst()
                .orElse(null);

        assertThat(rahul).isNotNull();
        assertThat(rahul.getName()).isEqualTo("Rahul");
        assertThat(rahul.getPersona().getRole()).contains("Cynical");
    }

    @Test
    void testTechnicalAnalystAgent() {
        AgentParticipant tech = agents.stream()
                .filter(a -> "tech".equals(a.getId()))
                .findFirst()
                .orElse(null);

        assertThat(tech).isNotNull();
        assertThat(tech.getName()).isEqualTo("Alex");
        assertThat(tech.getAvatarUrl()).isEqualTo("/images/alex.png");
    }

    @Test
    void testBusinessConsultantAgent() {
        AgentParticipant business = agents.stream()
                .filter(a -> "business".equals(a.getId()))
                .findFirst()
                .orElse(null);

        assertThat(business).isNotNull();
        assertThat(business.getName()).isEqualTo("Jordan");
        assertThat(business.getAvatarUrl()).isEqualTo("/images/jordan.png");
    }

    @Test
    void testCreativeWriterAgent() {
        AgentParticipant creative = agents.stream()
                .filter(a -> "creative".equals(a.getId()))
                .findFirst()
                .orElse(null);

        assertThat(creative).isNotNull();
        assertThat(creative.getName()).isEqualTo("Sasha");
        assertThat(creative.getAvatarUrl()).isEqualTo("/images/sasha.png");
    }

    @Test
    void testResearchScientistAgent() {
        AgentParticipant research = agents.stream()
                .filter(a -> "research".equals(a.getId()))
                .findFirst()
                .orElse(null);

        assertThat(research).isNotNull();
        assertThat(research.getName()).isEqualTo("Dr. Aris");
        assertThat(research.getAvatarUrl()).isEqualTo("/images/aris.png");
    }

    @Test
    void testCustomerSupportAgent() {
        AgentParticipant customer = agents.stream()
                .filter(a -> "customer".equals(a.getId()))
                .findFirst()
                .orElse(null);

        assertThat(customer).isNotNull();
        assertThat(customer.getName()).isEqualTo("Casey");
        assertThat(customer.getAvatarUrl()).isEqualTo("/images/casey.png");
    }

    @Test
    void testAgentIdsAreUnique() {
        List<String> agentIds = agents.stream()
                .map(AgentParticipant::getId)
                .toList();

        long uniqueCount = agentIds.stream().distinct().count();
        assertThat(uniqueCount).isEqualTo(agentIds.size());
    }

    @Test
    void testAgentNamesAreUnique() {
        List<String> agentNames = agents.stream()
                .map(AgentParticipant::getName)
                .toList();

        long uniqueCount = agentNames.stream().distinct().count();
        assertThat(uniqueCount).isEqualTo(agentNames.size());
    }
}
