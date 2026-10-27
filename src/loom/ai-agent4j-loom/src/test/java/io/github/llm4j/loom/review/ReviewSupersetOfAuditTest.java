package io.github.llm4j.loom.review;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.review.engine.ReviewInput;
import io.github.llm4j.loom.review.engine.ReviewReport;
import io.github.llm4j.loom.review.model.Finding;
import io.github.llm4j.loom.review.settings.ReviewSettings;
import io.github.llm4j.loom.security.SecurityAudit;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** R7 of spec 09: no finding {@code weave audit} produces is missing from {@code weave review}. */
class ReviewSupersetOfAuditTest {

    static final Path EXAMPLES = Path.of("../../examples");

    static List<Path> scripts() throws Exception {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> s = Files.walk(EXAMPLES, 3)) {
            s.filter(p -> p.toString().endsWith(".loom")).sorted().forEach(out::add);
        }
        return out;
    }

    @Test
    void everyAuditFindingOfEveryExampleAppearsInTheReview() throws Exception {
        int audited = 0;
        for (Path p : scripts()) {
            LoomScript script;
            try {
                script = new LoomLoader().load(p.toAbsolutePath().toString());
            } catch (Exception e) {
                continue; // an example that needs files or settings the test does not have is not
                // the subject here
            }
            var audit = SecurityAudit.audit(script, p.getFileName().toString(), Map.of());
            ReviewReport review =
                    io.github.llm4j.loom.review.Reviews.engine()
                            .review(
                                    new ReviewInput(
                                            p.getFileName().toString(),
                                            p.getParent(),
                                            script,
                                            Map.of(),
                                            List.of(),
                                            null),
                                    ReviewSettings.defaults());
            for (var f : audit.findings()) {
                assertThat(review.findings())
                        .as("%s: %s line %d", p, f.rule(), f.line())
                        .anyMatch(
                                r ->
                                        r.rule().equals(f.rule())
                                                && r.where().line() == f.line()
                                                && r.title().equals(f.title()));
            }
            audited++;
        }
        assertThat(audited)
                .as("the examples folder was found and some scripts load")
                .isGreaterThan(3);
    }

    @Test
    void auditFindingsAreNotInventedOrMultipliedByTheReview() throws Exception {
        Path f = Files.createTempFile("w", ".loom");
        Files.writeString(f, AdapterTest.RISKY);
        LoomScript script = new LoomLoader().load(f.toString());
        var audit = SecurityAudit.audit(script, "w.loom", Map.of());
        ReviewReport review =
                io.github.llm4j.loom.review.Reviews.engine()
                        .review(
                                new ReviewInput(
                                        "w.loom", f.getParent(), script, Map.of(), List.of(), null),
                                ReviewSettings.defaults());
        List<Finding> security =
                review.findings().stream()
                        .filter(x -> x.lens() == io.github.llm4j.loom.review.model.Lens.SECURITY)
                        .toList();
        assertThat(security).hasSameSizeAs(audit.findings());
    }
}
