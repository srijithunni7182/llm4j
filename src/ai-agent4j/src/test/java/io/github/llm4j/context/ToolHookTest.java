package io.github.llm4j.context;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.agent.AgentEventListener;
import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.agent.ReActAgent;
import io.github.llm4j.agent.Tool;
import io.github.llm4j.agent.ToolHook;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.Message;
import io.github.llm4j.model.ToolSchema;
import io.github.llm4j.telemetry.ErrorCategory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Checks on tool calls: the schema check and hooks that reject, replace or rewrite. */
class ToolHookTest {

    /** A tool with a real schema; records what it was called with. */
    static final class Lookup implements Tool {
        final List<Map<String, Object>> calls = new ArrayList<>();
        boolean needsApproval;

        public String getName() {
            return "lookup";
        }

        public String getDescription() {
            return "looks things up";
        }

        public Map<String, Object> getParametersSchema() {
            return ToolSchema.object()
                    .string("q", "what to find", true)
                    .integer("limit", "how many", false)
                    .enumeration("sort", "order", false, List.of("new", "old"))
                    .build();
        }

        public boolean requiresApproval(Map<String, Object> args) {
            return needsApproval;
        }

        public String execute(Map<String, Object> args) {
            calls.add(args);
            return "found: " + args.get("q") + " key=sk-SECRET-123";
        }
    }

    /** Asks for the tool with these arguments, then answers once it has seen a result. */
    static ScriptedModel asks(Map<String, Object>... attempts) {
        return ScriptedModel.by(
                true,
                true,
                n ->
                        n < attempts.length
                                ? ScriptedModel.calls(
                                        "trying",
                                        ScriptedModel.call("c" + n, "lookup", attempts[n]))
                                : ScriptedModel.answer("done"));
    }

    static String lastToolResult(LLMRequest request) {
        List<Message> messages = request.getMessages();
        for (int i = messages.size() - 1; i >= 0; i--)
            if (messages.get(i).getRole() == Message.Role.TOOL) return messages.get(i).getContent();
        return null;
    }

    @SafeVarargs
    static ReActAgent.Builder agent(
            ScriptedModel model, Lookup tool, Map<String, Object>... ignored) {
        return ReActAgent.builder().llmClient(model).addTool(tool);
    }

    @Test
    @SuppressWarnings("unchecked")
    void theSchemaCheckStopsABadCallAndTellsTheModelWhatToFix() {
        Lookup tool = new Lookup();
        ScriptedModel model =
                asks(Map.of("limit", "ten", "sort", "sideways"), Map.of("q", "cats", "limit", 10));
        List<String> rejected = new ArrayList<>();
        AgentEventListener listener =
                new AgentEventListener() {
                    public void onThought(String t) {}

                    public void onAction(String n, String i) {}

                    public void onObservation(String o) {}

                    @Override
                    public void onToolRejected(String n, String m) {
                        rejected.add(m);
                    }
                };

        AgentResult r =
                agent(model, tool)
                        .validateToolArguments(true)
                        .addListener(listener)
                        .build()
                        .run("find cats");

        assertThat(r.getFinalAnswer()).isEqualTo("done");
        assertThat(tool.calls).hasSize(1); // only the corrected call ran
        assertThat(tool.calls.get(0)).containsEntry("q", "cats");
        String told = lastToolResult(model.requests.get(1));
        assertThat(told)
                .contains("was not run")
                .contains("'q' is required but missing")
                .contains("'limit' must be an integer")
                .contains("'sort' must be one of [new, old]");
        assertThat(rejected).hasSize(1);
        assertThat(r.getSteps().get(0).getOutcome())
                .isEqualTo(AgentResult.StepOutcome.REJECTED_BY_HOOK);
        assertThat(r.getSteps().get(1).getOutcome()).isEqualTo(AgentResult.StepOutcome.EXECUTED);
        assertThat(r.getStats().tools().toString()).contains("INVALID_ARGUMENTS");
    }

