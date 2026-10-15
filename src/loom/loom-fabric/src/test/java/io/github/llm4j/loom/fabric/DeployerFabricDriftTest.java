package io.github.llm4j.loom.fabric;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/** R45: every Fabric command and option the deployer skill and its chapters name exists in the server's command line. */
class DeployerFabricDriftTest {

    private static final Path REPO = Path.of("../../..");

    private static String everything() throws Exception {
        StringBuilder b = new StringBuilder(Files.readString(REPO.resolve(".claude/skills/llm4j-loom-deployer/SKILL.md"))).append('\n');
        try (Stream<Path> s = Files.list(REPO.resolve("docs/deploy"))) {
            for (Path p : s.filter(f -> f.toString().endsWith(".md")).sorted().toList()) b.append(Files.readString(p)).append('\n');
        }
        return b.toString();
    }

    @Test
    void everyFabricCommandAndOptionNamedExists() throws Exception {
        CommandLine root = FabricCLI.commandLine();
        List<String> missing = new ArrayList<>();
        int seen = 0;
        Pattern line = Pattern.compile("(?:java -jar \\S*fabric\\.jar|fabric) ((?:init|serve|clients|tls|admin)\\b[^`\\n]*)");
        for (String text : everything().split("\n")) {
            Matcher m = line.matcher(text);
            while (m.find()) {
                seen++;
                String[] words = m.group(1).split("\\s+");
                CommandLine sub = root.getSubcommands().get(words[0]);
                if (sub == null) { missing.add(words[0]); continue; }
                int i = 1;
                while (i < words.length && sub.getSubcommands().containsKey(words[i])) sub = sub.getSubcommands().get(words[i++]);
                for (String w : words) {
                    if (!w.startsWith("--")) continue;
                    String opt = w.contains("=") ? w.substring(0, w.indexOf('=')) : w;
                    if (!sub.getCommandSpec().optionsMap().containsKey(opt)) missing.add(words[0] + " " + opt);
                }
            }
        }
        assertThat(seen).as("the chapters name Fabric commands").isGreaterThanOrEqualTo(3);
        assertThat(missing).isEmpty();
    }

    @Test
    void theServeOptionsTheScriptsAndChaptersPassAreReal() {
        CommandLine serve = FabricCLI.commandLine().getSubcommands().get("serve");
        for (String o : List.of("--data", "--port", "--bind", "--trust-proxy", "--tls-cert", "--tls-key", "--tls-keystore", "--tls-password-file", "--master-key-file", "--retention-days", "--retention-size")) {
            assertThat(serve.getCommandSpec().optionsMap()).as(o).containsKey(o);
        }
    }
}
