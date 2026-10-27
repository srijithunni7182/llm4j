package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.llm4j.loom.serve.ResultCache;
import io.github.llm4j.loom.serve.ServeClient;
import io.github.llm4j.loom.serve.Server;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * R6, R17, R35 to R39 of spec 10, server side: the text a fix, an accept or a baseline would
 * produce, checked the way {@code weave review} checks it, and nothing written by the server.
 */
class ServeFixTest {

    static final String SCRIPT =
            """
            tool Mailer { use: email  outbox: "outbox"  from: "a@example.com"  to: "b@example.com" }
            intentclassifier Intent {
                model: "claude-opus-5-5"
                choose kind: order_status | refund | other
                ask urgent: "Is the customer threatening to leave?"
            }
            agent Replier { model: "claude-haiku-5-5"  system: "Answer the customer politely."  tools: [Mailer] }
            workflow Main(message) {
                intentclassify Intent on message -> intent
                alt (intent.kind == "order_status") {
                    delegate "Reply to: {message}" to Replier -> reply
                    return reply
                } else {
                    return "escalated"
                }
            }
            """;

    @TempDir Path dir;
    Path script;
    ServeClient client;

    @BeforeEach
    void start() throws Exception {
        script = dir.resolve("main.loom");
        Files.writeString(script, SCRIPT);
        client =
                new ServeClient(
                        new Server(new CliEngine(new ResultCache(64L << 20), "test"), 60000));
        ObjectNode init = ServeClient.params();
        init.putArray("roots").add(dir.toString());
        client.request("initialize", init);
    }

    @AfterEach
    void stop() throws Exception {
        client.close();
    }

    private ObjectNode file() {
        return ServeClient.params().put("file", script.toString());
    }

    private JsonNode review() throws Exception {
        return client.request("loom/review", file()).path("result").path("result");
    }

    private JsonNode finding(String rule) throws Exception {
        for (JsonNode f : review().path("findings"))
            if (f.path("rule").asText().equals(rule)) return f;
        throw new AssertionError("no finding " + rule);
    }

