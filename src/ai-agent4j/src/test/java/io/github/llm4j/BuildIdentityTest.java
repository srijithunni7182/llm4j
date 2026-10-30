package io.github.llm4j;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.jar.Attributes;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;

/** What a jar says it is: a release or a development build, from which commit, built when. */
class BuildIdentityTest {
    private static Manifest manifest(String... pairs) {
        Manifest m = new Manifest();
        m.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        for (int i = 0; i < pairs.length; i += 2)
            m.getMainAttributes().putValue(pairs[i], pairs[i + 1]);
        return m;
    }

    @Test
    void aReleaseBuildDescribesItselfWithItsCommitAndTime() {
        BuildIdentity id =
                BuildIdentity.of(
                        manifest(
                                "Implementation-Version", "6.1.0",
                                "Build-Kind", "release",
                                "Build-Commit", "db5b43c7",
                                "Build-Dirty", "false",
                                "Build-Time", "2026-10-17T09:00:00Z"));

        assertThat(id.describe())
                .isEqualTo("6.1.0 (release, db5b43c7, built 2026-10-17T09:00:00Z)");
        assertThat(id.isDevelopment()).isFalse();
    }

    @Test
    void aDevelopmentBuildFromAModifiedTreeSaysSo() {
        BuildIdentity id =
                BuildIdentity.of(
                        manifest(
                                "Implementation-Version", "6.2.0-SNAPSHOT",
                                "Build-Kind", "development",
                                "Build-Commit", "3d683a35",
                                "Build-Dirty", "true",
                                "Build-Time", "2026-10-29T15:30:00Z"));

        assertThat(id.describe())
                .isEqualTo(
                        "6.2.0-SNAPSHOT (development, 3d683a35, modified tree, built 2026-10-29T15:30:00Z)");
        assertThat(id.isDevelopment()).isTrue();
    }

    @Test
    void anOldJarWithOnlyAVersionAndATimeIsStillDescribedHonestly() {
        BuildIdentity id =
                BuildIdentity.of(
                        manifest(
                                "Implementation-Version", "6.0.0",
                                "Build-Time", "2026-10-17T09:00:00Z"));

        assertThat(id.describe())
                .isEqualTo("6.0.0 (build kind unknown, built 2026-10-17T09:00:00Z)");
    }

    @Test
    void anUnexpandedPlaceholderMeansUnknownNotAFakeCommit() {
        BuildIdentity id =
                BuildIdentity.of(
                        manifest(
                                "Implementation-Version", "6.2.0-SNAPSHOT",
                                "Build-Kind", "development",
                                "Build-Commit", "${git.commit.id.abbrev}"));

        assertThat(id.commit()).isEqualTo("unknown");
    }

    @Test
    void classesRunFromADirectoryAreNotMistakenForAJar() {
        BuildIdentity id = BuildIdentity.ofClass(BuildIdentityTest.class);

        assertThat(id.describe()).startsWith("run from classes");
    }

    @Test
    void twoBuildsAreTheSameWhenTheVersionAndTheCommitAgree() {
        BuildIdentity a =
                BuildIdentity.of(
                        manifest(
                                "Implementation-Version",
                                "6.2.0-SNAPSHOT",
                                "Build-Kind",
                                "development",
                                "Build-Commit",
                                "aaaa1111"));
        BuildIdentity same =
                BuildIdentity.of(
                        manifest(
                                "Implementation-Version",
                                "6.2.0-SNAPSHOT",
                                "Build-Kind",
                                "development",
                                "Build-Commit",
                                "aaaa1111"));
        BuildIdentity other =
                BuildIdentity.of(
                        manifest(
                                "Implementation-Version",
                                "6.2.0-SNAPSHOT",
                                "Build-Kind",
                                "development",
                                "Build-Commit",
                                "bbbb2222"));
        BuildIdentity release =
                BuildIdentity.of(
                        manifest(
                                "Implementation-Version",
                                "6.1.0",
                                "Build-Kind",
                                "release",
                                "Build-Commit",
                                "cccc3333"));
        BuildIdentity releaseOtherCommit =
                BuildIdentity.of(
                        manifest(
                                "Implementation-Version",
                                "6.1.0",
                                "Build-Kind",
                                "release",
                                "Build-Commit",
                                "dddd4444"));

        assertThat(a.sameBuildAs(same)).isTrue();
        assertThat(a.sameBuildAs(other)).isFalse();
        assertThat(a.sameBuildAs(release)).isFalse();
        // a release is identified by its version alone: its commit is the tag
        assertThat(release.sameBuildAs(releaseOtherCommit)).isTrue();
    }
}
