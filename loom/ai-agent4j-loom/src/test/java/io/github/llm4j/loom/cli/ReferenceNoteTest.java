package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.guide.Guide;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

/** What `weave init` copies is a reference to be modified; each template's README and the skill say so. */
class ReferenceNoteTest {

    @Test
    void everyTemplateReadmeSaysItIsAReferenceToBeChanged() throws IOException {
        for (String t : new String[] {"pipeline", "approval", "classifier"}) {
            try (InputStream in = getClass().getClassLoader().getResourceAsStream("templates/" + t + "/README.md")) {
                assertThat(new String(in.readAllBytes())).as(t).contains("This is a reference, not a finished product");
            }
        }
    }

    @Test
    void theSkillTellsTheAgentToChangeWhatItCopies() {
        assertThat(Guide.skill().orElseThrow()).contains("reference to be modified");
    }

    @Test
    void everyTemplateReadmeTellsTheUserToKeepKeysInTheSecretStoreWithCommandsThatExist() throws IOException {
        var root = WeaveCLI.commandLine();
        var secrets = root.getSubcommands().get("secrets");
        for (String t : new String[] {"pipeline", "approval", "classifier"}) {
            try (InputStream in = getClass().getClassLoader().getResourceAsStream("templates/" + t + "/README.md")) {
                String readme = new String(in.readAllBytes());
                assertThat(readme).as(t).contains("Set up your keys").contains("weave secrets create --secrets").contains("weave secrets set GEMINI_API_KEY --secrets")
                        .doesNotContain("in your environment");
                for (String sub : new String[] {"create", "set", "list"}) assertThat(secrets.getSubcommands()).containsKey(sub);
                assertThat(secrets.getSubcommands().get("set").getCommandSpec().optionsMap()).containsKey("--secrets");
                assertThat(root.getSubcommands().get("run").getCommandSpec().optionsMap()).containsKey("--secrets");
                assertThat(root.getSubcommands().get("eval").getCommandSpec().optionsMap()).containsKeys("--secrets", "--secrets-key-env");
            }
        }
    }
}