    private List<String> files() throws Exception {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.map(p -> dir.relativize(p).toString()).sorted().toList();
        }
    }

    // ---- fixes

    @Test
    void anOptionWithItsAnswersBecomesNewTextAndNothingIsWritten() throws Exception {
        List<String> before = files();
        ObjectNode p = file().put("option", "timeout-only");
        p.putArray("fingerprints").add(finding("LR01").path("fingerprint").asText());
        p.putObject("answers").put("seconds", "45");
        JsonNode r = client.request("loom/applyFix", p).path("result");
        assertThat(r.path("changed").asBoolean()).isTrue();
        assertThat(r.path("text").asText()).contains("timeout 45s").isNotEqualTo(SCRIPT);
        assertThat(r.path("original").asText()).isEqualTo(SCRIPT);
        assertThat(Files.readString(script))
                .as("the server did not write the script")
                .isEqualTo(SCRIPT);
        assertThat(files()).as("and left no file behind").isEqualTo(before);
    }

    @Test
    void theDefaultFixOfAFindingIsAppliedWhenNamed() throws Exception {
        ObjectNode p = file();
        p.putArray("fingerprints").add(finding("LR01").path("fingerprint").asText());
        JsonNode r = client.request("loom/applyFix", p).path("result");
        assertThat(r.path("changed").asBoolean()).isTrue();
        assertThat(r.path("applied")).isNotEmpty();
        assertThat(r.path("applied").get(0).path("rule").asText()).isEqualTo("LR01");
    }

    @Test
    void safeMeansOnlyTheSafeFixesAndNoneIsNotAnError() throws Exception {
        JsonNode r = client.request("loom/applyFix", file().put("safe", true)).path("result");
        assertThat(r.path("changed").asBoolean()).isFalse();
        assertThat(r.path("applied")).isEmpty();
        assertThat(r.path("text").asText()).isEqualTo(SCRIPT);
    }

    @Test
    void theFixIsComputedOnTheOpenBufferNotTheFile() throws Exception {
        String buffer = SCRIPT + "\n// edited in the editor\n";
        ObjectNode p = file().put("text", buffer);
        p.putArray("fingerprints").add(finding("LR01").path("fingerprint").asText());
        JsonNode r = client.request("loom/applyFix", p).path("result");
        assertThat(r.path("original").asText()).isEqualTo(buffer);
        assertThat(r.path("text").asText()).contains("// edited in the editor");
    }

    @Test
    void anOptionThatDoesNotExistIsRefusedWithAReason() throws Exception {
        ObjectNode p = file().put("option", "no-such-option");
        p.putArray("fingerprints").add(finding("LR01").path("fingerprint").asText());
        JsonNode e = client.request("loom/applyFix", p).path("error");
        assertThat(e.path("data").path("kind").asText()).isEqualTo("fix-refused");
        assertThat(e.path("message").asText()).isNotBlank();
    }

    @Test
    void anOptionNeedsExactlyOneFinding() throws Exception {
        ObjectNode p = file().put("option", "timeout-only");
        JsonNode e = client.request("loom/applyFix", p).path("error");
        assertThat(e.path("data").path("kind").asText()).isEqualTo("invalid-params");
    }

    @Test
    void askingForNothingIsInvalid() throws Exception {
        assertThat(
                        client.request("loom/applyFix", file())
                                .path("error")
                                .path("data")
                                .path("kind")
                                .asText())
                .isEqualTo("invalid-params");
    }

    // ---- accepting

    @Test
    void acceptingOneFindingGivesTheLineAndTheWholeNewFileAndWritesNothing() throws Exception {
        List<String> before = files();
        ObjectNode p =
                file().put("scope", "finding")
                        .put("reason", "handled by the retry in the harness")
                        .put("by", "dev");
        p.putArray("fingerprints").add(finding("LR01").path("fingerprint").asText());
        JsonNode r = client.request("loom/accept", p).path("result");
        assertThat(r.path("file").asText()).endsWith("review.properties");
        assertThat(r.path("existed").asBoolean()).isFalse();
        assertThat(r.path("lines").get(0).asText())
                .startsWith("LR01.accept = finding ")
                .contains("handled by the retry")
                .contains("by \"dev\"");
        assertThat(r.path("text").asText()).contains(r.path("lines").get(0).asText());
        assertThat(files()).isEqualTo(before);
    }

    @Test
    void acceptingKeepsWhatIsAlreadyInTheFile() throws Exception {
        Files.writeString(dir.resolve("review.properties"), "notice = off\n");
        ObjectNode p = file().put("reason", "intended");
        p.putArray("fingerprints").add(finding("LR01").path("fingerprint").asText());
        JsonNode r = client.request("loom/accept", p).path("result");
        assertThat(r.path("existed").asBoolean()).isTrue();
        assertThat(r.path("text").asText()).startsWith("notice = off\n").contains("LR01.accept");
        assertThat(Files.readString(dir.resolve("review.properties"))).isEqualTo("notice = off\n");
    }

    @Test
    void anAcceptNeedsAReason() throws Exception {
        ObjectNode p = file();
        p.putArray("fingerprints").add(finding("LR01").path("fingerprint").asText());
        JsonNode e = client.request("loom/accept", p).path("error");
        assertThat(e.path("data").path("kind").asText()).isEqualTo("accept-refused");
        assertThat(e.path("message").asText()).contains("reason");
    }

    @Test
    void aReasonWithADoubleQuoteIsRefusedBeforeItCanBreakTheFile() throws Exception {
        ObjectNode p = file().put("reason", "he said \"fine\"");
        p.putArray("fingerprints").add(finding("LR01").path("fingerprint").asText());
        assertThat(
                        client.request("loom/accept", p)
                                .path("error")
                                .path("data")
                                .path("kind")
                                .asText())
                .isEqualTo("accept-refused");
    }

    @Test
    void aNameThatIsNotAFindingIsRefused() throws Exception {
        ObjectNode p = file().put("reason", "x");
        p.putArray("fingerprints").add("0000000000000000");
        assertThat(
                        client.request("loom/accept", p)
                                .path("error")
                                .path("data")
                                .path("kind")
                                .asText())
                .isEqualTo("no-such-finding");
    }

    @Test
    void theScopeAndTheEndDateAreChecked() throws Exception {
        ObjectNode p = file().put("reason", "x").put("scope", "galaxy");
        p.putArray("fingerprints").add(finding("LR01").path("fingerprint").asText());
        assertThat(
                        client.request("loom/accept", p)
                                .path("error")
                                .path("data")
                                .path("kind")
                                .asText())
                .isEqualTo("invalid-params");
        ObjectNode q = file().put("reason", "x").put("until", "next week");
        q.putArray("fingerprints").add(finding("LR01").path("fingerprint").asText());
        assertThat(
                        client.request("loom/accept", q)
                                .path("error")
                                .path("data")
                                .path("kind")
                                .asText())
                .isEqualTo("invalid-params");
        ObjectNode ok = file().put("reason", "x").put("until", "2099-12-31");
        ok.putArray("fingerprints").add(finding("LR01").path("fingerprint").asText());
        assertThat(client.request("loom/accept", ok).path("result").path("lines").get(0).asText())
                .contains("until 2099-12-31");
    }

    // ---- the baseline

    @Test
    void theBaselineIsTextForTheFindingsThatAreOpenAndSaysWhatStaysOpen() throws Exception {
        List<String> before = files();
        JsonNode r = client.request("loom/baseline", file()).path("result");
        JsonNode open = review().path("findings");
        long serious = 0;
        for (JsonNode f : open) {
            String sev = f.path("severity").asText();
            String lens = f.path("lens").asText();
            if (sev.equals("high")
                    || lens.equals("security")
                    || lens.equals("durability")
                    || lens.equals("privacy")) serious++;
        }
        assertThat(r.path("file").asText()).endsWith("review.baseline.json");
        assertThat(r.path("leftOut").asLong()).isEqualTo(serious);
        assertThat(r.path("recorded").asLong()).isEqualTo(open.size() - serious);
        assertThat(r.path("text").asText()).contains("\"entries\"");
        assertThat(files()).isEqualTo(before);
    }

    @Test
    void includingTheSeriousOnesRecordsEverything() throws Exception {
        ObjectNode p = file().put("includeSerious", true);
        JsonNode r = client.request("loom/baseline", p).path("result");
        assertThat(r.path("leftOut").asLong()).isZero();
        assertThat(r.path("recorded").asLong()).isEqualTo(review().path("findings").size());
    }

    // ---- the server stays inside its boundaries

    @Test
    void aFileOutsideTheFoldersIsRefusedByEveryNewMethod() throws Exception {
        Path outside = Files.createTempFile("outside", ".loom");
        try {
            Files.writeString(outside, SCRIPT);
            for (String m : List.of("loom/applyFix", "loom/accept", "loom/baseline")) {
                ObjectNode p =
                        ServeClient.params().put("file", outside.toString()).put("safe", true);
                assertThat(client.request(m, p).path("error").path("data").path("kind").asText())
                        .as(m)
                        .isEqualTo("outside-workspace");
            }
        } finally {
            Files.deleteIfExists(outside);
        }
    }
}
