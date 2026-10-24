package io.github.llm4j.loom.review.adapt;

import io.github.llm4j.loom.review.engine.FindingSource;
import io.github.llm4j.loom.review.engine.ReviewInput;
import io.github.llm4j.loom.review.model.Finding;
import io.github.llm4j.loom.review.model.Lens;
import io.github.llm4j.loom.review.model.Location;
import io.github.llm4j.loom.review.model.Severity;
import io.github.llm4j.loom.review.settings.ReviewSettings;
import io.github.llm4j.loom.security.AuditReport;
import io.github.llm4j.loom.security.SecurityAudit;
import java.util.List;
import java.util.Optional;

/**
 * Brings the security audit ({@code weave audit}, rules {@code LA01} to {@code LA20}) into a
 * review. The audit is unchanged; each of its findings becomes a {@link Finding} in the security
 * lens, so a review is never missing anything the audit reports.
 */
public final class AuditSource implements FindingSource {

    @Override
    public String name() {
        return "audit";
    }

    @Override
    public void collect(ReviewInput input, ReviewSettings settings, Sink sink) {
        AuditReport report = SecurityAudit.audit(input.script(), input.file(), input.lootReach());
        for (io.github.llm4j.loom.security.Finding f : report.findings())
            sink.finding(convert(f, input.file()));
    }

    /** One audit finding as a review finding. */
    static Finding convert(io.github.llm4j.loom.security.Finding f, String file) {
        List<String> owasp = f.owasp().stream().map(Enum::name).toList();
        return new Finding(
                f.rule(),
                Lens.SECURITY,
                severity(f.severity()),
                Location.at(file, f.line()),
                f.title(),
                f.risk(),
                f.fix(),
                owasp,
                Optional.empty(),
                List.of(),
                "audit");
    }

    private static Severity severity(io.github.llm4j.loom.security.Severity s) {
        return switch (s) {
            case HIGH -> Severity.HIGH;
            case MEDIUM -> Severity.MEDIUM;
            case LOW -> Severity.LOW;
            case INFO -> Severity.INFO;
        };
    }
}
