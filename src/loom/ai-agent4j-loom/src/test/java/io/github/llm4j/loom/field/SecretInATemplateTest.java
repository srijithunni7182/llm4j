package io.github.llm4j.loom.field;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.cli.FieldCli;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * F19: keys were typed into a file meant to be committed. A secret in any template file
 * ({@code .env.example}, {@code *.example}, {@code *.sample}, {@code *.template}) is noticed by
 * name, and the value, a prefix of it or its length is never shown.
 */
class SecretInATemplateTest {
    private static final String FAKE = "AIzaFakeKeyForATest0123456789abcdef";

    @TempDir Path dir;

    private Path project(String templateName, String content) throws Exception {
        Path script = dir.resolve("main.loom");
        Files.writeString(
                script,
                "agent A { model: \"gemini-2.5-flash\" system: \"You are A.\" }\n"
                        + "workflow Main() { delegate \"hi\" to A -> out\n note \"{out}\" }\n");
        Files.writeString(dir.resolve(templateName), content);
        return script;
    }

    private static String all(FieldCli.Result r) {
        return r.out() + "\n" + r.err();
    }

    @Test
    void aKeyInDotEnvExampleIsNamedByCheckAndNeverShown() throws Exception {
        Path script = project(".env.example", "GEMINI_API_KEY=" + FAKE + "\n");

        String text = all(FieldCli.weave("check", script.toString()));

        assertThat(text).contains("GEMINI_API_KEY");
        assertThat(text)
                .doesNotContain(FAKE)
                .doesNotContain(FAKE.substring(0, 6))
                .doesNotContain(FAKE.substring(FAKE.length() - 6))
                .doesNotContainPattern("\\b" + FAKE.length() + "\\b");
    }

    @Test
    void theWarningSaysWhichFileAndHowToMoveTheKeyIntoTheStore() throws Exception {
        Path script = project(".env.example", "GEMINI_API_KEY=" + FAKE + "\n");

        String text = all(FieldCli.weave("check", script.toString()));

        assertThat(text).contains(".env.example").contains("weave secrets import-env");
    }

    @Test
    void otherTemplateFilesAreWatchedToo() throws Exception {
        for (String name : new String[] {"keys.sample", "secrets.template", "config.example"}) {
            Path script = project(name, "OPENAI_API_KEY=" + FAKE + "\n");

            String text = all(FieldCli.weave("check", script.toString()));

            assertThat(text).as(name).contains("OPENAI_API_KEY").contains(name);
            Files.delete(dir.resolve(name));
        }
    }

    @Test
    void anEmptyTemplateAndAnInnocentValueAreLeftAlone() throws Exception {
        Path script =
                project(
                        ".env.example",
                        "GEMINI_API_KEY=\n# a comment\nLOG_LEVEL=debug\nPORT=8080\nSERVICE_NAME=article-flow\n");

        String text = all(FieldCli.weave("check", script.toString()));

        assertThat(text).doesNotContain("is shared with everyone").doesNotContain("import-env");
    }

    @Test
    void aSecretLookingNameWithAValueIsFlaggedButAnOrdinarySettingIsNot() throws Exception {
        Path script =
                project(
                        ".env.example",
                        "APP_PASSWORD=hunter2hunter2\nPORT=8080\nREGION=eu-west-1\n");

        String text = all(FieldCli.weave("check", script.toString()));

        assertThat(text).contains("APP_PASSWORD").doesNotContain("REGION").doesNotContain("PORT");
        assertThat(text).doesNotContain("hunter2");
    }
}
