package io.github.llm4j.loom.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * docs/specs/31-multimodal-input R21 (LA23): a picture, a recording or a document whose file is
 * chosen at run time can carry instructions, so an agent that receives one reads untrusted content,
 * as one that searches the web does (LA20). A file the script's author named does not count.
 */
class AttachAuditTest {

    private static List<Finding> audit(String source) {
        var script = new LoomParser(new Lexer(source).tokenize()).parseScript();
        return SecurityAudit.audit(script, "w.loom").findings();
    }

    private static List<Finding> la23(List<Finding> all) {
        return all.stream().filter(f -> f.rule().equals("LA23")).toList();
    }

    private static final String PLAIN =
            "media_root: \"assets\"\nagent Reader { model: \"gemini-2.5-flash\" system: \"x\" }\n";

    @Test
    void aFileChosenAtRunTimeIsReportedForTheAgentThatReceivesIt() {
        List<Finding> found =
                la23(audit(PLAIN + "workflow M(file) { delegate \"total?\" to Reader attach image \"{file}\" -> out }"));

        assertThat(found).hasSize(1);
        Finding f = found.get(0);
        assertThat(f.where()).isEqualTo("Reader");
        assertThat(f.severity()).isEqualTo(Severity.LOW);
        assertThat(f.owasp()).contains(Owasp.LLM01);
        assertThat(f.title()).contains("chosen at run time");
        assertThat(f.risk()).contains("picture").contains("instructions");
        assertThat(f.fix()).containsIgnoringCase("name the file");
    }

    @Test
    void aFileTheScriptNamesIsNotReported() {
        assertThat(
                        la23(
                                audit(
                                        PLAIN
                                                + "workflow M() { delegate \"total?\" to Reader attach image \"receipt.png\" -> out }")))
                .isEmpty();
    }

    @Test
    void aScriptThatAttachesNothingIsNotReported() {
        assertThat(la23(audit(PLAIN + "workflow M() { delegate \"hello\" to Reader -> out }"))).isEmpty();
    }

    @Test
    void broadcastHandoffAndAChosenTargetAreCoveredToo() {
        String agents =
                PLAIN + "agent Other { model: \"gemini-2.5-flash\" system: \"x\" }\n";

        assertThat(
                        la23(audit(agents + "workflow M(f) { broadcast \"x\" to [Reader, Other] attach image \"{f}\" -> out }"))
                                .stream()
                                .map(Finding::where))
                .containsExactlyInAnyOrder("Reader", "Other");
        assertThat(
                        la23(audit(agents + "workflow M(f) { handoff \"x\" to Other attach image \"{f}\" }"))
                                .stream()
                                .map(Finding::where))
                .containsExactly("Other");
        assertThat(
                        la23(
                                        audit(
                                                agents
                                                        + "workflow M(f, who) { delegate \"x\" to {who} in [Reader, Other] attach image \"{f}\" -> out }"))
                                .stream()
                                .map(Finding::where))
                .containsExactlyInAnyOrder("Reader", "Other");
    }

    @Test
    void anAgentIsReportedOnceHoweverManyStepsSendItFiles() {
        assertThat(
                        la23(
                                audit(
                                        PLAIN
                                                + "workflow M(f) {\n delegate \"a\" to Reader attach image \"{f}\" -> o1\n delegate \"b\" to Reader attach image \"{f}\" -> o2\n}")))
                .hasSize(1);
    }

    @Test
    void itIsTheUntrustedLegOfTheTrifecta() {
        String tools =
                """
                tool Files { use: file  root: "."  mode: readwrite }
                tool Mailer { use: email  host: "smtp.example.com"  username: env.U  password: env.P }
                media_root: "assets"
                agent Reader { model: "gemini-2.5-flash" tools: [Files, Mailer] }
                """;

        List<Finding> withRunTimeFile =
                audit(tools + "workflow M(f) { delegate \"x\" to Reader attach image \"{f}\" -> out }");
        List<Finding> withNamedFile =
                audit(tools + "workflow M() { delegate \"x\" to Reader attach image \"receipt.png\" -> out }");

        assertThat(withRunTimeFile)
                .anySatisfy(
                        f -> {
                            assertThat(f.rule()).isEqualTo("LA01");
                            assertThat(f.risk()).contains("attachments chosen at run time");
                        });
        assertThat(withNamedFile).noneMatch(f -> f.rule().equals("LA01"));
    }

    @Test
    void theRuleIsInTheCatalogOfRules() {
        assertThat(io.github.llm4j.loom.review.adapt.AuditCatalog.rules())
                .anySatisfy(
                        r -> {
                            assertThat(r.id()).isEqualTo("LA23");
                            assertThat(r.title()).contains("run time");
                        });
    }
}
