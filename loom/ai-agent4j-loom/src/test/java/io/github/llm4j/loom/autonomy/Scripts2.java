package io.github.llm4j.loom.autonomy;

/** Decisions with small thresholds, so a test can walk a ladder in a few cases. */
final class Scripts2 {

    private Scripts2() { }

    static final String WORKFLOW = """
            workflow Triage(ticket, tier, amount, reason, customer_since) {
                decide Refund -> verdict
                note "verdict {verdict} proposal {verdict_proposal} level {verdict_level}"
            }
            """;

    /** A small ladder: 5 blind cases at 80% to suggest, 8 at 90% to act, a 20-case window. */
    static String refund(String trustExtra) {
        return Scripts.AGENT + """
                decision Refund {
                    proposed by:       Triager
                    choices:           approve, reject, escalate
                    group cases by:    tier
                    remember:          amount, reason, customer_since
                    dangerous mistake: propose approve, person decides reject
                    ask:               support-lead
                    trust {
                        start at watch
                        never go above act
                        to suggest: after 5 cases, agreeing at least 50%
                        to act:     after 8 cases, agreeing at least 50%, with no dangerous mistakes
                        judge on the latest 20 cases
                        moving up is automatic
                """ + trustExtra + """
                    }
                }
                """ + WORKFLOW;
    }
}
