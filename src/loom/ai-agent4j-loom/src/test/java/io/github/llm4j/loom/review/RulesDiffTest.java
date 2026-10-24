package io.github.llm4j.loom.review;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.review.pack.Pack;
import io.github.llm4j.loom.review.pack.PackDiff;
import io.github.llm4j.loom.review.pack.PackLoader;
import io.github.llm4j.loom.review.tools.RulesTool;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R74 of spec 09: upgrading a pack is predictable. */
class RulesDiffTest {

    @TempDir Path dir;

    static String rule(String id, String severity, String when, String title, String example) {
        return """
            - id: %s
              lens: reliability
              severity: %s
              about: step
              when: %s
              title: "%s"
              fixHint: "h"
              examples:
                fires: [ { inline: "%s" } ]
                quiet: [ { inline: "agent A { model: \\"m\\"  system: \\"s\\" }\\nworkflow Main(q) { note \\"x\\" }" } ]
            """
                .formatted(id, severity, when, title, example);
    }

    static final String EX =
            "agent A { model: \\\"m\\\"  system: \\\"s\\\" }\\nworkflow Main(q) { delegate \\\"{q}\\\" to A -> r  note \\\"{r}\\\" }";
    static final String DELEGATE = "{ field: kind, is: delegate }";

    Pack pack(String name, String version, int factModel, String... rules) throws Exception {
        Path p = Files.createDirectories(dir.resolve(name));
        Files.writeString(
                p.resolve("pack.yaml"),
                "name: acme\nversion: "
                        + version
                        + "\nfactModel: "
                        + factModel
                        + "\nrulesSchema: 1\nprefix: ACME\nrules: [rules/r.yaml]\n");
        Files.createDirectories(p.resolve("rules"));
        Files.writeString(p.resolve("rules/r.yaml"), String.join("\n", rules));
        return PackLoader.directory(p);
    }

    String r1(String severity, String when, String title) {
        return rule("ACME-001", severity, when, title, EX);
    }

    @Test
    void nothingChangedNeedsNoNewVersion() throws Exception {
        Pack a = pack("a", "1.0.0", 1, r1("low", DELEGATE, "t"));
        PackDiff d = PackDiff.of(a, pack("b", "1.0.0", 1, r1("low", DELEGATE, "t")));
        assertThat(d.unchanged()).isTrue();
        assertThat(d.required()).isEqualTo(PackDiff.Bump.NONE);
        assertThat(d.versionTooSmall()).isFalse();
    }

    @Test
    void aNewRuleOrAChangedConditionOrSeverityIsAMinorChange() throws Exception {
        Pack a = pack("a", "1.0.0", 1, r1("low", DELEGATE, "t"));
        PackDiff added =
                PackDiff.of(
                        a,
                        pack(
                                "b",
                                "1.1.0",
                                1,
                                r1("low", DELEGATE, "t"),
                                rule("ACME-002", "low", DELEGATE, "u", EX)));
        assertThat(added.added()).containsExactly("ACME-002");
        assertThat(added.required()).isEqualTo(PackDiff.Bump.MINOR);
        assertThat(added.declared()).isEqualTo(PackDiff.Bump.MINOR);
        assertThat(added.versionTooSmall()).isFalse();

        PackDiff louder = PackDiff.of(a, pack("c", "1.1.0", 1, r1("high", DELEGATE, "t")));
        assertThat(louder.changed())
                .singleElement()
                .satisfies(c -> assertThat(c.behaviour()).containsExactly("severity low -> high"));
        assertThat(louder.required()).isEqualTo(PackDiff.Bump.MINOR);

        PackDiff tighter =
                PackDiff.of(
                        a,
                        pack(
                                "d",
                                "1.1.0",
                                1,
                                r1(
                                        "low",
                                        "{ all: [ { field: kind, is: delegate }, { field: retry, is: false } ] }",
                                        "t")));
        assertThat(tighter.changed().get(0).behaviour().get(0))
                .contains("kind is delegate")
                .contains("retry is false");
    }

