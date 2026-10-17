package io.github.llm4j.spring;

import static io.github.llm4j.spring.ClientsTest.rootConfigurationProblem;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.agent.Tool;
import io.github.llm4j.agent.tool.EffectContext;
import io.github.llm4j.agent.tool.ToolKind;
import io.github.llm4j.agent.tool.ToolRegistry;
import io.github.llm4j.secret.InMemorySecretStore;
import io.github.llm4j.secret.SecretStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The tool catalog: beans, built-ins, guarded kinds. V6 (the tasks and the Loom side are in {@link LoomSpringTest}). */
class ToolsTest {

    @TempDir
    Path dir;

    private static Tool named(String name) {
        return new Tool() {
            @Override public String getName() { return name; }
            @Override public String getDescription() { return name; }
            @Override public String execute(Map<String, Object> args) { return name; }
        };
    }

    /** An effect context standing at one step of a run; {@code attempt} says which try of that step this is, so a test can play a replay after a crash. */
    private static final java.util.concurrent.atomic.AtomicLong ATTEMPT = new java.util.concurrent.atomic.AtomicLong(1);

    private static EffectContext inStep() {
        EffectContext base = EffectContext.noop();
        return new EffectContext() {
            @Override public void audit(String event, Map<String, Object> data) { base.audit(event, data); }
            @Override public void trace(String text, Map<String, Object> data) { base.trace(text, data); }
            @Override public io.github.llm4j.agent.tool.EffectJournal journal() { return base.journal(); }
            @Override public String currentStep() { return "Main/s1"; }
            @Override public long attempt() { return ATTEMPT.get(); }
            @Override public java.util.Set<Path> reservedPaths() { return base.reservedPaths(); }
            @Override public io.github.llm4j.ratelimit.Sleeper sleeper() { return base.sleeper(); }
            @Override public java.time.Clock clock() { return base.clock(); }
        };
    }

    private static SecretStore secrets(String name, String value) {
        InMemorySecretStore store = new InMemorySecretStore();
        store.put(name, value);
        return store;
    }

    @Tag("SB-V6.1")
    @Test
    void aWebhookToolIsMadeFromItsOptionsAndSecret() throws Exception {
        try (MockWebServer slack = new MockWebServer()) {
            slack.enqueue(new MockResponse().setBody("ok"));
            slack.start();
            Fixtures.runner().withBean(SecretStore.class, () -> secrets("SLACK_WEBHOOK", slack.url("/hook").toString())).withPropertyValues(
                    "llm4j.tools.custom.Slack.kind=webhook", "llm4j.tools.custom.Slack.options.url-secret=SLACK_WEBHOOK", "llm4j.tools.custom.Slack.options.format=slack",
                    "llm4j.tools.custom.Slack.options.allow_http=true", "llm4j.tools.custom.Slack.options.allow_private=true").run(ctx -> {
                assertThat(ctx).hasNotFailed();
                String out = ctx.getBean(ToolRegistry.class).getTool("Slack").execute(Map.of("text", "digest is ready"));
                assertThat(out).isNotBlank();
                assertThat(slack.getRequestCount()).isEqualTo(1);
                assertThat(slack.takeRequest().getBody().readUtf8()).contains("digest is ready");
            });
        }
    }

