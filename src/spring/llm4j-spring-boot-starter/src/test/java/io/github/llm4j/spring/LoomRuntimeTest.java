package io.github.llm4j.spring;

import static io.github.llm4j.spring.ClientsTest.rootConfigurationProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.agent.Tool;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.LLMClientFactory;
import io.github.llm4j.loom.execution.LoomRun;
import io.github.llm4j.loom.host.LoomRunFailedException;
import io.github.llm4j.loom.host.RunOutcome;
import io.github.llm4j.loom.host.RunRecord;
import io.github.llm4j.loom.host.RunRequest;
import io.github.llm4j.secret.InMemorySecretStore;
import io.github.llm4j.secret.SecretStore;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.Ordered;

/** Loom through Spring: loading, checking, running, and refusing what cannot run safely. V9 (and the task checks of V6). */
class LoomRuntimeTest {

    @TempDir
    Path dir;

    private final LoomFixtures.Models models = new LoomFixtures.Models();
    private final LoomFixtures.Notify notify = new LoomFixtures.Notify();
    private final LoomFixtures.Refund refund = new LoomFixtures.Refund();

    private ApplicationContextRunner loom(String script, String... props) {
        List<String> all = new ArrayList<>(List.of("llm4j.loom.script=classpath:scripts/" + script));
        all.addAll(List.of(props));
        return Fixtures.runner().withBean(LLMClientFactory.class, () -> models).withBean("notify", io.github.llm4j.agent.task.Task.class, () -> notify)
                .withBean("refund", io.github.llm4j.agent.task.Task.class, () -> refund).withPropertyValues(all.toArray(new String[0]));
    }

    private static Tool named(String name) {
        return new Tool() {
            @Override public String getName() { return name; }
            @Override public String getDescription() { return name; }
            @Override public String execute(Map<String, Object> args) { return name; }
        };
    }

