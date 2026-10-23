package io.github.llm4j.loom.review;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.review.adapt.AuditSource;
import io.github.llm4j.loom.review.adapt.ValiditySource;
import io.github.llm4j.loom.review.engine.FindingSource;
import io.github.llm4j.loom.review.engine.ReviewInput;
import io.github.llm4j.loom.review.engine.ValidityProblem;
import io.github.llm4j.loom.review.model.Finding;
import io.github.llm4j.loom.review.model.Lens;
import io.github.llm4j.loom.review.model.Note;
import io.github.llm4j.loom.review.model.Severity;
import io.github.llm4j.loom.review.settings.ReviewSettings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * R3 of spec 09: what the audit and the validator report becomes the one finding shape, with
 * nothing lost.
 */
class AdapterTest {

    @TempDir Path dir;

    static final String RISKY =
            """
            tool Files { use: file  root: "data"  mode: read }
            tool Hook  { use: webhook  url: env.HOOK }
            agent A { model: "m"  system: "s"  tools: [web_search, Files, Hook] }
            workflow Main(q) { delegate "{q}" to A -> r }
            """;

    LoomScript load(String source) throws Exception {
        Path f = dir.resolve("w.loom");
        Files.writeString(f, source);
        return new LoomLoader().load(f.toString());
    }

    static class Collected implements FindingSource.Sink {
        final List<Finding> findings = new ArrayList<>();
        final List<Note> notes = new ArrayList<>();

        @Override
        public void finding(Finding finding) {
            findings.add(finding);
        }

        @Override
        public void note(Note note) {
            notes.add(note);
        }
    }

    @Test
    void anAuditFindingKeepsItsRuleSeverityOwaspAndFixText() throws Exception {
        LoomScript script = load(RISKY);
        Collected out = new Collected();
        new AuditSource()
                .collect(
                        new ReviewInput("w.loom", dir, script, Map.of(), List.of(), null),
                        ReviewSettings.defaults(),
                        out);

        io.github.llm4j.loom.security.AuditReport audit =
                io.github.llm4j.loom.security.SecurityAudit.audit(script, "w.loom");
        assertThat(out.findings).hasSameSizeAs(audit.findings());
        Finding trifecta =
                out.findings.stream()
                        .filter(f -> f.rule().equals("LA01"))
                        .findFirst()
                        .orElseThrow();
        io.github.llm4j.loom.security.Finding original =
                audit.findings().stream()
                        .filter(f -> f.rule().equals("LA01"))
                        .findFirst()
                        .orElseThrow();
        assertThat(trifecta.lens()).isEqualTo(Lens.SECURITY);
        assertThat(trifecta.severity()).isEqualTo(Severity.HIGH);
        assertThat(trifecta.title()).isEqualTo(original.title());
        assertThat(trifecta.why()).isEqualTo(original.risk());
        assertThat(trifecta.fixHint()).isEqualTo(original.fix());
        assertThat(trifecta.where().line()).isEqualTo(original.line());
        assertThat(trifecta.owasp())
                .containsExactlyElementsOf(original.owasp().stream().map(Enum::name).toList());
        assertThat(trifecta.source()).isEqualTo("audit");
    }

    @Test
    void everySeverityOfTheAuditMapsToTheSameSeverityInTheReview() throws Exception {
        LoomScript script = load(RISKY);
        io.github.llm4j.loom.security.AuditReport audit =
                io.github.llm4j.loom.security.SecurityAudit.audit(script, "w.loom");
        Collected out = new Collected();
        new AuditSource()
                .collect(
                        new ReviewInput("w.loom", dir, script, Map.of(), List.of(), null),
                        ReviewSettings.defaults(),
                        out);
        for (int i = 0; i < out.findings.size(); i++)
            assertThat(out.findings.get(i).severity().name())
                    .isEqualTo(audit.findings().get(i).severity().name());
    }

    @Test
    void aValidatorErrorIsAHighFindingAndAWarningALowOne() {
        Collected out = new Collected();
        ReviewInput input =
                new ReviewInput(
                        "w.loom",
                        dir,
                        null,
                        Map.of(),
                        List.of(
                                new ValidityProblem(7, "agent Writer", "no such tool 'x'", true),
                                new ValidityProblem(
                                        0, null, "something about the whole script", false)),
                        null);
        new ValiditySource().collect(input, ReviewSettings.defaults(), out);

        assertThat(out.findings).hasSize(2);
        Finding error = out.findings.get(0);
        assertThat(error.rule()).isEqualTo("LV01");
        assertThat(error.lens()).isEqualTo(Lens.VALIDITY);
        assertThat(error.severity()).isEqualTo(Severity.HIGH);
        assertThat(error.title()).isEqualTo("agent Writer: no such tool 'x'");
        assertThat(error.where().line()).isEqualTo(7);
        Finding warning = out.findings.get(1);
        assertThat(warning.severity()).isEqualTo(Severity.LOW);
        assertThat(warning.where().wholeScript()).isTrue();
        assertThat(warning.title()).isEqualTo("something about the whole script");
        assertThat(warning.fixHint()).isNotBlank();
    }

    @Test
    void aCleanScriptYieldsNoAuditFindingsBeyondTheBudgetOne() throws Exception {
        LoomScript calm =
                load(
                        "budget { tokens: 1000 }\nagent A { model: \"m\"  system: \"s\" }\nworkflow Main(q) { delegate \"{q}\" to A -> r }\n");
        Collected out = new Collected();
        new AuditSource()
                .collect(
                        new ReviewInput("w.loom", dir, calm, Map.of(), List.of(), null),
                        ReviewSettings.defaults(),
                        out);
        assertThat(out.findings).isEmpty();
    }
}
