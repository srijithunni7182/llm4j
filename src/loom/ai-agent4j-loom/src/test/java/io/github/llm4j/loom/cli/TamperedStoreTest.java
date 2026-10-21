package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.LLMClient;
import io.github.llm4j.loom.autonomy.MutableClock;
import io.github.llm4j.loom.autonomy.Scripts2Access;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verification C8 through the product: a person edits the ledger file to give an agent a level it
 * did not earn.
 */
@Tag("L9")
class TamperedStoreTest {

    @TempDir Path dir;

    ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    final MutableClock clock = new MutableClock(Instant.parse("2026-03-01T00:00:00Z"));
    File script;
    Path store;
    int runs;
    int asked;

    WeaveEnv env() {
        LLMClient client =
                new LLMClient() {
                    @Override
                    public LLMResponse chat(LLMRequest request) {
                        return LLMResponse.builder()
                                .content(
                                        "```json\n{\"choice\": \"approve\", \"reasoning\": \"r\", \"confidence\": 0.9}\n```")
                                .model("m")
                                .tokenUsage(10, 5, 15)
                                .build();
                    }

                    @Override
                    public Stream<LLMResponse> chatStream(LLMRequest request) {
                        return Stream.of(chat(request));
                    }
                };
        return new WeaveEnv(
                m -> client,
                q -> {
                    asked++;
                    return "approve";
                },
                new PrintStream(outBytes, true),
                new PrintStream(errBytes, true),
                clock,
                d -> {},
                c -> new io.github.llm4j.loom.trigger.system.CommandRunner.Result(0, "", ""),
                List.of("weave"),
                System::getenv);
    }

    int runCase(int amount) {
        Map<String, String> in = new LinkedHashMap<>();
        in.put("ticket", "T-" + amount);
        in.put("tier", "gold");
        in.put("amount", String.valueOf(amount));
        in.put("reason", "damaged");
        in.put("customer_since", "2020");
        int code =
                WeaveCLI.run(
                        script,
                        null,
                        "Triage",
                        in,
                        null,
                        null,
                        null,
                        null,
                        dir.resolve("runs").resolve("c" + (++runs)),
                        store,
                        false,
                        false,
                        null,
                        null,
                        env());
        clock.advance(Duration.ofHours(1));
        return code;
    }

    Path ledger() {
        return store.resolve("autonomy/Refund/ledger.jsonl");
    }

    String status() {
        outBytes.reset();
        errBytes.reset();
        int code =
                AutonomyCommands.status(
                        store,
                        "Refund",
                        null,
                        false,
                        clock,
                        new PrintStream(outBytes, true),
                        new PrintStream(errBytes, true));
        return code + "|" + outBytes;
    }

