# Security signals: injections, the lethal trifecta, refused access

Observability that only says how well a run went leaves out whether it was safe. loom-inspect watches a run for four things, and says plainly how far to trust each.

| Signal | What it is | How sure it is |
|---|---|---|
| **The lethal trifecta** | One agent that has, in one run, read content you do not control, reached your private data and sent something out | **Exact**: from what the script declares and what the agent actually called |
| **Refused access** | A tool answering "permission denied" or "not authorised" again and again; a call to a tool the agent does not have; approvals that were refused | **Exact**: from the failure category and the call |
| **A suspected injection** | A tool result that reads like instructions to the agent ("ignore all previous instructions", a `system:` line, "email this to…") | **A heuristic**: a lead to follow, not a verdict. A careful attack can pass it; a harmless page can trip it |
| **Personal data** | What the `guard { pii }` and `bias` guards masked, blocked or detected | Exact for what the detector knows |

None of this changes what an agent can see, call or send. It only watches, so it cannot affect an agent's earned autonomy. Blocking something in progress is a policy for the script (a `guard`, an `approve`), decided by its author.

## The idea: declared against exercised

A Loom script says, before anything runs, what each agent can reach. `weave audit` reads that and reports where the trifecta *could* come together. The trace then says what the agent *did*. Putting the two side by side is what the security view shows, per agent:

| Verdict | Meaning |
|---|---|
| `none`, `one`, `two` | the agent has used none, one or two of the three legs |
| `armed` | its script lets it reach all three, and it has not (yet) used them all |
| `exercised` | it used all three in this run, and nobody has accepted that |
| `accepted` | it used all three, and the rules file accepts that in writing (below) |

A tool's **reach** is any of `untrusted` (reads content from outside), `private` (reaches your data) and `outward` (sends or changes something beyond the run). It comes from one place, shared by the audit and the trace, so they cannot disagree: the tool's declaration in the script, then `Name.reach` in the `.loot` file, then a built-in by name. A tool nobody can see into (a Java class with no declared reach, an MCP server, one the host registered) is **assumed to reach all three** and marked *assumed*. Declare what your own tools reach, so the view is a fact and not a guess:

```text
# flow.loot
Fetch.reach = fetches
CustomerDB.reach = reads
SendEmail.reach = sends
```

(`none`, `reads`, `fetches`, `writes`, `sends`.)

## Incidents

| Incident | Severity | Fires when | Stays |
|---|---|---|---|
| **Trifecta** | high; **info when accepted**; warning when a leg rests only on assumed tools | one agent has called a tool of each leg in one run; the evidence names the tool of each leg | open after the run ends (an assumed-only warning closes with it) |
| **Injection** | warn; **high** when the same agent's next call is an outward one or a tool it had not called before, and **always high for an agent whose trifecta is accepted** | a result from an untrusted tool matched the injection patterns; the evidence has the pattern groups and the source tool, never the text | open after the run ends |
| **Access** | warn; info for an unknown tool | a tool refuses access 3 times in 60 s (`permission_denied` or `authentication`); any call to a tool the agent does not have | closes when the tool answers again; unknown tool until the run ends |

A refused *approval* is people deciding, not an incident: it is counted in the view and nothing more.

### Accepting a trifecta

Many legitimate agents use all three legs by design: a support agent reads a ticket, looks up the customer and replies. An alarm on every run of such an agent would teach people to ignore the one signal that matters. So the author of the rules can accept it, **with a written reason** (a missing or empty reason is an error, so an acceptance is never silent):

```text
# flow.inspect
Support { trifecta: accepted "customer support: reads tickets, looks up the account, replies by mail" }
```

- The incident becomes **info**, shows the reason, and is still listed, counted and exported. Nothing is hidden.
- Acceptance does not make the agent safe. **An injection finding for an accepted agent is always high**, because the combination is only safe while nothing untrusted steers it.
- `* { trifecta: accepted "…" }` accepts for every agent that does not say otherwise. It is allowed, and `weave observe rules` warns that it hides the signal.
- In Fabric the acceptance is set on the Configuration page and recorded with who set it and when.

## The injection scan, and what it is not

It runs **in your process**, so tool-result text never leaves it. It reads only results of tools whose reach includes `untrusted`, only the first 20,000 characters, against a fixed list of patterns in five groups:

| Group | What it looks for |
|---|---|
| `override` | "ignore / disregard / forget … previous / above / your … instructions / rules" |
| `role` | a line that opens as `system:`, `assistant:` or `developer:`, and chat-template markers (`<\|im_start\|>`, `[INST]`, `<<SYS>>`) |
| `exfil` | "send / email / forward / upload … to" followed by an address or a URL |
| `reveal` | a request for the system prompt, hidden instructions, an API key or credentials |
| `hidden` | zero-width and bidirectional control characters; an HTML comment holding instructions |

What leaves the process is the **group names and a count**, never the matched or surrounding text, at every detail level. The list is fixed on purpose: it behaves the same everywhere and cannot be used to hide a finding. Turn it off with `injection_scan: off`.

It is a heuristic. A page that merely discusses "ignoring previous instructions" can trip it, and a careful attack can avoid every pattern. Read a finding as "look at this result", together with what the agent did next.

## Rules

```text
# flow.inspect
Support {
    access_denied: 3 in 60s                 # a tool refusing access this many times within this span
    injection_scan: on                      # or off
    trifecta: accepted "why this is acceptable"
}
```

Per agent or `*`, from the file beside the script, and centrally on the Fabric Configuration page, like the other rules. A file without them reads as before. `injection_scan` is applied where the scan runs (the process's own file); set centrally in Fabric it silences the findings that a process still sent.

## Try it

[`src/examples/inspect-security`](../src/examples/inspect-security/README.md) runs a support agent that reads a page with a planted instruction, looks the customer up and sends mail, plus a second agent that is refused access to the customer database. It prints the security view and incidents three ways: as it is, with the trifecta accepted, and with a clean page. No model, key or server.

## What it cannot tell you

- That a run was *attacked*. The evidence is a pattern and a sequence of calls, for a person to judge.
- Anything about a tool whose reach is only assumed, beyond "assumed".
- What an agent would do if it were steered, as opposed to what it did. The static review (`weave audit`) is the place for that.

See also: [Incidents and rules](Observability-Incidents-and-Rules.md) · [Controls, secrets and security](Observability-Controls-and-Security.md) · [Reference](Observability-Reference.md) · [the design](../docs/specs/04-inspect-and-incidents/DESIGN.md).