    @Tag("SB-V9.1")
    @Test
    void aWorkflowRunsAndItsToolsAreTheOnesYouDeclared() {
        loom("from-sources.loom", "llm4j.loom.loot=classpath:scripts/reach.loot", "llm4j.tools.builtin=calculator", "llm4j.tools.base-dir=" + dir.resolve("..").normalize(),
                "llm4j.tools.custom.Files.kind=file", "llm4j.tools.custom.Files.options.root=" + dir.resolve("..").normalize())
                .withBean("fromBean", Tool.class, () -> named("FromBean")).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            RunOutcome out = ctx.getBean(LoomRuntime.class).run(RunRequest.of("Main", Map.of()));
            assertThat(out).isInstanceOf(RunOutcome.Completed.class);
            assertThat(models.calls("Writer")).isEqualTo(1);
        });
    }

    @Tag("SB-V9.1")
    @Test
    void aScriptNamingAToolNobodyDeclaredDoesNotStart() {
        loom("from-sources.loom", "llm4j.loom.audit.fail-on=none").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(rootConfigurationProblem(ctx.getStartupFailure())).hasMessageContaining("FromBean");
        });
    }

    private Path jar(Map<String, String> entries) throws IOException {
        Path jar = dir.resolve("scripts.jar");
        try (OutputStream out = Files.newOutputStream(jar); JarOutputStream jos = new JarOutputStream(out)) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                jos.putNextEntry(new JarEntry(e.getKey()));
                jos.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                jos.closeEntry();
            }
        }
        return jar;
    }

    @Tag("SB-V9.2")
    @Test
    void aScriptItsImportAndItsLootFileAreReadFromInsideAJar() throws Exception {
        Path jar = jar(Map.of("jar/main.loom", "import \"lib/agents.loom\"\nworkflow Main() { delegate \"go\" to Writer -> out }\n",
                "jar/lib/agents.loom", "agent Writer { model: \"test/model\" system: \"You are Writer.\" tools: [Echo] }\n", "jar/tools.loot", "Echo = io.github.llm4j.agent.tools.EchoTool\nEcho.reach = none\n"));
        Set<String> before = tmpLoot();
        try (URLClassLoader loader = new URLClassLoader(new URL[] {jar.toUri().toURL()}, getClass().getClassLoader())) {
            Fixtures.runner().withClassLoader(loader).withBean(LLMClientFactory.class, () -> models).withPropertyValues("llm4j.loom.script=classpath:jar/main.loom", "llm4j.loom.loot=classpath:jar/tools.loot")
                    .run(ctx -> {
                assertThat(ctx).hasNotFailed();
                assertThat(ctx.getBean(LoomRuntime.class).run(RunRequest.of("Main", Map.of()))).isInstanceOf(RunOutcome.Completed.class);
                assertThat(models.calls("Writer")).isEqualTo(1);
            });
        }
        assertThat(tmpLoot()).isEqualTo(before);
    }

    private static Set<String> tmpLoot() throws IOException {
        try (var files = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".loot")).collect(java.util.stream.Collectors.toSet());
        }
    }

    @Tag("SB-V9.3")
    @Test
    void modelKeysComeFromABeanTheSecretStoreOrTheEnvironment() {
        // a factory bean wins
        loom("gemini.loom").run(ctx -> assertThat(ctx).hasNotFailed());
        // otherwise the library's own, looking in the secret store, then the Environment
        InMemorySecretStore store = new InMemorySecretStore();
        store.put("GEMINI_API_KEY", "k");
        Fixtures.runner().withBean(SecretStore.class, () -> store).withPropertyValues("llm4j.loom.script=classpath:scripts/gemini.loom").run(ctx -> assertThat(ctx).hasNotFailed());
        Fixtures.runner().withPropertyValues("llm4j.loom.script=classpath:scripts/gemini.loom", "GEMINI_API_KEY=k").run(ctx -> assertThat(ctx).hasNotFailed());
        Fixtures.runner().withPropertyValues("llm4j.loom.script=classpath:scripts/gemini.loom").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(rootConfigurationProblem(ctx.getStartupFailure()).getMessage()).contains("GEMINI_API_KEY");
        });
    }

    @Tag("SB-V9.4")
    @Test
    void aBrokenScriptFailsOnceListingEveryProblemOrWarnsWhenLenient() {
        loom("broken.loom").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(rootConfigurationProblem(ctx.getStartupFailure()).getMessage()).contains("Nobody").contains("NoSuchTask").contains("Ghost").contains("line");
        });
        loom("broken.loom", "llm4j.loom.validate=lenient").run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Tag("SB-V9.5")
    @Test
    void aScriptWithTheLethalTrifectaDoesNotStart() {
        loom("trifecta.loom", "llm4j.tools.base-dir=" + dir).run(ctx -> {
            assertThat(ctx).hasFailed();
            Llm4jConfigurationException problem = rootConfigurationProblem(ctx.getStartupFailure());
            assertThat(problem.getMessage()).contains("LA01").contains("security audit");
            assertThat(problem.action()).contains("split the agent").contains("approve:").contains("fail-on");
        });
    }

    @Tag("SB-V9.6")
    @Test
    void clearingTheRiskOrLoweringTheGateKnowinglyLetsItStart() {
        // approving the tool that sends turns the trifecta from high to medium, below the default gate
        // (approve: can pause a run to wait for a person, so it needs somewhere to remember the run)
        loom("trifecta-approved.loom", "llm4j.loom.persistence=file", "llm4j.loom.dir=" + dir).run(ctx -> assertThat(ctx).hasNotFailed());
        loom("trifecta-approved.loom", "llm4j.loom.persistence=file", "llm4j.loom.dir=" + dir, "llm4j.loom.audit.fail-on=medium").run(ctx -> assertThat(ctx).hasFailed());
        loom("trifecta.loom", "llm4j.loom.audit.fail-on=none").run(ctx -> assertThat(ctx).hasNotFailed());
        loom("trifecta.loom", "llm4j.loom.audit.fail-on=sideways").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(rootConfigurationProblem(ctx.getStartupFailure()).action()).contains("high, medium, low, info or none");
        });
    }

    @Tag("SB-V9.8")
    @Test
    void aScriptThatWaitsNeedsAPlaceToRememberTheRun() {
        loom("approve.loom").run(ctx -> {
            assertThat(ctx).hasFailed();
            Llm4jConfigurationException problem = rootConfigurationProblem(ctx.getStartupFailure());
            assertThat(problem.getMessage()).contains("human_prompt").contains("persistence");
            assertThat(problem.action()).contains("llm4j.loom.persistence").contains("allow-non-durable");
        });
        loom("approve.loom", "llm4j.loom.allow-non-durable=true").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThatThrownBy(() -> ctx.getBean(LoomRuntime.class).run(RunRequest.of("Main", Map.of()))).isInstanceOf(LoomRunFailedException.class).hasMessageContaining("no persistence");
        });
        loom("ladder.loom", "llm4j.loom.allow-non-durable=true").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(rootConfigurationProblem(ctx.getStartupFailure()).getMessage()).contains("decision D");
        });
    }

    @Tag("SB-V9.9")
    @Test
    void workflowsRunOnThePlatformThreadsAnApplicationExecutorOrVirtualThreads() throws Exception {
        List<String> threads = Collections.synchronizedList(new ArrayList<>());
        LLMClientFactory spy = model -> {
            var inner = models.createClient(model);
            return new io.github.llm4j.LLMClient() {
                @Override public io.github.llm4j.model.LLMResponse chat(io.github.llm4j.model.LLMRequest r) { threads.add(Thread.currentThread().getName() + "|" + isVirtual()); return inner.chat(r); }
                @Override public java.util.stream.Stream<io.github.llm4j.model.LLMResponse> chatStream(io.github.llm4j.model.LLMRequest r) { return inner.chatStream(r); }
            };
        };
        for (String kind : List.of("platform", "application")) {
            threads.clear();
            ApplicationContextRunner runner = Fixtures.runner().withBean(LLMClientFactory.class, () -> spy).withPropertyValues("llm4j.loom.script=classpath:scripts/three-steps.loom", "llm4j.loom.executor=" + kind);
            if (kind.equals("application")) {
                runner = runner.withBean("applicationTaskExecutor", org.springframework.core.task.TaskExecutor.class, () -> {
                    var pool = new org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor();
                    pool.setThreadNamePrefix("app-exec-");
                    pool.initialize();
                    return pool;
                });
            }
            runner.run(ctx -> {
                LoomRun run = ctx.getBean(LoomRuntime.class).start(RunRequest.of("Main", Map.of()));
                assertThat(run.await(Duration.ofSeconds(10))).isTrue();
            });
            assertThat(threads).as(kind).isNotEmpty().allSatisfy(t -> assertThat(t).startsWith(kind.equals("platform") ? "llm4j-loom-" : "app-exec-").endsWith("|false"));
        }
    }

    @Tag("SB-V9.9")
    @EnabledForJreRange(min = JRE.JAVA_21)
    @Test
    void workflowsCanRunOnVirtualThreads() throws Exception {
        List<Boolean> virtual = Collections.synchronizedList(new ArrayList<>());
        LLMClientFactory spy = model -> {
            var inner = models.createClient(model);
            return new io.github.llm4j.LLMClient() {
                @Override public io.github.llm4j.model.LLMResponse chat(io.github.llm4j.model.LLMRequest r) { virtual.add(isVirtual()); return inner.chat(r); }
                @Override public java.util.stream.Stream<io.github.llm4j.model.LLMResponse> chatStream(io.github.llm4j.model.LLMRequest r) { return inner.chatStream(r); }
            };
        };
        Fixtures.runner().withBean(LLMClientFactory.class, () -> spy).withPropertyValues("llm4j.loom.script=classpath:scripts/three-steps.loom", "llm4j.loom.executor=virtual-threads").run(ctx -> {
            assertThat(ctx.getBean(LoomRuntime.class).start(RunRequest.of("Main", Map.of())).await(Duration.ofSeconds(10))).isTrue();
        });
        assertThat(virtual).isNotEmpty().containsOnly(true);
    }

    private static boolean isVirtual() {
        try {
            return (boolean) Thread.class.getMethod("isVirtual").invoke(Thread.currentThread());
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    @Tag("SB-V9.10")
    @Test
    void runsBeyondTheLimitAreRefusedUntilOneFinishes() throws Exception {
        models.holdAgent = "Researcher";
        models.hold = new CountDownLatch(1);
        loom("three-steps.loom", "llm4j.loom.max-concurrent-runs=2").run(ctx -> {
            LoomRuntime loom = ctx.getBean(LoomRuntime.class);
            LoomRun a = loom.start(RunRequest.of("Main", Map.of()).withRunId("a"));
            LoomRun b = loom.start(RunRequest.of("Main", Map.of()).withRunId("b"));
            assertThatThrownBy(() -> loom.start(RunRequest.of("Main", Map.of()).withRunId("c"))).isInstanceOf(TooManyRuns.class).hasMessageContaining("2");
            models.hold.countDown();
            assertThat(a.await(Duration.ofSeconds(10))).isTrue();
            assertThat(b.await(Duration.ofSeconds(10))).isTrue();
            LoomRun d = loom.start(RunRequest.of("Main", Map.of()).withRunId("d")); // a place is free again
            assertThat(d.await(Duration.ofSeconds(10))).isTrue();
            assertThat(loom.run("c")).as("the refused run left no record that it ran").map(RunRecord::status).isNotEqualTo(java.util.Optional.of(RunRecord.Status.COMPLETED));
        });
    }

    @Tag("SB-V9.12")
    @Test
    void yourExecutorCustomizersRunInOrderAfterTheStartersSetupAndBeforeInitialize() {
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        class Step implements LoomExecutorCustomizer, Ordered {
            private final int order;
            Step(int order) { this.order = order; }
            @Override public int getOrder() { return order; }
            @Override public void customize(HarnessExecutor ex) { seen.add(order + (ex.getJournal() != null && ex.getJournal().isDurable() ? " with the run's journal" : " without a journal")); }
        }
        loom("three-steps.loom", "llm4j.loom.persistence=file", "llm4j.loom.dir=" + dir).withBean("two", LoomExecutorCustomizer.class, () -> new Step(2)).withBean("one", LoomExecutorCustomizer.class, () -> new Step(1))
                .run(ctx -> {
            ctx.getBean(LoomRuntime.class).run(RunRequest.of("Main", Map.of()));
            // in order, once per executor (a run, and the one start-up validation makes), and the journal was already attached
            assertThat(seen).contains("1 with the run's journal", "2 with the run's journal");
            assertThat(seen.indexOf("1 with the run's journal")).isLessThan(seen.indexOf("2 with the run's journal"));
        });
    }

    @Tag("SB-V9.13")
    @Test
    void theExecutorBudgetFromSettingsStopsARun() {
        loom("three-steps.loom", "llm4j.loom.budget.tokens=1").run(ctx -> {
            org.assertj.core.api.Assertions.assertThat(catchRun(ctx.getBean(LoomRuntime.class))).isNotInstanceOf(RunOutcome.Completed.class);
        });
    }

    private static Object catchRun(LoomRuntime loom) {
        try {
            return loom.run(RunRequest.of("Main", Map.of()));
        } catch (RuntimeException e) {
            return e;
        }
    }

    @Tag("SB-V9.14")
    @Test
    void contextWindowFilesAreReadAndAMissingOneIsNamed() throws Exception {
        Path windows = dir.resolve("windows.yaml");
        Files.writeString(windows, "# a model and its window in tokens\ntest/model = 8000\n");
        loom("three-steps.loom", "llm4j.loom.context.windows-files=file:" + windows).run(ctx -> assertThat(ctx).hasNotFailed());
        loom("three-steps.loom", "llm4j.loom.context.windows-files=file:" + dir.resolve("nope.yaml")).run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(rootConfigurationProblem(ctx.getStartupFailure())).hasMessageContaining("nope.yaml");
        });
    }

    @Tag("SB-V9.15")
    @Test
    void theV1WaysToRunAndStartStillWork() throws Exception {
        loom("three-steps.loom").run(ctx -> {
            LoomRuntime loom = ctx.getBean(LoomRuntime.class);
            assertThat(loom.run("Main", Map.of())).isInstanceOf(RunOutcome.Completed.class);
            assertThat(loom.start("Main", Map.of()).await(Duration.ofSeconds(10))).isTrue();
        });
    }

    @Tag("SB-V6.9")
    @Test
    void yourSpringBeanIsTheTaskARunStepCalls() {
        loom("tasks.loom").run(ctx -> {
            RunOutcome out = ctx.getBean(LoomRuntime.class).run(RunRequest.of("Main", Map.of()));
            assertThat(out).isInstanceOfSatisfying(RunOutcome.Completed.class, c -> assertThat(String.valueOf(c.variables().get("verdict"))).contains("approved").contains("12.50"));
            assertThat(refund.calls.get()).isEqualTo(1);
        });
    }

    @Tag("SB-V6.10")
    @Test
    void duplicateTasksAreRefusedAndDiscoveryCanBeTurnedOff() {
        loom("tasks.loom").withBean("refund2", io.github.llm4j.agent.task.Task.class, LoomFixtures.Refund::new).run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(rootConfigurationProblem(ctx.getStartupFailure())).hasMessageContaining("Refund").hasMessageContaining("already registered");
        });
        Fixtures.runner().withBean(LLMClientFactory.class, () -> models).withPropertyValues("llm4j.loom.script=classpath:scripts/tasks.loom").run(ctx -> {
            assertThat(ctx).hasFailed(); // no Refund task anywhere: the script names a task that does not exist
            assertThat(rootConfigurationProblem(ctx.getStartupFailure())).hasMessageContaining("Refund");
        });
        Fixtures.runner().withBean(LLMClientFactory.class, () -> models).withPropertyValues("llm4j.loom.script=classpath:scripts/three-steps.loom", "llm4j.tasks.discover=false")
                .run(ctx -> assertThat(ctx.getBean(io.github.llm4j.agent.task.TaskRegistry.class).get("ServiceTask")).isNull());
    }

    @Tag("SB-V1.4")
    @Test
    void theV1ScriptPropertyStillNamesTheScript() {
        loom("three-steps.loom").run(ctx -> assertThat(ctx.getBean(LoomRuntime.class).script().getWorkflows()).hasSize(1));
    }
}
