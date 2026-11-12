# Workflow patterns

Each pattern below is a real, small Loom workflow that loads (`weave check`) and has tests that need no key. They are skeletons to adapt, not
answers: the questions under each one are what the person has to decide before the script is worth writing. Ask them, one pattern at a time,
and record the answers (stage 1 of the guide).

The models in the skeletons are named `gemini-2.5-flash`. Change the `model:` lines to whatever the person uses; never put a key in a script.

Patterns combine. An extract-and-validate step often sits in front of a route; an approval at the edge closes almost every workflow that
sends something. When two patterns meet, keep the control flow in the script and let each agent do one job.

When no pattern fits, say so, and fall back to `weave generate "what it should do" --plan-only --out plan` (the llm4j-workflow-guide skill
explains it).

## 1. Route by a label, answer by specialist

**Use it when:** Messages of different kinds need different handling: support tickets, intents, triage, any "which one is this?" step.

**Decide with the person:**

- The labels (a closed list), and who answers each. Use `output_schema` with an `enum` so the classifier can say nothing else.
- Whether the label needs an agent at all. A step that only chooses a label is an `intentclassify` (guide chapter 1), not a chat agent; the skeleton uses an agent only to show the routing.
- What happens on a label nobody expected: the last `else` is the answer, so make it the safest one (often: ask a person).

<!-- starter:04-router -->
```loom
// 04-router: decide which specialist should answer. The classifier only labels; the script's own `alt` does the routing,
// so no wording in a message can change which branch runs.

substitution: braces

agent Classifier {
    model: "gemini-2.5-flash"
    system: "Classify the customer message."
    temperature: 0.0
    output_schema: { kind: enum["BILLING", "TECHNICAL", "OTHER"] }
}
agent Billing   { model: "gemini-2.5-flash"  system: "You answer billing questions politely and briefly." }
agent Technical { model: "gemini-2.5-flash"  system: "You answer technical questions with numbered steps." }
agent Generalist { model: "gemini-2.5-flash"  system: "You answer general questions briefly." }

workflow Main(message) {
    delegate "Classify: {message}" to Classifier -> label

    alt (label.kind == "BILLING") {
        delegate "{message}" to Billing -> reply
    } else {
        alt (label.kind == "TECHNICAL") {
            delegate "{message}" to Technical -> reply
        } else {
            delegate "{message}" to Generalist -> reply
        }
    }

    return reply
}
```

**What to keep and what to watch:**

- The classifier only labels; the script's own `alt` routes. Never let a model's words choose which branch runs.
- A wrong label is the usual failure, not a wrong answer: put the confusable pairs in the golden dataset.

Stages that matter: 1 (is it a classifier?), 2 (a case per label and per confusable pair), 8 (a scripted case down every branch).

## 2. Write, review, rewrite, bounded

**Use it when:** A draft improves when something else reads it: descriptions, replies, code comments, summaries.

**Decide with the person:**

- What the reviewer checks, in one sentence, and what "OK" means. A reviewer with no rule approves everything.
- The round limit (`max`) and what happens when it is reached (`on_exhausted`): use the last draft, ask a person, or stop.
- The budget: every round is two model calls.

<!-- starter:03-review-loop -->
```loom
// 03-review-loop: write, review, rewrite until the reviewer is satisfied. The loop is bounded: it can never run away.

substitution: braces

budget { tokens: 150000  calls: 20 }

agent Writer   { model: "gemini-2.5-flash"  system: "Write clear, short product descriptions."  temperature: 0.7 }
agent Reviewer { model: "gemini-2.5-flash"  system: "Review the description. Say OK only if it is clear and under 60 words."  temperature: 0.1 }

workflow Main(product) {
    delegate "Write a product description for: {product}" to Writer -> draft

    loop until (review.verdict == "OK") max 3 {
        delegate "Review this description:\n{draft}" to Reviewer -> review expecting { verdict: enum["OK", "REWRITE"], advice: string }
        alt (review.verdict == "REWRITE") {
            delegate "Rewrite it. The reviewer says: {review.advice}\n\n{draft}" to Writer -> draft
        }
    } on_exhausted {
        note "The reviewer was still not satisfied after {_loopRound} rounds; using the last draft."
    }

    return draft
}
```

**What to keep and what to watch:**