    @Test
    void aRemovedRuleOrADifferentFactModelNeedsAMajorVersion() throws Exception {
        Pack a =
                pack(
                        "a",
                        "1.0.0",
                        1,
                        r1("low", DELEGATE, "t"),
                        rule("ACME-002", "low", DELEGATE, "u", EX));
        PackDiff removed = PackDiff.of(a, pack("b", "1.1.0", 1, r1("low", DELEGATE, "t")));
        assertThat(removed.removed()).containsExactly("ACME-002");
        assertThat(removed.required()).isEqualTo(PackDiff.Bump.MAJOR);
        assertThat(removed.versionTooSmall()).isTrue();
        var m = a.manifest();
        Pack other =
                new Pack(
                        new io.github.llm4j.loom.review.pack.PackManifest(
                                m.name(),
                                "2.0.0",
                                2,
                                m.rulesSchema(),
                                m.prefix(),
                                m.description(),
                                m.ruleFiles()),
                        a.rules(),
                        a.files());
        PackDiff model = PackDiff.of(a, other);
        assertThat(model.factModelChanged()).isTrue();
        assertThat(model.required()).isEqualTo(PackDiff.Bump.MAJOR);
        assertThat(model.versionTooSmall()).isFalse();
    }

    @Test
    void wordingAndExamplesAloneAreAPatch() throws Exception {
        Pack a = pack("a", "1.0.0", 1, r1("low", DELEGATE, "t"));
        PackDiff words =
                PackDiff.of(a, pack("b", "1.0.1", 1, r1("low", DELEGATE, "a clearer title")));
        assertThat(words.required()).isEqualTo(PackDiff.Bump.PATCH);
        assertThat(words.changed().get(0).wording()).containsExactly("title");
        assertThat(words.versionTooSmall()).isFalse();
        PackDiff same =
                PackDiff.of(a, pack("c", "1.0.0", 1, r1("low", DELEGATE, "a clearer title")));
        assertThat(same.versionTooSmall()).isTrue();
    }

    @Test
    void aVersionThatDoesNotMoveForwardIsNotAStep() throws Exception {
        Pack a = pack("a", "1.2.0", 1, r1("low", DELEGATE, "t"));
        PackDiff d = PackDiff.of(a, pack("b", "1.1.0", 1, r1("high", DELEGATE, "t")));
        assertThat(d.declared()).isEqualTo(PackDiff.Bump.NONE);
        assertThat(d.versionTooSmall()).isTrue();
    }

    // ---- the command

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();

    RulesTool tool() {
        return new RulesTool(new PrintStream(out, true), new PrintStream(err, true));
    }

    @Test
    void theCommandListsChangesAndFailsWhenTheVersionIsTooSmall() throws Exception {
        pack("a", "1.0.0", 1, r1("low", DELEGATE, "t"), rule("ACME-002", "low", DELEGATE, "u", EX));
        pack("b", "1.0.1", 1, r1("low", DELEGATE, "t"));
        int code =
                tool().diff(dir.resolve("a").toString(), dir.resolve("b").toString(), null, false);
        assertThat(code).isEqualTo(1);
        assertThat(out.toString())
                .contains("acme@1.0.0  ->  acme@1.0.1")
                .contains("removed  ACME-002")
                .contains("at least a major version");
        assertThat(err.toString()).contains("too small a step");
    }

    @Test
    void withAScriptItShowsTheFindingsThatWouldAppearAndDisappear() throws Exception {
        pack("a", "1.0.0", 1, r1("low", "{ field: kind, is: delegate }", "delegate found"));
        pack("b", "1.1.0", 1, r1("low", "{ field: kind, is: alt }", "alt found"));
        Path script = dir.resolve("main.loom");
        Files.writeString(
                script,
                "agent A { model: \"m\"  system: \"s\" }\nworkflow Main(q) {\n  delegate \"{q}\" to A -> r\n  alt (r == \"x\") { note \"a\" } else { note \"b\" }\n}\n");
        int code =
                tool().diff(
                                dir.resolve("a").toString(),
                                dir.resolve("b").toString(),
                                script,
                                false);
        assertThat(code).isZero();
        assertThat(out.toString())
                .contains("would disappear  ACME-001 line 3: delegate found")
                .contains("would appear     ACME-001 line 4: alt found");
    }

    @Test
    void theChangelogEntryIsMarkdown() throws Exception {
        pack("a", "1.0.0", 1, r1("low", DELEGATE, "t"));
        pack(
                "b",
                "1.1.0",
                1,
                r1("high", DELEGATE, "t"),
                rule("ACME-002", "low", DELEGATE, "u", EX));
        tool().diff(dir.resolve("a").toString(), dir.resolve("b").toString(), null, true);
        assertThat(out.toString())
                .startsWith("## acme@1.1.0 (from acme@1.0.0)")
                .contains("- Added ACME-002")
                .contains("- ACME-001: severity low -> high");
    }

    @Test
    void aPackThatDoesNotLoadIsRefused() {
        int code = tool().diff(dir.resolve("none").toString(), "core", null, false);
        assertThat(code).isEqualTo(2);
        assertThat(err.toString()).contains("not a sound pack");
    }
}
