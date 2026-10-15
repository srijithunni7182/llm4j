# D7. Read the pages: a tour, and what to do about each incident

**Goal:** the developer can find what they need on the page, and knows what each signal means and what to do. Teach by having them **find one thing on each page**.

## The embedded page and Fabric

The header has **Live / Paused**: while Paused nothing redraws, so you can read; it counts what changed. On Fabric there are tabs: **Processes** (who is reporting, online or offline), **Tools & tasks** (health across all runs), **Security** (across all runs), **Runs** (every run, with a search and a filter), **Configuration** (admin only) and a tab for each live run (at most eight; everything else is in **Runs**).

## A run's page

- A **banner** says if the run stopped, and why: *the task X failed*, with its category, attempts and step; a quieter line says a step *went through on_failure*.
- **Needs attention** lists open incidents, worst first.
- The **cards**: tokens, cost so far, what is running, the context (the fullest agent), tool calls, tasks.
- The **time bar** under the view tabs: drag it, or click a marker (start, incident, failure, compaction), and the whole page shows the run **as it was**, under a banner "a snapshot, not live"; **Back to live** returns. Use it to answer "what happened just before it broke?".
- Views: **Overview** (the workflow graph with each step's state; the incidents; the agents; the live trace), **Context** (a gauge per agent and a timeline with a marker for each compaction), **Tools & tasks** (health, success, latency, failures by category), **Security** (the trifecta per agent: what each agent *can* reach and what it *did*; suspected injection; refused access; the statement of what the view cannot tell you).

## When something is wrong: where to look

| The developer says | Look at | Meaning |
|---|---|---|
| "It is stuck / looping" | incidents *Looping: same call / same answer*, *No progress* | the agent repeats itself; read its prompt and the tool's result; add a limit or change the task |
| "It is slow" | *Latency spike*, the Tools & tasks p95, the Context timeline | a provider or dependency is slow now; or the prompt is huge |
| "It is expensive" | the cost card, Context (a prompt that keeps growing), *Compaction thrash* | the agent resends a lot; shorten tool results, add a `context` block, or split the job |
| "It stopped" | the banner, *Workflow broken* | the named step failed with no `on_failure`; decide on `retry` or `on_failure` |
| "It went another way" | *Workflow diverted* | a step failed and its `on_failure` took over; make sure that is what you want |
| "A tool is failing" | Tools & tasks, *Failing tool*, *Tool success rate* | the evidence names the most common failure category (`timeout`, `rate_limited`, ...) |
| "Will it run out of room?" | the Context view, *Context nearly full*, *Compaction thrash* | an agent's prompt fills its model's window (a warning at 85 %, high at 95 % by default); compaction frees space, and thrash means it cannot keep up: shorten what the tools return or add a `context` block |
| "The answer is cut off" | *Truncated answer* | the model hit its token limit |
| "Is it safe?" | the Security view, *Lethal trifecta*, *Suspected injection*, *Refused access* | see below |

## Reading the security view honestly

- **Lethal trifecta** (high): one agent used a tool of each leg (untrusted content, private data, a way out) in one run. Exact about what was declared and called; it does not say anyone abused it. Split the agent, or accept it in writing (chapter 2). *Armed* means it could but has not.
- **Suspected injection**: a tool result read like instructions. It is a **heuristic**: the page keeps only the names of the pattern groups, never the text. Look at the agent's *next* call: an unusual or outward one makes it high.
- **Refused access**: a tool refused an agent several times, or a call to a tool the agent does not have: a wrong role or credential, or something steering it.
- The page says what it cannot tell you. **A green page is not a review.** Injection detection can be passed by a careful attack and tripped by a harmless page.

## Teach it by doing

Ask the developer to: open the run's page and say the cost; open Tools & tasks and say which tool is least healthy; open Security and say which agents could send something out; drag the time bar to the first incident and say what changed. If they cannot, show them, then ask again.

## Gate

The user found one thing on each page, knows the three or four incidents most likely for their workflow, and knows the page is a lead, not a verdict.
