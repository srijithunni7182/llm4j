package io.github.llm4j.agent.persona;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for AgentPersona
 */
class AgentPersonaTest {

    @Test
    void testBasicPersonaCreation() {
        AgentPersona persona = AgentPersona.builder()
                .name("Test Agent")
                .role("test specialist")
                .expertise("Testing and quality assurance")
                .tone("Professional and thorough")
                .build();

        assertThat(persona.getName()).isEqualTo("Test Agent");
        assertThat(persona.getRole()).isEqualTo("test specialist");
        assertThat(persona.getExpertise()).isEqualTo("Testing and quality assurance");
        assertThat(persona.getTone()).isEqualTo("Professional and thorough");
        assertThat(persona.getConstraints()).isEmpty();
        assertThat(persona.getCustomAttributes()).isEmpty();
    }

    @Test
    void testPersonaWithConstraints() {
        AgentPersona persona = AgentPersona.builder()
                .name("Constrained Agent")
                .addConstraint("Always verify facts")
                .addConstraint("Never speculate")
                .build();

        assertThat(persona.getConstraints()).hasSize(2);
        assertThat(persona.getConstraints()).contains("Always verify facts", "Never speculate");
    }

    @Test
    void testPersonaWithCustomAttributes() {
        AgentPersona persona = AgentPersona.builder()
                .name("Custom Agent")
                .addCustomAttribute("language", "formal")
                .addCustomAttribute("domain", "finance")
                .build();

        assertThat(persona.getCustomAttributes()).hasSize(2);
        assertThat(persona.getCustomAttributes()).containsEntry("language", "formal");
        assertThat(persona.getCustomAttributes()).containsEntry("domain", "finance");
    }

    @Test
    void testSystemPromptGeneration() {
        AgentPersona persona = AgentPersona.builder()
                .name("Test Analyst")
                .role("data analyst")
                .expertise("Statistical analysis and data visualization")
                .tone("Analytical and precise")
                .addConstraint("Always cite data sources")
                .addConstraint("Use quantitative evidence")
                .build();

        String prompt = persona.toSystemPromptAddition();

        assertThat(prompt).contains("You are Test Analyst");
        assertThat(prompt).contains("a data analyst");
        assertThat(prompt).contains("Statistical analysis and data visualization");
        assertThat(prompt).contains("Analytical and precise");
        assertThat(prompt).contains("Always cite data sources");
        assertThat(prompt).contains("Use quantitative evidence");
    }

    @Test
    void testMinimalPersona() {
        AgentPersona persona = AgentPersona.builder()
                .name("Minimal")
                .build();

        String prompt = persona.toSystemPromptAddition();
        assertThat(prompt).contains("You are Minimal");
        assertThat(prompt).doesNotContain("expertise");
        assertThat(prompt).doesNotContain("constraints");
    }

    @Test
    void testNameIsRequired() {
        assertThrows(NullPointerException.class, () -> {
            AgentPersona.builder().build();
        });
    }

    @Test
    void testPersonaImmutability() {
        AgentPersona persona = AgentPersona.builder()
                .name("Immutable")
                .addConstraint("Test constraint")
                .build();

        // Verify collections are unmodifiable
        assertThrows(UnsupportedOperationException.class, () -> {
            persona.getConstraints().add("New constraint");
        });

        assertThrows(UnsupportedOperationException.class, () -> {
            persona.getCustomAttributes().put("new", "value");
        });
    }

    @Test
    void testToString() {
        AgentPersona persona = AgentPersona.builder()
                .name("Test")
                .role("tester")
                .tone("friendly")
                .build();

        String str = persona.toString();
        assertThat(str).contains("Test");
        assertThat(str).contains("tester");
        assertThat(str).contains("friendly");
    }
}
