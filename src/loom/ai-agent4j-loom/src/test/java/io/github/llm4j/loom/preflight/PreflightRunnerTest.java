package io.github.llm4j.loom.preflight;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.LLMClient;
import io.github.llm4j.context.ContextWindows;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.LLMClientFactory;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * F30 and F31: between "the script checks clean" and "build the dataset" nothing tried one real
 * answer, so a model that 404s, an answer cut at the limit, a shared prompt that never arrived and
 * grounded agents without sources were all found late. Preflight sends one probe per agent through
 * the agent's real prompts and says, per check, pass, warn, fail or skipped, with the one fix.
 */
class PreflightRunnerTest {
    private enum Behaviour { ECHO_CANARY, IGNORE_CANARY, CUT, BLANK }

    /** A provider that answers by a behaviour and remembers every system prompt it was sent. */
    private static final class Fake implements LLMClientFactory {
        final List<String> systems = Collections.synchronizedList(new ArrayList<>());
        final Behaviour behaviour;
        String noKeyFor;

        Fake(Behaviour behaviour) {
            this.behaviour = behaviour;
        }

        @Override
        public String problem(String modelName) {
            return modelName.equals(noKeyFor) ? "GEMINI_API_KEY is not set" : null;
        }

        @Override
        public LLMClient createClient(String model) {
            return new LLMClient() {
                @Override
                public LLMResponse chat(LLMRequest request) {
                    String system = request.getMessages().get(0).getContent();
                    systems.add(system);
                    String canary = "";
                    int at = system.indexOf("CANARY-");
                    if (at >= 0) canary = system.substring(at, system.indexOf(' ', at) < 0 ? system.length() : Math.min(system.length(), at + 15)).replaceAll("[^A-Za-z0-9-].*$", "");
                    String answer =
                            behaviour == Behaviour.BLANK
                                    ? ""
                                    : behaviour == Behaviour.ECHO_CANARY && !canary.isEmpty()
                                            ? canary + " Hello there."
                                            : "Hello there.";
                    String reply = "```json\n{\"thought\": \"t\", \"final_answer\": \"" + answer + "\"}\n```";
                    var b = LLMResponse.builder().content(reply).model(model).tokenUsage(100, 20, 120);
                    if (behaviour == Behaviour.CUT) b.finishReason(LLMResponse.FinishReason.LENGTH);
                    return b.build();
                }

                @Override
                public Stream<LLMResponse> chatStream(LLMRequest request) {
                    return Stream.of(chat(request));
                }
            };
        }
    }

    private static final String SCRIPT =
            "shared_prompt: \"Be brief.\"\nagent Writer { model: \"gemini-x\" system: \"You write.\" }\nagent Editor { model: \"gemini-x\" system: \"You edit.\" }\nworkflow Main(t) { delegate \"{t}\" to Writer -> r }\n";
    private static final String NO_SHARED =
            "agent Writer { model: \"gemini-x\" system: \"You write.\" }\nworkflow Main(t) { delegate \"{t}\" to Writer -> r }\n";

    private static LoomScript parse(String source) {
        return new LoomParser(new Lexer(source).tokenize()).parseScript();
    }

    private PreflightRunner.Executors executors(String source, Fake fake) {
        return (canaryInstruction, tokenCap, costCap) -> {
            LoomScript fresh = parse(source);
            if (canaryInstruction != null)
                fresh.setSharedPrompt(
                        (fresh.getSharedPrompt() == null ? "" : fresh.getSharedPrompt() + "\n\n") + canaryInstruction,
                        null,
                        1);
            HarnessExecutor e = new HarnessExecutor(fresh, new ToolRegistry(), fake);
            e.setHumanInterface(m -> "yes");
            e.setEnvLookup(name -> "x");
            e.setBudgetOverrides(tokenCap, null, costCap);
            e.initialize();
            return e;
        };
    }

    private PreflightRunner.Report run(String source, Fake fake, Long tokens) {
        return PreflightRunner.run(
                parse(source), executors(source, fake), fake, a -> "Say hello.", tokens, null, null,
                ContextWindows.defaults(), Clock.systemUTC(), "weave test");
    }

