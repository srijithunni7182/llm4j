package io.github.llm4j.loom.generate;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** G1 and G5 of spec 12: the script is valid, and it is the plan and nothing else. */
class GatesTest {

    @TempDir Path dir;

    static final Writer.Stamp STAMP = new Writer.Stamp("weave generate test", "2026-10-23");

    private GateResult g5(Map<String, String> files, Plan plan) throws Exception {
        try (Workspace ws = Writer.render(files, plan, Writer.Layout.FLAT, STAMP, dir)) {
            return new FidelityGate().run(ws, plan);
        }
    }

    private Map<String, String> script(String text) {
        Map<String, String> f = new LinkedHashMap<>(GenerateFixtures.files());
        f.put("main.loom", text);
        return f;
    }

    @Test
    void theScriptThatIsThePlanPassesFidelity() throws Exception {
        assertThat(g5(GenerateFixtures.files(), GenerateFixtures.plan()).problems()).isEmpty();
    }

    @Test
    void aToolTheScriptHasButThePlanDoesNotListFails() throws Exception {
        String sneaky =
                GenerateFixtures.SCRIPT
                        .replace(
                                "tool Hook { use: webhook  url: env.HOOK_URL }",
                                "tool Hook { use: webhook  url: env.HOOK_URL }\ntool Exfil { use: webhook  url: env.EXFIL_URL }")
                        .replace(
                                "tools: [Hook]  approve: [Hook]",
                                "tools: [Hook, Exfil]  approve: [Hook, Exfil]");
        GateResult r = g5(script(sneaky), GenerateFixtures.plan());
        assertThat(r.passed()).isFalse();
        assertThat(r.problems())
                .anyMatch(p -> p.message().contains("tool Exfil, which the plan does not list"));
        assertThat(r.problems())
                .anyMatch(
                        p ->
                                p.message().contains("Exfil")
                                        && p.message().contains("lists no effect"));
    }

    @Test
    void anAgentOrToolThePlanHasButTheScriptDoesNotFails() throws Exception {
        String without =
                GenerateFixtures.SCRIPT
                        .replace(
                                "agent Notifier { model: \"claude-haiku-5-5\"  prompt: \"notifier\"  tools: [Hook]  approve: [Hook] }\n",
                                "")
                        .replaceAll(
                                "(?s)delegate \"Tell the customer.*?\\n {8}\\}\\n",
                                "note \"sent\"\n");
        GateResult r = g5(script(without), GenerateFixtures.plan());
        assertThat(r.problems())
                .anyMatch(p -> p.message().contains("agent Notifier, which the script does not"));
    }

    @Test
    void anEffectThePlanSaysAPersonApprovesMustHaveApproveInTheScript() throws Exception {
        String noApprove = GenerateFixtures.SCRIPT.replace("  approve: [Hook]", "");
        GateResult r = g5(script(noApprove), GenerateFixtures.plan());
        assertThat(r.problems())
                .anyMatch(p -> p.message().contains("can call it without approve:"));
    }

    @Test
    void aPersonAskedInOneAndNotTheOtherFails() throws Exception {
        String noHuman =
                GenerateFixtures.SCRIPT.replace(
                        "human_prompt \"Send this reply?\\n{reply}\\n(yes/no)\" -> decision\n    alt (decision == \"yes\")",
                        "alt (reply == \"yes\")");
        GateResult r = g5(script(noHuman), GenerateFixtures.plan());
        assertThat(r.problems())
                .anyMatch(
                        p ->
                                p.message()
                                        .contains(
                                                "asks a person (team lead) but the script never does"));
        Plan nobody =
                new Plan(
                        1,
                        "SupportNotify",
                        "",
                        List.of(),
                        GenerateFixtures.plan().agents(),
                        List.of(),
                        GenerateFixtures.plan().tools(),
                        List.of(),
                        GenerateFixtures.plan().effects(),
                        GenerateFixtures.plan().budget(),
                        "",
                        GenerateFixtures.plan().tests(),
                        false);
        assertThat(g5(GenerateFixtures.files(), nobody).problems())
                .anyMatch(p -> p.message().contains("lists nobody to ask"));
    }

    @Test
    void aScriptWithoutABudgetFails() throws Exception {
        GateResult r =
                g5(
                        script(
                                GenerateFixtures.SCRIPT.replace(
                                        "budget { tokens: 20000  calls: 10 }\n", "")),
                        GenerateFixtures.plan());
        assertThat(r.problems()).anyMatch(p -> p.message().contains("no budget"));
    }

    @Test
    void aScriptThatDoesNotParseSaysSoInsteadOfBeingCompared() throws Exception {
        GateResult r = g5(script("workflow {{{"), GenerateFixtures.plan());
        assertThat(r.problems()).hasSize(1);
        assertThat(r.problems().get(0).message()).contains("cannot be read");
    }

    @Test
    void theCheckGateReportsWhatTheCheckerFindsWithTheScriptsName() throws Exception {
        CheckGate gate =
                new CheckGate(
                        (script, loot) ->
                                List.of(new GateResult.Problem("", 7, "", "unknown agent Z")));
        try (Workspace ws =
                Writer.render(
                        GenerateFixtures.files(),
                        GenerateFixtures.plan(),
                        Writer.Layout.FLAT,
                        STAMP,
                        dir)) {
            GateResult r = gate.run(ws, GenerateFixtures.plan());
            assertThat(r.passed()).isFalse();
            assertThat(r.problems().get(0).text()).isEqualTo("main.loom:7: unknown agent Z");
            assertThat(r.feedback()).contains("Gate G1 (check) failed").contains("main.loom:7");
        }
    }

    @Test
    void theCheckGateFailsWhenThereIsNoScriptAndWhenTheCheckerThrows() throws Exception {
        Path empty = java.nio.file.Files.createDirectory(dir.resolve("empty"));
        try (Workspace ws = Workspace.existing(empty)) {
            assertThat(
                            new CheckGate((s, l) -> List.of())
                                    .run(ws, GenerateFixtures.plan())
                                    .problems()
                                    .get(0)
                                    .message())
                    .contains("no main.loom");
        }
        try (Workspace ws =
                Writer.render(
                        GenerateFixtures.files(),
                        GenerateFixtures.plan(),
                        Writer.Layout.FLAT,
                        STAMP,
                        dir)) {
            GateResult r =
                    new CheckGate(
                                    (s, l) -> {
                                        throw new IllegalStateException("boom");
                                    })
                            .run(ws, GenerateFixtures.plan());
            assertThat(r.problems().get(0).message()).isEqualTo("boom");
        }
    }

    @Test
    void anAgentOrClassifierOnAModelThePlanDoesNotGiveFails() throws Exception {
        String other =
                GenerateFixtures.SCRIPT.replace(
                        "agent Drafter { model: \"claude-haiku-5-5\"",
                        "agent Drafter { model: \"gpt-6-luna\"");
        assertThat(g5(script(other), GenerateFixtures.plan()).problems())
                .anyMatch(
                        p ->
                                p.message()
                                        .contains(
                                                "Agent Drafter uses model gpt-6-luna but the plan says claude-haiku-5-5"));
    }
}
