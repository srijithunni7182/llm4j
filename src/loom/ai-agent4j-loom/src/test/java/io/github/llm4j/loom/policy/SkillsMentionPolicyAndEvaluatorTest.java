package io.github.llm4j.loom.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Phase 26, R12: the skills teach policies at the edge and the evaluator, not rules in prompts. */
class SkillsMentionPolicyAndEvaluatorTest {

    static final Path SKILLS = Path.of("../../../.claude/skills");

    static String skill(String name) throws Exception {
        return Files.readString(SKILLS.resolve(name).resolve("SKILL.md"));
    }

    @Test
    void theWorkflowGuideSeparatesToneFromRulesAndAsksWhoseWordsReachAPerson() throws Exception {
        String s = skill("llm4j-workflow-guide");
        assertThat(s).contains("a prompt is a wish, not a guarantee");
        assertThat(s).contains("do its words reach a person?");
        assertThat(s).contains("policies/").contains("check <reply> against <Policy> using <Evaluator>");
        assertThat(s).contains("LY07").contains("prompt_leak");
        assertThat(s).doesNotContain("move them to one top-level `shared_prompt: \"…\"` (or `shared_prompt: prompt \"house-rules\"`); keep it stable, with no dates or run ids.\n");
    }

    @Test
    void theHarnessSkillNamesAudienceAndFailClosedEvaluators() throws Exception {
        String s = skill("llm4j-harness-design");
        assertThat(s).contains("External and internal agents, by audience").contains("fails closed");
    }

    @Test
    void theEvaluationSkillTreatsAnEvaluatorAsAJudgeWithNearMissCases() throws Exception {
        String s = skill("llm4j-eval-design");
        assertThat(s).contains("evaluator").contains("near-misses").contains("unsure rate");
    }

    @Test
    void theDeployerSkillNamesTheOverrideAuditEvent() throws Exception {
        assertThat(skill("llm4j-loom-deployer")).contains("policy_override");
    }
}