    private static PreflightRunner.Check check(PreflightRunner.Report r, String id, String agent) {
        return r.checks().stream()
                .filter(c -> c.id().equals(id) && java.util.Objects.equals(c.agent(), agent))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + id + " for " + agent + " in " + r.checks()));
    }

    @Test
    void everyCheckPassesWhenTheKeyIsThereTheAnswerIsWholeAndTheCanaryComesBack() {
        PreflightRunner.Report r = run(SCRIPT, new Fake(Behaviour.ECHO_CANARY), 50_000L);

        for (String agent : List.of("Writer", "Editor")) {
            assertThat(check(r, "C1", agent).result()).isEqualTo(PreflightRunner.Result.PASS);
            assertThat(check(r, "C2", agent).result()).isEqualTo(PreflightRunner.Result.PASS);
            assertThat(check(r, "C3", agent).result()).isEqualTo(PreflightRunner.Result.PASS);
            assertThat(check(r, "C4", agent).result()).isEqualTo(PreflightRunner.Result.PASS);
        }
        assertThat(r.exitCode()).isZero();
        assertThat(r.tokens()).isPositive();
    }

    @Test
    void aMissingKeyFailsThatAgentWithTheFixAndSkipsTheCallsThatNeedIt() {
        Fake fake = new Fake(Behaviour.ECHO_CANARY);
        fake.noKeyFor = "gemini-x";

        PreflightRunner.Report r = run(SCRIPT, fake, 50_000L);

        assertThat(check(r, "C1", "Writer").result()).isEqualTo(PreflightRunner.Result.FAIL);
        assertThat(check(r, "C1", "Writer").fix()).contains("GEMINI_API_KEY");
        assertThat(check(r, "C3", "Writer").result()).isEqualTo(PreflightRunner.Result.SKIPPED);
        assertThat(check(r, "C3", "Writer").detail()).contains("no key");
        assertThat(fake.systems).as("no call was made").isEmpty();
        assertThat(r.exitCode()).isEqualTo(1);
    }

    @Test
    void anAnswerCutAtTheLimitFailsTheWholeAnswerCheck() {
        PreflightRunner.Report r = run(SCRIPT, new Fake(Behaviour.CUT), 50_000L);

        assertThat(check(r, "C3", "Writer").result()).isEqualTo(PreflightRunner.Result.FAIL);
        assertThat(check(r, "C3", "Writer").detail()).contains("cut");
    }

    @Test
    void aBlankAnswerFailsTheWholeAnswerCheck() {
        PreflightRunner.Report r = run(SCRIPT, new Fake(Behaviour.BLANK), 50_000L);

        assertThat(check(r, "C3", "Writer").result()).isEqualTo(PreflightRunner.Result.FAIL);
    }

    @Test
    void aModelThatIgnoresTheCanaryFailsTheSharedPromptCheck() {
        PreflightRunner.Report r = run(SCRIPT, new Fake(Behaviour.IGNORE_CANARY), 50_000L);

        PreflightRunner.Check c4 = check(r, "C4", "Writer");
        assertThat(c4.result()).isEqualTo(PreflightRunner.Result.FAIL);
        assertThat(c4.detail()).contains("does not begin with").contains("shared prompt");
    }

    @Test
    void aScriptWithoutASharedPromptSkipsTheCanaryAndSendsNoCanaryInstruction() {
        Fake fake = new Fake(Behaviour.ECHO_CANARY);

        PreflightRunner.Report r = run(NO_SHARED, fake, 50_000L);

        assertThat(check(r, "C4", "Writer").result()).isEqualTo(PreflightRunner.Result.SKIPPED);
        assertThat(String.join("\n", fake.systems)).doesNotContain("CANARY-");
        assertThat(check(r, "C3", "Writer").result()).isEqualTo(PreflightRunner.Result.PASS);
    }

    @Test
    void theCanaryIsDifferentEveryRunAndNeverInTheRecord() {
        Fake first = new Fake(Behaviour.ECHO_CANARY);
        Fake second = new Fake(Behaviour.ECHO_CANARY);

        PreflightRunner.Report a = run(SCRIPT, first, 50_000L);
        PreflightRunner.Report b = run(SCRIPT, second, 50_000L);

        String canaryA = first.systems.get(0).replaceAll("(?s).*(CANARY-[0-9a-f]+).*", "$1");
        String canaryB = second.systems.get(0).replaceAll("(?s).*(CANARY-[0-9a-f]+).*", "$1");
        assertThat(canaryA).startsWith("CANARY-").isNotEqualTo(canaryB);
        assertThat(a.toJson()).doesNotContain(canaryA);
        assertThat(b.toJson()).doesNotContain(canaryB);
    }

    @Test
    void itsOwnCapStopsItAndTheRestIsSkipped() {
        PreflightRunner.Report r = run(SCRIPT, new Fake(Behaviour.ECHO_CANARY), 150L);

        assertThat(r.stoppedByCap()).isTrue();
        assertThat(r.exitCode()).isEqualTo(3);
        assertThat(r.checks()).anyMatch(c -> c.result() == PreflightRunner.Result.SKIPPED && c.detail().contains("cap"));
    }

    @Test
    void anUnknownPriceOrWindowIsAWarningNamingTheFix() {
        PreflightRunner.Report r = run(SCRIPT, new Fake(Behaviour.ECHO_CANARY), 50_000L);

        PreflightRunner.Check c6 = check(r, "C6", "Writer");
        assertThat(c6.result()).isEqualTo(PreflightRunner.Result.WARN);
        assertThat(c6.fix()).contains("--prices");
    }

    @Test
    void theJsonRecordHoldsNoAnswerBeyondAShortExcerpt() {
        PreflightRunner.Report r = run(SCRIPT, new Fake(Behaviour.ECHO_CANARY), 50_000L);

        String json = r.toJson();
        assertThat(json).contains("\"build\":\"weave test\"").contains("\"C3\"");
        assertThat(json.length()).isLessThan(6000);
    }

    @Test
    void theCostItSpentIsReported() {
        PreflightRunner.Report r = run(SCRIPT, new Fake(Behaviour.ECHO_CANARY), 50_000L);

        assertThat(r.tokens()).isEqualTo(240L); // two agents, 120 tokens each
        assertThat(r.cost()).isNull(); // no price table, so no figure: not zero
        assertThat(r.toText()).contains("tokens").contains("no prices");
    }
}
