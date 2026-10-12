package io.github.llm4j.loom.invoker;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.ScriptValidator;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.secret.CredentialSource;
import io.github.llm4j.secret.InvokerCredentials;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The documentation of invoker credentials stays true: its examples load, and what it names exists. */
class InvokerDocsTest {

    private static final Path ROOT = Path.of("../../..").normalize();
    private static final Path GUIDE = ROOT.resolve("src/loom/ai-agent4j-loom/LOOM_GUIDE.md");
    private static final Path PROMPT = ROOT.resolve("src/loom/ai-agent4j-loom/LOOM_PROMPT.md");
    private static final Path WIKI = ROOT.resolve("src/ai-agent4j/wiki/OpenAPI-Tool.md");

    private static String read(Path p) throws Exception {
        return Files.readString(p);
    }

    @Test
    @Tag("CC-V7.1")
    void theGuideExplainsRunningAsTheInvoker() throws Exception {
        String guide = read(GUIDE);

        assertThat(guide).contains("### Running as the person who runs the workflow")
                .contains("auth_value: invoker.token")
                .contains("on every request")
                .contains("MissingInvokerCredentialException")
                .contains("`InvokerCredentials`")
                .contains("IllegalStateException")
                .contains("--invoker-env token=MY_API_TOKEN")
                .contains("`LA17`")
                .contains("`approve:` needs someone to ask");
        assertThat(guide).contains("(#running-as-the-person-who-runs-the-workflow)");
    }

    @Test
    @Tag("CC-V7.2")
    void thePromptTheWikiAndTheIndexesMentionIt() throws Exception {
        assertThat(read(PROMPT)).contains("auth_value: invoker.token");
        assertThat(read(WIKI)).contains("CredentialSource").contains("rejected()").contains("`auth_value: invoker.token`").contains("only sent over `https://`");
        assertThat(read(ROOT.resolve("llms.txt"))).contains("invoker.NAME").contains("InvokerCredentials");
        assertThat(read(ROOT.resolve("src/ai-agent4j/llms.txt"))).contains("CredentialSource");
    }

    private static List<String> examples(Path doc) throws Exception {
        List<String> examples = new ArrayList<>();
        Matcher m = Pattern.compile("```(\\w*)\\n(.*?)```", Pattern.DOTALL).matcher(read(doc));
        while (m.find()) {
            if ((m.group(1).isEmpty() || m.group(1).equals("loom")) && m.group(2).contains("invoker.")) examples.add(m.group(2));
        }
        return examples;
    }

    @Test
    @Tag("CC-V7.3")
    void everyLoomExampleInTheGuideThatUsesInvokerLoadsWithoutProblems() throws Exception {
        List<String> examples = examples(GUIDE);
        assertThat(examples).isNotEmpty();
        for (String example : examples) {
            var script = new LoomParser(new Lexer(example).tokenize()).parseScript();
            HarnessExecutor executor = new HarnessExecutor(script, new ToolRegistry(), model -> null);
            executor.setEnvLookup(Map.<String, String>of()::get);
            executor.setHumanInterface(message -> "yes"); // a host that uses approve: supplies one
            var problems = new ScriptValidator().validate(script, executor.validationContext()).stream()
                    .filter(p -> p.severity() == ScriptValidator.Severity.ERROR).toList();
            assertThat(problems).as(example).isEmpty();
        }
    }

    @Test
    @Tag("CC-V7.3")
    void thePromptsExampleParses() throws Exception {
        List<String> examples = examples(PROMPT);
        assertThat(examples).isNotEmpty();
        for (String example : examples) new LoomParser(new Lexer(example).tokenize()).parseScript();
    }

    @Test
    @Tag("CC-V7.3")
    void theTypesTheDocumentationShowExist() throws Exception {
        assertThat(InvokerCredentials.class.getMethod("get", String.class)).isNotNull();
        assertThat(InvokerCredentials.class.getMethod("invalidate", String.class)).isNotNull();
        assertThat(InvokerCredentials.class.getMethod("provides", String.class)).isNotNull();
        assertThat(CredentialSource.class.getMethod("get")).isNotNull();
        assertThat(CredentialSource.class.getMethod("rejected")).isNotNull();
        assertThat(HarnessExecutor.class.getMethod("executeWorkflow", String.class, Map.class, InvokerCredentials.class)).isNotNull();
    }
}
