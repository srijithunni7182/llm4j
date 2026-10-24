package io.github.llm4j.loom.generate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** R3, R4, R6 of spec 12: the plan as a document, and what makes one unfit to build from. */
class PlanTest {

    private static List<String> problems(Plan p) {
        return PlanChecker.check(p, null);
    }

    @Test
    void aSoundPlanHasNoProblemsAndRoundTripsThroughJson() {
        Plan p = GenerateFixtures.plan();
        assertThat(problems(p)).isEmpty();
        Plan again = PlanJson.read(PlanJson.write(p));
        assertThat(again).isEqualTo(p);
        assertThat(PlanJson.hash(again)).isEqualTo(PlanJson.hash(p));
    }

    @Test
    void aChangeToThePlanChangesItsFingerprint() {
        Plan p = GenerateFixtures.plan();
        Plan edited =
                new Plan(
                        p.version(),
                        p.name(),
                        p.summary() + "!",
                        p.answers(),
                        p.agents(),
                        p.classifiers(),
                        p.tools(),
                        p.humans(),
                        p.effects(),
                        p.budget(),
                        p.failure(),
                        p.tests(),
                        p.personalData());
        assertThat(PlanJson.hash(edited)).isNotEqualTo(PlanJson.hash(p));
    }

    @Test
    void theMarkdownNamesEveryPartOfThePlan() {
        String md = PlanMarkdown.render(GenerateFixtures.plan());
        assertThat(md)
                .contains(
                        "# Plan: SupportNotify",
                        "**Drafter**",
                        "**Notifier**",
                        "tools: Hook",
                        "**Hook** (webhook, reach sends)",
                        "team lead",
                        "a person approves each call",
                        "20000 tokens / 10 calls",
                        "approved reply is sent",
                        "(default)".substring(0, 0));
    }

    @Test
    void anUnreadablePlanIsRefusedWithAReason() {
        assertThatThrownBy(() -> PlanJson.read("{not json")).hasMessageContaining("not valid JSON");
        assertThatThrownBy(() -> PlanJson.read("")).isInstanceOf(IllegalArgumentException.class);
    }

    private static Plan with(java.util.function.UnaryOperator<Builder> edit) {
        return edit.apply(new Builder(GenerateFixtures.plan())).build();
    }

    /** Edits one part of a plan at a time. */
    record Builder(
            Plan p,
            List<Plan.Agent> agents,
            List<Plan.Tool> tools,
            List<Plan.Effect> effects,
            Plan.Budget budget,
            List<Plan.TestIdea> tests,
            String name) {
        Builder(Plan p) {
            this(p, p.agents(), p.tools(), p.effects(), p.budget(), p.tests(), p.name());
        }

        Builder agents(List<Plan.Agent> a) {
            return new Builder(p, a, tools, effects, budget, tests, name);
        }

        Builder tools(List<Plan.Tool> t) {
            return new Builder(p, agents, t, effects, budget, tests, name);
        }

        Builder effects(List<Plan.Effect> e) {
            return new Builder(p, agents, tools, e, budget, tests, name);
        }

        Builder budget(Plan.Budget b) {
            return new Builder(p, agents, tools, effects, b, tests, name);
        }

        Builder tests(List<Plan.TestIdea> t) {
            return new Builder(p, agents, tools, effects, budget, t, name);
        }

        Builder name(String n) {
            return new Builder(p, agents, tools, effects, budget, tests, n);
        }

        Plan build() {
            return new Plan(
                    p.version(),
                    name,
                    p.summary(),
                    p.answers(),
                    agents,
                    p.classifiers(),
                    tools,
                    p.humans(),
                    effects,
                    budget,
                    p.failure(),
                    tests,
                    p.personalData());
        }
    }

    @Test
    void anAgentNeedsARoleAModelInTheAllowedSetAndAPromptName() {
        Plan p =
                with(
                        b ->
                                b.agents(
                                        List.of(
                                                new Plan.Agent(
                                                        "Drafter",
                                                        "gpt-x",
                                                        "",
                                                        List.of(),
                                                        "Bad Name"))));
        List<String> found = PlanChecker.check(p, Set.of("claude-haiku-5-5"));
        assertThat(found).anyMatch(s -> s.contains("has no role"));
        assertThat(found).anyMatch(s -> s.contains("gpt-x") && s.contains("not an allowed model"));
        assertThat(found).anyMatch(s -> s.contains("prompt file name"));
    }

