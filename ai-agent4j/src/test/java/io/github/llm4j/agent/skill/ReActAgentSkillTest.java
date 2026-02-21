package io.github.llm4j.agent.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.llm4j.LLMClient;
import io.github.llm4j.agent.ReActAgent;
import io.github.llm4j.agent.persona.AgentPersona;
import io.github.llm4j.agent.tools.EchoTool;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class ReActAgentSkillTest {

    @Mock private LLMClient mockClient;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(mockClient.chat(any(LLMRequest.class)))
                .thenReturn(createResponse("Final Answer: done"));
    }

    @Test
    void testAgentWithSingleSkillInjectsContent() {
        AgentSkill skill = AgentSkill.of("Coding Tips", "Always write unit tests.");

        ReActAgent agent =
                ReActAgent.builder()
                        .llmClient(mockClient)
                        .addTool(new EchoTool())
                        .addSkill(skill)
                        .build();

        agent.run("test");

        String systemPrompt = captureSystemPrompt();
        assertThat(systemPrompt).contains("Always write unit tests.");
        assertThat(systemPrompt).contains("## Skills");
        assertThat(systemPrompt).contains("### Coding Tips");
    }

    @Test
    void testAgentWithMultipleSkillsInjectsAll() {
        AgentSkill skill1 = AgentSkill.of("Skill One", "Content of skill one.");
        AgentSkill skill2 = AgentSkill.of("Skill Two", "Content of skill two.");

        ReActAgent agent =
                ReActAgent.builder()
                        .llmClient(mockClient)
                        .addTool(new EchoTool())
                        .addSkill(skill1)
                        .addSkill(skill2)
                        .build();

        agent.run("test");

        String systemPrompt = captureSystemPrompt();
        assertThat(systemPrompt).contains("Content of skill one.");
        assertThat(systemPrompt).contains("Content of skill two.");
        assertThat(systemPrompt).contains("### Skill One");
        assertThat(systemPrompt).contains("### Skill Two");
    }

    @Test
    void testAgentWithNoSkillsHasNoSkillSection() {
        ReActAgent agent =
                ReActAgent.builder().llmClient(mockClient).addTool(new EchoTool()).build();

        agent.run("test");

        String systemPrompt = captureSystemPrompt();
        assertThat(systemPrompt).doesNotContain("## Skills");
    }

    @Test
    void testSkillSectionAppearsAfterPersona() {
        AgentPersona persona = AgentPersona.builder().name("TestBot").role("assistant").build();
        AgentSkill skill = AgentSkill.of("My Skill", "Skill content.");

        ReActAgent agent =
                ReActAgent.builder()
                        .llmClient(mockClient)
                        .addTool(new EchoTool())
                        .persona(persona)
                        .addSkill(skill)
                        .build();

        agent.run("test");

        String systemPrompt = captureSystemPrompt();
        int personaIndex = systemPrompt.indexOf("TestBot");
        int skillsIndex = systemPrompt.indexOf("## Skills");
        assertThat(personaIndex).isLessThan(skillsIndex);
    }

    @Test
    void testSkillSectionAppearsBeforeToolDescriptions() {
        AgentSkill skill = AgentSkill.of("My Skill", "Skill content.");

        ReActAgent agent =
                ReActAgent.builder()
                        .llmClient(mockClient)
                        .addTool(new EchoTool())
                        .addSkill(skill)
                        .build();

        agent.run("test");

        String systemPrompt = captureSystemPrompt();
        int skillsIndex = systemPrompt.indexOf("## Skills");
        int toolsIndex = systemPrompt.indexOf("Echo");
        assertThat(skillsIndex).isLessThan(toolsIndex);
    }

    @Test
    void testToBuilderPreservesSkills() {
        AgentSkill skill = AgentSkill.of("Preserved Skill", "Preserved content.");

        ReActAgent original =
                ReActAgent.builder()
                        .llmClient(mockClient)
                        .addTool(new EchoTool())
                        .addSkill(skill)
                        .build();

        ReActAgent copy = original.toBuilder().build();
        copy.run("test");

        String systemPrompt = captureSystemPrompt();
        assertThat(systemPrompt).contains("Preserved content.");
    }

    @Test
    void testClearSkillsRemovesAll() {
        AgentSkill skill = AgentSkill.of("Skill", "Some content.");

        ReActAgent agent =
                ReActAgent.builder()
                        .llmClient(mockClient)
                        .addTool(new EchoTool())
                        .addSkill(skill)
                        .clearSkills()
                        .build();

        agent.run("test");

        String systemPrompt = captureSystemPrompt();
        assertThat(systemPrompt).doesNotContain("## Skills");
    }

    @Test
    void testAddSkillNullThrows() {
        assertThatThrownBy(() -> ReActAgent.builder().llmClient(mockClient).addSkill(null).build())
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void testSkillsMethodBatchAdd() {
        AgentSkill skill1 = AgentSkill.of("Batch One", "Batch content one.");
        AgentSkill skill2 = AgentSkill.of("Batch Two", "Batch content two.");

        ReActAgent agent =
                ReActAgent.builder()
                        .llmClient(mockClient)
                        .addTool(new EchoTool())
                        .skills(List.of(skill1, skill2))
                        .build();

        agent.run("test");

        String systemPrompt = captureSystemPrompt();
        assertThat(systemPrompt).contains("Batch content one.");
        assertThat(systemPrompt).contains("Batch content two.");
    }

    private String captureSystemPrompt() {
        ArgumentCaptor<LLMRequest> captor = ArgumentCaptor.forClass(LLMRequest.class);
        verify(mockClient).chat(captor.capture());
        return captor.getValue().getMessages().get(0).getContent();
    }

    private LLMResponse createResponse(String content) {
        return LLMResponse.builder().content(content).model("test-model").build();
    }
}