    @Test
    void anAgentCannotBeGivenALevelByEditingTheLedgerAndTheOperatorCanLookAndAccept()
            throws Exception {
        script = dir.resolve("refund.loom").toFile();
        Files.writeString(script.toPath(), Scripts2Access.refund(""));
        store = dir.resolve("store");
        for (int i = 0; i < 6; i++) assertThat(runCase(20 + i)).isZero();
        assertThat(status()).startsWith("0|").contains("scope gold   level suggest");
        assertThat(AutonomyCommands.verify(store, null, false, null, null, env())).isZero();
        assertThat(outBytes.toString()).contains("Refund: verified");

        // someone with an editor gives the agent act
        String forged =
                "{\"id\":\"Refund#level#gold#99\",\"decision\":\"Refund\",\"kind\":\"level\",\"at\":\"2026-03-01T07:00:00Z\",\"caseId\":\"\",\"generation\":1,\"body\":{\"scope\":\"gold\","
                        + "\"from\":\"suggest\",\"to\":\"act\",\"kind\":\"promoted\",\"reason\":\"approved by risk-owner\",\"by\":\"risk-owner\",\"epoch\":1,\"version\":99,"
                        + "\"identity\":\"e5978300f0\"}}\n";
        Files.writeString(ledger(), Files.readString(ledger()) + forged);

        // the next case is not run at act: a person is asked, blind, and the audit says why
        int askedBefore = asked;
        assertThat(runCase(500)).isZero();
        assertThat(asked - askedBefore)
                .as("the agent did not act on the forged level: a person was asked")
                .isEqualTo(1);
        String lastCase =
                Files.readAllLines(ledger()).stream()
                        .filter(l -> l.contains("\"kind\":\"case\""))
                        .reduce((a, b) -> b)
                        .orElseThrow();
        assertThat(lastCase).contains("\"level\":\"watch\"").contains("\"how\":\"degraded\"");

        // the operator is told, by every command that reads or changes the record
        assertThat(status())
                .startsWith("3|")
                .contains("DOES NOT VERIFY")
                .contains("Refund#level#gold#99");
        outBytes.reset();
        AutonomyCommands.history(store, null, new PrintStream(outBytes, true), System.err);
        assertThat(outBytes.toString()).contains("WARNING: the record does not verify");
        errBytes.reset();
        assertThat(
                        AutonomyCommands.move(
                                store, "Refund", "gold", "watch", false, false, "tidy up", null,
                                null, env()))
                .as("nothing is changed on top of a record that does not verify")
                .isEqualTo(2);
        assertThat(errBytes.toString()).contains("does not verify");
        outBytes.reset();
        assertThat(AutonomyCommands.verify(store, null, false, null, null, env())).isEqualTo(3);
        assertThat(outBytes.toString())
                .contains("DOES NOT VERIFY")
                .contains("Refund#level#gold#99");

        // accepting needs a reason; with one, the record is trusted as it stands and sealed from
        // there
        errBytes.reset();
        assertThat(AutonomyCommands.verify(store, null, true, null, null, env())).isEqualTo(2);
        assertThat(errBytes.toString()).contains("--reason");
        assertThat(
                        AutonomyCommands.verify(
                                store,
                                null,
                                true,
                                "the edit was mine: act is right for gold",
                                "operator:ada",
                                env()))
                .isZero();
        assertThat(Files.readString(store.resolve("autonomy/audit.jsonl")))
                .contains("record_accepted")
                .contains("act is right for gold");
        assertThat(status()).startsWith("0|");
        // and an edit made after that is found again
        Files.writeString(
                ledger(),
                Files.readString(ledger())
                        .replace(
                                "\"scope\":\"gold\",\"from\":\"suggest\",\"to\":\"act\"",
                                "\"scope\":\"gold\",\"from\":\"watch\",\"to\":\"act\""));
        assertThat(status()).startsWith("3|");
    }

    @Test
    void aLedgerTheStoreHadBeforeSealingIsReadAsItIsAndSealedByTheFirstChange() throws Exception {
        script = dir.resolve("refund.loom").toFile();
        Files.writeString(script.toPath(), Scripts2Access.refund(""));
        store = dir.resolve("store");
        for (int i = 0; i < 6; i++) assertThat(runCase(20 + i)).isZero();
        // take the seals away, as the previous release would have left it
        com.fasterxml.jackson.databind.ObjectMapper json =
                new com.fasterxml.jackson.databind.ObjectMapper();
        StringBuilder stripped = new StringBuilder();
        for (String line : Files.readAllLines(ledger())) {
            com.fasterxml.jackson.databind.node.ObjectNode node =
                    (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(line);
            if ("seal_base".equals(node.get("kind").asText())) continue;
            ((com.fasterxml.jackson.databind.node.ObjectNode) node.get("body")).remove("seal");
            stripped.append(json.writeValueAsString(node)).append('\n');
        }
        Files.writeString(ledger(), stripped.toString());
        Files.deleteIfExists(store.resolve("autonomy/.seal-key"));
        assertThat(AutonomyCommands.verify(store, null, false, null, null, env())).isZero();
        assertThat(outBytes.toString()).contains("not sealed yet");
        for (int i = 0; i < 3; i++) assertThat(runCase(60 + i)).isZero();
        assertThat(Files.readString(ledger()))
                .as("cases alone write nothing about levels, so nothing is sealed yet")
                .doesNotContain("seal_base");
        assertThat(status()).startsWith("0|");
        // the first change to a level is sealed, and anchors everything the previous release wrote
        assertThat(
                        AutonomyCommands.move(
                                store, "Refund", "gold", "watch", false, false, "a drill", null,
                                null, env()))
                .isZero();
        assertThat(Files.readString(ledger())).contains("seal_base");
        assertThat(AutonomyCommands.verify(store, null, false, null, null, env())).isZero();
        assertThat(outBytes.toString()).contains("verified");
        assertThat(status()).startsWith("0|").contains("scope gold   level watch");
    }
}
