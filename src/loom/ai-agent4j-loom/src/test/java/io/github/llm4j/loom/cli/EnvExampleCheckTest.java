package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A key written into the committed .env.example is flagged by name; empty values and placeholders
 * are not.
 */
class EnvExampleCheckTest {

    @TempDir Path dir;

    private Path project(String envExample) throws Exception {
        Files.writeString(dir.resolve("pom.xml"), "<project/>");
        Path script = dir.resolve("src/main/resources/main.loom");
        Files.createDirectories(script.getParent());
        Files.writeString(script, "workflow Main(text) {\n    note \"{text}\"\n}\n");
        Files.writeString(dir.resolve(".env.example"), envExample);
        return script;
    }

    @Test
    void realLookingValueIsFlaggedByNameOnly() throws Exception {
        Path script =
                project(
                        "# keys\nOPENAI_API_KEY=sk-live-abcdef123456\nEMPTY=\nOTHER=your-key-here\n");
        assertThat(EnvExampleCheck.namesWithValues(script)).containsExactly("OPENAI_API_KEY");
        assertThat(EnvExampleCheck.warning(EnvExampleCheck.findings(script)))
                .contains("OPENAI_API_KEY")
                .doesNotContain("sk-live");
    }

    @Test
    void aUrlThatCarriesACredentialIsFlaggedWhateverItsNameIs() throws Exception {
        Path script =
                project(
                        "DATABASE_URL=postgres://user:realpass@db.internal/app\n"
                                + "SMTP_URL=smtps://u:p@mail.internal\n"
                                + "API_URL=https://api.internal/v1\n"
                                + "PLACEHOLDER_URL=postgres://user:<password>@host/db\n"
                                + "NO_PASSWORD_URL=postgres://host/db\n");
        assertThat(EnvExampleCheck.namesWithValues(script))
                .containsExactly("DATABASE_URL", "SMTP_URL");
        assertThat(EnvExampleCheck.warning(EnvExampleCheck.findings(script)))
                .doesNotContain("realpass");
    }

    @Test
    void aUrlWhosePasswordIsAReferenceToAVariableIsNotASecret() throws Exception {
        Path script =
                project(
                        "DATABASE_URL=postgres://app:${DB_PASSWORD}@db/app\n"
                                + "CACHE_URL=redis://app:$CACHE_PASS@cache:6379\n"
                                + "REAL_URL=postgres://app:hunter2@db/app\n");
        assertThat(EnvExampleCheck.namesWithValues(script)).containsExactly("REAL_URL");
    }

    @Test
    void templateWithEmptyValuesIsQuiet() throws Exception {
        assertThat(
                        EnvExampleCheck.namesWithValues(
                                project("OPENAI_API_KEY=\nANTHROPIC_API_KEY=\n")))
                .isEmpty();
    }
}
