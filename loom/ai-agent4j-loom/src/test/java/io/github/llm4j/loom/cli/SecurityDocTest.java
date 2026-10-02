package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.generic.support.ScriptedRun;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The repository's SECURITY.md shows scripts that really load, and links to files that really exist. */
class SecurityDocTest {

    static final Path DOC = Path.of("../../SECURITY.md");
    static final Path PAGES = Path.of("../../docs/security");
    static final Path WORKFLOWS = PAGES.resolve("securing-workflows.md");

    /** The landing page and every page of the guide. */
    static java.util.List<Path> allPages() throws Exception {
        java.util.List<Path> out = new java.util.ArrayList<>(java.util.List.of(DOC));
        try (var s = Files.list(PAGES)) {
            out.addAll(s.filter(p -> p.toString().endsWith(".md")).sorted().toList());
        }
        return out;
    }

    @TempDir
    Path dir;

    @Test
    void everyLoomExampleInTheSecurityGuideLoadsAndPassesTheChecks() throws Exception {
        String text = Files.readString(WORKFLOWS);
        List<String> blocks = new ArrayList<>();
        Matcher m = Pattern.compile("```loom\\n(.*?)```", Pattern.DOTALL).matcher(text);
        while (m.find()) blocks.add(m.group(1));
        assertThat(blocks).hasSizeGreaterThanOrEqualTo(5);
        for (String block : blocks) {
            Files.createDirectories(dir.resolve("work"));
            ScriptedRun run = new ScriptedRun(dir);
            run.env.put("TEAM_WEBHOOK", "https://hooks.slack.com/services/T000/B000/XXXX");
            run.env.put("DB_URL", "jdbc:h2:mem:sec");
            run.env.put("DB_RO_USER", "reader");
            run.env.put("DB_RO_PASSWORD", "secret");
            try {
                run.executor(block).initialize();
            } catch (RuntimeException e) {
                throw new AssertionError("this SECURITY.md example doesn't load:\n" + block + "\n→ " + e.getMessage(), e);
            }
        }
    }

    @Test
    void theWorkedExampleHasNoHighOrMediumFindingsInTheAudit() throws Exception {
        String text = Files.readString(WORKFLOWS);
        int at = text.indexOf("## A worked example");
        Matcher m = Pattern.compile("```loom\\n(.*?)```", Pattern.DOTALL).matcher(text.substring(at));
        assertThat(m.find()).isTrue();
        Path f = dir.resolve("weekly.loom");
        Files.writeString(f, m.group(1));
        var report = io.github.llm4j.loom.security.SecurityAudit.audit(new io.github.llm4j.loom.execution.LoomLoader().load(f.toString()), "weekly.loom");
        assertThat(report.findings()).as(report.markdown()).noneMatch(x -> x.severity().atLeast(io.github.llm4j.loom.security.Severity.MEDIUM));
        assertThat(report.agents()).noneMatch(io.github.llm4j.loom.security.SecurityAudit.AgentProfile::trifecta);
    }

    @Test
    void everyLocalLinkInTheSecurityGuidePointsAtAFileThatExists() throws Exception {
        int seen = 0;
        for (Path page : allPages()) {
            Matcher m = Pattern.compile("\\]\\(([^)#:]+)(#[^)]*)?\\)").matcher(Files.readString(page));
            while (m.find()) {
                seen++;
                assertThat(page.getParent().resolve(m.group(1))).as(page + ": " + m.group(0)).exists();
            }
        }
        assertThat(seen).isGreaterThan(20);
        String audit = Files.readString(PAGES.resolve("weave-audit.md"));
        for (int i = 1; i <= 14; i++) assertThat(audit).as("the audit page lists rule LA%02d", i).contains(String.format("| LA%02d |", i));
        String owasp = Files.readString(PAGES.resolve("owasp-llm-top-10.md"));
        for (var o : io.github.llm4j.loom.security.Owasp.values()) assertThat(owasp).contains("**" + o.name() + " " + o.title() + "**");
        String landing = Files.readString(DOC);
        for (String page : java.util.List.of("building-blocks.md", "securing-workflows.md", "weave-audit.md", "owasp-llm-top-10.md")) {
            assertThat(landing).as("the landing page links " + page).contains("docs/security/" + page);
        }
        assertThat(landing).contains("## Reporting a vulnerability");
        assertThat(Files.readString(Path.of("../../README.md"))).contains("SECURITY.md");
    }

}
