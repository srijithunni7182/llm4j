package io.github.llm4j.loom.execution;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.StatementWalker;
import io.github.llm4j.loom.ast.IntentClassifyStmt;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The documentation of classification stays true: every Loom example that classifies parses and passes the load-time checks. */
@Tag("R11")
class ClassifierDocsTest {

    static final Path ROOT = Path.of("../../..").normalize();

    /** The documents that show {@code intentclassify} examples; each must show at least one. */
    static final List<Path> EXAMPLE_DOCS = List.of(
            ROOT.resolve("src/loom/ai-agent4j-loom/LOOM_GUIDE.md"),
            ROOT.resolve("docs/guide/06-build-the-workflow.md"),
            ROOT.resolve("docs/guide/RECIPES.md"));

    private static List<String> classifyBlocks(Path doc) throws Exception {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("```loom\\n(.*?)```", Pattern.DOTALL).matcher(Files.readString(doc));
        while (m.find()) if (m.group(1).contains("intentclassify ")) out.add(m.group(1));
        return out;
    }

    @Test
    void everyDocumentedClassifierExampleParsesAndPassesTheChecks() throws Exception {
        for (Path doc : EXAMPLE_DOCS) {
            List<String> blocks = classifyBlocks(doc);
            assertThat(blocks).as(doc + " shows an intentclassify example").isNotEmpty();
            for (String block : blocks) {
                LoomScript script;
                try {
                    script = new LoomParser(new Lexer(block).tokenize()).parseScript();
                } catch (RuntimeException e) {
                    throw new AssertionError(doc + " has an example that does not parse:\n" + block + "\n→ " + e.getMessage(), e);
                }
                assertThat(StatementWalker.any(script, s -> s instanceof IntentClassifyStmt)).as(doc.toString()).isTrue();
                var problems = new ScriptValidator().validate(script, new ScriptValidator.Context().registeredTools(Set.of()).humanInterface(true));
                assertThat(problems).as(doc + ":\n" + block)
                        .noneMatch(p -> p.severity() == ScriptValidator.Severity.ERROR && !p.message().contains("there is no agent named"));
            }
        }
    }
}
