package io.github.llm4j.spring;

import io.github.llm4j.LLMClient;
import io.github.llm4j.agent.Tool;
import io.github.llm4j.agent.tool.ToolRegistry;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.execution.DefaultLLMClientFactory;
import io.github.llm4j.loom.execution.LLMClientFactory;
import io.github.llm4j.loom.execution.LootLoader;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.secret.SecretStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

/**
 * Wires llm4j's plain Java objects as beans. Each bean is what you would construct yourself, each backs off to a bean of your
 * own, and each appears only when you configured it:
 *
 * <ul>
 *   <li>{@link LLMClient}: when {@code llm4j.provider} is set;
 *   <li>{@link ToolRegistry}: always, holding every {@link Tool} bean;
 *   <li>Loom ({@link LoomScript}, its tool registry, {@link LLMClientFactory}, {@link LoomRuntime}): when ai-agent4j-loom is on the
 *       class path and {@code llm4j.loom.script} is set.
 * </ul>
 *
 * Nothing is scanned, no tool is added that you did not declare, and nothing calls a model at start-up.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "llm4j", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(Llm4jProperties.class)
public class Llm4jAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "llm4j", name = "provider")
    public LLMClient llmClient(Llm4jProperties properties) {
        return LLMClients.create(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public ToolRegistry toolRegistry(ObjectProvider<Tool> tools) {
        ToolRegistry registry = new ToolRegistry();
        tools.orderedStream().forEach(registry::register);
        return registry;
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.github.llm4j.loom.execution.HarnessExecutor")
    static class LoomConfiguration {

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnProperty(prefix = "llm4j.loom", name = "script")
        LoomScript loomScript(Llm4jProperties properties, ResourceLoader resources) throws IOException {
            Resource resource = resources.getResource(properties.getLoom().getScript());
            if (!resource.exists()) throw new IllegalStateException("llm4j.loom.script " + properties.getLoom().getScript() + " does not exist");
            if (resource.isFile()) return new LoomLoader().load(resource.getFile().getPath()); // a file: imports resolve beside it
            try (var in = resource.getInputStream()) {                                          // inside a jar: no imports of other files
                return new LoomParser(new Lexer(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).tokenize()).parseScript();
            }
        }

        /** The tools a script can name: every {@link Tool} bean by its own name, plus what the {@code .loot} file maps. */
        @Bean
        @ConditionalOnMissingBean(io.github.llm4j.loom.execution.ToolRegistry.class)
        @ConditionalOnProperty(prefix = "llm4j.loom", name = "script")
        io.github.llm4j.loom.execution.ToolRegistry loomToolRegistry(Llm4jProperties properties, ObjectProvider<Tool> beans, ResourceLoader resources)
                throws IOException {
            io.github.llm4j.loom.execution.ToolRegistry registry = new io.github.llm4j.loom.execution.ToolRegistry();
            beans.orderedStream().forEach(t -> registry.register(t.getName(), t));
            Path loot = loot(properties, resources);
            if (loot != null) new LootLoader().loadIntoRegistry(loot.toString(), registry);
            return registry;
        }

        /** Keys and endpoints come from the Spring {@code Environment}, so {@code GEMINI_API_KEY} works as an environment variable or a property. */
        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnProperty(prefix = "llm4j.loom", name = "script")
        LLMClientFactory loomModels(Environment environment, ObjectProvider<SecretStore> secrets) {
            return new DefaultLLMClientFactory(environment::getProperty, secrets.getIfAvailable());
        }

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnProperty(prefix = "llm4j.loom", name = "script")
        LoomRuntime loomRuntime(LoomScript script, io.github.llm4j.loom.execution.ToolRegistry tools, LLMClientFactory models,
                                Llm4jProperties properties, ResourceLoader resources, ObjectProvider<LoomExecutorCustomizer> customizers) throws IOException {
            Path loot = loot(properties, resources);
            Map<String, String> reach = loot == null ? Map.of() : LootLoader.reaches(loot);
            List<LoomExecutorCustomizer> list = customizers.orderedStream().toList();
            return new LoomRuntime(script, tools, models, reach, list);
        }

        private static Path loot(Llm4jProperties properties, ResourceLoader resources) throws IOException {
            String location = properties.getLoom().getLoot();
            if (location == null) return null;
            Resource resource = resources.getResource(location);
            if (!resource.exists()) throw new IllegalStateException("llm4j.loom.loot " + location + " does not exist");
            if (resource.isFile()) return resource.getFile().toPath();
            Path copy = Files.createTempFile("llm4j-", ".loot"); // from a jar: loaded through a copy
            copy.toFile().deleteOnExit();
            try (var in = resource.getInputStream()) { Files.write(copy, in.readAllBytes()); }
            return copy;
        }
    }
}
