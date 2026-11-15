package io.github.llm4j.loom.effects;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.LLMClient;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.loom.security.SecurityAudit;
import io.github.llm4j.mcp.testkit.FakeMcpServer;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An {@code mcp} block's {@code effects:} and {@code reach:} reach the audit and the run through the
 * production path (phase 36a, D13 and D14; guard G77): asking the audit and running a workflow, never
 * calling a helper.
 */
class McpEffectsWiringTest {

    @TempDir Path dir;

    private static String block(String options) {
        List<String> command = FakeMcpServer.command();
        List<String> quoted = new ArrayList<>();
        for (String a : command.subList(1, command.size())) quoted.add("\"" + a + "\"");
        return "mcp Fake { transport: \"stdio\" cmd: \""
                + command.get(0)
                + "\" args: ["
                + String.join(", ", quoted)
                + "] isolation: process "
                + options
                + " }\n";
    }

    private static String agent(String options) {
        return "agent A { model: \"m\" system: \"s\" mcp_servers: [Fake] " + options + " }\n"
                + "workflow Main() { delegate \"go\" to A -> r }\n";
    }

    private static io.github.llm4j.loom.security.AuditReport audit(String source) {
        return SecurityAudit.audit(
                new LoomParser(new Lexer(source).tokenize()).parseScript(), "t.loom");
    }

    @Test
    void aToolDeclaredToChangeNeedsApprovalAndOneDeclaredToReadDoesNot() {
        String source = block("effects: { echo: reads, drop: changes }") + agent("");

        assertThat(audit(source).agents().get(0).unapprovedEffects())
                .contains("drop")
                .doesNotContain("echo");
    }

    @Test
    void approvingTheChangingToolClearsIt() {
        String source = block("effects: { echo: reads, drop: changes }") + agent("approve: all");

        assertThat(audit(source).agents().get(0).unapprovedEffects())
                .doesNotContain("drop");
    }

    @Test
    void aDeclaredReachNarrowsWhatTheServerIsAssumedToDo() {
        var assumed = audit(block("") + agent("")).agents().get(0);
        var narrowed = audit(block("reach: reads") + agent("")).agents().get(0);

        assertThat(assumed.outward()).isNotEmpty();
        assertThat(narrowed.outward()).isEmpty();
        assertThat(narrowed.privateData()).isNotEmpty();
    }

    /** Runs the agent against the real server and counts how often the policy evaluator is asked. */
    private int evaluatorCalls(String effects, String tool, String input) throws Exception {
        Files.createDirectories(dir.resolve("policies"));
        Files.writeString(dir.resolve("policies/house-rules.md"), "## no_promise\nThe reply promises a refund.\n");
        String source =
                "evaluator Compliance { model: \"judge\" }\n"
                        + "policy HouseRules: \"house-rules\" checked by Compliance\n"
                        + block(effects)
                        + agent("");
        AtomicInteger judged = new AtomicInteger();
        AtomicInteger turn = new AtomicInteger();
        LLMClient client =
                new LLMClient() {
                    @Override
                    public LLMResponse chat(LLMRequest request) {
                        String all =
                                request.getMessages().stream()
                                        .map(m -> m.getContent())
                                        .reduce("", (a, b) -> a + "\n" + b);
                        if (all.contains("You are a policy evaluator.")) {
                            judged.incrementAndGet();
                            return LLMResponse.builder()
                                    .content("{\"no_promise\":{\"violated\":\"no\",\"reason\":\"fine\"}}")
                                    .model("t")
                                    .build();
                        }
                        String reply =
                                turn.getAndIncrement() == 0
                                        ? "```json\n{\"thought\": \"t\", \"action\": \""
                                                + tool
                                                + "\", \"action_input\": "
                                                + input
                                                + "}\n```"
                                        : "```json\n{\"thought\": \"t\", \"final_answer\": \"ok\"}\n```";
                        return LLMResponse.builder().content(reply).model("t").build();
                    }

                    @Override
                    public Stream<LLMResponse> chatStream(LLMRequest request) {
                        return Stream.empty();
                    }
                };
        HarnessExecutor executor =
                new HarnessExecutor(
                        new LoomParser(new Lexer(source).tokenize()).parseScript(),
                        new ToolRegistry(),
                        model -> client);
        executor.setBaseDir(dir);
        executor.setHumanInterface(message -> "yes");
        executor.initialize();
        executor.executeWorkflow("Main", Map.of());
        executor.shutdown();
        return judged.get();
    }

    @Test
    void aPolicyChecksACallToAnMcpToolNobodyDescribed() throws Exception {
        assertThat(evaluatorCalls("", "echo", "{\"message\": \"hello\"}")).isPositive();
    }

    @Test
    void aPolicyDoesNotCheckACallToAnMcpToolDeclaredToRead() throws Exception {
        assertThat(evaluatorCalls("effects: { echo: reads }", "echo", "{\"message\": \"hello\"}"))
                .isZero();
    }

    @Test
    void aPolicyStillChecksADeclaredChangingMcpTool() throws Exception {
        assertThat(evaluatorCalls("effects: { echo: changes }", "echo", "{\"message\": \"hello\"}"))
                .isPositive();
    }

    @Test
    void theRunKnowsADeclaredReadIsARead() throws Exception {
        Collections.emptyList();
        assertThat(
                        io.github.llm4j.loom.execution.McpEffectsAccess.isRead(
                                block("effects: { echo: reads }"), "echo"))
                .isTrue();
        assertThat(io.github.llm4j.loom.execution.McpEffectsAccess.isRead(block(""), "echo"))
                .isFalse();
    }
}
