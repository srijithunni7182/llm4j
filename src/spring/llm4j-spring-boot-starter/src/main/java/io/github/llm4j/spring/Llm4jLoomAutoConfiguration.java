package io.github.llm4j.spring;

import io.github.llm4j.agent.prompt.PromptRegistry;
import io.github.llm4j.agent.task.Task;
import io.github.llm4j.agent.task.TaskRegistry;
import io.github.llm4j.audit.AuditLogger;
import io.github.llm4j.budget.PriceTable;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.autonomy.Ledger;
import io.github.llm4j.loom.autonomy.LevelStore;
import io.github.llm4j.loom.context.WindowLayers;
import io.github.llm4j.loom.execution.DefaultLLMClientFactory;
import io.github.llm4j.loom.execution.LLMClientFactory;
import io.github.llm4j.loom.execution.LoomLoadException;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.execution.LootLoader;
import io.github.llm4j.loom.host.LoomHost;
import io.github.llm4j.loom.host.Persistence;
import io.github.llm4j.loom.host.QuestionListener;
import io.github.llm4j.loom.knowledge.EmbeddingFactory;
import io.github.llm4j.loom.memory.MemoryEngine;
import io.github.llm4j.loom.observe.ObserveProvider;
import io.github.llm4j.loom.runtime.HumanInterface;
import io.github.llm4j.loom.security.AuditReport;
import io.github.llm4j.loom.security.SecurityAudit;
import io.github.llm4j.loom.security.Severity;
import io.github.llm4j.ratelimit.Sleeper;
import io.github.llm4j.secret.SecretStore;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.EmbeddedDatabaseConnection;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

/**
 * Loom: {@link LoomRuntime} over a {@link LoomHost}, created when {@code llm4j.loom.script} names a
 * script. At start-up it loads the script (and what it imports, and its {@code .loot} file, from
 * disk or from inside your jar), checks that it can run here, runs the same security audit as
 * {@code weave audit}, and refuses a script that waits for a person, a timer or a limit when there
 * is nowhere to remember the run ({@code llm4j.loom.persistence}). It calls no model.
 */
@AutoConfiguration(
        after = {
            Llm4jSecretsAutoConfiguration.class,
            Llm4jClientsAutoConfiguration.class,
            Llm4jToolsAutoConfiguration.class,
            Llm4jAgentsAutoConfiguration.class,
            Llm4jRagAutoConfiguration.class
        })