    @Test
    void aToolIsABuiltInKindOrADeclaredStub() {
        Plan p =
                with(b -> b.tools(List.of(new Plan.Tool("Hook", "teleport", "sends", false, "x"))));
        assertThat(problems(p)).anyMatch(s -> s.contains("not built in") && s.contains("stub"));
        Plan stub =
                with(b -> b.tools(List.of(new Plan.Tool("Hook", "teleport", "sends", true, "x"))));
        assertThat(problems(stub)).noneMatch(s -> s.contains("not built in"));
    }

    @Test
    void aToolMustSayWhatItReaches() {
        Plan p = with(b -> b.tools(List.of(new Plan.Tool("Hook", "webhook", null, false, "x"))));
        assertThat(problems(p)).anyMatch(s -> s.contains("must say what it reaches"));
    }

    @Test
    void everyEffectHasAnApprovalOrAReasonForNone() {
        Plan none = with(b -> b.effects(List.of(new Plan.Effect("Hook", "sends", "none", " "))));
        assertThat(problems(none)).anyMatch(s -> s.contains("no approval and no reason"));
        Plan reasoned =
                with(
                        b ->
                                b.effects(
                                        List.of(
                                                new Plan.Effect(
                                                        "Hook",
                                                        "sends",
                                                        "none",
                                                        "an internal test channel"))));
        assertThat(problems(reasoned)).isEmpty();
    }

    @Test
    void aToolThatSendsMustBeListedAsAnEffect() {
        Plan p = with(b -> b.effects(List.of()));
        assertThat(problems(p)).anyMatch(s -> s.contains("Hook") && s.contains("lists no effect"));
    }

    @Test
    void anAgentCannotUseATheToolThePlanDoesNotDeclare() {
        Plan p = with(b -> b.tools(List.of()).effects(List.of()));
        assertThat(problems(p))
                .anyMatch(s -> s.contains("uses the tool Hook, which the plan does not declare"));
    }

    @Test
    void aBudgetAndTestsAreRequired() {
        assertThat(problems(with(b -> b.budget(null)))).anyMatch(s -> s.contains("no budget"));
        assertThat(problems(with(b -> b.budget(new Plan.Budget(-1L, null)))))
                .anyMatch(s -> s.contains("no budget"));
        assertThat(problems(with(b -> b.tests(List.of())))).anyMatch(s -> s.contains("no tests"));
    }

    @Test
    void namesAreTidyAndUnique() {
        assertThat(problems(with(b -> b.name("support notify"))))
                .anyMatch(s -> s.contains("workflow name"));
        Plan twice = with(b -> b.agents(List.of(b.agents().get(0), b.agents().get(0))));
        assertThat(problems(twice)).anyMatch(s -> s.contains("used twice"));
    }

    @Test
    void aShellToolNeedsAReason() {
        Plan p = with(b -> b.tools(List.of(new Plan.Tool("Hook", "shell", "sends", false, ""))));
        assertThat(problems(p)).anyMatch(s -> s.contains("shell tool: say why"));
    }

    @Test
    void aPlanThatCannotBeBuiltIsAskedAgainOnceWithTheProblems() {
        Plan p = GenerateFixtures.plan();
        Plan empty =
                new Plan(
                        1,
                        p.name(),
                        p.summary(),
                        p.answers(),
                        List.of(),
                        List.of(),
                        p.tools(),
                        p.humans(),
                        p.effects(),
                        p.budget(),
                        p.failure(),
                        p.tests(),
                        false);
        ScriptedModel model = ScriptedModel.of(PlanJson.write(empty), PlanJson.write(p));
        Plan got = Planner.plan(model, "m", "x", List.of(), "ref", null);
        assertThat(got.agents()).isNotEmpty();
        assertThat(model.calls()).isEqualTo(2);
        assertThat(model.asked.get(1)).contains("has these problems").contains("no agent");
    }

    @Test
    void thePlannerNamesTheToolKindsThatAreBuiltIn() {
        assertThat(Planner.SYSTEM).contains("duckduckgo").contains("webhook").contains("stub");
    }
}
