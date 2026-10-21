package io.github.llm4j.loom.inspect.rules;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.inspect.state.InspectRules;
import io.github.llm4j.loom.inspect.state.Thresholds;
import org.junit.jupiter.api.Test;

/**
 * R38, R41, R42 (grammar): access_denied, injection_scan and an accepted trifecta in the rules
 * file.
 */
class SecurityRulesFileTest {

    private static RulesFile.Parsed parse(String text) {
        return RulesFile.parse(text);
    }

    private static InspectRules.Security security(RulesFile.Parsed p, String agent) {
        return p.rules().get(agent).security();
    }

    @Test
    void theThreeRulesAreRead() {
        RulesFile.Parsed p =
                parse(
                        """
                Support {
                    access_denied: 5 in 2m
                    injection_scan: off
                    trifecta: accepted "customer support: reads tickets, looks up the account, replies"
                }
                """);
        assertThat(p.ok()).isTrue();
        InspectRules.Security s = security(p, "Support");
        assertThat(s.accessDeniedCount()).isEqualTo(5);
        assertThat(s.accessDeniedSeconds()).isEqualTo(120L);
        assertThat(s.injectionScan()).isFalse();
        assertThat(s.trifectaAccepted())
                .isEqualTo("customer support: reads tickets, looks up the account, replies");
    }

    @Test
    void injectionScanOnIsRead() {
        assertThat(security(parse("* { injection_scan: on }"), "*").injectionScan()).isTrue();
    }

    @Test
    void aReasonMayHoldTheCharactersThatMarkCommentsAndSeparators() {
        RulesFile.Parsed p =
                parse(
                        "Support { trifecta: accepted \"reads tickets; replies by mail # see https://wiki.example/support\" }");
        assertThat(p.ok()).as(p.problems().toString()).isTrue();
        assertThat(security(p, "Support").trifectaAccepted())
                .isEqualTo("reads tickets; replies by mail # see https://wiki.example/support");
    }

    @Test
    void aCommentAfterTheRuleIsStillACommentAndTwoRulesCanShareALine() {
        RulesFile.Parsed p =
                parse("Support { trifecta: accepted \"ok\"; access_denied: 4 in 30s   # note\n}");
        assertThat(p.ok()).as(p.problems().toString()).isTrue();
        assertThat(security(p, "Support").accessDeniedCount()).isEqualTo(4);
    }

    @Test
    void anAcceptanceWithoutAReasonIsAnErrorWithItsLine() {
        for (String bad :
                new String[] {
                    "trifecta: accepted",
                    "trifecta: accepted \"\"",
                    "trifecta: accepted \"   \"",
                    "trifecta: accepted no quotes here"
                }) {
            RulesFile.Parsed p = parse("Support {\n    " + bad + "\n}\n");
            assertThat(p.ok()).as(bad).isFalse();
            assertThat(p.problems())
                    .anySatisfy(
                            x -> {
                                assertThat(x.line()).isEqualTo(2);
                                assertThat(x.error()).isTrue();
                                assertThat(x.message()).contains("reason");
                            });
        }
    }

    @Test
    void aReasonWithADoubleQuoteInsideIsRefused() {
        RulesFile.Parsed p = parse("Support { trifecta: accepted \"says \"hi\" to users\" }");
        assertThat(p.ok()).isFalse();
    }

    @Test
    void acceptingForEveryAgentIsAllowedButWarns() {
        RulesFile.Parsed p = parse("* { trifecta: accepted \"all of our agents are reviewed\" }");
        assertThat(p.ok()).isTrue();
        assertThat(p.problems())
                .singleElement()
                .satisfies(
                        x -> {
                            assertThat(x.error()).isFalse();
                            assertThat(x.message()).contains("every agent").contains("hides");
                        });
    }

    @Test
    void badValuesAreErrorsThatSayHowToWriteThem() {
        assertThat(parse("A { injection_scan: maybe }").problems())
                .anySatisfy(
                        x ->
                                assertThat(x.message())
                                        .contains("injection_scan: on")
                                        .contains("off"));
        assertThat(parse("A { access_denied: 0 in 60s }").ok()).isFalse();
        assertThat(parse("A { access_denied: 3 in 0s }").ok()).isFalse();
        assertThat(parse("A { access_denied: 3 times }").problems())
                .anySatisfy(x -> assertThat(x.message()).contains("access_denied: 3 in 60s"));
    }

    @Test
    void theRulesAreListedAmongTheKnownOnes() {
        assertThat(RulesFile.RULES).contains("access_denied", "injection_scan", "trifecta");
        assertThat(parse("A { trifecta_ok: 3 }").problems())
                .anySatisfy(x -> assertThat(x.message()).contains("trifecta"));
    }

    @Test
    void theyRoundTripThroughWrite() {
        RulesFile.Parsed p =
                parse(
                        """
                * { injection_scan: on }
                Support {
                    access_denied: 5 in 2m
                    trifecta: accepted "reads tickets; replies by mail"
                }
                Other { injection_scan: off }
                """);
        assertThat(p.ok()).isTrue();
        RulesFile.Parsed again = parse(RulesFile.write(p.rules()));
        assertThat(again.ok()).as(again.problems().toString()).isTrue();
        assertThat(again.rules()).isEqualTo(p.rules());
    }

    @Test
    void aFileWithoutThemReadsAsBeforeAndTheirAbsenceChangesNothing() {
        RulesFile.Parsed p = parse("Editor { same_action: 3 times }");
        assertThat(p.rules().get("Editor").security()).isNull();
        Thresholds t = p.rules().get("Editor").over(Thresholds.defaults());
        assertThat(t.accessDeniedCount()).isEqualTo(3);
        assertThat(t.accessDeniedSeconds()).isEqualTo(60L);
        assertThat(t.injectionScan()).isTrue();
        assertThat(t.trifectaAccepted()).isNull();
    }

    @Test
    void anAgentsBlockOverridesTheStarBlockRuleByRule() {
        InspectRules star =
                parse("* { access_denied: 5 in 2m; trifecta: accepted \"all\" }").rules().get("*");
        InspectRules own = parse("Support { access_denied: 2 in 30s }").rules().get("Support");
        InspectRules.Security merged = own.security().over(star.security());
        assertThat(merged.accessDeniedCount()).isEqualTo(2);
        assertThat(merged.accessDeniedSeconds()).isEqualTo(30L);
        assertThat(merged.trifectaAccepted()).isEqualTo("all");
    }
}
