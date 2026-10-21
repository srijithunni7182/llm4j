package io.github.llm4j.spring;

import io.github.llm4j.LLMClient;
import io.github.llm4j.agent.AgentEventListener;
import io.github.llm4j.agent.ApprovalCallback;
import io.github.llm4j.agent.EffectClassifier;
import io.github.llm4j.agent.ReActAgent;
import io.github.llm4j.agent.RunHook;
import io.github.llm4j.agent.ToolHook;
import io.github.llm4j.agent.memory.ConversationStore;
import io.github.llm4j.agent.memory.FileConversationStore;
import io.github.llm4j.agent.memory.InMemoryConversationStore;
import io.github.llm4j.agent.prompt.FileSystemPromptRegistry;
import io.github.llm4j.agent.prompt.MarkdownFolderPromptRegistry;
import io.github.llm4j.agent.prompt.PromptRegistry;
import io.github.llm4j.agent.skill.AgentSkill;
import io.github.llm4j.agent.skill.ClasspathSkillLoader;
import io.github.llm4j.agent.skill.FileSystemSkillLoader;
import io.github.llm4j.agent.skill.RemoteSkillLoader;
import io.github.llm4j.audit.AuditLogger;
import io.github.llm4j.budget.Budget;
import io.github.llm4j.budget.BudgetPolicy;
import io.github.llm4j.budget.PriceTable;
import io.github.llm4j.budget.TokenEstimator;
import io.github.llm4j.context.ContextCompactor;
import io.github.llm4j.context.ContextCompactors;
import io.github.llm4j.context.ContextPolicy;
import io.github.llm4j.context.ContextWindows;
import io.github.llm4j.context.ProviderContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Scope;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

/**
 * Agents. {@code ReActAgent.Builder} is a prototype bean, not an agent: an agent keeps a
 * conversation, so one shared instance would mix your users' chats. Each injection is a new builder
 * with the client, tools, prompts, context limits, hooks and listeners already applied; call {@code
 * build()} for each conversation. Also here: the conversation store, prompt registry and loaded
 * skills, each only when you configured it.
 */