    @Test
    @SuppressWarnings("unchecked")
    void withoutTheSwitchNothingIsChecked() {
        Lookup tool = new Lookup();
        ScriptedModel model = asks(Map.of("limit", "ten"));

        agent(model, tool).build().run("find cats");

        assertThat(tool.calls).hasSize(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aHookCanRejectWithAReasonTheModelCanUse() {
        Lookup tool = new Lookup();
        ScriptedModel model = asks(Map.of("q", "x", "limit", 5000), Map.of("q", "x", "limit", 50));
        ToolHook cap =
                new ToolHook() {
                    @Override
                    public Decision beforeCall(String name, Map<String, Object> args) {
                        return args.get("limit") instanceof Integer n && n > 100
                                ? Decision.reject("limit must be at most 100; you sent " + n)
                                : Decision.allow();
                    }
                };

        AgentResult r = agent(model, tool).addToolHook(cap).build().run("find x");

        assertThat(lastToolResult(model.requests.get(1)))
                .contains("limit must be at most 100; you sent 5000");
        assertThat(tool.calls).hasSize(1);
        assertThat(tool.calls.get(0)).containsEntry("limit", 50);
        assertThat(r.getSteps().get(0).getOutcome())
                .isEqualTo(AgentResult.StepOutcome.REJECTED_BY_HOOK);
    }

    @Test
    @SuppressWarnings("unchecked")
    void replacedArgumentsReachTheToolAndThePersonWhoApproves() {
        Lookup tool = new Lookup();
        tool.needsApproval = true;
        ScriptedModel model = asks(Map.of("q", "  Cats  "));
        List<Map<String, Object>> approvedWith = new ArrayList<>();
        ToolHook tidy =
                new ToolHook() {
                    @Override
                    public Decision beforeCall(String name, Map<String, Object> args) {
                        return Decision.replaceArguments(
                                Map.of("q", String.valueOf(args.get("q")).trim().toLowerCase()));
                    }
                };

        agent(model, tool)
                .addToolHook(tidy)
                .approvalCallback(
                        (n, args, t) -> {
                            approvedWith.add(args);
                            return true;
                        })
                .build()
                .run("find cats");

        assertThat(approvedWith).containsExactly(Map.of("q", "cats"));
        assertThat(tool.calls).containsExactly(Map.of("q", "cats"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void anAfterHookChangesWhatTheModelSees() {
        Lookup tool = new Lookup();
        ScriptedModel model = asks(Map.of("q", "cats"));
        ToolHook redact =
                new ToolHook() {
                    @Override
                    public String afterCall(String name, Map<String, Object> args, String result) {
                        return result.replaceAll("sk-[A-Z0-9-]+", "[KEY]");
                    }
                };

        agent(model, tool).addToolHook(redact).build().run("find cats");

        assertThat(lastToolResult(model.requests.get(1))).isEqualTo("found: cats key=[KEY]");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aHookThatThrowsMeansTheCallDoesNotRun() {
        Lookup tool = new Lookup();
        ScriptedModel model = asks(Map.of("q", "cats"));
        ToolHook broken =
                new ToolHook() {
                    @Override
                    public Decision beforeCall(String name, Map<String, Object> args) {
                        throw new IllegalStateException("policy service down");
                    }
                };

        AgentResult r = agent(model, tool).addToolHook(broken).build().run("find cats");

        assertThat(tool.calls).isEmpty();
        assertThat(lastToolResult(model.requests.get(1))).contains("could not be completed");
        assertThat(r.getSteps().get(0).getOutcome())
                .isEqualTo(AgentResult.StepOutcome.REJECTED_BY_HOOK);
    }

    @Test
    @SuppressWarnings("unchecked")
    void anAfterHookThatThrowsWithholdsTheResult() {
        Lookup tool = new Lookup();
        ScriptedModel model = asks(Map.of("q", "cats"));
        ToolHook broken =
                new ToolHook() {
                    @Override
                    public String afterCall(String name, Map<String, Object> args, String result) {
                        throw new IllegalStateException("x");
                    }
                };

        agent(model, tool).addToolHook(broken).build().run("find cats");

        assertThat(lastToolResult(model.requests.get(1)))
                .doesNotContain("sk-SECRET")
                .contains("withheld");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aRejectCanCarryItsOwnCategory() {
        Lookup tool = new Lookup();
        ScriptedModel model = asks(Map.of("q", "salaries"));
        ToolHook policy =
                new ToolHook() {
                    @Override
                    public Decision beforeCall(String name, Map<String, Object> args) {
                        return Decision.reject(
                                "searching for salaries is not allowed",
                                ErrorCategory.PERMISSION_DENIED);
                    }
                };

        AgentResult r = agent(model, tool).addToolHook(policy).build().run("find salaries");

        assertThat(tool.calls).isEmpty();
        assertThat(r.getStats().tools().toString()).contains("PERMISSION_DENIED");
    }
}
