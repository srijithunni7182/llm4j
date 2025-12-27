package io.github.llm4j.agent.persona;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for PersonaLibrary
 */
class PersonaLibraryTest {

    @Test
    void testTechnicalAnalystPersona() {
        AgentPersona persona = PersonaLibrary.technicalAnalyst();

        assertThat(persona.getName()).isEqualTo("Technical Analyst");
        assertThat(persona.getRole()).contains("analyst");
        assertThat(persona.getExpertise()).contains("Statistical analysis");
        assertThat(persona.getTone()).contains("precise");
        assertThat(persona.getConstraints()).isNotEmpty();
    }

    @Test
    void testCreativeWriterPersona() {
        AgentPersona persona = PersonaLibrary.creativeWriter();

        assertThat(persona.getName()).isEqualTo("Creative Writer");
        assertThat(persona.getRole()).contains("creative");
        assertThat(persona.getTone()).contains("imaginative");
    }

    @Test
    void testCustomerSupportPersona() {
        AgentPersona persona = PersonaLibrary.customerSupport();

        assertThat(persona.getName()).contains("Customer Support");
        assertThat(persona.getTone()).contains("empathetic");
        assertThat(persona.getConstraints()).anyMatch(c -> c.contains("acknowledge"));
    }

    @Test
    void testSoftwareDeveloperPersona() {
        AgentPersona persona = PersonaLibrary.softwareDeveloper();

        assertThat(persona.getName()).isEqualTo("Software Developer");
        assertThat(persona.getExpertise()).contains("Software development");
        assertThat(persona.getConstraints()).anyMatch(c -> c.contains("edge cases"));
    }

    @Test
    void testResearchScientistPersona() {
        AgentPersona persona = PersonaLibrary.researchScientist();

        assertThat(persona.getName()).isEqualTo("Research Scientist");
        assertThat(persona.getExpertise()).contains("Scientific methodology");
        assertThat(persona.getConstraints()).anyMatch(c -> c.contains("hypotheses"));
    }

    @Test
    void testBusinessConsultantPersona() {
        AgentPersona persona = PersonaLibrary.businessConsultant();

        assertThat(persona.getName()).isEqualTo("Business Consultant");
        assertThat(persona.getTone()).contains("Strategic");
        assertThat(persona.getConstraints()).anyMatch(c -> c.contains("ROI"));
    }

    @Test
    void testEducatorPersona() {
        AgentPersona persona = PersonaLibrary.educator();

        assertThat(persona.getName()).isEqualTo("Educator");
        assertThat(persona.getTone()).contains("patient");
        assertThat(persona.getConstraints()).anyMatch(c -> c.contains("examples"));
    }

    @Test
    void testMedicalAdvisorPersona() {
        AgentPersona persona = PersonaLibrary.medicalAdvisor();

        assertThat(persona.getName()).isEqualTo("Medical Advisor");
        assertThat(persona.getConstraints()).anyMatch(c -> c.contains("healthcare professionals"));
        assertThat(persona.getConstraints()).anyMatch(c -> c.contains("Never diagnose"));
    }

    @Test
    void testAllPersonasGenerateValidPrompts() {
        AgentPersona[] personas = {
                PersonaLibrary.technicalAnalyst(),
                PersonaLibrary.creativeWriter(),
                PersonaLibrary.customerSupport(),
                PersonaLibrary.softwareDeveloper(),
                PersonaLibrary.researchScientist(),
                PersonaLibrary.businessConsultant(),
                PersonaLibrary.educator(),
                PersonaLibrary.medicalAdvisor()
        };

        for (AgentPersona persona : personas) {
            String prompt = persona.toSystemPromptAddition();
            assertThat(prompt).isNotEmpty();
            assertThat(prompt).contains("You are");
            assertThat(prompt).contains(persona.getName());
        }
    }
}
