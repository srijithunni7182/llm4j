package io.github.llm4j.loom.loop;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R36 to R38 and A2 of spec 14: what Fabric's two actions return, and where they never write. */
class FabricBridgeTest {

    @TempDir Path dir;

    @Test
    void aCaptureIsReturnedMaskedAndNothingIsWritten() throws Exception {
        String hostile = Hostile.message();
        var f = new FabricBridgeFixture(dir, hostile);
        Map<String, Object> r =
                FabricBridge.capture(f.store.toString(), "r1", "looping", false, null);
        assertThat(r.get("ok")).isEqualTo(true);
        String text = (String) r.get("text");
        // names are masked only when a team gives them (--mask-term); every pattern-shaped value is
        // gone
        for (String secret : Hostile.mustNotSurvive())
            if (!Hostile.TERMS.contains(secret)) assertThat(text).doesNotContain(secret);
        assertThat(r).doesNotContainKey("savedTo");
        assertThat(Files.exists(f.repo.resolve("proj/tests"))).isFalse();
    }

    @Test
    void aFolderGetsTheFileAndTheRepositoryDoesNot() throws Exception {
        var f = new FabricBridgeFixture(dir, "where is my parcel?");
        Path out = dir.resolve("captures");
        Map<String, Object> r =
                FabricBridge.capture(f.store.toString(), "r1", null, false, out.toString());
        assertThat(r.get("ok")).isEqualTo(true);
        assertThat(Path.of((String) r.get("savedTo"))).exists().startsWith(out);
        assertThat(Files.exists(f.repo.resolve("proj/tests"))).isFalse();
    }

    @Test
    void aFolderInsideTheRepositoryIsRefusedAndNothingIsWritten() throws Exception {
        var f = new FabricBridgeFixture(dir, "where is my parcel?");
        for (Path inside :
                List.of(
                        f.repo.resolve("proj/tests/captured"),
                        f.repo,
                        f.repo.resolve("elsewhere/../proj/tests"))) {
            Map<String, Object> r =
                    FabricBridge.capture(f.store.toString(), "r1", null, false, inside.toString());
            assertThat(r.get("ok")).isEqualTo(false);
            assertThat((String) r.get("message")).contains("never writes there");
        }
        assertThat(Files.exists(f.repo.resolve("proj/tests"))).isFalse();
        assertThat(Files.exists(f.repo.resolve("elsewhere"))).isFalse();
    }

    @Test
    void aRunThatIsNotInTheStoreSaysSoInAWayTheCallerCanRecognise() throws Exception {
        var f = new FabricBridgeFixture(dir, "hi");
        Map<String, Object> r = FabricBridge.capture(f.store.toString(), "nope", null, false, null);
        assertThat(r.get("ok")).isEqualTo(false);
        assertThat((String) r.get("message")).startsWith("no run ");
    }

    @Test
    void aTeamsPolicyStillApplies() throws Exception {
        var f = new FabricBridgeFixture(dir, "hi");
        Files.writeString(f.repo.resolve("proj/capture.properties"), "workflows=Other\n");
        Map<String, Object> r = FabricBridge.capture(f.store.toString(), "r1", null, false, null);
        assertThat(r.get("ok")).isEqualTo(false);
        assertThat((String) r.get("message")).contains("may not be captured");
    }

    @Test
    void tryAChangeReplaysFromRecordedAnswersAndLeavesTheStoreAlone() throws Exception {
        var f = new FabricBridgeFixture(dir, "where is my parcel?");
        String before = StoreReader.fingerprint(f.store);
        Map<String, Object> r =
                FabricBridge.whatIf(
                        f.store.toString(),
                        "r1",
                        "Main/s1/a0",
                        Map.of(),
                        Map.of("Replier", "Sorry, it is late."));
        assertThat(r.get("ok")).as(String.valueOf(r.get("message"))).isEqualTo(true);
        assertThat((String) r.get("report")).contains("Sorry, it is late.");
        assertThat(StoreReader.fingerprint(f.store)).isEqualTo(before);
    }

    @Test
    void tryAChangeNeedsAPlaceAndAChange() throws Exception {
        var f = new FabricBridgeFixture(dir, "hi");
        assertThat(
                        FabricBridge.whatIf(
                                        f.store.toString(), "r1", "", Map.of("a", "b"), Map.of())
                                .get("ok"))
                .isEqualTo(false);
        assertThat(
                        FabricBridge.whatIf(f.store.toString(), "r1", "Main/s1", Map.of(), Map.of())
                                .get("ok"))
                .isEqualTo(false);
    }
}
