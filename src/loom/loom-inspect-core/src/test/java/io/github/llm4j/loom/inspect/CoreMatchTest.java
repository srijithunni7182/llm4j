package io.github.llm4j.loom.inspect;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.jar.Attributes;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;

/**
 * F11: an add-on built from one commit ran against a core from another and died with a missing
 * class. Start-up now compares the two builds and says which is which, in one sentence.
 */
class CoreMatchTest {
    private static Manifest manifest(String version, String kind, String commit) {
        Manifest m = new Manifest();
        m.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (version != null) m.getMainAttributes().putValue("Implementation-Version", version);
        if (kind != null) m.getMainAttributes().putValue("Build-Kind", kind);
        if (commit != null) m.getMainAttributes().putValue("Build-Commit", commit);
        return m;
    }

    @Test
    void theSameVersionAndCommitAgree() {
        var m = manifest("6.2.0-SNAPSHOT", "development", "3d683a35");

        assertThat(CoreMatch.mismatch("loom-inspect", m, "ai-agent4j-loom", m)).isEmpty();
    }

    @Test
    void twoReleasesOfTheSameVersionAgreeWhateverTheCommit() {
        assertThat(
                        CoreMatch.mismatch(
                                "loom-inspect", manifest("6.1.0", "release", "a"),
                                "ai-agent4j-loom", manifest("6.1.0", "release", "b")))
                .isEmpty();
    }

    @Test
    void aDifferentVersionIsRefusedAndBothAreNamed() {
        var sentence =
                CoreMatch.mismatch(
                        "loom-inspect", manifest("6.1.0", "release", "db5b43c7"),
                        "ai-agent4j-loom", manifest("6.0.0", "release", "aaaa1111"));

        assertThat(sentence).isPresent();
        assertThat(sentence.get())
                .contains("loom-inspect 6.1.0")
                .contains("ai-agent4j-loom 6.0.0")
                .contains("same build");
    }

    @Test
    void aDevelopmentBuildFromAnotherCommitIsRefused() {
        var sentence =
                CoreMatch.mismatch(
                        "loom-fabric", manifest("6.2.0-SNAPSHOT", "development", "3d683a35"),
                        "loom-inspect-core", manifest("6.2.0-SNAPSHOT", "development", "9999aaaa"));

        assertThat(sentence).isPresent();
        assertThat(sentence.get()).contains("3d683a35").contains("9999aaaa");
    }

    @Test
    void aJarThatDoesNotSayWhatItIsIsNotRefused() {
        // classes run from a folder, or a jar from before the identity existed: nothing to compare
        assertThat(
                        CoreMatch.mismatch(
                                "loom-inspect", manifest(null, null, null),
                                "ai-agent4j-loom", manifest("6.2.0-SNAPSHOT", "development", "x")))
                .isEmpty();
    }

    @Test
    void theRunningClassesAreCheckedWithoutThrowingWhenTheyAreAllClasses() {
        // under test both sides are class folders, so there is nothing to refuse
        CoreMatch.require("loom-inspect-core", CoreMatchTest.class, CoreMatch.class);
    }
}
