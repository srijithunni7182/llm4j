# 13. Earned autonomy (optional)

**Skip this chapter unless** an agent will make a decision that a person makes today (approve a refund, grant access, post a reply)
and you want it to take over that decision *gradually, on evidence*, instead of being trusted or distrusted on the day it launches.
If every step of your workflow is fine to run unattended, or a person already approves every step (`approve:`), you do not need it
and nothing else in this guide depends on it.

The idea: the agent **proposes**, a person **decides**, and a ledger records both. The agent starts on a rung where people decide
without seeing its proposal, climbs when the record says it has earned it, and slips back when the record says it should.
Reference: the "Earned Autonomy" and "Ladders" sections of `LOOM_GUIDE.md` (`weave guide loom`).

## 1. Decide whether you want it

Ask the user, and write the answer in the project's README (`Earned autonomy: none` or `Earned autonomy: Refund decision, ladder RefundLadder`):

- Which decision? What are its choices (a short list), or does the agent write something (a script, a post)?
- What would make them trust the agent? How many cases, over how long, agreeing how often? Which mistake is dangerous (the agent says approve, the person says reject)? What should send a case to a person whatever the record says (an amount over a limit)? What should make it slip back?
- Should it ever be allowed to act alone? The default is yes: every agent can reach the top rung. Only set a lower `ceiling:` when the decision must always have a person.

You do not choose the numbers for them; the policy is theirs.

## 2. Write it

Two ways, the same engine underneath. For a choice from a short list, a `trust { }` block is the shortest:

```loom
agent Triager {
    model: "gemini-2.5-flash"
    system: "You are Triager. Decide whether a refund request should be approved."
    output_schema: { choice: enum["approve", "reject", "escalate"], reasoning: string, confidence: number }
}

decision Refund {
    proposed by:            Triager
    choices:                approve, reject, escalate
    remember:               amount, reason
    dangerous mistake:      propose approve, person decides reject
    ask:                    support-lead
    when the agent changes: test it on past cases

    trust {
        never go above act
        to suggest:  after 100 cases over 14 days, agreeing at least 90%
        to act:      after 300 cases over 30 days, agreeing at least 97%, with no dangerous mistakes
        check 5% of cases with a person who doesn't see the proposal
        always ask a person when amount > 200
        drop to suggest when 2 dangerous mistakes in 50 cases
        moving up needs approval from: risk-owner
    }
}

workflow Triage(ticket, amount, reason) {
    decide Refund -> verdict
    note "case {ticket}: {verdict}"
}
```

When the success measure is not "agrees with the person" (how much a reviewer rewrites a script, whether a change was reverted a week later),
write a `ladder` with your own `measure`s and bind it with `ladder: Name`. An agent can also carry a default (`ladder:` and `ceiling:` inside the agent)
that every decision it proposes for uses unless the decision names its own. See the Ladders section of `LOOM_GUIDE.md` for a complete example.

## 3. Check it, free

```
weave check refund.loom --no-env              # every condition is checked when the script loads, errors name the line
weave ladder describe refund.loom Refund      # the policy in plain words: show it to the user and ask "is this what you meant?"
weave ladder test refund.loom Refund --store runs/.loom-triggers   # the rung each scope would hold on recorded history; writes nothing
weave audit refund.loom                       # LA10 notes a decision that may reach act automatically
```

`describe` is the gate: the person who owns the risk reads it and agrees. If a rule reads wrongly, it is wrong in the script.

## 4. Run it, and keep it honest

- Nothing is earned until real cases have run. Say so in your report; do not claim a level.
- The agent cannot see or change its level or the ledger. Never put them in a prompt or a tool.
- Record what happens later (a refund reversed, a post's views) with `weave autonomy outcome`.
- Before a changed prompt or model goes live, `weave replay` re-runs past cases under it (nothing is sent, nothing is written) and `when the agent changes: test it on past cases` makes the new agent inherit only the rung the old record supports.
- Operate it: `weave autonomy status|history|approve|promote|demote|freeze <store>`.
- `weave autonomy verify <store>` shows whether the level record was edited outside Loom. If it does not verify, a decision asks a person until the operator looks and decides (`--accept --reason R --by B`). That is the operator's call, not yours.

## Gate

The user has read `weave ladder describe`, the README says what was decided, `weave check` and `weave audit` are clean or explained, and
the report says that no level has been earned yet.
