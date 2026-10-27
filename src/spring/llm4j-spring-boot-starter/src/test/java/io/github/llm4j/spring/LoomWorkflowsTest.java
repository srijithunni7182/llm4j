package io.github.llm4j.spring;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.host.LoomHost;
import io.github.llm4j.loom.host.RunOutcome;
import io.github.llm4j.spring.scan.Greetings;
import io.github.llm4j.spring.scan.ScanApp;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** R17 to R21 of spec 15: workflows as beans, checked at start, with no model in the test. */
class LoomWorkflowsTest {

    interface WrongName {
        RunOutcome mian(String name);
    }

    interface WrongArity {
        RunOutcome main();
    }

    interface WrongReturn {
        String main(String name);
    }

    /** A facade as bindgen writes it: an interface with a static of(LoomHost). */
    interface Facade {
        String host();

        static Facade of(LoomHost host) {
            return () -> host.script().getWorkflows().get(0).getName();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableLoomWorkflows(Facade.class)
    static class FacadeApp {}

    @Configuration(proxyBeanMethods = false)
    @EnableLoomWorkflows(WrongName.class)
    static class WrongNameApp {}

    @Configuration(proxyBeanMethods = false)
    @EnableLoomWorkflows(WrongArity.class)
    static class WrongArityApp {}

    @Configuration(proxyBeanMethods = false)
    @EnableLoomWorkflows(WrongReturn.class)
    static class WrongReturnApp {}

    @LoomTest
    @Configuration(proxyBeanMethods = false)
    static class TestApp {}

    private ApplicationContextRunner app(Class<?>... configs) {
        return Fixtures.runner()
                .withUserConfiguration(configs)
                .withPropertyValues("llm4j.loom.script=classpath:scripts/greet.loom");
    }

    @Test
    void anAnnotatedInterfaceIsABeanThatRunsTheWorkflowItIsNamedAfter() {
        app(ScanApp.class, TestApp.class)
                .run(
                        ctx -> {
                            assertThat(ctx).hasNotFailed();
                            ctx.getBean(ScriptedLoomModels.class).agent("Greeter", "Hello, Asha!");
                            Greetings g = ctx.getBean(Greetings.class);
                            RunOutcome out = g.main("Asha");
                            assertThat(out).isInstanceOf(RunOutcome.Completed.class);
                            assertThat(
                                            ((RunOutcome.Completed) out)
                                                    .variables()
                                                    .get("result")
                                                    .toString())
                                    .contains("Hello, Asha!");
                            assertThat(((RunOutcome.Completed) g.other()).variables().get("result"))
                                    .isEqualTo("other");
                            assertThat(ctx.getBean(ScriptedLoomModels.class).calls("Greeter"))
                                    .isEqualTo(1);
                        });
    }

    @Test
    void aMethodThatMatchesNoWorkflowStopsTheStartAndNamesTheNearestOne() {
        app(WrongNameApp.class, TestApp.class)
                .run(
                        ctx ->
                                assertThat(ctx.getStartupFailure())
                                        .hasStackTraceContaining(
                                                "WrongName.mian matches no workflow")
                                        .hasStackTraceContaining("did you mean main?"));
    }

    @Test
    void theWrongNumberOfParametersOrTheWrongReturnTypeStopsTheStartToo() {
        app(WrongArityApp.class, TestApp.class)
                .run(
                        ctx ->
                                assertThat(ctx.getStartupFailure())
                                        .hasStackTraceContaining(
                                                "one String for each of the workflow's parameters"));
        app(WrongReturnApp.class, TestApp.class)
                .run(
                        ctx ->
                                assertThat(ctx.getStartupFailure())
                                        .hasStackTraceContaining(
                                                "returns String; a workflow method returns RunOutcome"));
    }

    @Test
    void aGeneratedFacadeIsBuiltWithItsOfMethod() {
        app(FacadeApp.class, TestApp.class)
                .run(
                        ctx -> {
                            assertThat(ctx).hasNotFailed();
                            assertThat(ctx.getBean(Facade.class).host()).isEqualTo("Main");
                        });
    }

    @Test
    void anApplicationWithoutTheAnnotationsSeesNothingNew() {
        app(TestApp.class)
                .run(
                        ctx -> {
                            assertThat(ctx).hasNotFailed();
                            assertThat(ctx).hasSingleBean(LoomRuntime.class);
                            assertThat(ctx).hasSingleBean(WorkflowRuns.class);
                            assertThat(ctx).doesNotHaveBean(Greetings.class);
                            assertThat(ctx.getBeansOfType(LoomWorkflowFactory.class)).isEmpty();
                        });
        new ApplicationContextRunner()
                .withConfiguration(
                        org.springframework.boot.autoconfigure.AutoConfigurations.of(
                                Llm4jLoomAutoConfiguration.class))
                .run(ctx -> assertThat(ctx).doesNotHaveBean(WorkflowRuns.class));
    }

    @Test
    void workflowRunsLooksAtRunsAndRefusesToAnswerWhereNothingWaits() {
        app(TestApp.class)
                .run(
                        ctx -> {
                            WorkflowRuns runs = ctx.getBean(WorkflowRuns.class);
                            ctx.getBean(LoomRuntime.class)
                                    .run("Main", java.util.Map.of("name", "x"));
                            assertThat(runs.status("nope")).isEmpty();
                            assertThat(runs.answer("nope", "Main/s0", "yes", "asha").recorded())
                                    .isFalse();
                        });
    }

    @Test
    void facadesAreRegisteredOnlyWhereTheStarterHasAScript() {
        Fixtures.runner()
                .withUserConfiguration(FacadeApp.class)
                .run(
                        ctx -> {
                            assertThat(ctx).hasNotFailed();
                            assertThat(ctx).doesNotHaveBean(Facade.class);
                        });
        app(FacadeApp.class, TestApp.class)
                .withPropertyValues("llm4j.enabled=false")
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void theScriptedModelsFindTheAgentByNameWhateverTheSystemTextSaysAndWhereverItIs() {
        ScriptedLoomModels m =
                new ScriptedLoomModels().agent("Triage-Bot", "billing").agent("*", "anything");
        io.github.llm4j.LLMClient client = m.createClient("any");
        var withSystemSecond =
                io.github.llm4j.model.LLMRequest.builder()
                        .addUserMessage("hello")
                        .addSystemMessage("Your role is the Triage-Bot of the shop.")
                        .build();
        assertThat(client.chat(withSystemSecond).getContent()).contains("billing");
        assertThat(m.calls("Triage-Bot")).isEqualTo(1);
        var other =
                io.github.llm4j.model.LLMRequest.builder()
                        .addSystemMessage("Something else entirely.")
                        .addUserMessage("x")
                        .build();
        assertThat(client.chat(other).getContent()).contains("anything");
        ScriptedLoomModels plain = new ScriptedLoomModels();
        var youAre =
                io.github.llm4j.model.LLMRequest.builder()
                        .addSystemMessage("You are Writer-2.")
                        .addUserMessage("x")
                        .build();
        assertThat(plain.createClient("m").chat(youAre).getContent()).contains("Writer-2#1");
    }

    @Test
    void loomTestKeepsTheApplicationsOwnSecretsAndOnlyFakesProviderKeys() {
        io.github.llm4j.secret.InMemorySecretStore real =
                new io.github.llm4j.secret.InMemorySecretStore();
        real.put("MY_SECRET", "s3");
        app(TestApp.class)
                .withBean(io.github.llm4j.secret.SecretStore.class, () -> real)
                .run(
                        ctx -> {
                            assertThat(ctx).hasNotFailed();
                            var store = ctx.getBean(io.github.llm4j.secret.SecretStore.class);
                            assertThat(store.contains("MY_SECRET")).isTrue();
                            assertThat(store.resolve("MY_SECRET")).isEqualTo("s3");
                            assertThat(store.contains("OTHER_SECRET")).isFalse();
                            assertThat(store.contains("GEMINI_API_KEY")).isTrue();
                        });
    }

    @Test
    void anOrchestratorThatMentionsAnotherAgentIsStillTheOrchestrator() {
        ScriptedLoomModels m = new ScriptedLoomModels().agent("Triage", "t").agent("Boss", "b");
        var req =
                io.github.llm4j.model.LLMRequest.builder()
                        .addSystemMessage("You are Boss. Hand billing questions off to Triage.")
                        .addUserMessage("x")
                        .build();
        for (int i = 0; i < 5; i++)
            assertThat(m.createClient("m").chat(req).getContent())
                    .contains("\"b\"".replace("\"", ""));
        assertThat(m.calls("Boss")).isEqualTo(5);
        assertThat(m.calls("Triage")).isZero();
    }
}
