package io.github.llm4j.spring;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ByteArrayResource;

/** The configuration metadata and the README are checked, not trusted. V13. */
class MetadataAndReadmeTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode metadata() throws IOException {
        try (var in = MetadataAndReadmeTest.class.getResourceAsStream("/META-INF/spring-configuration-metadata.json")) {
            return JSON.readTree(in);
        }
    }

    @Tag("SB-V13.1")
    @Test
    void everyPropertyHasADescription() throws IOException {
        List<String> bare = new ArrayList<>();
        for (JsonNode p : metadata().get("properties")) {
            if (!p.hasNonNull("description") || p.get("description").asText().isBlank()) bare.add(p.get("name").asText());
        }
        assertThat(bare).as("properties without a description").isEmpty();
    }

    @Tag("SB-V13.1")
    @Test
    void everyClosedChoiceHasHintsThatTheStarterAccepts() throws IOException {
        Map<String, List<String>> hints = new LinkedHashMap<>();
        for (JsonNode h : metadata().get("hints")) {
            List<String> values = new ArrayList<>();
            h.get("values").forEach(v -> values.add(v.get("value").asText()));
            hints.put(h.get("name").asText(), values);
        }
        assertThat(hints.keySet()).contains("llm4j.secrets.store", "llm4j.routing.strategy", "llm4j.budget.window", "llm4j.agent.tool-calling", "llm4j.agent.on-budget-exhausted",
                "llm4j.memory.conversations", "llm4j.rag.embedding", "llm4j.rag.store", "llm4j.loom.validate", "llm4j.loom.persistence", "llm4j.loom.initialize-schema",
                "llm4j.loom.executor", "llm4j.loom.audit.fail-on", "llm4j.observe.detail");
        // each hinted value is accepted by the starter: the ones that can be tried without more setup are tried here
        for (String v : hints.get("llm4j.secrets.store")) {
            if (!v.equals("env")) continue;
            Fixtures.runner().withPropertyValues("llm4j.secrets.store=" + v).run(ctx -> assertThat(ctx).hasNotFailed());
        }
        for (String v : hints.get("llm4j.loom.validate")) {
            Fixtures.runner().withBean(io.github.llm4j.loom.execution.LLMClientFactory.class, LoomFixtures.Models::new)
                    .withPropertyValues("llm4j.loom.script=classpath:scripts/three-steps.loom", "llm4j.loom.validate=" + v).run(ctx -> assertThat(ctx).hasNotFailed());
        }
        for (String v : hints.get("llm4j.loom.executor")) {
            Fixtures.runner().withBean(io.github.llm4j.loom.execution.LLMClientFactory.class, LoomFixtures.Models::new)
                    .withBean(java.util.concurrent.Executor.class, () -> Runnable::run)
                    .withPropertyValues("llm4j.loom.script=classpath:scripts/three-steps.loom", "llm4j.loom.executor=" + v).run(ctx -> assertThat(ctx.getStartupFailure() == null ? "" : String.valueOf(ctx.getStartupFailure().getMessage())).doesNotContain("executor"));
        }
        for (String v : hints.get("llm4j.routing.strategy")) {
            if (v.equals("none")) Fixtures.runner().withPropertyValues("llm4j.routing.strategy=none").run(ctx -> assertThat(ctx).hasNotFailed());
        }
        for (String v : hints.get("llm4j.memory.conversations")) {
            Fixtures.runner().withPropertyValues("llm4j.memory.conversations=" + v, "llm4j.memory.dir=" + System.getProperty("java.io.tmpdir")).run(ctx -> assertThat(ctx).hasNotFailed());
        }
    }

    // ---- README ----

    private static final Pattern BLOCK = Pattern.compile("```(yaml|java)\\n(.*?)```", Pattern.DOTALL);

    private record Section(String title, String text, List<String> yaml, List<String> java) { }

    private static List<Section> sections() throws IOException {
        String readme = Files.readString(Path.of("README.md"), StandardCharsets.UTF_8);
        List<Section> out = new ArrayList<>();
        for (String part : readme.split("(?m)^## ")) {
            String title = part.lines().findFirst().orElse("");
            List<String> yaml = new ArrayList<>(), java = new ArrayList<>();
            Matcher m = BLOCK.matcher(part);
            while (m.find()) (m.group(1).equals("yaml") ? yaml : java).add(m.group(2));
            out.add(new Section(title, part, yaml, java));
        }
        return out;
    }

    @Tag("SB-V13.2")
    @Test
    void everyCapabilitySectionShowsYamlABeanAndPlainJava() throws IOException {
        List<String> capabilities = List.of("Clients", "Prices, budgets, privacy and audit", "Agents, memory, prompts and skills", "Tools", "RAG", "Loom");
        Map<String, Section> byTitle = new LinkedHashMap<>();
        for (Section s : sections()) byTitle.put(s.title(), s);
        for (String name : capabilities) {
            Section s = byTitle.get(name);
            assertThat(s).as(name).isNotNull();
            assertThat(s.yaml()).as(name + " yaml").isNotEmpty();
            assertThat(s.text()).as(name + " bean").contains("**Bean:**");
            assertThat(s.text()).as(name + " without Spring").contains("Without Spring:");
            assertThat(s.java()).as(name + " java").isNotEmpty();
        }
        assertThat(byTitle).containsKey("Not in the starter (yet)");
        assertThat(byTitle.get("Not in the starter (yet)").text()).contains("eval4j");
    }

    @Tag("SB-V13.2")
    @Test
    void everyBeanTheReadmeNamesExists() throws Exception {
        Pattern name = Pattern.compile("`(io\\.github\\.llm4j\\.[A-Za-z0-9_.]+)`");
        List<String> missing = new ArrayList<>();
        for (Section s : sections()) {
            for (String line : s.text().split("\n")) {
                if (!line.startsWith("**Bean:**")) continue;
                Matcher m = name.matcher(line);
                while (m.find()) if (!exists(m.group(1))) missing.add(m.group(1));
            }
        }
        assertThat(missing).isEmpty();
    }

    private static boolean exists(String dotted) {
        String candidate = dotted;
        while (true) {
            try {
                Class.forName(candidate, false, MetadataAndReadmeTest.class.getClassLoader());
                return true;
            } catch (ClassNotFoundException e) {
                int at = candidate.lastIndexOf('.');
                if (at < 0) return false;
                candidate = candidate.substring(0, at) + "$" + candidate.substring(at + 1);
            }
        }
    }

    @Tag("SB-V13.2")
    @Test
    void everyYamlBlockBindsAndUsesOnlyPropertiesThatExist() throws Exception {
        Set<String> known = new java.util.HashSet<>();
        for (JsonNode p : metadata().get("properties")) known.add(p.get("name").asText());
        Set<String> maps = Set.of("llm4j.clients", "llm4j.pricing.models", "llm4j.tools.custom", "llm4j.agent.context.compaction-options");
        List<String> unknown = new ArrayList<>();
        int blocks = 0;
        for (Section s : sections()) {
            for (String yaml : s.yaml()) {
                blocks++;
                YamlPropertiesFactoryBean f = new YamlPropertiesFactoryBean();
                f.setResources(new ByteArrayResource(yaml.getBytes(StandardCharsets.UTF_8)));
                var props = f.getObject();
                Map<String, Object> flat = new LinkedHashMap<>();
                props.forEach((k, v) -> flat.put(String.valueOf(k), v));
                for (String key : flat.keySet()) {
                    String bare = key.replaceAll("\\[\\d+]", "");
                    boolean ok = known.contains(bare) || maps.stream().anyMatch(m -> bare.startsWith(m + "."));
                    if (!ok) unknown.add(key + " (" + s.title() + ")");
                }
                StandardEnvironment env = new StandardEnvironment();
                env.getPropertySources().addFirst(new MapPropertySource("readme", flat));
                Llm4jProperties bound = new Binder(ConfigurationPropertySources.get(env)).bind("llm4j", Llm4jProperties.class).get();
                assertThat(bound).as(s.title()).isNotNull();
            }
        }
        assertThat(blocks).isGreaterThanOrEqualTo(7);
        assertThat(unknown).as("settings in the README that do not exist").isEmpty();
    }

    @Tag("SB-V13.2")
    @Test
    void everyJavaBlockCompiles(@TempDir Path out) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        List<String> failures = new ArrayList<>();
        int n = 0;
        for (Section s : sections()) {
            for (String block : s.java()) {
                if (block.contains("@Service") || block.contains("@Component")) continue;
                StringBuilder imports = new StringBuilder(), body = new StringBuilder();
                for (String line : block.split("\n")) (line.startsWith("import ") ? imports : body).append(line).append('\n');
                String name = "Readme" + (n++);
                String source = imports + "class " + name + " { void run() throws Exception {\n" + body + "} }\n";
                DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
                JavaFileObject file = new SimpleJavaFileObject(URI.create("string:///" + name + ".java"), JavaFileObject.Kind.SOURCE) {
                    @Override
                    public CharSequence getCharContent(boolean ignore) {
                        return source;
                    }
                };
                boolean ok = compiler.getTask(null, null, diagnostics, List.of("-classpath", System.getProperty("java.class.path"), "-d", out.toString(), "-proc:none"), null, List.of(file)).call();
                if (!ok) {
                    for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                        if (d.getKind() == Diagnostic.Kind.ERROR) failures.add(s.title() + ": " + d.getMessage(null));
                    }
                }
            }
        }
        assertThat(n).isGreaterThanOrEqualTo(6);
        assertThat(failures).isEmpty();
    }

    @Tag("SB-V13.3")
    @Test
    void theCompatibilityPageListsTheHostApi() throws IOException {
        String page = Files.readString(Path.of("../../../docs/API_COMPATIBILITY.md"));
        assertThat(page).contains("LoomHost", "RunRequest", "RunOutcome", "RunRecord", "ScriptSource");
    }
}
