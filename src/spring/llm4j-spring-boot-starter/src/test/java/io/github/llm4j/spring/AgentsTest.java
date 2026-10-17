package io.github.llm4j.spring;

import static io.github.llm4j.spring.ClientsTest.rootConfigurationProblem;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.agent.AgentEventListener;
import io.github.llm4j.agent.ApprovalCallback;
import io.github.llm4j.agent.ReActAgent;
import io.github.llm4j.agent.RunHook;
import io.github.llm4j.agent.Tool;
import io.github.llm4j.agent.ToolHook;
import io.github.llm4j.agent.memory.ConversationHistory;
import io.github.llm4j.agent.memory.ConversationStore;
import io.github.llm4j.agent.prompt.FileSystemPromptRegistry;
import io.github.llm4j.agent.prompt.MarkdownFolderPromptRegistry;
import io.github.llm4j.agent.prompt.PromptRegistry;
import io.github.llm4j.model.Message;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.Ordered;

/** The agent builder, context limits, conversation memory, prompts and skills. V5 (and V4.10, which needs an agent run). */
class AgentsTest {

    @TempDir
    Path dir;

    private static Tool tool(String name, boolean needsApproval, AtomicInteger runs) {
        return new Tool() {
            @Override public String getName() { return name; }
            @Override public String getDescription() { return "the " + name + " tool"; }
            @Override public String execute(Map<String, Object> args) { runs.incrementAndGet(); return name + " done"; }
            @Override public boolean requiresApproval(Map<String, Object> args) { return needsApproval; }
        };
    }

    private static String[] client(Fixtures.Provider p, String... more) {
        List<String> all = new ArrayList<>(List.of("llm4j.clients.default.provider=ollama", "llm4j.clients.default.model=m", "llm4j.clients.default.base-url=" + p.url(), "llm4j.clients.default.retry.enabled=false"));
        all.addAll(List.of(more));
        return all.toArray(new String[0]);
    }

    private static class Ordering implements ToolHook, Ordered {
        private final int order;
        private final List<String> calls;
        Ordering(int order, List<String> calls) { this.order = order; this.calls = calls; }
        @Override public int getOrder() { return order; }
        @Override public Decision beforeCall(String toolName, Map<String, Object> arguments) { calls.add("hook " + order + " " + toolName); return Decision.allow(); }
    }

    @Tag("SB-V5.1")
    @Test
    void beansFromTheApplicationAreAppliedToEveryAgent() throws Exception {
        try (var p = new Fixtures.Provider("ollama")) {
            p.queue(Fixtures.Provider.react("Pay", "{\"amount\": 5}"), Fixtures.Provider.done("paid"));
            AtomicInteger paid = new AtomicInteger();
            List<String> events = Collections.synchronizedList(new ArrayList<>());
            List<String> asked = Collections.synchronizedList(new ArrayList<>());
            AgentEventListener listener = new AgentEventListener() {
                @Override public void onThought(String thought) { events.add("thought"); }
                @Override public void onAction(String toolName, String toolInput) { events.add("action " + toolName); }
                @Override public void onObservation(String observation) { events.add("observation"); }
            };
            RunHook hook = new RunHook() {
                @Override public ModelCall beforeModelCall(CallView view) { events.add("model call " + view.iteration()); return ModelCall.proceed(); }
            };
            ApprovalCallback approval = (name, args, thought) -> { asked.add(name); return true; };
            Fixtures.runner().withBean("pay", Tool.class, () -> tool("Pay", true, paid)).withBean(AgentEventListener.class, () -> listener).withBean(RunHook.class, () -> hook)
                    .withBean("toolHookTwo", ToolHook.class, () -> new Ordering(2, events)).withBean("toolHookOne", ToolHook.class, () -> new Ordering(1, events))
                    .withBean(ApprovalCallback.class, () -> approval).withPropertyValues(client(p)).run(ctx -> {
                ReActAgent agent = ctx.getBean(ReActAgent.Builder.class).build();
                assertThat(agent.run("pay five").getFinalAnswer()).isEqualTo("paid");
                assertThat(paid.get()).isEqualTo(1);
                assertThat(asked).containsExactly("Pay");
                assertThat(events).contains("action Pay", "observation", "model call 1");
                assertThat(events.indexOf("hook 1 Pay")).isGreaterThanOrEqualTo(0).isLessThan(events.indexOf("hook 2 Pay"));
            });
        }
    }