- `loop until ... max N` always has a limit, so a reviewer that is never satisfied costs N rounds, not an endless bill.
- `expecting { ... }` makes the reviewer's answer a typed value the script can test.

Stages that matter: 2 (a draft the reviewer should reject), 5 (cost near the model), 8 (the loop-limit case).

## 3. Ask several at once, carry on if one fails

**Use it when:** Independent look-ups that need not wait for each other: two sources, two specialists, a web search and the documents.

**Decide with the person:**

- What the report says when a branch failed. The skeleton records it in a flag and says so in the report.
- Timeouts and retries per branch.

<!-- starter:05-parallel-research -->
```loom
// 05-parallel-research: ask several specialists at the same time, and carry on even if one of them fails.
// Each branch has its own flag (web_ok, docs_ok): branches run together, so they must never share one variable.

substitution: braces

budget { tokens: 200000  calls: 20 }

agent WebResearcher  { model: "gemini-2.5-flash"  system: "Search the web and report what you find in five lines."  tools: [web_search] }
agent DocsResearcher { model: "gemini-2.5-flash"  system: "Report what the company documents say, in five lines." }
agent Writer         { model: "gemini-2.5-flash"  system: "Write a short report from the notes you are given. Say what is missing."  temperature: 0.4 }

workflow Main(topic) {
    set web_ok = "yes"
    set docs_ok = "yes"

    parallel {
        delegate "Find recent public information about {topic}" to WebResearcher -> web_notes retry 1 timeout 60s
            on_failure { set web_ok = "no"  set web_notes = "nothing found on the web" }
        delegate "Find what our documents say about {topic}" to DocsResearcher -> docs_notes retry 1 timeout 60s
            on_failure { set docs_ok = "no"  set docs_notes = "nothing found in the documents" }
    }

    alt (web_ok == "no") {
        note "The web research failed, so the report below rests on documents only."
    }
    alt (docs_ok == "no") {
        note "The document research failed, so the report below rests on the web only."
    }

    delegate "Topic: {topic}\nWeb notes: {web_notes}\nDocument notes: {docs_notes}" to Writer -> report
    return report
}
```

**What to keep and what to watch:**

- Branches run together, so each has its own variable and flag. Two branches must never write the same variable.
- A failed branch that goes unnoticed makes a confident, wrong report: the `note` and the report must say what is missing.

Stages that matter: 6 (`on_failure` on every branch), 8 (a test where one branch fails).

## 4. Plan, then one task per item

**Use it when:** The number and kind of tasks are not known until the work starts: a goal split into tasks, a list of files, a list of questions.

**Decide with the person:**

- Who owns each task, as an `enum`: a model cannot send work to an agent you did not list.
- The most tasks allowed (`max`): a longer plan fails before any work starts, which is what you want.
- Whether the planner needs to be a model, or the list already exists (then use `parallel for each` over it with no planner).

<!-- starter:06-plan-and-fan-out -->
```loom
// 06-plan-and-fan-out: one agent makes a plan (a list), and the script runs one step per item, all at once.
// The agent for each item comes from the plan itself, so one line routes work to whoever should do it. The plan is
// typed: `owner` can only be one of the two agents, so a model cannot send work anywhere else, and each answer is stored
// under its task's name in one variable, `results`, so a task name cannot overwrite anything.

substitution: braces

budget { tokens: 300000  calls: 40 }

agent Planner {
    model: "gemini-2.5-flash"
    system: "Split the goal into 2 to 4 small tasks. Give each task a short one-word name, a description and an owner."
    temperature: 0.2
    output_schema: { tasks: list<{ name: string, description: string, owner: enum["Researcher", "Writer"] }> }
}
agent Researcher { model: "gemini-2.5-flash"  system: "Do the research task and report briefly." }
agent Writer     { model: "gemini-2.5-flash"  system: "Do the writing task and report briefly." }
agent Editor     { model: "gemini-2.5-flash"  system: "Combine the pieces into one short, consistent document." }

workflow Main(goal) {
    delegate "Plan this goal: {goal}" to Planner -> plan

    // the planner is asked for 2 to 4 tasks; a longer list fails the run before any task starts (usually a problem upstream)
    parallel for each task in plan.tasks max 4 {
        delegate "{task.description}" to {task.owner} -> results[task.name]
            retry 1 timeout 90s
            on_failure { note "Task {task.name} failed: {_error}" }
    }

    delegate "Goal: {goal}\nHere is what the team produced:\n{results}" to Editor -> document
    return document
}
```