@AutoConfiguration(after = {Llm4jClientsAutoConfiguration.class, Llm4jToolsAutoConfiguration.class})
@ConditionalOnProperty(prefix = "llm4j", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(Llm4jProperties.class)
public class Llm4jAgentsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "llm4j.memory", name = "conversations")
    public ConversationStore llm4jConversationStore(Llm4jProperties properties) {
        Llm4jProperties.Memory m = properties.memory();
        return switch (m.conversations().trim().toLowerCase(Locale.ROOT)) {
            case "memory" -> new InMemoryConversationStore();
            case "file" -> {
                if (m.dir() == null || m.dir().isBlank())
                    throw new Llm4jConfigurationException(
                            "llm4j.memory.conversations=file needs llm4j.memory.dir",
                            "Give the folder conversations are kept in.");
                yield new FileConversationStore(Path.of(m.dir()));
            }
            default -> throw new Llm4jConfigurationException(
                    "llm4j.memory.conversations=" + m.conversations() + " is not known",
                    "Use memory or file, or declare a ConversationStore bean (a database one, say).");
        };
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "llm4j.prompts", name = "location")
    public PromptRegistry llm4jPromptRegistry(
            Llm4jProperties properties, ResourceLoader resources) {
        String location = properties.prompts().location();
        Path path = onDisk("llm4j.prompts.location", location, resources);
        boolean yaml =
                Files.isRegularFile(path)
                        && (path.toString().endsWith(".yaml") || path.toString().endsWith(".yml"));
        if (!yaml && !Files.isDirectory(path)) {
            throw new Llm4jConfigurationException(
                    "llm4j.prompts.location "
                            + location
                            + " is neither a folder of .md files nor a .yaml file",
                    "Point it at a folder of markdown prompts or a YAML prompts file.");
        }
        return yaml ? new FileSystemPromptRegistry(path) : new MarkdownFolderPromptRegistry(path);
    }

    /**
     * The skills named in {@code llm4j.skills.locations}, loaded: each location is a skill file, or
     * a folder of {@code .md} skill files.
     */
    @Bean
    @ConditionalOnMissingBean(name = "llm4jSkills")
    @ConditionalOnProperty(prefix = "llm4j.skills", name = "locations")
    public LoadedSkills llm4jSkills(Llm4jProperties properties, ResourceLoader resources) {
        List<AgentSkill> skills = new ArrayList<>();
        for (String location : properties.skills().locations()) {
            try {
                if (location.startsWith("http://") || location.startsWith("https://")) {
                    if (!properties.skills().allowRemote()) {
                        throw new Llm4jConfigurationException(
                                "llm4j.skills.locations contains a remote skill ("
                                        + location
                                        + ") and llm4j.skills.allow-remote is false",
                                "Set llm4j.skills.allow-remote=true to load skills from a URL, or copy the skill into your application.");
                    }
                    skills.add(new RemoteSkillLoader().load(location));
                } else if (location.startsWith("classpath:")) {
                    skills.add(
                            new ClasspathSkillLoader()
                                    .load(
                                            location.substring("classpath:".length())
                                                    .replaceFirst("^/", "")));
                } else {
                    Path path = onDisk("llm4j.skills.locations", location, resources);
                    if (Files.isDirectory(path)) {
                        try (Stream<Path> files = Files.list(path)) {
                            for (Path f :
                                    files.filter(p -> p.toString().endsWith(".md"))
                                            .sorted()
                                            .toList())
                                skills.add(new FileSystemSkillLoader().load(f.toString()));
                        }
                    } else {
                        skills.add(new FileSystemSkillLoader().load(path.toString()));
                    }
                }
            } catch (IOException e) {
                throw new Llm4jConfigurationException(
                        "llm4j.skills.locations: "
                                + location
                                + " could not be loaded: "
                                + e.getMessage(),
                        "Check the path.");
            }
        }
        return new LoadedSkills(skills);
    }

    /** The skills loaded from configuration, by name. */
    public record LoadedSkills(List<AgentSkill> all) {
        List<AgentSkill> named(List<String> names, String property) {
            List<AgentSkill> out = new ArrayList<>();
            for (String name : names) {
                out.add(
                        all.stream()
                                .filter(s -> name.equals(s.getName()))
                                .findFirst()
                                .orElseThrow(
                                        () ->
                                                new Llm4jConfigurationException(
                                                        property
                                                                + " names a skill that was not loaded: "
                                                                + name,
                                                        "The loaded skills are: "
                                                                + String.join(
                                                                        ", ",
                                                                        all.stream()
                                                                                .map(
                                                                                        AgentSkill
                                                                                                ::getName)
                                                                                .toList())
                                                                + ".")));
            }
            return out;
        }
    }

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    @ConditionalOnMissingBean
    @ConditionalOnBean(LLMClient.class)
    public ReActAgent.Builder reActAgentBuilder(
            LLMClient primary,
            ObjectProvider<LLMClients> clients,
            ToolCatalog tools,
            Llm4jProperties properties,
            ObjectProvider<PromptRegistry> prompts,
            ObjectProvider<LoadedSkills> skills,
            ObjectProvider<AuditLogger> audit,
            ObjectProvider<Budget> budget,
            ObjectProvider<PriceTable> prices,
            ObjectProvider<TokenEstimator> estimator,
            ObjectProvider<Clock> clock,
            ObjectProvider<ContextPolicy> contextPolicy,
            ObjectProvider<ContextCompactor> compactor,
            ObjectProvider<AgentEventListener> listeners,
            ObjectProvider<RunHook> runHooks,
            ObjectProvider<ToolHook> toolHooks,
            ObjectProvider<ApprovalCallback> approval,
            ObjectProvider<EffectClassifier> effects,
            ObjectProvider<ReActAgentBuilderCustomizer> customizers) {
        Llm4jProperties.Agent a = properties.agent();
        LLMClient client = a.client() == null ? primary : clients.getObject().get(a.client());
        ReActAgent.Builder b = ReActAgent.builder().llmClient(client);
        b.addTools(
                a.tools() == null ? tools.tools() : tools.select(a.tools(), "llm4j.agent.tools"));
        if (a.systemPrompt() != null && a.systemPromptId() != null) {
            throw new Llm4jConfigurationException(
                    "llm4j.agent.system-prompt and llm4j.agent.system-prompt-id are both set",
                    "Set one: the text itself, or the id of a prompt in llm4j.prompts.location.");
        }
        if (a.systemPrompt() != null) b.systemPrompt(a.systemPrompt());
        if (a.systemPromptId() != null) {
            PromptRegistry registry = prompts.getIfAvailable();
            if (registry == null)
                throw new Llm4jConfigurationException(
                        "llm4j.agent.system-prompt-id is set but there is no prompt registry",
                        "Set llm4j.prompts.location, or declare a PromptRegistry bean.");
            b.promptRegistry(registry).systemPromptId(a.systemPromptId());
        }
        if (a.instructions() != null) b.instructions(a.instructions());
        if (a.maxIterations() != null) b.maxIterations(a.maxIterations());
        if (a.temperature() != null) b.temperature(a.temperature());
        if (a.toolCalling() != null)
            b.toolCalling(
                    enumOf(
                            ReActAgent.ToolCalling.class,
                            "llm4j.agent.tool-calling",
                            a.toolCalling()));
        if (a.streaming() != null) b.streaming(a.streaming());
        if (a.reasoning() != null) b.reasoning(a.reasoning());
        if (a.validateToolArguments() != null) b.validateToolArguments(a.validateToolArguments());
        if (a.maxTokensPerCall() != null) b.maxTokensPerCall(a.maxTokensPerCall());
        if (a.onBudgetExhausted() != null)
            b.onBudgetExhausted(
                    enumOf(
                            BudgetPolicy.class,
                            "llm4j.agent.on-budget-exhausted",
                            a.onBudgetExhausted()));
        if (a.skills() != null && !a.skills().isEmpty()) {
            LoadedSkills loaded = skills.getIfAvailable();
            if (loaded == null)
                throw new Llm4jConfigurationException(
                        "llm4j.agent.skills is set but no skills are loaded",
                        "Set llm4j.skills.locations.");
            b.skills(loaded.named(a.skills(), "llm4j.agent.skills"));
        }
        context(
                b,
                a.context(),
                clients,
                primary,
                contextPolicy.getIfAvailable(),
                compactor.getIfAvailable());
        audit.ifAvailable(b::auditLogger);
        budget.ifAvailable(b::budget);
        prices.ifAvailable(b::priceTable);
        estimator.ifAvailable(b::tokenEstimator);
        clock.ifAvailable(b::clock);
        approval.ifAvailable(b::approvalCallback);
        effects.ifAvailable(b::effectClassifier);
        listeners.orderedStream().forEach(b::addListener);
        runHooks.orderedStream().forEach(b::addRunHook);
        toolHooks.orderedStream().forEach(b::addToolHook);
        customizers.orderedStream().forEach(c -> c.customize(b));
        return b;
    }

    private static void context(
            ReActAgent.Builder b,
            Llm4jProperties.Agent.Context c,
            ObjectProvider<LLMClients> clients,
            LLMClient primary,
            ContextPolicy policy,
            ContextCompactor compactor) {
        if (policy != null) b.contextPolicy(policy);
        else if (c.window() != null) b.contextWindow(c.window());
        if (c.windowsFile() != null) {
            try {
                b.contextWindows(ContextWindows.load(Path.of(c.windowsFile())));
            } catch (IOException e) {
                throw new Llm4jConfigurationException(
                        "llm4j.agent.context.windows-file "
                                + c.windowsFile()
                                + " could not be read: "
                                + e.getMessage(),
                        "Check the path.");
            }
        }
        if (compactor != null) b.compaction(compactor);
        else if (c.compaction() != null) b.compaction(compactor(c, clients.getObject(), primary));
    }

    /**
     * The compaction strategy named in {@code llm4j.agent.context.compaction}. The library spells
     * strategies and options with underscores ({@code drop_oldest}, {@code max_chars}); YAML reads
     * better with dashes, so either is taken. {@code summarize} writes its summaries with {@code
     * compaction-client}, else the primary.
     */
    static ContextCompactor compactor(
            Llm4jProperties.Agent.Context c, LLMClients clients, LLMClient primary) {
        LLMClient forSummary =
                c.compactionClient() == null ? primary : clients.get(c.compactionClient());
        Map<String, String> options = new LinkedHashMap<>();
        if (c.compactionOptions() != null)
            c.compactionOptions().forEach((k, v) -> options.put(k.replace('-', '_'), v));
        try {
            return ContextCompactors.byName(
                    c.compaction().replace('-', '_'), options, new ProviderContext(forSummary));
        } catch (IllegalArgumentException e) {
            throw new Llm4jConfigurationException(
                    "llm4j.agent.context.compaction: " + e.getMessage(),
                    "Use one of the strategies the message lists (dashes or underscores), with the options it accepts.");
        }
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, String property, String value) {
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            throw new Llm4jConfigurationException(
                    property + "=" + value + " is not known",
                    "Use one of: "
                            + String.join(
                                    ", ",
                                    java.util.Arrays.stream(type.getEnumConstants())
                                            .map(x -> x.name().toLowerCase(Locale.ROOT))
                                            .toList())
                            + ".");
        }
    }

    /** A location ({@code file:} or {@code classpath:}) that is a file or folder on disk. */
    static Path onDisk(String property, String location, ResourceLoader resources) {
        Resource resource = resources.getResource(location);
        if (!resource.exists())
            throw new Llm4jConfigurationException(
                    property + " " + location + " does not exist", "Check the path.");
        try {
            return resource.getFile().toPath();
        } catch (IOException e) {
            throw new Llm4jConfigurationException(
                    property + " " + location + " is inside an archive, not on disk",
                    "Use a file: location, or let the application be run from an exploded layout.");
        }
    }
}
