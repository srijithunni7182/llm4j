package io.github.llm4j.loom.loop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.wtest.TestFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Consent, policy, approval, candidates and the store: R3, R12, R13, R15, R41 of spec 14. */
class LoopStateTest {

    @TempDir Path dir;

    private RunRecord run(String id, String models, String humans) throws Exception {
        Path s = Runs.project(dir.resolve("proj"), Runs.TRIAGE);
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var body = mapper.createObjectNode();
        body.putObject("input").put("message", "where is my parcel?");
        var m = body.putObject("models");
        m.putObject("Intent").put("kind", models).put("urgent", "no");
        m.put("Replier", "On its way.");
        m.put("Escalator", "A person should look.");
        body.putArray("humans").add(humans);
        Path r =
                Runs.run(
                        dir.resolve("store"),
                        id,
                        s,
                        body,
                        Map.of("message", "where is my parcel?"));
        return StoreReader.open(r);
    }

    // ---- consent (R12)

    @Test
    void consentIsAskedOnceAndRecordedWhereTheRepositoryKeepsIt() throws Exception {
        Path script = Runs.project(dir.resolve("repo/src"), Runs.TRIAGE);
        Files.createDirectories(dir.resolve("repo/.git"));
        assertThat(Consent.given(script)).isFalse();
        Consent.record(script, "ana@example.com");
        assertThat(Consent.given(script)).isTrue();
        assertThat(Files.readString(dir.resolve("repo/.weave/capture.consent")))
                .contains("ana@example.com")
                .contains("committed");
        assertThat(Consent.NOTICE).contains("committed").contains("replaced");
        // a damaged record is not an agreement
        Files.writeString(dir.resolve("repo/.weave/capture.consent"), "{ not json");
        assertThat(Consent.given(script)).isFalse();
    }

    @Test
    void aPolicyRestrictsWorkflowsFieldsAndAge() throws Exception {
        Path script = Runs.project(dir.resolve("p"), Runs.TRIAGE);
        Files.writeString(
                dir.resolve("p/capture.properties"),
                "workflows = Support\ndeny-fields = ssn, card\nmax-age = 30d\n");
        Policy p = Policy.load(script);
        assertThat(p.denyFields()).containsExactlyInAnyOrder("ssn", "card");
        RunRecord r = run("old", "order_status", "yes");
        assertThat(p.violations(r, Instant.now()))
                .anyMatch(v -> v.contains("allows only the workflows"));
        assertThat(p.violations(r, Instant.now().plus(Duration.ofDays(40))))
                .anyMatch(v -> v.contains("30 days"));
        assertThat(Policy.none().violations(r, Instant.now())).isEmpty();
    }

    // ---- approval (R3, R15)

    @Test
    void aCaseIsObservedUntilAPersonApprovesItAndTheRecordSaysWhoAndWhen() throws Exception {
        RunRecord r = run("a1", "order_status", "yes");
        Capture.Captured c = Capture.capture(r, Capture.Options.defaults(), true);
        assertThat(TestFile.read(c.file()).capturedStatus()).isEqualTo("observed");
        Approval.approve(c.file(), "ana@example.com", "read it, it is right", Map.of());
        TestFile f = TestFile.read(c.file());
        assertThat(f.capturedStatus()).isEqualTo("approved");
        String text = Files.readString(c.file());
        assertThat(text)
                .contains("approvedBy: \"ana@example.com\"")
                .contains("approvedAt:")
                .contains("approvedWhy");
        // approving again does not stack records
        Approval.approve(c.file(), "bo@example.com", null, Map.of());
        assertThat(Files.readString(c.file()).split("approvedBy").length).isEqualTo(2);
        Approval.retire(c.file(), "ana@example.com", "workflow changed on purpose");
        assertThat(TestFile.read(c.file()).capturedStatus()).isEqualTo("retired");
    }

    @Test
    void aCaseThatNoLongerPassesCannotBeApprovedUnlessItsExpectationIsAmendedInTheSameStep()
            throws Exception {
        RunRecord r = run("a2", "order_status", "yes");
        Capture.Captured c = Capture.capture(r, Capture.Options.defaults(), true);
        Files.writeString(
                c.file(), Files.readString(c.file()).replace("final: completed", "final: failed"));
        assertThatThrownBy(() -> Approval.approve(c.file(), "ana", null, Map.of()))
                .hasMessageContaining("does not pass")
                .hasMessageContaining("--amend");
        Approval.approve(
                c.file(), "ana", "the run should have ended failed", Map.of("final", "completed"));
        assertThat(TestFile.read(c.file()).capturedStatus()).isEqualTo("approved");
    }