@ConditionalOnClass(name = "io.github.llm4j.loom.host.LoomHost")
@ConditionalOnProperty(prefix = "llm4j", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(Llm4jProperties.class)
public class Llm4jLoomAutoConfiguration {

    private static final Logger log = Logger.getLogger(Llm4jLoomAutoConfiguration.class.getName());
    private static final AtomicInteger THREADS = new AtomicInteger();

    /**
     * The deterministic tasks {@code run} steps may use: every {@code Task} bean, plus those on the
     * class path unless {@code llm4j.tasks.discover=false}.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "llm4j.loom", name = "script")
    public TaskRegistry llm4jTaskRegistry(ObjectProvider<Task> beans, Llm4jProperties properties) {
        TaskRegistry registry =
                properties.tasks().discover() ? TaskRegistry.discovered() : new TaskRegistry();
        for (Task task : beans.orderedStream().toList()) {
            try {
                registry.register(task);
            } catch (IllegalArgumentException e) {
                throw new Llm4jConfigurationException(
                        "Task bean " + task.getClass().getSimpleName() + ": " + e.getMessage(),
                        "Give each task a unique name; a task is never replaced silently.");
            }
        }
        return registry;
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "llm4j.loom", name = "script")
    public LoomRuntime loomRuntime(
            Llm4jProperties properties,
            ResourceLoader resources,
            Environment environment,
            BeanFactory beans,
            ToolCatalog tools,
            TaskRegistry tasks,
            ObjectProvider<LLMClientFactory> models,
            ObjectProvider<SecretStore> secrets,
            ObjectProvider<PromptRegistry> prompts,
            ObjectProvider<PriceTable> prices,
            ObjectProvider<AuditLogger> audit,
            ObjectProvider<Clock> clock,
            ObjectProvider<Sleeper> sleeper,
            ObjectProvider<EmbeddingFactory> embeddings,
            ObjectProvider<MemoryEngine> memory,
            ObjectProvider<HumanInterface> human,
            ObjectProvider<QuestionListener> questionBeans,
            ObjectProvider<LoomExecutorCustomizer> customizers,
            ObjectProvider<Ledger> ledger,
            ObjectProvider<LevelStore> levels,
            ObjectProvider<ObserveProvider.Session> observe,
            ObjectProvider<DataSource> dataSources,
            ApplicationEventPublisher events)
            throws IOException {
        Llm4jProperties.Loom l = properties.loom();
        String location = l.script();
        refuseRemote("llm4j.loom.script", location);
        Resource scriptResource = resources.getResource(location);
        if (!scriptResource.exists())
            throw new Llm4jConfigurationException(
                    "llm4j.loom.script " + location + " does not exist",
                    "Point it at a .loom file (classpath: or file:).");
        boolean onDisk = scriptResource.isFile();
        String scriptRef = onDisk ? scriptResource.getFile().getAbsolutePath() : location;
        Path baseDir =
                onDisk
                        ? scriptResource.getFile().getAbsoluteFile().getParentFile().toPath()
                        : (l.dir() != null ? Path.of(l.dir()) : Path.of("").toAbsolutePath());

        LoomScript script;
        try {
            script = new LoomLoader().load(location, new ResourceScriptSource(resources));
        } catch (RuntimeException | IOException e) {
            throw new Llm4jConfigurationException(
                    "llm4j.loom.script " + location + " could not be loaded: " + e.getMessage(),
                    "Fix the script (weave check " + scriptRef + " shows the same).");
        }

        io.github.llm4j.loom.execution.ToolRegistry loomTools =
                new io.github.llm4j.loom.execution.ToolRegistry();
        tools.tools().forEach(t -> loomTools.register(t.getName(), t));
        Map<String, String> reach = Map.of();
        if (l.loot() != null) {
            refuseRemote("llm4j.loom.loot", l.loot());
            Resource loot = resources.getResource(l.loot());
            if (!loot.exists())
                throw new Llm4jConfigurationException(
                        "llm4j.loom.loot " + l.loot() + " does not exist",
                        "Point it at a .loot file (tool name = class), or remove it.");
            try (Reader in = new InputStreamReader(loot.getInputStream(), StandardCharsets.UTF_8)) {
                new LootLoader().loadIntoRegistry(in, loomTools);
            }
            try (Reader in = new InputStreamReader(loot.getInputStream(), StandardCharsets.UTF_8)) {
                reach = LootLoader.reaches(in);
            }
        }

        Persistence persistence = persistence(l, environment, beans, dataSources);
        HostRunner runner = runner(l, beans);
        List<QuestionListener> listenerBeans = questionBeans.orderedStream().toList();
        LoomHost.Builder builder =
                LoomHost.builder(
                                script,
                                loomTools,
                                models.getIfAvailable(
                                        () ->
                                                new DefaultLLMClientFactory(
                                                        environment::getProperty,
                                                        secrets.getIfAvailable())))
                        .persistence(persistence)
                        .scriptRef(scriptRef)
                        .baseDir(baseDir)
                        .envLookup(environment::getProperty)
                        .taskRegistry(tasks)
                        .toolReach(reach)
                        .lenient(l.validate().equalsIgnoreCase("lenient"))
                        .allowNonDurable(l.allowNonDurable())
                        .runner(runner)
                        .questionListener(
                                (run, step, prompt, choices) -> {
                                    events.publishEvent(
                                            new HumanQuestionEvent(run, step, prompt, choices));
                                    listenerBeans.forEach(b -> b.asked(run, step, prompt, choices));
                                });
        if (onDisk) builder.scriptFile(scriptResource.getFile().toPath());
        secrets.ifAvailable(builder::secretStore);
        prompts.ifAvailable(builder::promptRegistry);
        prices.ifAvailable(builder::priceTable);
        audit.ifAvailable(builder::auditLogger);
        clock.ifAvailable(builder::clock);
        sleeper.ifAvailable(builder::sleeper);
        embeddings.ifAvailable(builder::embeddingFactory);
        memory.ifAvailable(builder::memoryEngine);
        human.ifAvailable(builder::humanInterface);
        observe.ifAvailable(builder::observe);
        Ledger userLedger = ledger.getIfAvailable();
        LevelStore userLevels = levels.getIfAvailable();
        if (userLedger != null && userLevels != null) builder.autonomy(userLedger, userLevels);
        Llm4jProperties.Loom.LoomBudget b = l.budget();
        if (b.tokens() != null || b.calls() != null || b.cost() != null) {
            builder.budget(
                    b.tokens(),
                    b.calls(),
                    b.cost() == null
                            ? null
                            : new BigDecimal(b.cost().strip().replaceFirst("^\\$", "")));
        }
        if (l.context().windowsFiles() != null && !l.context().windowsFiles().isEmpty()) {
            builder.contextWindows(windows(l.context().windowsFiles(), resources));
        }
        List<LoomExecutorCustomizer> userCustomizers = customizers.orderedStream().toList();
        if (!userCustomizers.isEmpty())
            builder.customizer(ex -> userCustomizers.forEach(c -> c.customize(ex)));

        LoomHost host;
        try {
            host = builder.build();
        } catch (IllegalStateException e) {
            runner.close();
            throw new Llm4jConfigurationException(
                    e.getMessage(),
                    "Set llm4j.loom.persistence to file or jdbc (and llm4j.loom.dir or a DataSource), or set llm4j.loom.allow-non-durable=true to accept that a run which would wait fails instead.");
        }
        try {
            host.validate();
        } catch (LoomLoadException e) {
            host.close();
            runner.close();
            if (!l.validate().equalsIgnoreCase("lenient")) {
                throw new Llm4jConfigurationException(
                        "the Loom script " + scriptRef + " cannot run:\n" + e.getMessage(),
                        "Fix the problems listed (weave check shows the same), or set llm4j.loom.validate=lenient to report them as warnings.");
            }
            log.warning("the Loom script " + scriptRef + " has problems: " + e.getMessage());
        } catch (RuntimeException e) {
            host.close();
            runner.close();
            throw new Llm4jConfigurationException(
                    "the Loom script " + scriptRef + " cannot start here: " + e.getMessage(),
                    "Check the models' keys, the tools and the files the script names.");
        }
        securityGate(script, scriptRef, reach, l.audit().failOn());
        if (l.triggers().enabled() && !persistence.durable()) {
            throw new Llm4jConfigurationException(
                    "llm4j.loom.triggers.enabled needs a store to keep triggers and runs in",
                    "Set llm4j.loom.persistence to file or jdbc.");
        }
        return new LoomRuntime(host, runner);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "llm4j.loom", name = "script")
    public Llm4jLoomLifecycle llm4jLoomLifecycle(
            LoomRuntime runtime, Llm4jProperties properties, ApplicationEventPublisher events) {
        return new Llm4jLoomLifecycle(runtime, properties.loom(), events);
    }

    /**
     * The trifecta and the other findings of {@code weave audit}, as a start-up check, with {@code
     * weave audit --fail-on}'s threshold. No setting silences a single rule.
     */
    /**
     * A script, and above all a loot file (which names classes to load), is code: it is never
     * fetched over the network.
     */
    static void refuseRemote(String property, String location) {
        String lower = location.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http:") || lower.startsWith("https:") || lower.startsWith("ftp:")) {
            throw new Llm4jConfigurationException(
                    property + " " + location + " is a network address",
                    "Use classpath: or file:. Workflow scripts and tool-reach files are code and are not loaded from the network.");
        }
    }

    static void securityGate(
            LoomScript script, String name, Map<String, String> reach, String failOn) {
        AuditReport report = SecurityAudit.audit(script, name, reach);
        if (failOn.equalsIgnoreCase("none")) {
            report.findings()
                    .forEach(
                            f ->
                                    log.warning(
                                            "weave audit: "
                                                    + f.rule()
                                                    + " "
                                                    + f.title()
                                                    + " ("
                                                    + f.where()
                                                    + ", line "
                                                    + f.line()
                                                    + ")"));
            log.warning(
                    "llm4j.loom.audit.fail-on=none: the security audit does not stop the application from starting");
            return;
        }
        Severity threshold;
        try {
            threshold = Severity.valueOf(failOn.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new Llm4jConfigurationException(
                    "llm4j.loom.audit.fail-on=" + failOn + " is not a severity",
                    "Use high, medium, low, info or none.");
        }
        if (report.failsAt(threshold)) {
            throw new Llm4jConfigurationException(
                    "the security audit of "
                            + name
                            + " found problems at or above "
                            + threshold.name().toLowerCase(Locale.ROOT)
                            + ":\n"
                            + report.markdown(),
                    "Fix them in the script (split the agent so no one agent reads untrusted content, holds private data and can send, or put approve: on the tool that sends), or lower llm4j.loom.audit.fail-on knowingly. There is no property that silences one rule.");
        }
    }

    private static Persistence persistence(
            Llm4jProperties.Loom l,
            Environment environment,
            BeanFactory beans,
            ObjectProvider<DataSource> dataSources) {
        String kind = l.persistence().trim().toLowerCase(Locale.ROOT);
        switch (kind) {
            case "none":
                return Persistence.none();
            case "file":
                if (l.dir() == null || l.dir().isBlank())
                    throw new Llm4jConfigurationException(
                            "llm4j.loom.persistence=file needs llm4j.loom.dir",
                            "Give the folder runs, journals and triggers are kept in.");
                return Persistence.file(Path.of(l.dir()));
            case "jdbc":
                {
                    DataSource ds =
                            l.datasource() != null
                                    ? beans.getBean(l.datasource(), DataSource.class)
                                    : dataSources.getIfUnique();
                    if (ds == null)
                        throw new Llm4jConfigurationException(
                                "llm4j.loom.persistence=jdbc needs a DataSource",
                                "Configure spring.datasource.*, or name one with llm4j.loom.datasource (there must be exactly one otherwise).");
                    initializeSchema(l.initializeSchema(), ds);
                    return Persistence.jdbc(ds);
                }
            default:
                throw new Llm4jConfigurationException(
                        "llm4j.loom.persistence=" + l.persistence() + " is not known",
                        "Use none, file or jdbc.");
        }
    }

    private static void initializeSchema(String mode, DataSource ds) {
        switch (mode.trim().toLowerCase(Locale.ROOT)) {
            case "always":
                LoomHost.createSchema(ds);
                return;
            case "embedded":
                if (EmbeddedDatabaseConnection.isEmbedded(ds)) LoomHost.createSchema(ds);
                else requireTables(ds);
                return;
            case "never":
                requireTables(ds);
                return;
            default:
                throw new Llm4jConfigurationException(
                        "llm4j.loom.initialize-schema=" + mode + " is not known",
                        "Use always, embedded or never.");
        }
    }

    private static void requireTables(DataSource ds) {
        for (String table :
                List.of("loom_journal", "loom_triggers", "llm4j_loom_runs", "llm4j_loom_answers")) {
            try (Connection c = ds.getConnection();
                    var st = c.createStatement()) {
                st.execute("SELECT 1 FROM " + table + " WHERE 1 = 0");
            } catch (SQLException e) {
                throw new Llm4jConfigurationException(
                        "the table " + table + " does not exist",
                        "Create the tables (LoomHost.createSchema(dataSource) does it), or set llm4j.loom.initialize-schema=always.");
            }
        }
    }

    private static HostRunner runner(Llm4jProperties.Loom l, BeanFactory beans) {
        Executor delegate;
        boolean owned = true;
        switch (l.executor().trim().toLowerCase(Locale.ROOT)) {
            case "platform":
                delegate =
                        Executors.newCachedThreadPool(
                                task -> {
                                    Thread t =
                                            new Thread(
                                                    task,
                                                    "llm4j-loom-" + THREADS.incrementAndGet());
                                    t.setDaemon(true);
                                    return t;
                                });
                break;
            case "virtual-threads":
                try {
                    delegate =
                            (Executor)
                                    Executors.class
                                            .getMethod("newVirtualThreadPerTaskExecutor")
                                            .invoke(null);
                } catch (ReflectiveOperationException e) {
                    throw new Llm4jConfigurationException(
                            "llm4j.loom.executor=virtual-threads needs Java 21 or later",
                            "Run on Java 21, or use platform or application.");
                }
                break;
            case "application":
                delegate = beans.getBean("applicationTaskExecutor", Executor.class);
                owned = false;
                break;
            default:
                throw new Llm4jConfigurationException(
                        "llm4j.loom.executor=" + l.executor() + " is not known",
                        "Use platform, virtual-threads or application.");
        }
        return new HostRunner(delegate, l.maxConcurrentRuns(), owned);
    }

    private static WindowLayers windows(List<String> files, ResourceLoader resources) {
        List<Path> paths = new ArrayList<>();
        for (String f : files)
            paths.add(
                    Llm4jAgentsAutoConfiguration.onDisk(
                            "llm4j.loom.context.windows-files", f, resources));
        try {
            return WindowLayers.load(paths, null, null);
        } catch (IllegalArgumentException e) {
            throw new Llm4jConfigurationException(
                    "llm4j.loom.context.windows-files: " + e.getMessage(), "Fix the windows file.");
        }
    }
}
