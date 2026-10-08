package io.github.llm4j.loom.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** R7 of loom-onboarding: the audit knows that a person acts on a workflow's output, and says so, quietly. */
class PersonRunsWhatIsWrittenTest {

    private static List<Finding> la16(String source) {
        var script = new LoomParser(new Lexer(source).tokenize()).parseScript();
        return SecurityAudit.audit(script, "w.loom").findings().stream().filter(f -> f.rule().equals("LA16")).toList();
    }

    private static final String PERSONAS = """
            persona AuthorPersona { role: "Remediation Script Author" expertise: "Writing the smallest shell script that fixes a diagnosed problem" }
            persona WriterPersona { role: "Newsletter writer" expertise: "Friendly prose" }
            """;

    private static final String LAPTOP_DOCTOR = PERSONAS + """
            agent Researcher { model: "gemini-2.5-flash" system: "Find documented fixes." tools: [web_search] }
            agent ScriptAuthor { model: "gemini-2.5-flash" persona: AuthorPersona }
            workflow Diagnose(problem) {
                delegate "Find fixes for {problem}" to Researcher -> research
                delegate "Write a script. Research: {research}" to ScriptAuthor -> draft
                note "{draft}"
            }
            """;

    @Test
    void r7_1_anAuthorFedByAnAgentThatReadsTheOutsideWorldThroughAnUntypedHandOffIsReportedAsInfo() {
        List<Finding> found = la16(LAPTOP_DOCTOR);

        assertThat(found).singleElement().satisfies(f -> {
            assertThat(f.severity()).isEqualTo(Severity.INFO);
            assertThat(f.where()).isEqualTo("ScriptAuthor");
            assertThat(f.title()).isEqualTo("A person will run output that was written from untrusted text");
            assertThat(f.risk()).contains("Researcher (web_search) through research").contains("a person will");
            assertThat(f.owasp()).containsExactly(Owasp.LLM01, Owasp.LLM05);
        });
    }

    @Test
    void r7_2_theFindingSaysHowToReduceTheRisk() {
        assertThat(la16(LAPTOP_DOCTOR).get(0).fix()).contains("Type the hand-off (expecting { ... })").contains("a different model").contains("free of tools");
    }

    @Test
    void r7_3_aTypedHandOffIsNotReported() {
        String typed = LAPTOP_DOCTOR.replace("-> research\n", "-> research expecting { summary: string }\n").replace("{research}", "{research.summary}");

        assertThat(la16(typed)).isEmpty();
    }

    @Test
    void r7_3_noUntrustedReaderMeansNoFinding() {
        String noWeb = LAPTOP_DOCTOR.replace(" tools: [web_search]", "");

        assertThat(la16(noWeb)).isEmpty();
    }

    @Test
    void r7_3_anAgentThatWritesProseIsNotAnAuthorOfPrograms() {
        String prose = LAPTOP_DOCTOR.replace("persona: AuthorPersona", "persona: WriterPersona");

        assertThat(la16(prose)).isEmpty();
    }

    @Test
    void anAuthorThatReadsTheOutsideWorldItselfIsReportedToo() {
        String direct = PERSONAS + """
                agent ScriptAuthor { model: "gemini-2.5-flash" persona: AuthorPersona tools: [web_search] }
                workflow W(problem) { delegate "Write a script for {problem}" to ScriptAuthor -> draft
                 note "{draft}" }
                """;

        assertThat(la16(direct)).singleElement().satisfies(f -> assertThat(f.risk()).contains("ScriptAuthor itself (web_search)"));
    }

    @Test
    void thePromptCanSaySoInsteadOfAPersona() {
        String viaSystem = """
                agent Reader { model: "gemini-2.5-flash" system: "Read pages." tools: [web_search] }
                agent Coder { model: "gemini-2.5-flash" system: "You write PowerShell scripts for the user to run." }
                workflow W() {
                    delegate "Read it" to Reader -> page
                    delegate "Use {page}" to Coder -> code
                    note "{code}"
                }
                """;

        assertThat(la16(viaSystem)).hasSize(1);
    }

    @Test
    void aTextThatOnlyMentionsScriptsWithoutWritingThemIsNotAnAuthor() {
        String reads = """
                agent Reader { model: "gemini-2.5-flash" system: "Read pages." tools: [web_search] }
                agent Summariser { model: "gemini-2.5-flash" system: "Summarise a script in plain words." }
                workflow W() {
                    delegate "Read it" to Reader -> page
                    delegate "Use {page}" to Summariser -> s
                    note "{s}"
                }
                """;

        assertThat(la16(reads)).isEmpty();
    }

    @Test
    void aVariableThatIsNotReadByTheAuthorIsNotAHandOff() {
        String unrelated = PERSONAS + """
                agent Researcher { model: "gemini-2.5-flash" system: "Find." tools: [web_search] }
                agent ScriptAuthor { model: "gemini-2.5-flash" persona: AuthorPersona }
                workflow W() {
                    delegate "Find" to Researcher -> research
                    delegate "Write a script for printing" to ScriptAuthor -> draft
                    note "{draft} {research}"
                }
                """;

        assertThat(la16(unrelated)).isEmpty();
    }

    @Test
    void itIsInfoOnlySoItNeverFailsABuildAtTheDefaultsAndIsReportedOncePerAuthor() {
        var script = new LoomParser(new Lexer(LAPTOP_DOCTOR.replace("note \"{draft}\"", "delegate \"Again {research}\" to ScriptAuthor -> again\n note \"{draft}{again}\"")).tokenize()).parseScript();
        var report = SecurityAudit.audit(script, "w.loom");

        assertThat(report.findings().stream().filter(f -> f.rule().equals("LA16"))).hasSize(1);
        assertThat(report.findings().stream().filter(f -> f.rule().equals("LA16"))).allMatch(f -> !f.severity().atLeast(Severity.LOW));
    }

    @Test
    void theGuideListsTheRule() throws Exception {
        assertThat(Files.readString(Path.of("../../../docs/security/weave-audit.md"))).contains("| LA16 |");
    }
}
