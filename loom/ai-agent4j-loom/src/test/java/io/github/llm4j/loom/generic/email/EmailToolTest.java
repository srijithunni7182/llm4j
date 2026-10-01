package io.github.llm4j.loom.generic.email;

import static org.assertj.core.api.Assertions.assertThat;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import io.github.llm4j.agent.Tool;
import io.github.llm4j.loom.generic.support.Declared;
import io.github.llm4j.loom.generic.support.FakeSmtpServer;
import io.github.llm4j.loom.generic.support.RecordingEffects;
import io.github.llm4j.loom.runtime.RunJournal;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EmailToolTest {

    @TempDir
    Path dir;
    GreenMail smtp;
    int port;
    RecordingEffects ctx;
    Declared declared;

    @BeforeEach
    void start() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        smtp = new GreenMail(new ServerSetup(port, "127.0.0.1", "smtp"));
        smtp.start();
        ctx = new RecordingEffects();
        declared = new Declared(Map.of("SMTP_PASSWORD", "smtp-PASSWORD-9876", "SMTP_USER", "digest-user"), dir);
    }

    @AfterEach
    void stop() {
        smtp.stop();
    }

    String decl(String options) {
        return "tool Mail { use: email  host: \"127.0.0.1\"  port: " + port + "  security: none  from: \"Loom <digest@example.com>\"  " + options + " }";
    }

    Tool tool(String options) throws Exception {
        return declared.create(decl(options), ctx);
    }

    static String run(Tool tool, Map<String, Object> args) {
        try {
            return tool.execute(args);
        } catch (Exception e) {
            throw new AssertionError("a generic tool threw instead of returning an Error: " + e, e);
        }
    }

    MimeMessage[] received() {
        return smtp.getReceivedMessages();
    }

    // ── V5.1 fixed recipients ────────────────────────────────────────────────────────────────

    @Test
    @Tag("V5.1")
    void aFixedRecipientListReceivesTheMessageAndTheAgentCannotChangeIt() throws Exception {
        Tool t = tool("to: \"team@example.com\"");

        String result = run(t, Map.of("subject", "Daily digest", "body", "Three things happened."));

        assertThat(result).isEqualTo("Sent to 1 recipient(s).");
        MimeMessage m = received()[0];
        assertThat(m.getFrom()[0].toString()).contains("digest@example.com").contains("Loom");
        assertThat(m.getAllRecipients()[0].toString()).isEqualTo("team@example.com");
        assertThat(m.getSubject()).isEqualTo("Daily digest");
        assertThat(m.getContent().toString().strip()).isEqualTo("Three things happened.");
        assertThat(m.getContentType()).startsWith("text/plain");

        assertThat(run(t, Map.of("subject", "x", "body", "y", "to", "attacker@evil.com"))).startsWith("Error:").contains("fixed list");
        assertThat(t.getDescription()).doesNotContain("to (required");
        assertThat(received()).hasSize(1);
    }

    @Test
    @Tag("V5.1")
    void ccAndBccAreFixedExtras() throws Exception {
        run(tool("to: \"a@example.com\"  cc: \"c@example.com\"  bcc: \"b@example.com\""), Map.of("subject", "s", "body", "b"));
        MimeMessage m = received()[0];
        assertThat(m.getHeader("Bcc")).isNull();
        assertThat(smtp.getReceivedMessages()).hasSize(3); // one copy per envelope recipient
    }

    // ── V5.2 allow_to ────────────────────────────────────────────────────────────────────────

    @Test
    @Tag("V5.2")
    @Tag("H3")
    void allowToLetsTheAgentChooseOnlyWithinThePatterns() throws Exception {
        Tool t = tool("allow_to: \"*@example.com, boss@partner.org\"");

        assertThat(run(t, Map.of("to", "a@example.com", "subject", "s", "body", "b"))).startsWith("Sent");
        assertThat(run(t, Map.of("to", "Ann <ann@example.com>, boss@partner.org", "subject", "s2", "body", "b"))).startsWith("Sent to 2");
        assertThat(received()).hasSize(3);

        for (String bad : List.of("a@evil.com", "a@example.com.evil.com", "a@evilexample.com", "a@sub.example.com", "other@partner.org")) {
            String result = run(t, Map.of("to", bad, "subject", "x" + bad, "body", "b"));
            assertThat(result).as(bad).startsWith("Error:").contains("not allowed").doesNotContain("*@example.com").doesNotContain("boss@");
        }
        assertThat(run(t, Map.of("to", "a@example.com, evil@evil.com", "subject", "mixed", "body", "b"))).startsWith("Error:");
        assertThat(run(t, Map.of("subject", "x", "body", "b"))).contains("to is required");
        assertThat(received()).as("nothing more was sent").hasSize(3);
        assertThat(t.getDescription()).contains("to (required");
    }

    // ── V5.3 limits and injection ────────────────────────────────────────────────────────────

    @Test
    @Tag("V5.3")
    @Tag("H3")
    void injectionOversizeAndTooManyRecipientsAreRefusedWithNothingSent() throws Exception {
        Tool t = tool("allow_to: \"*@example.com\"  max_recipients: 3");

        assertThat(run(t, Map.of("to", "a@example.com", "subject", "hi\r\nBcc: evil@evil.com", "body", "b"))).startsWith("Error:").contains("single line");
        assertThat(run(t, Map.of("to", "a@example.com", "subject", "hi\nTo: x", "body", "b"))).startsWith("Error:");
        assertThat(run(t, Map.of("to", "a@example.com", "subject", "hi x", "body", "b"))).startsWith("Error:");
        assertThat(run(t, Map.of("to", "a@example.com\r\nBcc: evil@evil.com", "subject", "s", "body", "b"))).startsWith("Error:");
        assertThat(run(t, Map.of("to", "a@example.com", "subject", "s".repeat(201), "body", "b"))).contains("subject is too long");
        assertThat(run(t, Map.of("to", "a@example.com", "subject", "s", "body", "b".repeat(201 * 1024)))).contains("body is too large");
        assertThat(run(t, Map.of("to", "a@example.com, b@example.com, c@example.com, d@example.com", "subject", "s", "body", "b"))).contains("too many recipients");
        List<String> five = java.util.stream.IntStream.range(0, 500).mapToObj(i -> "u" + i + "@example.com").toList();
        assertThat(run(t, Map.of("to", five, "subject", "s", "body", "b"))).contains("too many recipients");
        assertThat(received()).isEmpty();
    }

    // ── V5.4 / C3 the per-run cap ────────────────────────────────────────────────────────────

    @Test
    @Tag("V5.4")
    void maxPerRunSurvivesARestart() throws Exception {
        Tool first = tool("to: \"a@example.com\"  max_per_run: 2");
        run(first, Map.of("subject", "one", "body", "b"));
        run(first, Map.of("subject", "two", "body", "b"));
        assertThat(run(first, Map.of("subject", "three", "body", "b"))).contains("limited to 2");

        Tool afterRestart = tool("to: \"a@example.com\"  max_per_run: 2");
        assertThat(run(afterRestart, Map.of("subject", "four", "body", "b"))).contains("limited to 2");
        assertThat(received()).hasSize(2);
    }

    @Test
    @Tag("C3")
    void fiftyThreadsAgainstALimitOfTwentySendExactlyTwenty() throws Exception {
        Tool t = tool("to: \"a@example.com\"  max_per_run: 20");
        ExecutorService pool = Executors.newFixedThreadPool(16);
        for (int i = 0; i < 50; i++) {
            int n = i;
            pool.submit(() -> run(t, Map.of("subject", "message " + n, "body", "b")));
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        assertThat(received()).hasSize(20);
    }

    // ── V5.5 html and attachments ────────────────────────────────────────────────────────────

    @Test
    @Tag("V5.5")
    void htmlBodiesAndAttachmentsInsideTheDirectoryAreSent() throws Exception {
        Files.writeString(dir.resolve("report.csv"), "a,b\n1,2\n");
        Tool t = tool("to: \"a@example.com\"  attachments: true");

        run(t, Map.of("subject", "html", "body", "<b>bold</b>", "html", true));
        assertThat(received()[0].getContentType()).startsWith("text/html");

        assertThat(run(t, Map.of("subject", "with file", "body", "see attached", "attach", "report.csv"))).startsWith("Sent");
        MimeMessage withFile = received()[1];
        assertThat(withFile.getContentType()).startsWith("multipart/mixed");
        jakarta.mail.Multipart mp = (jakarta.mail.Multipart) withFile.getContent();
        assertThat(mp.getCount()).isEqualTo(2);
        assertThat(mp.getBodyPart(1).getFileName()).isEqualTo("report.csv");
    }

    @Test
    @Tag("V5.5")
    @Tag("H3")
    void attachmentsOutsideTheDirectoryHiddenOrOversizeAreRefused() throws Exception {
        Files.writeString(dir.getParent().resolve("outside-secret.txt"), "SECRET");
        Files.writeString(dir.resolve(".env"), "SECRET");
        Files.write(dir.resolve("big.bin"), new byte[11 * 1024 * 1024]);
        Tool t = tool("to: \"a@example.com\"  attachments: true");

        for (String bad : List.of("../outside-secret.txt", "/etc/passwd", ".env", "missing.txt", "../../.env")) {
            assertThat(run(t, Map.of("subject", "s" + bad, "body", "b", "attach", bad))).as(bad).startsWith("Error:");
        }
        assertThat(run(t, Map.of("subject", "big", "body", "b", "attach", "big.bin"))).contains("too large");
        assertThat(received()).isEmpty();

        Tool plain = tool("to: \"a@example.com\"");
        assertThat(run(plain, Map.of("subject", "s", "body", "b", "attach", "big.bin"))).contains("doesn't send attachments");
        assertThat(plain.getDescription()).doesNotContain("attach");
    }

    // ── V5.6 outbox ──────────────────────────────────────────────────────────────────────────

    @Test
    @Tag("V5.6")
    void outboxModeWritesAParsableEmlAndSendsNothing() throws Exception {
        Tool t = declared.create("tool Mail { use: email  outbox: \"mail/\"  from: \"digest@example.com\"  to: \"a@example.com\"  bcc: \"hidden@example.com\" }", ctx);

        String result = run(t, Map.of("subject", "Offline digest", "body", "Saved, not sent."));

        assertThat(result).contains("outbox").contains("nothing was sent");
        try (Stream<Path> files = Files.list(dir.resolve("mail"))) {
            List<Path> eml = files.toList();
            assertThat(eml).hasSize(1).allMatch(p -> p.toString().endsWith(".eml"));
            try (InputStream in = Files.newInputStream(eml.get(0))) {
                MimeMessage m = new MimeMessage(Session.getInstance(new Properties()), in);
                assertThat(m.getSubject()).isEqualTo("Offline digest");
                assertThat(m.getHeader("Bcc")).as("Bcc is not written into the headers").isNull();
                assertThat(m.getHeader("X-Loom-Envelope-To")[0]).contains("a@example.com").contains("hidden@example.com");
            }
        }
        assertThat(received()).isEmpty();
    }

    @Test
    @Tag("V5.6")
    void anOutboxOutsideTheScriptDirectoryIsALoadError() {
        assertThat(declared.problems("tool Mail { use: email  outbox: \"../elsewhere\"  from: \"a@example.com\"  to: \"b@example.com\" }"))
                .anyMatch(p -> p.contains("outbox"));
    }

    // ── V5.7 TLS ─────────────────────────────────────────────────────────────────────────────

    @Test
    @Tag("V5.7")
    void starttlsIsRequiredByDefaultSoAServerThatDoesntOfferItIsRefused() throws Exception {
        try (FakeSmtpServer plain = new FakeSmtpServer()) {
            Tool t = declared.create("tool Mail { use: email  host: \"127.0.0.1\"  port: " + plain.port() + "  from: \"a@example.com\"  to: \"b@example.com\" }", ctx);

            String result = run(t, Map.of("subject", "s", "body", "b"));

            assertThat(result).startsWith("Error:").contains("STARTTLS");
            assertThat(plain.messages).isEmpty();
            assertThat(ctx.journal().all().values()).extracting(RunJournal.Entry::kind).containsExactly("effect_failed");
        }
    }

    @Test
    @Tag("V5.7")
    void unencryptedMailIsOnlyAllowedForLoopbackOrWhenAcknowledged() {
        String base = "tool Mail { use: email  host: \"smtp.example.com\"  security: none  from: \"a@example.com\"  to: \"b@example.com\"";
        assertThat(declared.problems(base + " }")).anyMatch(p -> p.contains("security: none") && p.contains("allow_insecure"));
        assertThat(declared.problems(base + "  allow_insecure: true }")).isEmpty();
        assertThat(declared.problems(decl("to: \"b@example.com\""))).isEmpty();
    }

    // ── V5.8 / V5.11 / V5.12 failure stages ──────────────────────────────────────────────────

    @Test
    @Tag("V5.8")
    void aWrongPasswordIsReportedWithoutThePasswordOrServerText() throws Exception {
        try (FakeSmtpServer server = new FakeSmtpServer()) {
            server.advertiseAuth = true;
            server.authAccepts = false;
            Tool t = declared.create("tool Mail { use: email  host: \"127.0.0.1\"  port: " + server.port()
                    + "  security: none  username: env.SMTP_USER  password: env.SMTP_PASSWORD  from: \"a@example.com\"  to: \"b@example.com\" }", ctx);

            String result = run(t, Map.of("subject", "s", "body", "b"));

            assertThat(result).isEqualTo("Error: authentication failed");
            assertThat(ctx.everything()).doesNotContain("PASSWORD-9876").doesNotContain("digest-user");
        }
    }

    @Test
    @Tag("V5.11")
    void everyStageOfAFailedSendEndsAsFailedWithARedactedReason() throws Exception {
        // connection refused
        int closed;
        try (ServerSocket s = new ServerSocket(0)) {
            closed = s.getLocalPort();
        }
        Tool refused = declared.create("tool Mail { use: email  host: \"127.0.0.1\"  port: " + closed + "  security: none  from: \"a@example.com\"  to: \"b@example.com\" }", ctx);
        assertThat(run(refused, Map.of("subject", "s1", "body", "b"))).startsWith("Error:").contains("couldn't connect");

        try (FakeSmtpServer server = new FakeSmtpServer()) {
            Tool t = declared.create("tool Mail { use: email  host: \"127.0.0.1\"  port: " + server.port()
                    + "  security: none  from: \"a@example.com\"  to: \"first@example.com, second@example.com\" }", ctx);

            server.rejectRecipientNumber = 2;
            String rejected = run(t, Map.of("subject", "s2", "body", "b"));
            assertThat(rejected).startsWith("Error:").contains("refused the sender or a recipient").doesNotContain("second@example.com");
            assertThat(server.messages).as("nobody gets the message when one recipient is refused").isEmpty();

            server.rejectRecipientNumber = -1;
            server.afterData = FakeSmtpServer.AfterData.TEMPORARY_FAILURE;
            assertThat(run(t, Map.of("subject", "s3", "body", "b"))).startsWith("Error:").contains("try again later (451)");

            server.afterData = FakeSmtpServer.AfterData.PERMANENT_FAILURE;
            assertThat(run(t, Map.of("subject", "s4", "body", "b"))).startsWith("Error:").contains("refused the message (554)");
        }
        assertThat(ctx.journal().all().values()).extracting(RunJournal.Entry::kind).containsOnly("effect_failed");
    }

    @Test
    @Tag("V5.12")
    void aConnectionLostAfterTheDataLeavesTheOutcomeUnknownAndTheResumeFollowsThePolicy() throws Exception {
        try (FakeSmtpServer server = new FakeSmtpServer()) {
            server.afterData = FakeSmtpServer.AfterData.HANG_UP;
            String opts = "to: \"a@example.com\"  max_per_run: 3";
            String d = "tool Mail { use: email  host: \"127.0.0.1\"  port: " + server.port() + "  security: none  from: \"a@example.com\"  " + opts + " }";

            String first = run(declared.create(d, ctx), Map.of("subject", "digest", "body", "b"));
            assertThat(first).startsWith("Error:").contains("delivery is unknown");
            assertThat(ctx.journal().all().values()).extracting(RunJournal.Entry::kind).containsExactly("effect_pending");

            // Resumed with the default policy (skip): not sent again.
            server.afterData = FakeSmtpServer.AfterData.ACCEPT;
            String resumed = run(declared.create(d, ctx), Map.of("subject", "digest", "body", "b"));
            assertThat(resumed).contains("outcome is unknown");
            assertThat(server.messages).isEmpty();

            // With retry, it is sent.
            String again = run(declared.create(d.replace("max_per_run: 3", "max_per_run: 3  on_unknown: retry"), ctx), Map.of("subject", "digest", "body", "b"));
            assertThat(again).startsWith("Sent");
            assertThat(server.messages).hasSize(1);
        }
    }

    // ── V5.10 option checks ──────────────────────────────────────────────────────────────────

    @Test
    @Tag("V5.10")
    void optionCombinationsAreChecked() {
        String base = "tool Mail { use: email  host: \"smtp.example.com\"  from: \"a@example.com\"  ";
        assertThat(declared.problems(base + "to: \"b@example.com\"  allow_to: \"*@example.com\" }")).anyMatch(p -> p.contains("exactly one of to"));
        assertThat(declared.problems(base + "}")).anyMatch(p -> p.contains("exactly one of to"));
        assertThat(declared.problems(base + "to: \"b@example.com\"  username: env.SMTP_USER }")).anyMatch(p -> p.contains("go together"));
        assertThat(declared.problems(base + "to: \"b@example.com\"  password: \"literal-password\"  username: \"u\" }")).anyMatch(p -> p.contains("must come from the environment"));
        assertThat(declared.problems(base + "to: \"not an address\" }")).anyMatch(p -> p.contains("to:"));
        assertThat(declared.problems(base + "to: \"b@example.com\"  cc: \"x@y\\r\\nz\" }")).anyMatch(p -> p.contains("cc:"));
        assertThat(declared.problems(base + "allow_to: \"*@\" }")).anyMatch(p -> p.contains("allow_to"));
        assertThat(declared.problems(base + "to: \"b@example.com\"  security: tls }")).anyMatch(p -> p.contains("security"));
        assertThat(declared.problems(base + "to: \"b@example.com\"  port: 70000 }")).anyMatch(p -> p.contains("port"));
        assertThat(declared.problems(base + "to: \"b@example.com\"  max_recipients: 1  cc: \"c@example.com\" }")).anyMatch(p -> p.contains("max_recipients"));
        assertThat(declared.problems("tool Mail { use: email  from: \"a@example.com\"  to: \"b@example.com\" }")).anyMatch(p -> p.contains("host: is required"));
        assertThat(declared.problems(base + "to: \"b@example.com\"  username: env.SMTP_USER  password: env.SMTP_PASSWORD }")).isEmpty();
    }

    @Test
    @Tag("V1.5")
    void thePasswordNeverAppearsInAnythingTheToolReportsEvenOnErrors() throws Exception {
        try (FakeSmtpServer server = new FakeSmtpServer()) {
            server.advertiseAuth = true;
            server.afterData = FakeSmtpServer.AfterData.PERMANENT_FAILURE;
            Tool t = declared.create("tool Mail { use: email  host: \"127.0.0.1\"  port: " + server.port()
                    + "  security: none  username: env.SMTP_USER  password: env.SMTP_PASSWORD  from: \"a@example.com\"  to: \"b@example.com\" }", ctx);
            String result = run(t, Map.of("subject", "s", "body", "b"));
            assertThat(result).doesNotContain("PASSWORD-9876");
            assertThat(ctx.everything()).doesNotContain("PASSWORD-9876");
        }
    }

    @Test
    @Tag("V1.7")
    void aServerThatNeverAnswersEndsWithinTheTimeout() throws Exception {
        try (ServerSocket silent = new ServerSocket(0)) {
            Tool t = declared.create("tool Mail { use: email  host: \"127.0.0.1\"  port: " + silent.getLocalPort()
                    + "  security: none  timeout: 1s  from: \"a@example.com\"  to: \"b@example.com\" }", ctx);
            long t0 = System.nanoTime();
            assertThat(run(t, Map.of("subject", "s", "body", "b"))).startsWith("Error:");
            assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - t0)).isLessThan(15);
        }
    }

    @SuppressWarnings("unused")
    private static String ignored(MimeMessage m) {
        return new String(new byte[0], StandardCharsets.UTF_8);
    }
}