    @Test
    void theSummaryCountsWhatWaitsForAPerson() throws Exception {
        Capture.Captured a =
                Capture.capture(run("c1", "order_status", "yes"), Capture.Options.defaults(), true);
        Capture.Captured b =
                Capture.capture(run("c2", "refund", "yes"), Capture.Options.defaults(), true);
        Approval.approve(a.file(), "ana", null, Map.of());
        Map<String, Integer> counts =
                Approval.counts(List.of(TestFile.read(a.file()), TestFile.read(b.file())));
        assertThat(counts).containsEntry("approved", 1).containsEntry("observed", 1);
    }

    // ---- candidates (R13, R14)

    @Test
    void failedDivertedUnsureAndSteppedInRunsAreFoundAndSlowOnesAreSaidNotToBeAvailable()
            throws Exception {
        run("ok", "order_status", "yes");
        Path s = dir.resolve("proj/main.loom");
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var body = mapper.createObjectNode();
        body.putObject("input").put("message", "x");
        var m = body.putObject("models");
        m.putObject("Intent").put("kind", "refund").put("urgent", "no").put("confidence", 0.4);
        m.put("Escalator", "ok");
        Runs.run(dir.resolve("store"), "unsure1", s, body, Map.of("message", "x"));
        List<Candidates.Candidate> all =
                Candidates.list(StoreReader.list(dir.resolve("store")), null);
        assertThat(all).extracting(k -> k.run().id()).containsExactly("unsure1");
        assertThat(all.get(0).classes()).contains("unsure");
        assertThat(Candidates.select(all, "unsure", 3)).hasSize(1);
        assertThatThrownBy(() -> Candidates.select(all, "slow", 3))
                .hasMessageContaining("keeps what happened");
        assertThatThrownBy(() -> Candidates.select(all, "bogus", 3))
                .hasMessageContaining("no such class");
        assertThat(Candidates.since("14d", Instant.parse("2026-10-24T00:00:00Z")))
                .isEqualTo(Instant.parse("2026-10-10T00:00:00Z"));
    }

    // ---- the store (R41)

    @Test
    void theStoreIsReadWholeAndLeftExactlyAsItWas() throws Exception {
        run("s1", "order_status", "yes");
        run("s2", "refund", "yes");
        Path store = dir.resolve("store");
        String before = StoreReader.fingerprint(store);
        assertThat(StoreReader.list(store)).hasSize(2);
        assertThat(StoreReader.find("s1", store).id()).isEqualTo("s1");
        assertThatThrownBy(() -> StoreReader.find("nope", store))
                .hasMessageContaining("runs in the store include");
        Capture.capture(StoreReader.find("s1", store), Capture.Options.defaults(), false);
        assertThat(StoreReader.fingerprint(store)).isEqualTo(before);
    }

    @Test
    void aStoreBeingWrittenWhileItIsReadStillGivesWholeRuns() throws Exception {
        run("w1", "order_status", "yes");
        Path store = dir.resolve("store");
        Path journal = store.resolve("runs/w1/journal.json");
        String text = Files.readString(journal);
        java.util.concurrent.atomic.AtomicBoolean stop =
                new java.util.concurrent.atomic.AtomicBoolean();
        Thread writer =
                new Thread(
                        () -> {
                            try {
                                while (!stop.get()) {
                                    Path tmp = journal.resolveSibling("journal.json.tmp");
                                    Files.writeString(tmp, text);
                                    Files.move(
                                            tmp,
                                            journal,
                                            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                                            java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                                }
                            } catch (Exception e) {
                                // the test fails below if a read was torn
                            }
                        });
        writer.start();
        try {
            for (int i = 0; i < 200; i++)
                assertThat(StoreReader.open(store.resolve("runs/w1")).journal()).isNotEmpty();
        } finally {
            stop.set(true);
            writer.join();
        }
    }

    // ---- architecture (R42)

    @Test
    void theLoopPackageIsUsedByNothingBelowIt() throws Exception {
        Path main = Path.of("src/main/java/io/github/llm4j/loom");
        try (var files = Files.walk(main)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String rel = main.relativize(f).toString();
                if (rel.startsWith("loop/") || rel.startsWith("cli/") || rel.startsWith("host/"))
                    continue;
                assertThat(Files.readString(f))
                        .as(rel + " must not depend on the production loop")
                        .doesNotContain("io.github.llm4j.loom.loop");
            }
        }
    }
}
