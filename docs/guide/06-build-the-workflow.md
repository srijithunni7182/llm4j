# 6. Build the workflow

**Goal:** compose the agents you have already tested into a Loom workflow whose every step is named, traced and budgeted.

## Why Loom, why now

Building the workflow *after* the agents are tested means a failure is the workflow's fault, not an untested prompt's. In Loom each step is a
named delegation with a trace event, a budget and a replay point, which is exactly what chapter 8's trajectory tests assert on.
Reference: [Loom guide](../../loom/ai-agent4j-loom/LOOM_GUIDE.md).

## Build it in this order

1. **Personas and agents**, copied from chapter 1 (the tested prompts and temperatures, unchanged).
2. **Tools** declared once; secrets only from `env.NAME` (a literal credential is a load error).
3. **A run budget** at the top. Start with a ceiling you can afford, then tighten it from measured cost.
4. **The workflow**: parallel rounds, a branch for the case that should end early, typed hand-offs, a final consensus.

```loom
audit  { logger: "file"  path: "hexamind-audit.json" }
budget { tokens: 900000  calls: 220  warn_at: 80% }
tool Search { use: serpapi  api_key: env.SERPAPI_KEY }

agent Rahul { model: "gemini-3.5-flash"  persona: AdversarialResearcher  temperature: 0.2  max_iterations: 12  tools: [Search] }
agent Moderator { model: "gemini-3.5-flash"  system: "You read the first-round analyses and report whether any expert said a term could not be verified."  temperature: 0.0 }

workflow Collaborate(problem) {
    parallel {                                                       // round 1: every agent searches independently
        delegate "{problem} ... verify literally ..." to Alex  -> a1
        delegate "{problem} ... verify literally ..." to Rahul -> h1
        // ... one line per agent
    }
    checkpoint round1 starting with fb = "none"
    delegate "Problem: {problem}\nAlex: {a1}\nRahul: {h1} ..." to Moderator -> fabcheck
        expecting { fabricated: enum["YES","NO"], term: string }     // a typed hand-off: not free text
    alt (fabcheck.fabricated == "YES") {
        delegate "Round 1 findings: ... State the debunk." to Debunker -> final_text     // skip the debate about nothing
    } else {
        // rounds 2 to 5 in parallel, then:
        delegate "Synthesize into one recommendation ..." to Coordinator -> final_text
    }
    handoff final_text to Coordinator
}
```

Full script: [`hexamind.loom`](../../examples/hexamind-hub/eval/hexamind.loom).

## Things that bit us (so they do not bite you)

- **Variable names are replaced everywhere in a string, braces or not.** A workflow parameter called `consensus` turned "Previous consensus: {consensus}"
  into "Previous old: old". Name variables so they can never be an ordinary word in your prompts (`final_text`, `prior`, `feedback_text`).
- **A budget is a hard stop.** Exhausting it ends the run with an error and *no* output. If you want a partial answer, design a wind-down step or use
  `when_exhausted: suspend`.
- **`handoff` to an agent makes one more model call.** Count it in your cost model.
- **Give each branch its own agent** when you need to tell the paths apart in a trace: Hexamind added a `Debunker` so the debunk path differs from the consensus path.
- **Roles without tools** (moderator, coordinator) cannot be hijacked into acting; keep tools on the agents that need them.

## Gate

`weave check your.loom` passes (next chapter), and the script uses the prompts and temperatures you tested, unchanged.
