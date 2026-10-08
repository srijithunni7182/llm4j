package io.github.llm4j.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.LLMClient;
import io.github.llm4j.agent.AgentEventListener;
import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.agent.ReActAgent;
import io.github.llm4j.agent.Tool;
import io.github.llm4j.budget.fixtures.FixedEstimator;
import io.github.llm4j.budget.fixtures.NoUsageClient;
import io.github.llm4j.budget.fixtures.ScriptedLLMClient;
import io.github.llm4j.budget.fixtures.Standard;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Verification plan, Requirement 4 (V4.1–V4.7). */
class ReActAgentBudgetTest {

    private static final Tool ECHO = new Tool() {
        @Override
        public String getName() {
            return "echo";
        }

        @Override
        public String getDescription() {
            return "echoes";
        }

        @Override
        public String execute(Map<String, Object> args) {
            return "echoed " + args;
        }
    };

    /** n tool turns (thoughts T1..Tn), then a final answer. */
    private static String[] turns(int n) {
        List<String> out = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            out.add("```json\n{\"thought\": \"T" + i + "\", \"action\": \"echo\", \"action_input\": {\"i\": " + i + "}}\n```");
        }
        out.add("```json\n{\"thought\": \"done\", \"final_answer\": \"FINAL\"}\n```");
        return out.toArray(String[]::new);
    }

    private static ReActAgent.Builder agent(LLMClient model) {
        return ReActAgent.builder().llmClient(model).addTool(ECHO).maxIterations(20)
                .tokenEstimator(FixedEstimator.INSTANCE);
    }

    @Test
    void v4_1_everyRequestIsCappedPerCall() {
        ScriptedLLMClient model = new ScriptedLLMClient(turns(2));
        agent(model).maxTokensPerCall(50).build().run("go");
        assertThat(model.requests()).isNotEmpty().allSatisfy(r -> assertThat(r.getMaxTokens()).isLessThanOrEqualTo(50));
    }

    @Test
    void v4_2_runningOutReturnsThePartialAnswer() {
        ScriptedLLMClient model = new ScriptedLLMClient(turns(4));
        AgentResult r = agent(model).budget(Budget.builder().calls(2).build()).build().run("go");
        assertThat(r.isCompleted()).isFalse();
        assertThat(r.budgetExhausted()).isTrue();
        assertThat(r.getSteps().get(r.getSteps().size() - 1).getOutcome()).isEqualTo(AgentResult.StepOutcome.BUDGET_EXHAUSTED);
        assertThat(r.getFinalAnswer()).isEqualTo("T2");
        assertThat(r.getUsage().getLlmCalls()).isEqualTo(2);
        assertThat(r.getBudgetExceeded().dimension()).isEqualTo(Dimension.CALLS);
    }

    @Test
    void v4_3_theFailPolicyPropagates() {
        ScriptedLLMClient model = new ScriptedLLMClient(turns(4));
        ReActAgent a = agent(model).budget(Budget.builder().calls(2).build()).onBudgetExhausted(BudgetPolicy.FAIL).build();
        assertThatThrownBy(() -> a.run("go")).isInstanceOf(BudgetExceeded.class);
    }

    @Test
    void v4_4_aRefusedCallIsNeverRetried() {
        ScriptedLLMClient model = new ScriptedLLMClient(turns(4));
        AgentResult r = agent(model).budget(Budget.builder().calls(1).build()).build().run("go");
        assertThat(model.calls()).isEqualTo(1);
        assertThat(r.budgetExhausted()).isTrue();
    }

    @Test
    void v4_5_estimatedUsageAndCostReachTheResult() {
        String answer = "```json\n{\"thought\": \"t\", \"final_answer\": \"" + "x".repeat(380) + "\"}\n```";
        AgentResult unpriced = agent(new NoUsageClient(answer)).budget(Budget.unlimited("run")).build().run("go");
        assertThat(unpriced.getUsage().isEstimated()).isTrue();
        assertThat(unpriced.getUsage().getCost()).isNull();

        AgentResult priced = agent(new ScriptedLLMClient(turns(0))).budget(Budget.unlimited("run"))
                .priceTable(Standard.PRICES).budgetModel("test/model").build().run("go");
        assertThat(priced.getUsage().isEstimated()).isFalse();
        assertThat(priced.getUsage().getCost()).isEqualByComparingTo("0.0002");
    }

    @Test
    void v4_6_listenersHearTheWarningThenExhaustion() {
        List<BudgetEvent.Kind> events = new ArrayList<>();
        AgentEventListener listener = new AgentEventListener() {
            @Override public void onThought(String thought) { }
            @Override public void onAction(String toolName, String toolInput) { }
            @Override public void onObservation(String observation) { }
            @Override public void onBudget(BudgetEvent event) { events.add(event.kind()); }
        };
        ScriptedLLMClient model = new ScriptedLLMClient(turns(10));
        AgentResult r = agent(model).budget(Budget.builder().tokens(1000).warnAt(0.8).build())
                .maxTokensPerCall(50).addListener(listener).build().run("go");
        assertThat(model.calls()).isEqualTo(6);
        assertThat(r.budgetExhausted()).isTrue();
        assertThat(events).containsExactly(BudgetEvent.Kind.WARNING, BudgetEvent.Kind.EXHAUSTED);
    }

    @Test
    void v4_7_anUnbudgetedAgentIsUntouched() throws Exception {
        ScriptedLLMClient model = new ScriptedLLMClient(turns(2));
        ReActAgent a = ReActAgent.builder().llmClient(model).addTool(ECHO).build();
        var field = ReActAgent.class.getDeclaredField("llmClient");
        field.setAccessible(true);
        assertThat(field.get(a)).isSameAs(model);
        AgentResult r = a.run("go");
        assertThat(r.getFinalAnswer()).isEqualTo("FINAL");
        assertThat(r.isCompleted()).isTrue();
        assertThat(r.budgetExhausted()).isFalse();
        assertThat(r.getUsage()).isEqualTo(new AgentResult.Usage(3, 300, 150, 450));
        assertThat(model.requests()).allSatisfy(req -> assertThat(req.getMaxTokens()).isNull());
    }

    @Test
    void toBuilderKeepsTheBudgetAndWrapsOnlyOnce() {
        Budget b = Budget.builder().calls(1).build();
        ScriptedLLMClient model = new ScriptedLLMClient(turns(3));
        ReActAgent a = agent(model).budget(b).build().toBuilder().build();
        assertThat(a.run("go").budgetExhausted()).isTrue();
        assertThat(model.calls()).isEqualTo(1);
    }
}
