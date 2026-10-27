package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * R56 of spec 09: the three skills tell a coding agent to run review, report it, offer to fix or
 * accept with the user deciding, and carry on with the work, and none tells it to stop on findings.
 */
class SkillAdvisoryTextTest {

    static final Path REPO = Path.of("../../..");
    static final List<String> SKILLS =
            List.of("llm4j-workflow-guide", "llm4j-harness-design", "llm4j-loom-deployer");

    static String skill(String name) throws IOException {
        return Files.readString(REPO.resolve(".claude/skills/" + name + "/SKILL.md"));
    }

    @Test
    void everySkillSaysToRunReviewReportItAndCarryOn() throws IOException {
        for (String name : SKILLS) {
            String s = skill(name);
            assertThat(s).as(name).contains("## Review is advice, and the decision is the user's");
            assertThat(s).as(name).contains("run `weave review <script>`");
            assertThat(s).as(name).contains("open counts and the top findings");
            assertThat(s).as(name).contains("say plainly when one is **high**");
            assertThat(s).as(name).contains("**carry on with what the user asked**");
            assertThat(s).as(name).contains("must not hold the work back");
            assertThat(s).as(name).contains("only when the user asks for a gate");
        }
    }

    @Test
    void theSkillsLetTheUserDecideTheFixAndTheReasonForAnAccept() throws IOException {
        for (String name : SKILLS) {
            String s = skill(name);
            assertThat(s).as(name).contains("let them pick and give the values");
            assertThat(s).as(name).contains("never write one for them");
            assertThat(s).as(name).contains("--dry-run");
            assertThat(s).as(name).contains("only after the user said yes");
        }
    }

    @Test
    void noSkillTellsTheAgentToStopOnFindings() throws IOException {
        for (String name : SKILLS) {
            String review = skill(name).toLowerCase();
            int from = review.indexOf("## review is advice");
            String section = review.substring(from, review.indexOf("\n## ", from + 5));
            for (String bad :
                    List.of(
                            "do not run until",
                            "stop until",
                            "must fix before",
                            "block the run",
                            "refuse to run"))
                assertThat(section).as(name + ": " + bad).doesNotContain(bad);
        }
    }
}
