package io.github.llm4j.spring;

import io.github.llm4j.agent.Tool;
import io.github.llm4j.agent.tool.EffectContext;
import io.github.llm4j.agent.tool.ToolKind;
import io.github.llm4j.agent.tool.ToolRegistry;
import io.github.llm4j.agent.tools.CalculatorTool;
import io.github.llm4j.agent.tools.CurrentTimeTool;
import io.github.llm4j.agent.tools.DateTimeTool;
import io.github.llm4j.agent.tools.DuckDuckGoSearchTool;
import io.github.llm4j.agent.tools.EchoTool;
import io.github.llm4j.agent.tools.SerpApiSearchTool;
import io.github.llm4j.secret.SecretStore;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * The tools an agent may use. {@link ToolCatalog} gathers every {@code Tool} bean, the built-ins named in {@code llm4j.tools.builtin} and the guarded tools
 * of {@code llm4j.tools.custom}; {@link ToolRegistry} (always present, empty until you add a tool) is filled from it. Nothing is added that you did not name.
 */
@AutoConfiguration(after = {Llm4jSecretsAutoConfiguration.class, Llm4jClientsAutoConfiguration.class})
@ConditionalOnProperty(prefix = "llm4j", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(Llm4jProperties.class)
public class Llm4jToolsAutoConfiguration {

    private static final Map<String, Supplier<Tool>> BUILTINS = Map.of(
            "calculator", CalculatorTool::new, "current-time", CurrentTimeTool::new, "date-time", DateTimeTool::new,
            "echo", EchoTool::new, "duckduckgo", DuckDuckGoSearchTool::new);

    @Bean
    @ConditionalOnMissingBean
    public ToolCatalog llm4jToolCatalog(ListableBeanFactory beans, Llm4jProperties properties, ObjectProvider<SecretStore> secrets, ObjectProvider<EffectContext> effects,
                                        ObjectProvider<ToolKind> extraKinds, Environment environment) {
        ToolCatalog catalog = new ToolCatalog();
        for (Map.Entry<String, Tool> e : beans.getBeansOfType(Tool.class).entrySet()) catalog.add(e.getValue(), "Tool bean '" + e.getKey() + "'");
        List<String> builtin = properties.tools().builtin() == null ? List.of() : properties.tools().builtin();
        for (String name : builtin) catalog.add(builtin(name, secrets.getIfAvailable(), environment), "llm4j.tools.builtin '" + name + "'");
        Map<String, Llm4jProperties.Tools.Custom> custom = properties.tools().custom() == null ? Map.of() : properties.tools().custom();
        if (!custom.isEmpty()) {
            Map<String, ToolKind> kinds = GuardedTools.kinds(extraKinds.orderedStream().toList());
            Path baseDir = properties.tools().baseDir() == null ? Path.of("").toAbsolutePath() : Path.of(properties.tools().baseDir()).toAbsolutePath();
            custom.forEach((name, def) -> catalog.add(GuardedTools.create(name, def, kinds, secrets.getIfAvailable(), effects.getIfAvailable(), baseDir), "llm4j.tools.custom." + name));
        }
        return catalog;
    }

    @Bean
    @ConditionalOnMissingBean
    public ToolRegistry toolRegistry(ToolCatalog catalog) {
        ToolRegistry registry = new ToolRegistry();
        catalog.tools().forEach(registry::register);
        return registry;
    }

    private static Tool builtin(String name, SecretStore secrets, Environment environment) {
        if (name.equals("serpapi")) {
            String key = secrets != null && secrets.contains("SERPAPI_API_KEY") ? secrets.resolve("SERPAPI_API_KEY") : environment.getProperty("SERPAPI_API_KEY");
            if (key == null || key.isBlank()) {
                throw new Llm4jConfigurationException("the built-in tool serpapi needs SERPAPI_API_KEY", "Put SERPAPI_API_KEY in the secret store (llm4j.secrets.store) or the environment.");
            }
            return new SerpApiSearchTool(key);
        }
        Supplier<Tool> make = BUILTINS.get(name);
        if (make == null) {
            throw new Llm4jConfigurationException("llm4j.tools.builtin names a tool that does not exist: " + name,
                    "The built-in tools are: calculator, current-time, date-time, echo, duckduckgo, serpapi.");
        }
        return make.get();
    }
}