    @Tag("SB-V5.2")
    @Test
    void anAgentCanBeGivenOnlySomeOfTheTools() throws Exception {
        try (var p = new Fixtures.Provider("ollama")) {
            p.reply = Fixtures.Provider.done("ok");
            Fixtures.runner().withBean("alpha", Tool.class, () -> tool("Alpha", false, new AtomicInteger())).withBean("beta", Tool.class, () -> tool("Beta", false, new AtomicInteger()))
                    .withPropertyValues(client(p, "llm4j.agent.tools=Alpha")).run(ctx -> {
                ctx.getBean(ReActAgent.Builder.class).build().run("hi");
                assertThat(p.allBodies()).contains("Alpha").doesNotContain("Beta");
            });
        }
    }

    @Tag("SB-V5.2")
    @Test
    void aToolNameThatDoesNotExistListsTheRegisteredOnes() throws Exception {
        try (var p = new Fixtures.Provider("ollama")) {
            Fixtures.runner().withBean("alpha", Tool.class, () -> tool("Alpha", false, new AtomicInteger())).withPropertyValues(client(p, "llm4j.agent.tools=Nope")).run(ctx -> {
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> ctx.getBean(ReActAgent.Builder.class)).satisfies(e -> {
                    Llm4jConfigurationException problem = rootConfigurationProblem(e);
                    assertThat(problem).hasMessageContaining("Nope");
                    assertThat(problem.action()).contains("Alpha");
                });
            });
        }
    }

    @Tag("SB-V5.3")
    @Test
    void anAgentCanUseAnotherNamedClient() throws Exception {
        try (var main = new Fixtures.Provider("ollama"); var cheap = new Fixtures.Provider("ollama")) {
            main.reply = Fixtures.Provider.done("from main");
            cheap.reply = Fixtures.Provider.done("from cheap");
            Fixtures.runner().withPropertyValues(client(main, "llm4j.clients.cheap.provider=ollama", "llm4j.clients.cheap.model=m", "llm4j.clients.cheap.base-url=" + cheap.url(),
                    "llm4j.agent.client=cheap")).run(ctx -> {
                assertThat(ctx.getBean(ReActAgent.Builder.class).build().run("hi").getFinalAnswer()).isEqualTo("from cheap");
                assertThat(main.seen).isEmpty();
            });
        }
    }

    @Tag("SB-V5.4")
    @Test
    void theSystemPromptCanComeFromThePromptFolder() throws Exception {
        try (var p = new Fixtures.Provider("ollama")) {
            p.reply = Fixtures.Provider.done("ok");
            Fixtures.runner().withPropertyValues(client(p, "llm4j.prompts.location=classpath:prompts/", "llm4j.agent.system-prompt-id=triage")).run(ctx -> {
                ctx.getBean(ReActAgent.Builder.class).build().run("hi");
                assertThat(p.allBodies()).contains("TRIAGE-MARKER-77");
            });
        }
    }

    @Tag("SB-V5.5")
    @Test
    void aCustomizerHasTheLastWord() throws Exception {
        try (var p = new Fixtures.Provider("ollama")) {
            p.reply = Fixtures.Provider.react("Nothing", "{}"); // never finishes: the agent stops at its iteration limit
            Fixtures.runner().withBean(ReActAgentBuilderCustomizer.class, () -> b -> b.maxIterations(2)).withPropertyValues(client(p, "llm4j.agent.max-iterations=8")).run(ctx -> {
                ctx.getBean(ReActAgent.Builder.class).build().run("loop");
                assertThat(p.seen).hasSize(2);
            });
        }
    }

    @Tag("SB-V5.6")
    @Tag("SB-V5.7")
    @Test
    void everyBuilderAndEveryAgentIsNewAndThereIsNoSharedAgent() throws Exception {
        try (var p = new Fixtures.Provider("ollama")) {
            p.reply = Fixtures.Provider.done("ok");
            Fixtures.runner().withPropertyValues(client(p)).run(ctx -> {
                ReActAgent.Builder first = ctx.getBean(ReActAgent.Builder.class);
                assertThat(ctx.getBean(ReActAgent.Builder.class)).isNotSameAs(first);
                ReActAgent a = first.build();
                ReActAgent b = first.build();
                assertThat(a).isNotSameAs(b);
                a.run("my secret is MARKER-A");
                b.run("a different chat");
                assertThat(p.seen.get(p.seen.size() - 1).getBody().clone().readUtf8()).doesNotContain("MARKER-A");
                assertThat(ctx.getBeansOfType(ReActAgent.class)).isEmpty();
            });
        }
    }

    @Tag("SB-V5.8")
    @Test
    void theContextWindowAndTheCompactionStrategyReachTheRunningAgent() throws Exception {
        try (var p = new Fixtures.Provider("ollama")) {
            p.realisticUsage = true;
            p.queue(Fixtures.Provider.react("Big", "{}"), Fixtures.Provider.react("Big", "{}"), Fixtures.Provider.done("finished"));
            List<Object> seen = Collections.synchronizedList(new ArrayList<>());
            AgentEventListener listener = new AgentEventListener() {
                @Override public void onThought(String thought) { }
                @Override public void onAction(String toolName, String toolInput) { }
                @Override public void onObservation(String observation) { }
                @Override public void onContextUsage(io.github.llm4j.context.ContextUsage usage) { seen.add(usage.window().tokens()); }
                @Override public void onCompaction(io.github.llm4j.context.CompactionEvent event) { seen.add("compaction"); }
            };
            Fixtures.runner().withBean("big", Tool.class, BigTool::new).withBean(AgentEventListener.class, () -> listener)
                    .withPropertyValues(client(p, "llm4j.agent.context.window=1200", "llm4j.agent.context.compaction=drop-oldest")).run(ctx -> {
                assertThat(ctx.getBean(ReActAgent.Builder.class).build().run("go").getFinalAnswer()).isEqualTo("finished");
                assertThat(seen).contains(1200, "compaction"); // the agent knows its window, and tried to make the conversation fit it
            });
        }
    }

    @Tag("SB-V5.9")
    @Test
    void aSummaryIsWrittenByTheClientNamedForIt() throws Exception {
        try (var main = new Fixtures.Provider("ollama"); var cheap = new Fixtures.Provider("ollama")) {
            cheap.reply = "SUMMARY-FROM-CHEAP";
            Fixtures.runner().withPropertyValues(client(main, "llm4j.clients.cheap.provider=ollama", "llm4j.clients.cheap.model=m", "llm4j.clients.cheap.base-url=" + cheap.url(),
                    "llm4j.agent.context.compaction=summarize", "llm4j.agent.context.compaction-client=cheap")).run(ctx -> {
                io.github.llm4j.context.ContextCompactor summarizer = Llm4jAgentsAutoConfiguration.compactor(ctx.getBean(Llm4jProperties.class).agent().context(), ctx.getBean(LLMClients.class),
                        ctx.getBean(io.github.llm4j.LLMClient.class));
                List<Message> chat = new ArrayList<>();
                chat.add(Message.system("you help"));
                for (int i = 0; i < 20; i++) {
                    chat.add(Message.user("question " + i + " " + "padding ".repeat(40)));
                    chat.add(Message.assistant("answer " + i + " " + "padding ".repeat(40)));
                }
                var result = summarizer.compact(chat, io.github.llm4j.context.CompactionRequest.builder(200).keepRecent(2).build());
                assertThat(cheap.seen).as("the summary was asked of the client named for it").isNotEmpty();
                assertThat(main.seen).isEmpty();
                assertThat(result.messages().toString()).contains("SUMMARY-FROM-CHEAP");
            });
        }
    }

    @Tag("SB-V5.10")
    @Test
    void strategyOptionsReachTheLibraryAndAPolicyBeanWinsOverSettings() throws Exception {
        try (var p = new Fixtures.Provider("ollama")) {
            // the library refuses a trim limit under 40 characters: seeing that refusal shows the option got there, spelled with a dash
            Fixtures.runner().withPropertyValues(client(p, "llm4j.agent.context.window=800", "llm4j.agent.context.compaction=trim-tool-results",
                    "llm4j.agent.context.compaction-options.max-chars=10")).run(ctx -> {
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> ctx.getBean(ReActAgent.Builder.class)).satisfies(e ->
                        assertThat(rootConfigurationProblem(e)).hasMessageContaining("llm4j.agent.context.compaction").hasMessageContaining("maxChars must be at least 40"));
            });
            Fixtures.runner().withPropertyValues(client(p, "llm4j.agent.context.compaction=trim-tool-results", "llm4j.agent.context.compaction-options.max-chars=200")).run(ctx ->
                    assertThat(Llm4jAgentsAutoConfiguration.compactor(ctx.getBean(Llm4jProperties.class).agent().context(), ctx.getBean(LLMClients.class), ctx.getBean(io.github.llm4j.LLMClient.class)).name())
                            .isEqualTo("trim_tool_results"));
            Fixtures.runner().withBean(io.github.llm4j.context.ContextPolicy.class, () -> io.github.llm4j.context.ContextPolicy.builder().build())
                    .withPropertyValues(client(p, "llm4j.agent.context.window=800")).run(ctx -> assertThat(ctx.getBean(ReActAgent.Builder.class)).isNotNull());
        }
    }

    /** A named class so a bean supplier can name it; its result is long. */
    static final class BigTool implements Tool {
        @Override public String getName() { return "Big"; }
        @Override public String getDescription() { return "returns a lot of text"; }
        @Override public String execute(Map<String, Object> args) { return "BIGRESULT-" + "X".repeat(3000); }
    }

    @Tag("SB-V5.11")
    @Test
    void aConversationSurvivesARestartAndStaysWithItsSession() throws Exception {
        try (var p = new Fixtures.Provider("ollama")) {
            p.reply = Fixtures.Provider.done("ok");
            String[] props = client(p, "llm4j.memory.conversations=file", "llm4j.memory.dir=" + dir, "llm4j.memory.max-history=4");
            Fixtures.runner().withPropertyValues(props).run(ctx -> {
                ConversationStore store = ctx.getBean(ConversationStore.class);
                ReActAgent agent = ctx.getBean(ReActAgent.Builder.class).conversationHistory(new ConversationHistory("s-1", store, 4)).build();
                agent.run("My name is Asha");
            });
            int before = p.seen.size();
            Fixtures.runner().withPropertyValues(props).run(ctx -> {
                ConversationStore store = ctx.getBean(ConversationStore.class);
                ctx.getBean(ReActAgent.Builder.class).conversationHistory(new ConversationHistory("s-1", store, 4)).build().run("What is my name?");
                assertThat(p.seen.get(before).getBody().clone().readUtf8()).contains("My name is Asha");
                ctx.getBean(ReActAgent.Builder.class).conversationHistory(new ConversationHistory("s-2", store, 4)).build().run("Who am I?");
                assertThat(p.seen.get(before + 1).getBody().clone().readUtf8()).doesNotContain("My name is Asha");
            });
            Fixtures.runner().run(ctx -> assertThat(ctx).doesNotHaveBean(ConversationStore.class));
        }
    }

    @Tag("SB-V5.12")
    @Test
    void promptRegistriesComeFromAFolderOrAYamlFile() throws Exception {
        Files.writeString(dir.resolve("prompts.yaml"), "prompts:\n  greeter:\n    template: \"Say hello\"\n");
        Fixtures.runner().withPropertyValues("llm4j.prompts.location=classpath:prompts/").run(ctx -> {
            assertThat(ctx.getBean(PromptRegistry.class)).isInstanceOf(MarkdownFolderPromptRegistry.class);
            assertThat(ctx.getBean(PromptRegistry.class).get("triage")).isPresent();
        });
        Fixtures.runner().withPropertyValues("llm4j.prompts.location=file:" + dir.resolve("prompts.yaml")).run(ctx -> assertThat(ctx.getBean(PromptRegistry.class)).isInstanceOf(FileSystemPromptRegistry.class));
    }

    @Tag("SB-V5.13")
    @Test
    void skillsAreLoadedFromFilesAndRemoteOnesNeedPermission() throws Exception {
        try (var p = new Fixtures.Provider("ollama")) {
            p.reply = Fixtures.Provider.done("ok");
            Fixtures.runner().withPropertyValues(client(p, "llm4j.skills.locations=classpath:skills/refunds.md", "llm4j.agent.skills=Refunds")).run(ctx -> {
                ctx.getBean(ReActAgent.Builder.class).build().run("hi");
                assertThat(p.allBodies()).contains("SKILL-MARKER-55");
            });
        }
        Fixtures.runner().withPropertyValues("llm4j.skills.locations=https://example.com/skills/x.md").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(rootConfigurationProblem(ctx.getStartupFailure()).action()).contains("llm4j.skills.allow-remote");
        });
    }

    @Tag("SB-V4.10")
    @Test
    void theAuditFileRecordsAnAgentsToolCalls() throws Exception {
        try (var p = new Fixtures.Provider("ollama")) {
            p.queue(Fixtures.Provider.react("Alpha", "{}"), Fixtures.Provider.done("ok"));
            Path audit = dir.resolve("audit.jsonl");
            Fixtures.runner().withBean("alpha", Tool.class, () -> tool("Alpha", false, new AtomicInteger())).withPropertyValues(client(p, "llm4j.audit.file=" + audit)).run(ctx -> {
                ctx.getBean(ReActAgent.Builder.class).build().run("use alpha");
            });
            assertThat(Files.readString(audit)).contains("Alpha");
        }
    }
}
