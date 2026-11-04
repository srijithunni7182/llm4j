package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.github.llm4j.loom.init.TemplateWriter;
import io.github.llm4j.loom.init.Templates;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R8 of docs/specs/28-evaluation-first: the CI file a new project gets runs the three costs safely. */
class CiTemplateTest {

    @TempDir Path dir;

    private static final List<String> TEMPLATES = List.of("pipeline", "approval", "classifier", "empty");

    private String text(String template) throws Exception {
        try (var in = CiTemplateTest.class.getResourceAsStream("/templates/" + template + "/dot-github-workflow.yml")) {
            assertThat(in).as(template + " has a CI file").isNotNull();
            return new String(in.readAllBytes());
        }
    }

    private JsonNode yaml(String template) throws Exception {
        return new ObjectMapper(new YAMLFactory()).readTree(text(template));
    }

    @Test
    void everyTemplateCarriesItAndItIsWrittenToTheRightPlace() throws Exception {
        assertThat(TemplateWriter.targetName("dot-github-workflow.yml")).isEqualTo(".github/workflows/weave.yml");
        for (String t : TEMPLATES) {
            assertThat(Templates.find(t).orElseThrow().files()).contains("dot-github-workflow.yml");
            assertThat(yaml(t).get("jobs")).isNotNull();
        }
    }

    @Test
    void theFreeChecksRunOnEveryPush() throws Exception {
        for (String t : TEMPLATES) {
            JsonNode doc = yaml(t);
            assertThat(doc.get("on").has("push")).isTrue();
            String free = doc.get("jobs").get("free-checks").toString();
            assertThat(free).contains("weave check").contains("weave test").contains("weave review main.loom --fail-on high");
        }
    }

    @Test
    void theRealEvaluationRunsOnPullRequestsOnlyWithACapAndSkipsWithoutAKey() throws Exception {
        for (String t : TEMPLATES) {
            JsonNode job = yaml(t).get("jobs").get("changed-evaluation");
            assertThat(job.get("if").asText()).contains("pull_request");
            String steps = job.get("steps").toString();
            assertThat(steps).contains("weave eval main.loom --changed").contains("--max-tokens");
            assertThat(steps).contains("No key is configured").contains("exit 0");
        }
    }

    @Test
    void theScheduledFullRunIsWrittenButCommentedOutSoATemplateNeverSpendsMoneyOnItsOwn() throws Exception {
        for (String t : TEMPLATES) {
            JsonNode doc = yaml(t);
            assertThat(doc.get("on").has("schedule")).isFalse();
            assertThat(doc.get("jobs").has("full-evaluation")).isFalse();
            assertThat(text(t)).contains("# schedule:").contains("# full-evaluation:");
        }
    }

    @Test
    void weaveInitWritesTheFile() throws Exception {
        Path out = dir.resolve("p");
        assertThat(WeaveCLI.commandLine().execute("init", "empty", out.toString())).isZero();
        assertThat(Files.readString(out.resolve(".github/workflows/weave.yml"))).contains("name: weave");
    }
}
