package io.github.llm4j.loom.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.review.pack.Pack;
import io.github.llm4j.loom.review.pack.PackException;
import io.github.llm4j.loom.review.pack.PackHash;
import io.github.llm4j.loom.review.pack.PackLoader;
import io.github.llm4j.loom.review.pack.PackPin;
import io.github.llm4j.loom.review.pack.PackSet;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R63, R64, R65 of spec 09: which packs are in force, pinned, hashed and never fetched. */
class PackSetTest {

    @TempDir Path dir;
    Path acme;

    @BeforeEach
    void pack() throws Exception {
        acme = dir.resolve("review-rules/acme-policy");
        Files.createDirectories(acme.resolve("rules"));
        Files.writeString(acme.resolve("pack.yaml"), PackLoaderTest.MANIFEST);
        Files.writeString(acme.resolve("rules/r.yaml"), PackLoaderTest.RULE);
    }

    Pack core() {
        return Reviews.corePack();
    }

    PackSet resolve(String pins) {
        return PackSet.resolve(Optional.ofNullable(pins), dir, core());
    }

    @Test
    void nothingDeclaredMeansTheCorePackAloneWhenNoPackFolderIsThere() throws Exception {
        try (var s = Files.walk(dir.resolve("review-rules"))) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
        assertThat(resolve(null).packs()).extracting(Pack::label).containsExactly(core().label());
    }

    @Test
    void aPinnedFolderPackLoadsAfterCoreAndKeepsTheOrderTheProjectGave() {
        assertThat(resolve("core@1, acme-policy@2").packs())
                .extracting(p -> p.manifest().name())
                .containsExactly("core", "acme-policy");
    }

    @Test
    void aPinMatchesMajorMajorMinorOrTheExactVersionOnly() {
        assertThat(resolve("acme-policy@2.0").packs()).hasSize(2);
        assertThat(resolve("acme-policy@2.0.1").packs()).hasSize(2);
        assertThatThrownBy(() -> resolve("acme-policy@2.1"))
                .isInstanceOf(PackException.class)
                .hasMessageContaining("is version 2.0.1, which is not 2.1");
        assertThatThrownBy(() -> resolve("acme-policy@3")).hasMessageContaining("which is not 3");
        assertThat(new PackPin("p", "2", Optional.empty()).accepts("20.0.0")).isFalse();
    }

    @Test
    void aHashPinAcceptsTheSameFilesAndRefusesAnyChange() throws Exception {
        String hash = PackHash.of(PackLoader.directory(acme).files());
        assertThat(resolve("acme-policy@2.0.1 sha256:" + hash).packs()).hasSize(2);
        Files.writeString(
                acme.resolve("rules/r.yaml"),
                PackLoaderTest.RULE.replace("is a delegate", "is a delegation"));
        assertThatThrownBy(() -> resolve("acme-policy@2.0.1 sha256:" + hash))
                .isInstanceOf(PackException.class)
                .hasMessageContaining("hash to sha256:")
                .hasMessageContaining("The pack changed since it was pinned");
    }

    @Test
    void theHashIgnoresLineEndingsAndCoversExampleFiles() throws Exception {
        String before = PackHash.of(PackLoader.directory(acme).files());
        Files.writeString(acme.resolve("rules/r.yaml"), PackLoaderTest.RULE.replace("\n", "\r\n"));
        assertThat(PackHash.of(PackLoader.directory(acme).files())).isEqualTo(before);
        Files.createDirectories(acme.resolve("examples"));
        Files.writeString(acme.resolve("examples/a.loom"), "note \"x\"");
        assertThat(PackHash.of(PackLoader.directory(acme).files())).isNotEqualTo(before);
    }

    @Test
    void aPackFolderThatIsNotListedIsAnErrorNotSilentlyUsedOrIgnored() {
        assertThatThrownBy(() -> resolve(null))
                .isInstanceOf(PackException.class)
                .hasMessageContaining("review-rules/acme-policy is a rule pack that is not listed");
    }

    @Test
    void aPinnedPackThatIsNotThereIsAnErrorAndNothingIsDownloaded() {
        assertThatThrownBy(() -> resolve("core@1, other@1"))
                .hasMessageContaining("other@1: there is no folder review-rules/other")
                .hasMessageContaining("never downloaded");
    }

    @Test
    void aBrokenPackIsRefusedWithItsProblemsNamedByPack() throws Exception {
        Files.writeString(acme.resolve("rules/r.yaml"), "id: ACME-001\nlens: nope\n");
        assertThatThrownBy(() -> resolve("acme-policy@2"))
                .isInstanceOf(PackException.class)
                .hasMessageContaining("acme-policy:");
    }

    @Test
    void aFolderWhoseManifestNamesAnotherPackIsRefused() throws Exception {
        Files.writeString(
                acme.resolve("pack.yaml"),
                PackLoaderTest.MANIFEST.replace("acme-policy", "something-else"));
        assertThatThrownBy(() -> resolve("acme-policy@2"))
                .hasMessageContaining("holds a pack called something-else");
    }

    @Test
    void twoPacksCannotDefineTheSameRule() throws Exception {
        Path second = dir.resolve("review-rules/acme-two");
        Files.createDirectories(second.resolve("rules"));
        Files.writeString(
                second.resolve("pack.yaml"),
                PackLoaderTest.MANIFEST.replace("acme-policy", "acme-two"));
        Files.writeString(second.resolve("rules/r.yaml"), PackLoaderTest.RULE);
        assertThatThrownBy(() -> resolve("acme-policy@2, acme-two@2"))
                .hasMessageContaining(
                        "ACME-001 is defined by both acme-policy@2.0.1 and acme-two@2.0.1");
    }

    @Test
    void badPinsAreAllReported() {
        assertThatThrownBy(() -> resolve("core, Acme@x, ok@1 sha256:zz"))
                .isInstanceOf(PackException.class)
                .hasMessageContaining("'core' is not a pack pin")
                .hasMessageContaining("'Acme@x'")
                .hasMessageContaining("'ok@1 sha256:zz'");
    }

    @Test
    void thePinOfTheCorePackIsCheckedToo() {
        assertThatThrownBy(() -> resolve("core@7"))
                .hasMessageContaining("core@7: the pack is version");
        assertThat(
                        resolve("core@1 sha256:" + PackHash.of(core().files()) + ", acme-policy@2")
                                .packs())
                .hasSize(2);
    }
}
