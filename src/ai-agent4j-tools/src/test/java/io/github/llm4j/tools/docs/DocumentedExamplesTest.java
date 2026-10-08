package io.github.llm4j.tools.docs;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.agent.tool.ToolKind;
import io.github.llm4j.tools.EmailKind;
import io.github.llm4j.tools.FileKind;
import io.github.llm4j.tools.HttpKind;
import io.github.llm4j.tools.ShellKind;
import io.github.llm4j.tools.SqlKind;
import io.github.llm4j.tools.WebhookKind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Java example on each kind's page must be usable: the options it passes to {@code create} have to pass the kind's own
 * {@code check}, so a renamed or removed option can't leave the documentation wrong. Also, every page is linked from the index.
 */
class DocumentedExamplesTest {

    private static final Path DOCS = Path.of("docs");
    private static final Pattern CREATE = Pattern.compile("new (\\w+)Kind\\(\\)\\.create\\(\"\\w+\",\\s*Map\\.of\\((.*?)\\),\\s*Path\\.of", Pattern.DOTALL);

    @TempDir
    Path dir;

    private static ToolKind kind(String name) {
        return switch (name) {
            case "Webhook" -> new WebhookKind();
            case "Email" -> new EmailKind();
            case "Http" -> new HttpKind();
            case "File" -> new FileKind();
            case "Shell" -> new ShellKind();
            case "Sql" -> new SqlKind();
            default -> throw new AssertionError("no kind " + name);
        };
    }

    /** The key/value string pairs of a {@code Map.of(...)} call, with environment reads replaced by a placeholder secret. */
    private static Map<String, String> options(String mapArguments) {
        // An environment read becomes a plausible value: a URL where the variable's name says it is one.
        String text = mapArguments.replaceAll("\"Bearer \"\\s*\\+\\s*System\\.getenv\\(\"[A-Z_]+\"\\)", "\"Bearer placeholder-secret-value\"");
        Matcher env = Pattern.compile("System\\.getenv\\(\"([A-Z_]+)\"\\)").matcher(text);
        StringBuilder replaced = new StringBuilder();
        while (env.find()) {
            String name = env.group(1);
            String value = name.contains("WEBHOOK") ? "https://hooks.example.com/services/placeholder-secret-value"
                    : name.endsWith("_URL") ? "jdbc:h2:mem:docs" : "placeholder-secret-value";
            env.appendReplacement(replaced, Matcher.quoteReplacement("\"" + value + "\""));
        }
        env.appendTail(replaced);
        text = replaced.toString();
        Matcher m = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(text);
        java.util.List<String> literals = new java.util.ArrayList<>();
        while (m.find()) literals.add(m.group(1));
        assertThat(literals.size() % 2).as("an even number of key/value strings in " + mapArguments).isZero();
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < literals.size(); i += 2) out.put(literals.get(i), literals.get(i + 1));
        return out;
    }

    @Test
    void theJavaExampleOnEachKindPageIsAcceptedByThatKind() throws IOException {
        for (String page : new String[] {"webhook", "email", "http", "file", "shell", "sql"}) {
            String text = Files.readString(DOCS.resolve(page + ".md"));
            Matcher m = CREATE.matcher(text);
            assertThat(m.find()).as(page + ".md has a create(...) example").isTrue();
            ToolKind kind = kind(m.group(1));
            assertThat(kind.name()).isEqualTo(page);
            Map<String, String> options = options(m.group(2));
            assertThat(kind.check(options, dir)).as(page + ".md example options " + options).isNull();
        }
    }

    @Test
    void everyPageInTheFolderIsLinkedFromTheIndexAndEveryLinkResolves() throws IOException {
        String index = Files.readString(DOCS.resolve("README.md"));
        try (var pages = Files.list(DOCS)) {
            for (Path p : pages.toList()) {
                String name = p.getFileName().toString();
                if (name.equals("README.md")) continue;
                assertThat(index).as(name + " is linked from docs/README.md").contains("(" + name + ")");
            }
        }
        Matcher links = Pattern.compile("\\]\\(([a-z\\-]+\\.md)(#[^)]*)?\\)").matcher(index);
        while (links.find()) assertThat(DOCS.resolve(links.group(1))).exists();
    }

    @Test
    void theModuleReadmeLinksTheDocumentation() throws IOException {
        String readme = Files.readString(Path.of("README.md"));
        assertThat(readme).contains("docs/README.md");
        for (String kind : new String[] {"webhook", "email", "http", "file", "shell", "sql"}) {
            assertThat(readme).contains("docs/" + kind + ".md");
        }
    }
}