**What to keep and what to watch:**

- Store each answer under the task's name in one variable, so a task name cannot overwrite anything else.
- Cost grows with the plan: set the budget for the largest plan, not the average.

Stages that matter: 5 (cost of the largest plan), 6 (`on_failure` per task), 7 (audit and review).

## 5. A person approves once, at the edge, and the script sends

**Use it when:** Anything that sends, pays, publishes or deletes.

**Decide with the person:**

- The effect and what it can reach: mark the tool `effect: changes`.
- The text the person approves: it must be the exact text that will be sent.
- What happens on declined and on failure. Failure after approval is the dangerous one: say "may not have been sent", never "failed".

<!-- starter:07-approval -->
```loom
// 07-approval: the model drafts, a person approves once, at the edge, and the script sends. No model carries out the
// send, so no wording in a message can talk it past the approval; the person sees the exact text that will be posted.

substitution: braces

budget { tokens: 100000  calls: 20 }

agent Drafter { model: "gemini-2.5-flash"  system: "Draft a short, polite announcement from the facts you are given."  temperature: 0.4  guard { pii: mask } }
tool Notify { use: webhook  url: env.SLACK_WEBHOOK  format: slack  effect: changes }

workflow Main(facts) {
    delegate "Draft an announcement from: {facts}" to Drafter -> draft
    use Notify(text = draft) approve "Send this to the team channel?" -> receipt timeout 30s
        on_declined { return "Not sent. The draft is kept for editing:\n{draft}" }
        on_failure { halt "The announcement may not have been sent: {_error}. Check the channel before trying again." }
    note "Sent. {receipt}"
}
```

**What to keep and what to watch:**

- The model drafts; the script sends. No model carries out the send, so no wording can talk it past the approval.
- A retry or a resume must never send twice: effects are journaled, so run with `--journal`.

Stages that matter: 7 (audit and the lethal trifecta), 8 (the approved, declined and failed paths), 9 (journal).

## 6. Free text in, checked fields out

**Use it when:** Invoices, emails, forms, CVs: any text that must become fields.

**Decide with the person:**

- Which fields are required and which are `optional:`. A field the text does not state is `missing`; never let a model guess it.
- What the script does with a missing field: ask a person (as here), stop, or carry on with a flag.
- The rules the script applies afterwards (totals add up, a date is in the past): rules in code, not in the prompt.

<!-- starter:08-extract-and-validate -->
```loom
// 08-extract-and-validate: turn free text into checked fields. The extractor sees only the text it is handed and the
// fields it must fill (never the run so far), a field the text does not state is `missing` (or fails the step if it is
// required), and the script then applies its own rules to the fields.

substitution: braces

budget { tokens: 80000  calls: 15 }

extractor Invoice {
    model: "gemini-2.5-flash"
    instructions: "Read the invoice. Use the vendor's legal name."
    fields {
        vendor: string
        invoice_number: string
        total: number
        currency: enum["USD", "EUR", "INR", "OTHER"]
    }
    optional: [total]
}

workflow Main(text) {
    extract Invoice from text -> invoice retry 1 on_failure {
        halt "Could not read an invoice from the text: {_error}"
    }

    alt (invoice.total == missing) {
        human_prompt "No total was stated for invoice {invoice.invoice_number} from {invoice.vendor}. What is the total?" -> typed_total
        set invoice_total = "{typed_total}"
    } else {
        set invoice_total = "{invoice.total}"
    }

    return { vendor: "{invoice.vendor}", number: "{invoice.invoice_number}", total: "{invoice_total}", currency: "{invoice.currency}" }
}
```

**What to keep and what to watch:**

- The extractor sees only the text it is handed and the fields it fills, never the run so far.
- Test with the awkward documents: missing field, two totals, text that gives instructions.

