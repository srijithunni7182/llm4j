package io.github.llm4j.loom.depth;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** DOC-*: the secret store documentation stays true: its Loom examples parse, its links resolve, and it states who owns the path and key. */
class SecretDocsTest {

    static final Path ROOT = Path.of("../..").normalize();
    static final Path WIKI = ROOT.resolve("ai-agent4j/wiki/Secret-Store.md");
    static final Path GUIDE = ROOT.resolve("loom/ai-agent4j-loom/LOOM_GUIDE.md");

    private static String read(Path p) throws Exception {
        return Files.readString(p);
    }

    @Test
    void docs_loomExamplesWithSecretReferencesParse() throws Exception {
        int seen = 0;
        for (Path doc : List.of(WIKI, GUIDE)) {
            Matcher m = Pattern.compile("```(\\w*)\\n(.*?)```", Pattern.DOTALL).matcher(read(doc));
            while (m.find()) {
                String lang = m.group(1);
                String block = m.group(2);
                if (!(lang.isEmpty() || lang.equals("loom")) || !block.contains("secret.") || !block.matches("(?s).*\\b(provider|tool)\\s+\\w+\\s*\\{.*")) continue;
                seen++;
                new LoomParser(new Lexer(block).tokenize()).parseScript();
            }
        }
        assertThat(seen).isGreaterThanOrEqualTo(2);
    }

    @Test
    void docs_stateWhoOwnsThePathAndTheKey() throws Exception {
        String wiki = read(WIKI);
        assertThat(wiki).contains("no default location").contains("no default key source").contains("ACL-protected").contains("never an argument");
        assertThat(read(GUIDE)).contains("There is no default location").contains("never passed as arguments");
    }

    @Test
    void docs_linksToTheWikiResolve() throws Exception {
        for (Path doc : List.of(GUIDE, ROOT.resolve("docs/guide/09-go-live.md"))) {
            Matcher m = Pattern.compile("\\]\\(([^)#]*Secret-Store\\.md)").matcher(read(doc));
            assertThat(m.find()).as(doc + " links to the secret store page").isTrue();
            String link = m.group(1);
            String web = "https://github.com/srijithunni7182/llm4j/blob/main/"; // the guide links by web address so it reads the same inside the jar
            assertThat(link.startsWith(web) ? ROOT.resolve(link.substring(web.length())) : doc.getParent().resolve(link).normalize()).exists();
        }
        assertThat(read(ROOT.resolve("ai-agent4j/wiki/Home.md"))).contains("Secret-Store.md");
        assertThat(read(ROOT.resolve("llms.txt"))).contains("Secret-Store.md");
        List<String> names = new ArrayList<>();
        for (String c : List.of("create", "set", "list", "remove", "import-env", "rekey")) names.add(c);
        assertThat(read(WIKI)).contains(names.stream().map(n -> "weave secrets " + n).toArray(String[]::new));
    }
}
