package io.github.llm4j.loom.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.execution.LoomLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 24, R11 (LA21): text an agent that reads untrusted content puts in the shared transcript
 * reaches every later agent that receives the transcript, so those agents count as reading
 * untrusted content for the trifecta.
 */
class TranscriptTaintTest {

    @TempDir Path dir;

    static final String TOOLS =
            """
            tool Files { use: file  root: "data"  mode: read }
            tool Mail  { use: email  host: "smtp.example.com"  from: "a@example.com"  to: "me@example.com" }
            """;

    static String agents(String senderExtra) {
        return TOOLS
                + "agent Researcher { model: \"m\" system: \"s\" tools: [web_search] }\n"
                + "agent Writer { model: \"m\" system: \"s\" }\n"
                + "agent Sender { model: \"m\" system: \"s\" tools: [Files, Mail] "
                + senderExtra
                + " }\n";
    }

    AuditReport audit(String source) throws Exception {
        Path f = dir.resolve("s" + System.nanoTime() + ".loom");
        Files.writeString(f, source);
        return SecurityAudit.audit(new LoomLoader().load(f.toString()), f.getFileName().toString());
    }

    static List<Finding> rule(AuditReport r, String id) {
        return r.findings().stream().filter(f -> f.rule().equals(id)).toList();
    }

    static final String CHAIN =
            """
            workflow Main(q) {
                delegate "{q}" to Researcher -> facts
                delegate "write {facts}" to Writer -> draft
                delegate "send {draft}" to Sender -> sent
            }
            """;

    @Test
    void anUntrustedReaderEarlierTaintsALaterReceiverAndCompletesTheTrifecta() throws Exception {
        AuditReport r = audit(agents("") + CHAIN);

        Finding la01 = rule(r, "LA01").stream().filter(f -> f.where().equals("Sender")).findFirst().orElseThrow();
        assertThat(la01.severity()).isEqualTo(Severity.HIGH);
        assertThat(la01.risk()).contains("Researcher").contains("shared transcript");
        Finding la21 = rule(r, "LA21").stream().filter(f -> f.where().equals("Sender")).findFirst().orElseThrow();
        assertThat(la21.severity()).isEqualTo(Severity.HIGH);
        assertThat(la21.risk()).contains("Researcher reads");
        assertThat(la21.fix()).contains("transcript: none");
    }

    @Test
    void whenEveryWayOutIsApprovedTheTrifectaIsMedium() throws Exception {
        AuditReport r = audit(agents("approve: [Mail]") + CHAIN);

        assertThat(rule(r, "LA01").stream().filter(f -> f.where().equals("Sender")))
                .singleElement()
                .extracting(Finding::severity)
                .isEqualTo(Severity.MEDIUM);
    }

    @Test
    void transcriptNoneOnTheReceiverBreaksTheTaint() throws Exception {
        AuditReport r = audit(agents("memory { transcript: none }") + CHAIN);

        assertThat(rule(r, "LA21").stream().filter(f -> f.where().equals("Sender"))).isEmpty();
        assertThat(rule(r, "LA01").stream().filter(f -> f.where().equals("Sender"))).isEmpty();
    }

    @Test
    void transcriptOwnAlsoBreaksItBecauseTheAgentSeesOnlyItsOwnExchanges() throws Exception {
        AuditReport r = audit(agents("memory { transcript: own }") + CHAIN);

        assertThat(rule(r, "LA21").stream().filter(f -> f.where().equals("Sender"))).isEmpty();
    }

    @Test
    void anExtractorBetweenThemDoesNotByItselfStopTheTranscript() throws Exception {
        // the researcher's answer is stored in the transcript whatever a later step extracts
        String script =
                agents("")
                        + "extractor Facts { model: \"m\" fields { summary: string } }\n"
                        + """
                workflow Main(q) {
                    delegate "{q}" to Researcher -> facts
                    extract Facts from facts -> typed
                    delegate "send {typed.summary}" to Sender -> sent
                }
                """;
        AuditReport r = audit(script);

        assertThat(rule(r, "LA21").stream().filter(f -> f.where().equals("Sender"))).hasSize(1);
    }

    @Test
    void theOrderOfStatementsDecidesAReaderAfterTheReceiverTaintsNothing() throws Exception {
        String script =
                agents("")
                        + """
                workflow Main(q) {
                    delegate "send" to Sender -> sent
                    delegate "{q}" to Researcher -> facts
                }
                """;
        AuditReport r = audit(script);

        assertThat(rule(r, "LA21").stream().filter(f -> f.where().equals("Sender"))).isEmpty();
    }

    @Test
    void inALoopTheReaderTaintsAnEarlierStatementOnTheNextRound() throws Exception {
        String script =
                agents("")
                        + """
                workflow Main(q) {
                    loop until (done == "yes") max 3 {
                        delegate "send" to Sender -> sent
                        delegate "{q}" to Researcher -> facts
                    }
                }
                """;
        AuditReport r = audit(script);

        assertThat(rule(r, "LA21").stream().filter(f -> f.where().equals("Sender"))).hasSize(1);
    }

    @Test
    void theTaintFollowsACallAndAHandoff() throws Exception {
        String script =
                agents("")
                        + """
                workflow Research(q) {
                    delegate "{q}" to Researcher -> facts
                }
                workflow Main(q) {
                    call Research(q = q) -> r
                    handoff "send" to Sender
                }
                """;
        AuditReport r = audit(script);

        assertThat(rule(r, "LA21").stream().filter(f -> f.where().equals("Sender"))).hasSize(1);
    }

    @Test
    void aReceiverThatCannotActIsReportedAsInfoOnly() throws Exception {
        AuditReport r = audit(agents("") + CHAIN);

        Finding writer = rule(r, "LA21").stream().filter(f -> f.where().equals("Writer")).findFirst().orElseThrow();
        assertThat(writer.severity()).isEqualTo(Severity.INFO);
        assertThat(rule(r, "LA01").stream().filter(f -> f.where().equals("Writer"))).isEmpty();
    }

    @Test
    void internalNodesAreNeverTainted() throws Exception {
        String script =
                agents("")
                        + "extractor Facts { model: \"m\" fields { summary: string } }\n"
                        + """
                workflow Main(q) {
                    delegate "{q}" to Researcher -> facts
                    extract Facts from facts -> typed
                    note "{typed.summary}"
                }
                """;
        AuditReport r = audit(script);

        assertThat(rule(r, "LA21")).isEmpty();
    }

    @Test
    void nothingIsReportedForAScriptWhereNoOneReadsUntrustedContent() throws Exception {
        AuditReport r =
                audit(
                        TOOLS
                                + "agent Writer { model: \"m\" system: \"s\" }\n"
                                + "agent Sender { model: \"m\" system: \"s\" tools: [Files, Mail] }\n"
                                + "workflow Main(q) { delegate \"{q}\" to Writer -> d\n delegate \"send\" to Sender -> s }");

        assertThat(rule(r, "LA21")).isEmpty();
    }

    @Test
    void aTrustedAgentRunningFirstTaintsNothing() throws Exception {
        String script =
                agents("")
                        + """
                workflow Main(q) {
                    delegate "{q}" to Writer -> d
                    delegate "send" to Sender -> s
                }
                """;
        assertThat(rule(audit(script), "LA21")).isEmpty();
    }
}
