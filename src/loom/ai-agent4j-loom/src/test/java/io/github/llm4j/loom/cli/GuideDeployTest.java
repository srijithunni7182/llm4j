package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Chapter 9 tells whoever builds a workflow with built-in tools how to prepare it for deployment: a development entry and a deployed entry over the
 * same workflows, and the names the deployer must supply read from {@code weave check --no-env}. This writes the guide's example and does exactly that.
 */
class GuideDeployTest {

    private static final String CHAPTER = "../../../docs/guide/09-go-live.md";

    private static Path write(Path dir) throws Exception {
        Matcher m = Pattern.compile("```loom file=deploy/(\\S+)\\n(.*?)```", Pattern.DOTALL).matcher(Files.readString(Path.of(CHAPTER)));
        Map<String, String> files = new LinkedHashMap<>();
        while (m.find()) files.put(m.group(1), m.group(2));
        assertThat(files).containsOnlyKeys("flows/digest.loom", "dev.loom", "prod.loom");
        for (var f : files.entrySet()) {
            Path p = dir.resolve(f.getKey());
            Files.createDirectories(p.getParent());
            Files.writeString(p, f.getValue());
        }
        return dir;
    }

    private static JsonNode check(Path script, String... extra) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream old = System.out;
        System.setOut(new PrintStream(out, true));
        try {
            String[] args = new String[4 + extra.length];
            args[0] = "check";
            args[1] = script.toString();
            args[2] = "--format";
            args[3] = "json";
            System.arraycopy(extra, 0, args, 4, extra.length);
            WeaveCLI.commandLine().execute(args);
        } finally {
            System.setOut(old);
        }
        return new ObjectMapper().readTree(out.toString());
    }

    @Test
    void theDevelopmentEntryChecksCleanAndNeedsOnlyTheModelsKey(@TempDir Path dir) throws Exception {
        JsonNode result = check(write(dir).resolve("dev.loom"), "--no-env");

        assertThat(result.path("ok").asBoolean()).as(result.toString()).isTrue();
        assertThat(result.path("diagnostics")).isEmpty();
        TreeSet<String> names = new TreeSet<>();
        result.path("notSetYet").forEach(n -> names.add(n.asText()));
        assertThat(names).containsExactly("GEMINI_API_KEY");
    }

    @Test
    void theDeployedEntryChecksAndListsExactlyTheNamesTheDeployerMustSupply(@TempDir Path dir) throws Exception {
        JsonNode result = check(write(dir).resolve("prod.loom"), "--no-env");

        assertThat(result.path("ok").asBoolean()).as(result.toString()).isTrue();
        TreeSet<String> names = new TreeSet<>();
        result.path("notSetYet").forEach(n -> names.add(n.asText()));
        assertThat(names).containsExactly("DIGEST_TO", "GEMINI_API_KEY", "SMTP_HOST", "SMTP_PASSWORD", "SMTP_USER");
    }

    @Test
    void onTheTargetMachineWithoutNoEnvTheSameEntryFailsUntilTheNamesAreSet(@TempDir Path dir) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getenv("SMTP_HOST") == null && System.getenv("DIGEST_TO") == null);

        JsonNode result = check(write(dir).resolve("prod.loom"));

        assertThat(result.path("ok").asBoolean()).isFalse();
        assertThat(result.toString()).contains("SMTP_HOST is not set");
    }

    @Test
    void theGuideAndTheSkillCoverEveryBuiltInToolAndTheDeploymentSteps() throws Exception {
        String chapter = Files.readString(Path.of(CHAPTER));
        for (String tool : new String[] {"webhook", "email", "http", "file", "shell", "sql"}) assertThat(chapter).contains("| `" + tool + "` |");
        assertThat(chapter).contains("## Built-in tools when deployed (webhook, email, http, file, shell, sql)").contains("Deploying").contains("`--journal <folder>`")
                .contains("`--ask-via telegram`").contains("`unattended: true`").contains("`weave check prod.loom` fails naming every variable that is not set there")
                .contains("a database user that is itself read-only");
        String skill = Files.readString(Path.of("../../../.claude/skills/llm4j-workflow-guide/SKILL.md"));
        assertThat(skill).contains("prepare it for deployment").contains("\"Deploying\" section").contains("**development entry**").contains("**read-only database user**").contains("never the project's `.env`");
    }
}
