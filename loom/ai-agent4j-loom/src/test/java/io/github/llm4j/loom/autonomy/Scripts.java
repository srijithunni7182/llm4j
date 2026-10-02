package io.github.llm4j.loom.autonomy;

/** The decision declaration the autonomy tests share. */
public final class Scripts {

    private Scripts() { }

    public static final String AGENT = """
            agent Triager {
                model: "m"
                system: "You are Triager."
                output_schema: { choice: string, reasoning: string, confidence: number }
            }
            """;

    /** The example of the design, §1.1. */
    public static final String REFUND = AGENT + """
            decision Refund {
                proposed by:            Triager
                choices:                approve, reject, escalate
                group cases by:         tier
                remember:               amount, reason, customer_since
                dangerous mistake:      propose approve, person decides reject
                ask:                    support-lead
                keep records for:       180 days
                when the agent changes: test it on past cases
                tell Slack when trust changes

                trust {
                    start at watch
                    never go above suggest

                    to suggest:  after 100 cases over 14 days, agreeing at least 90%
                    to act:      after 300 cases over 30 days, agreeing at least 97%, with no dangerous mistakes
                    judge on the latest 300 cases

                    check 5% of cases with a person who doesn't see the proposal
                    always ask a person when amount > 200
                    always ask a person after 50 cases a day

                    drop to suggest when 2 dangerous mistakes in 50 cases
                    drop to suggest when 2 reversals in 100 cases
                    drop to suggest when agreement falls below 92%
                    drop to suggest when 5 unusable proposals in 50 cases

                    moving up needs approval from: risk-owner
                }
            }
            workflow Triage(ticket, tier, amount, reason, customer_since) {
                decide Refund -> verdict
                alt (verdict == "approve") { note "refund" }
            }
            """;
}