    @Tag("SB-V6.2")
    @Test
    void aToolWhoseOptionsAreWrongFailsStartUpWithTheLibrarysReason() {
        Fixtures.runner().withPropertyValues("llm4j.tools.custom.Shell.kind=shell").run(ctx -> {
            assertThat(ctx).hasFailed();
            Llm4jConfigurationException problem = rootConfigurationProblem(ctx.getStartupFailure());
            assertThat(problem).hasMessageContaining("llm4j.tools.custom.Shell").hasMessageContaining("allow");
            assertThat(problem.action()).contains("use: shell");
        });
        Fixtures.runner().withPropertyValues("llm4j.tools.custom.X.kind=teleport").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(rootConfigurationProblem(ctx.getStartupFailure()).action()).contains("webhook", "email", "http", "file", "shell", "sql");
        });
    }

    @Tag("SB-V6.3")
    @Test
    void everyKindIsMadeAndRunsOnceAgainstItsStandIn() throws Exception {
        try (MockWebServer api = new MockWebServer()) {
            api.setDispatcher(new okhttp3.mockwebserver.Dispatcher() {
                @Override
                public MockResponse dispatch(okhttp3.mockwebserver.RecordedRequest request) {
                    return new MockResponse().setHeader("Content-Type", "application/json").setBody("{\"pong\":true}");
                }
            });
            api.start();
            Path outbox = Files.createDirectories(dir.resolve("outbox"));
            Path files = Files.createDirectories(dir.resolve("files"));
            Files.writeString(files.resolve("note.txt"), "hello from a file");
            String url = "jdbc:h2:mem:tools-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
            try (var keep = java.sql.DriverManager.getConnection(url, "sa", ""); var st = keep.createStatement()) {
                st.execute("CREATE TABLE t (n INT)");
                st.execute("INSERT INTO t VALUES (42)");
                Fixtures.runner().withPropertyValues(
                        "llm4j.tools.base-dir=" + dir, "llm4j.tools.custom.Hook.kind=webhook", "llm4j.tools.custom.Hook.options.url=" + api.url("/h"), "llm4j.tools.custom.Hook.options.allow_http=true", "llm4j.tools.custom.Hook.options.allow_private=true",
                        "llm4j.tools.custom.Mail.kind=email", "llm4j.tools.custom.Mail.options.from=bot@example.com", "llm4j.tools.custom.Mail.options.to=ops@example.com", "llm4j.tools.custom.Mail.options.outbox=" + outbox,
                        "llm4j.tools.custom.Api.kind=http", "llm4j.tools.custom.Api.options.base_url=" + api.url("/"), "llm4j.tools.custom.Api.options.allow_http=true", "llm4j.tools.custom.Api.options.allow_private=true", "llm4j.tools.custom.Api.options.allow_paths=/ping",
                        "llm4j.tools.custom.Files.kind=file", "llm4j.tools.custom.Files.options.root=" + files,
                        "llm4j.tools.custom.Run.kind=shell", "llm4j.tools.custom.Run.options.allow=echo", "llm4j.tools.custom.Run.options.unattended=true",
                        "llm4j.tools.custom.Db.kind=sql", "llm4j.tools.custom.Db.options.url=" + url, "llm4j.tools.custom.Db.options.user=sa").run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    ToolRegistry tools = ctx.getBean(ToolRegistry.class);
                    assertThat(tools.getAllTools()).extracting(Tool::getName).containsExactlyInAnyOrder("Hook", "Mail", "Api", "Files", "Run", "Db");
                    assertThat(tools.getTool("Hook").execute(Map.of("text", "hi"))).isNotNull();
                    assertThat(api.getRequestCount()).isEqualTo(1);
                    assertThat(tools.getTool("Api").execute(Map.of("path", "/ping"))).contains("pong");
                    assertThat(tools.getTool("Db").execute(Map.of("sql", "SELECT n FROM t"))).contains("42");
                    assertThat(tools.getTool("Mail").execute(Map.of("subject", "hi", "body", "text"))).isNotNull();
                    try (var list = Files.list(outbox)) {
                        assertThat(list.count()).isEqualTo(1); // one .eml written, nothing sent
                    }
                    assertThat(tools.getTool("Files").execute(Map.of("action", "read", "path", "note.txt"))).isNotNull();
                    assertThat(tools.getTool("Run").execute(Map.of("program", "echo", "args", List.of("ran")))).isNotNull();
                });
            }
        }
    }

    @Tag("SB-V6.4")
    @Test
    void aToolKindOfYourOwnCanBeNamedInSettings() {
        ToolKind pager = new ToolKind() {
            @Override public String name() { return "pager"; }
            @Override public String check(Map<String, String> options, Path baseDir) { return null; }
            @Override public Tool create(String name, Map<String, String> options, Path baseDir) { return named(name + "-from-pager"); }
            @Override public Tool create(String name, Map<String, String> options, Path baseDir, EffectContext context) { return create(name, options, baseDir); }
        };
        Fixtures.runner().withBean(ToolKind.class, () -> pager).withPropertyValues("llm4j.tools.custom.Page.kind=pager").run(ctx ->
                assertThat(ctx.getBean(ToolCatalog.class).names()).containsExactly("Page-from-pager"));
    }

    @Tag("SB-V6.5")
    @Test
    void builtInsAreRegisteredOnlyWhenNamed() {
        Fixtures.runner().withPropertyValues("llm4j.tools.builtin=calculator,current-time").run(ctx ->
                assertThat(ctx.getBean(ToolCatalog.class).names()).containsExactly("Calculator", "CurrentTime")); // the tools' own names
        Fixtures.runner().run(ctx -> assertThat(ctx.getBean(ToolRegistry.class).getAllTools()).isEmpty());
        Fixtures.runner().withPropertyValues("llm4j.tools.builtin=nope").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(rootConfigurationProblem(ctx.getStartupFailure()).action()).contains("calculator", "current-time", "date-time", "echo");
        });
    }

    @Tag("SB-V6.6")
    @Test
    void twoToolsWithOneNameAreAnErrorThatNamesBoth() {
        Fixtures.runner().withBean("slackTool", Tool.class, () -> named("Slack")).withPropertyValues("llm4j.tools.custom.Slack.kind=webhook", "llm4j.tools.custom.Slack.options.url=http://localhost:1/x",
                "llm4j.tools.custom.Slack.options.allow_http=true", "llm4j.tools.custom.Slack.options.allow_private=true").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(rootConfigurationProblem(ctx.getStartupFailure())).hasMessageContaining("Tool bean 'slackTool'").hasMessageContaining("llm4j.tools.custom.Slack");
        });
    }

    @Tag("SB-V6.7")
    @Test
    void tools_from_every_source_land_in_one_catalog() {
        Fixtures.runner().withBean("beanTool", Tool.class, () -> named("FromBean")).withPropertyValues("llm4j.tools.base-dir=" + dir, "llm4j.tools.builtin=calculator", "llm4j.tools.custom.Files.kind=file",
                "llm4j.tools.custom.Files.options.root=" + dir).run(ctx -> {
            assertThat(ctx.getBean(ToolCatalog.class).names()).containsExactlyInAnyOrder("FromBean", "Calculator", "Files");
            assertThat(ctx.getBean(ToolRegistry.class).getAllTools()).extracting(Tool::getName).containsExactlyInAnyOrder("FromBean", "Calculator", "Files");
        });
    }

    @Tag("SB-V6.8")
    @Test
    void aJournalledToolIsNotRunTwiceForTheSameCall() throws Exception {
        try (MockWebServer slack = new MockWebServer()) {
            slack.setDispatcher(new okhttp3.mockwebserver.Dispatcher() {
                @Override public MockResponse dispatch(okhttp3.mockwebserver.RecordedRequest r) { return new MockResponse().setBody("ok"); }
            });
            slack.start();
            Fixtures.runner().withBean(EffectContext.class, ToolsTest::inStep).withPropertyValues("llm4j.tools.custom.Slack.kind=webhook", "llm4j.tools.custom.Slack.options.url=" + slack.url("/hook"),
                    "llm4j.tools.custom.Slack.options.allow_http=true", "llm4j.tools.custom.Slack.options.allow_private=true").run(ctx -> {
                Tool slackTool = ctx.getBean(ToolRegistry.class).getTool("Slack");
                ATTEMPT.set(1);
                slackTool.execute(Map.of("text", "same message"));
                ATTEMPT.set(2); // the step is run again after a crash: the same call is recognised, not sent a second time
                String again = slackTool.execute(Map.of("text", "same message"));
                assertThat(again).contains("already done");
                assertThat(slack.getRequestCount()).isEqualTo(1);
            });
        }
    }
}