Stages that matter: 2 (the awkward documents), 3 (the extractor's instructions), 8 (a case with a missing field).

## 7. Answers from your own documents

**Use it when:** A handbook, a policy set, a product manual, a knowledge base.

**Decide with the person:**

- Which documents, and who keeps them current.
- What the agent says when the passages do not answer: the skeleton makes it say so, which is the behaviour to test.
- The embedding model and a key for it: `weave check` can read the script but a hermetic test cannot index the documents.

<!-- starter:09-handbook-qa -->
```loom
// 09-handbook-qa: answer questions from your own documents. The relevant passages are found first (by meaning) and put
// in front of the question, and the agent is told to say so when the handbook does not answer.

substitution: braces

budget { tokens: 80000  calls: 10 }

knowledge Handbook {
    source: "handbook/"
    embedding: "gemini/text-embedding-004"
    chunk_size: 600
    top_k: 3
    store: "index/handbook.json"
}

agent Answerer {
    model: "gemini-2.5-flash"
    system: "Answer only from the handbook passages you are given. If they do not answer the question, say: The handbook does not say."
    temperature: 0.1
    knowledge: [Handbook]
}

workflow Main(question) {
    delegate "{question}" to Answerer -> answer
    return answer
}
```

**What to keep and what to watch:**

- Retrieved text is untrusted input: a document can contain instructions. Run `weave audit` and keep the agent's tools minimal.
- A question the documents do not answer is the most valuable case in the golden dataset.

Stages that matter: 2 (unanswerable and injected-document cases), 7 (audit), 9 (a re-index plan when documents change).

## 8. Wait for a person, or for time

**Use it when:** An approval or reply that can take days; a follow-up after two days; any step that must not hold a thread.

**Decide with the person:**

- The longest wait and what happens when it is exceeded (`rate_limits`, a timeout on the question).
- Where the journal lives: a waiting run is only a run if it is journaled.

<!-- starter:12-wait-for-a-person -->
```loom
// 12-wait-for-a-person: a workflow that can wait for days. With a run journal (weave run --journal ...), a question to a person or a
// `sleep` pauses the run and holds no thread; it carries on when the answer arrives or the time has passed. Write the script as if
// the time simply passes.

substitution: braces

budget { tokens: 80000  calls: 15 }

rate_limits { on_limit: suspend  max_wait: 24h  max_resumes: 50 }

agent Drafter  { model: "gemini-2.5-flash"  system: "Write a short, polite message to the customer."  temperature: 0.4 }
agent Checker  { model: "gemini-2.5-flash"  system: "State whether the customer replied. Answer yes or no."  output_schema: { replied: enum["yes", "no"] } }

workflow Main(customer, topic) {
    delegate "Write to {customer} about: {topic}" to Drafter -> message
    human_prompt "Draft for {customer}:\n{message}\nSend it? (yes/no)" -> go
    alt (go == "yes") {
        note "Sent to {customer}."
        sleep 2d
        delegate "Did {customer} reply about {topic}?" to Checker -> status
        alt (status.replied == "no") {
            note "No reply from {customer} after two days: follow up."
        } else {
            note "{customer} replied."
        }
    } else {
        note "Not sent."
    }
}
```

**What to keep and what to watch:**

- Write the script as if time simply passes: a question or a `sleep` pauses the run and it resumes when answered.
- Answering and resuming are `weave answer`, `weave tick` or `weave daemon`; the deployer skill sets them up.

Stages that matter: 9 (run with `--journal`), then the llm4j-loom-deployer skill.

## 9. A schedule: runs itself on a clock

**Use it when:** A morning digest, a nightly check, a weekly report.

**Decide with the person:**

- The cron expression and the timezone, written by the person, not guessed.
- `misfire` (a missed run happens once, not once per missed day) and `overlap` (skip, or queue).
- What happens when the run fails: a digest that did not arrive must say so somewhere a person will see.

<!-- starter:11-daily-digest -->
```loom
// 11-daily-digest: a workflow that runs itself on a clock. The `schedule` block is kept in a trigger store, so it survives restarts
// (weave schedule sync, then weave daemon or weave tick). A missed run happens once, not once per missed day.

substitution: braces

budget { tokens: 120000  calls: 12 }

tool Slack { use: webhook  url: env.SLACK_WEBHOOK  format: slack }

agent Reader {
    model: "gemini-2.5-flash"
    system: "Find today's most useful news for the topic and report five items with one line each."
    tools: [web_search]
}
agent Poster {
    model: "gemini-2.5-flash"
    system: "Post the digest you are given to Slack with a short title. Do nothing else."
    tools: [Slack]
}

workflow Digest(topic) {
    delegate "Today's news about {topic}" to Reader -> items retry 1 timeout 90s
        on_failure { halt "No digest today: the reader failed ({_error})." }
    delegate "Post this digest:\n{items}" to Poster -> receipt retry 1 timeout 30s
        on_failure { halt "The digest was written but may not have been posted: {_error}." }
    return receipt
}

schedule Morning {
    cron: "0 7 * * 1-5"
    timezone: "Asia/Kolkata"
    run: Digest(topic="renewable energy")
    misfire: run_once
    overlap: skip
}
```

**What to keep and what to watch:**

- A schedule block is kept in a trigger store: `weave schedule sync`, then `weave daemon` or `weave tick`.
- Posting is an effect: mark the tool and think about what a double post costs.

Stages that matter: 6 (`on_failure` that halts loudly), 9 (the store, the daemon), then the llm4j-loom-deployer skill.

## 10. A long job, one small step at a time

**Use it when:** Work that would fill a model's context: refactoring many files, a long research task, a migration.

**Decide with the person:**

- The shape of "progress": a typed value the script owns (done, next, finished), not something an agent remembers.
- The round limit and what is reported when it is reached.
- What the worker may touch (`root:` and `mode:` on the file tool).

<!-- starter:16-step-by-step-agent -->
```loom
// 16-step-by-step-agent: a long job done one small step at a time. What is done and what is next is a typed value the script owns
// (`progress`), not something an agent has to remember in a long conversation that may be compacted. The worker is fresh every
// round and sees no transcript; an extractor reads its report and writes the new progress; the loop is bounded.

substitution: braces

budget { tokens: 150000  calls: 40 }

tool Files { use: file  root: "work"  mode: write }

agent Worker {
    model: "gemini-2.5-flash"
    system: "You do ONE step of a larger job. You are told the goal, what is already done and the single step to do now. Do that step with your tools, then report in plain prose what you did and anything the next step needs to know."
    tools: [Files]
    memory { transcript: none }
    max_iterations: 8
}

extractor Progress {
    model: "gemini-2.5-flash"
    instructions: "Read the goal, the previous progress and the worker's report. List every step now done, say the one step to do next, and say whether the whole goal is finished."
    fields {
        done: list<string>
        next: string
        finished: boolean
    }
}

workflow Main(goal) {
    set progress = { done: "nothing yet", next: "plan the first step", finished: "false" }

    loop until (progress.finished == "true") max 6 {
        delegate "GOAL: {goal}\nDONE SO FAR: {progress.done}\nDO THIS ONE STEP NOW: {progress.next}" to Worker -> report retry 1 timeout 120s on_failure {
            halt "The worker failed: {_error}"
        }
        extract Progress from (goal = goal, previous = progress, report = report) -> progress retry 1 on_failure {
            halt "Could not record the progress: {_error}"
        }
    } on_exhausted {
        note "Stopped after {_loopRound} steps without finishing. Done so far: {progress.done}"
    }

    return progress.done
}
```

**What to keep and what to watch:**

- The worker starts fresh every round and sees no transcript (`memory { transcript: none }`), so compaction cannot lose the plan.
- An extractor, not the worker, writes the new progress.

Stages that matter: 5 (cost per round, the round limit), 14 (context and compaction), 15 (stop and supervise).

## 11. Check a reply against house rules before it leaves

**Use it when:** Replies that must obey rules owned by someone else: legal, compliance, brand, safety.

**Decide with the person:**

- The rules, in a file written for the person who owns them (not in a prompt).
- The evaluator: a separate model that sees only the rules and the reply.
- What happens on a violation: rewrite and check again (as here), or ask a person.

<!-- starter:17-compliance-boundary -->
```loom
// 17-compliance-boundary: a support reply is checked against house rules before it reaches anyone. The rules live in
// policies/house-rules.md, written for whoever owns compliance, and never in a prompt. A separate evaluator, which
// sees only the rules and the reply, judges it at the edge; if the reply breaks a rule an editor rewrites it, and the
// rewrite is checked again.

substitution: braces

budget { tokens: 120000  calls: 20 }

evaluator Compliance { model: "gemini-2.5-pro" }
policy HouseRules: "house-rules" checked by Compliance

agent Support { model: "gemini-2.5-flash"  system: "You answer customers politely, in plain English and short sentences." }
agent Editor  { model: "gemini-2.5-flash"  system: "You rewrite a reply so it keeps the help and drops whatever the broken rules do not allow." }

workflow Main(question) {
    delegate "{question}" to Support -> reply
    check reply against HouseRules using Compliance -> verdict on_violation { rewrite with Editor }
    return reply
}
```

**What to keep and what to watch:**

- The check sits at the edge, after the reply is made and before it reaches anyone.
- A rewrite is checked again; the loop is bounded.

Stages that matter: 2 (a reply that breaks each rule), 7 (audit), 8 (a violation case).

## 12. Start several workflows at once and supervise them

**Use it when:** One job splits into pieces that can run at the same time, and you want to watch them, step in when one goes wrong, and carry on with the rest: parallel research with a spending cap on each researcher, a coding agent that must not touch certain files, a batch where one slow item must not hold the others.

**Decide with the person:**

- What each child may spend (`budget` on every `start`) and the most children at once (`max_children`).
- Which rules are counts (tokens, calls, idle time) and which need a judge. Start with counts; a judge is a model's opinion, so give it a small budget and a cadence of a few minutes.
- What a rule does: steer (text written in the script), wrap up, or cancel and keep what the child had.
- What the parent does with a child that failed or was cancelled: a child's outcome is a value, so decide whether the report goes ahead without it.

<!-- starter:18-supervise-workflows -->
```loom
// 18-supervise-workflows: start a workflow without waiting for it, then watch it. A planner names the topics; each topic
// becomes a researcher that runs as a child of its own, all at once. While they run, rules count what each one spends and
// how long it sits idle, and a judge asks whether it stays on topic. A child that spends too much is cancelled and keeps
// what it had; one that drifts is steered. Every child has its own budget, and a restart finds the same children again.

substitution: braces
max_children: 3

budget { tokens: 400000  calls: 80 }

evaluator TopicJudge { model: "gemini-2.5-flash" }

agent Planner {
    model: "gemini-2.5-flash"
    system: "Split the question into 2 or 3 topics. Give each a short one-word name and the topic to research."
    temperature: 0.2
    output_schema: { topics: list<{ name: string, topic: string }> }
}
agent Researcher {
    model: "gemini-2.5-flash"
    system: "Research the topic and answer in a short paragraph."
    steerable: true
    streaming: true
}
agent Editor { model: "gemini-2.5-flash"  system: "Combine the findings into one short report. Say which topics are missing." }

workflow Research(topic) {
    delegate "Research: {topic}" to Researcher -> notes
    checkpoint collected
    return notes
}

workflow Main(question) {
    delegate "Question: {question}" to Planner -> plan

    for each t in plan.topics max 3 {
        start Research(topic = t.topic) timeout 20m budget { tokens: 100000 } -> work[t.name]
        supervise work[t.name] budget { tokens: 20000 } {
            when tokens > 90000 { cancel use partial }
            when tokens > 60000 { steer "You are using a lot. Narrow the topic and answer with what you have." once }
            when idle > 3m      { steer "You seem stuck. Narrow the topic." once }
            assert "searches only for the topic" using TopicJudge every 2m max 4 { steer "Go back to the topic." once }
        }
    }

    wait all work timeout 25m -> results
    delegate "Question: {question}\nFindings by topic:\n{results}" to Editor -> report
    return report
}
```

**What to keep and what to watch:**

- `start` needs somewhere to keep each child's run: use `--journal` with `weave run`, or a `LoomHost` with persistence. Kill the parent and run it again and it finds the same children.
- A handle is never written into a prompt and never given to a task; read `work.status` or `work is ok` instead.
- `never calls` refuses the call before it runs; a steer needs an agent marked `steerable`. `weave audit` lists who may steer a child (LA28) and each judged rule (LA29).

Stages that matter: 5 (cost per child, the reservation), 7 (audit), 8 (trajectory tests with `children:` and `fired:`), 15 (stop and supervise).

## Shapes that are not a pattern of their own

- **Remembers each person:** an agent with a `memory` block and a `persona`; ask what may be remembered, for how long, and who may erase it.
- **Rewind and retry:** a `checkpoint` and a `rewind` that carries feedback back to an earlier step; use it where a late step can show an early one was wrong.
- **Rules that watch an agent:** a `supervise` block (guide chapter 15) when a person or a rule may need to step in while it runs; for watching a whole started workflow, pattern 12.
- **An agent that earns the right to decide:** the earned-autonomy ladder (guide chapter 13), only when an agent will take over a decision a person makes today.
- **A person asked mid-run:** `human_prompt` waits for a typed answer; the run is durable when it has a journal.
