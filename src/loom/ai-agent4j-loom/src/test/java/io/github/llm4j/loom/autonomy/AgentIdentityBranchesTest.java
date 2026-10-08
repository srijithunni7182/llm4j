package io.github.llm4j.loom.autonomy;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.agent.prompt.MarkdownFolderPromptRegistry;
import io.github.llm4j.loom.prompt.PromptCatalog;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The corners of an agent's identity: a template's text, a persona or knowledge base that is not the one named, a secret option, a file too big to read whole. */
class AgentIdentityBranchesTest {

    @TempDir Path dir;

    private static String id(String source, Path base, PromptCatalog catalog) {
        var script = DecisionParseTest.parse(source);
        return AgentIdentity.of(script, script.getDecisions().get(0), base, catalog);
    }

    private static final String TAIL = "decision D { proposed by: A choices: x, y }\nworkflow W() { decide D -> v }\n";

    @Test
    void aSystemTemplateIsPartOfTheAgentByTheTextItResolvesTo() throws Exception {
        Files.createDirectories(dir.resolve("prompts"));
        Files.writeString(dir.resolve("prompts/tpl.md"), "Be strict.");
        PromptCatalog catalog = new PromptCatalog(new MarkdownFolderPromptRegistry(dir.resolve("prompts")), Map.of());
        String source = "agent A { model: \"m\" system_template: \"tpl\" }\n" + TAIL;

        String before = id(source, dir, catalog);
        Files.writeString(dir.resolve("prompts/tpl.md"), "Be lenient.");

        assertThat(id(source, dir, new PromptCatalog(new MarkdownFolderPromptRegistry(dir.resolve("prompts")), Map.of()))).isNotEqualTo(before);
    }

    @Test
    void aPersonaOrKnowledgeBaseThatIsNotDeclaredContributesOnlyItsName() {
        String declaredElsewhere = "persona Other { role: \"r\" }\nknowledge Elsewhere { type: \"local\" path: \"kb\" }\n"
                + "agent A { model: \"m\" persona: Library knowledge: [Missing] }\n" + TAIL;
        String another = declaredElsewhere.replace("persona: Library", "persona: Another");

        assertThat(id(declaredElsewhere, dir, null)).hasSize(64).isNotEqualTo(id(another, dir, null));
    }

    @Test
    void aToolOptionReadFromTheSecretStoreContributesItsNameNotItsValue() {
        String fromSecret = "tool T { use: http  url: \"https://x.example\"  api_key: secret.THE_KEY }\nagent A { model: \"m\" tools: [T] }\n" + TAIL;
        String fromEnv = fromSecret.replace("secret.THE_KEY", "env.THE_KEY");

        assertThat(id(fromSecret, dir, null)).isNotEqualTo(id(fromEnv, dir, null));
        assertThat(id(fromSecret, dir, null)).isEqualTo(id(fromSecret, dir, null));
    }

    @Test
    void aFileTooBigToReadWholeCountsBySizeAndStillGivesAnIdentity() throws Exception {
        Path big = dir.resolve("big.md");
        Files.write(big, new byte[8 * 1024 * 1024 + 1]);
        String source = "agent A { model: \"m\" skills: [\"fs://big.md\"] }\n" + TAIL;

        String first = id(source, dir, null);
        Files.write(big, new byte[8 * 1024 * 1024 + 2]);

        assertThat(first).hasSize(64);
        assertThat(id(source, dir, null)).isNotEqualTo(first);
    }

    @Test
    void aKnowledgeBaseWithNoPathStillGivesAnIdentity() {
        String source = "knowledge K { type: \"memory\" }\nagent A { model: \"m\" knowledge: [K] }\n" + TAIL;

        assertThat(id(source, dir, null)).hasSize(64);
    }
}
