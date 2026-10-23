# Debugging a workflow

A Loom workflow is a list of choices (`alt`, loops, classifiers) around calls to models. When a run went somewhere you did not expect, the question is always the same: *which choice, over which values, and what would have had to be different?* Four commands answer it from what the run left behind, without running a model, a tool or a person. Nothing is sent, written or spent unless you ask for live models under a cap.

The example is the triage workflow of the [Loom guide](../src/loom/ai-agent4j-loom/LOOM_GUIDE.md): a classifier decides whether a customer is urgent, then an `alt` picks a reply.

## 1. Why did it do that? `weave why`

```bash
weave run triage.loom -w Main -i message="where is my parcel?" --journal runs/r1   # or step through it with --journal
weave why runs/r1
```

```
Run of Main · completed

 1  line 8  intentclassify Intent  → answered
      kind = order_status (0.99)   urgent = no   confidence = 0.99
 2  line 9  alt (intent.urgent == "yes")  → false  → else
      intent.urgent = "no"  (bound at step 1 by intentclassify Intent)
      not taken: then: note
 3  line 13  alt (intent.kind == "order_status")  → TRUE  → then
      intent.kind = "order_status"  (bound at step 1 by intentclassify Intent)
      not taken: else: return
 4  line 14  delegate Replier  → replied (13 chars)
 5  line 15  return
```

Ask about one statement: `weave why runs/r1 --line 10` says *note did not run: alt (intent.urgent == "yes") was false (intent.urgent was "no"), so the then branch was not taken. It would have run if intent.urgent == "yes"*. A run that failed shows the failing step and why; one that is waiting shows the question; a run with rewinds explains each attempt. `--format md` pastes into an issue, `--format json` is for tools.

If you have edited the script since the run, `why` refuses (the choices would not be the run's). Give the version the run used with `--script old.loom`.

## 2. What if? `weave whatif`

```bash
weave whatif runs/r1 --at 8 --answer Intent='{"kind":"refund","urgent":"yes"}'
```

The run is copied and cut back to the classifier; the classifier now says *urgent*; the script runs forward over the copy. You see where the two runs part (`alt … went else in the original and then in the candidate`), the new result, and the effects the candidate would cause that the original did not (marked **unsafe** when new). Other changes: `--set var=value`, `--human text`, `--fail Replier=rate_limit:2`, `--tool Name=text`.

A model's answer is **never guessed**. It is reused only where the question put to it is unchanged; where your change flows into a prompt, the candidate stops and says *which answer it needs and why*. To try a different prompt or model: `--prompt Replier=@v2` (another version of the prompt file) or `=file.md`, `--model Replier=other-model`, or `--script edited.loom`. To let real models answer what has no recording: `--models live --max-tokens 2000 --yes` (or `--max-cost 0.05 --prices prices.txt`); the run ends at the cap. `--save DIR` keeps the candidate as a run you can open with `weave why`. The original run is never written.

## 3. Step through it: `weave step`

```bash
weave step triage.loom -i message="where is my parcel?" --break 13
```

```
▶ line 8  classify Intent   (statement 1)
    then:   line 9  intent.urgent==yes?
    message = where is my parcel?   (input)
> next
▶ line 9  intent.urgent==yes?   (statement 2)
    intent = {kind=order_status, urgent=no, …}   (line 8: intentclassify Intent)
> back
```

`next` runs the statement whole, `into` stops at the very next one (inside a branch or loop), `out` leaves the block, `continue` runs to a breakpoint or the end, and **`back`** goes one statement back: the run is replayed from its journal, so the state you see is exactly the one that was. `set a = value` changes a variable (and survives going back), `model Replier "text"` and `classifier Intent {…}` script the next answer, `answer text` answers a question the run waits on, `why` explains the last choice, `where` and `cost` say where you are and what was spent. Breakpoints: a line number (or the next statement after it), `n3` (a graph node), an agent or classifier name, or `when <condition>`.

Models are **mock** (fixed, well-formed replies) and classifiers answer their first label unless you script them, so it works with no keys. Tools that change things are only described. `--journal DIR` keeps the steps so `weave why` and `weave resume` can use them; `--record FILE` writes the session and `--replay FILE` runs it again.

## 4. The run on the graph

```bash
weave graph triage.loom --run runs/r1 --run runs/r2 --run runs/r3 --format mermaid
```

Taken edges are bold, untaken ones dotted, and a statement that ran several times shows `×3`. The JSON form adds an `overlay` with the counts per statement and edge, and, over several runs, the statements **none** reached: your production coverage. (The journal holds no times, token counts or costs, so none are shown.)

## In VS Code

Press **F5** on a `.loom` file, or click **Debug** above a workflow. The editor starts `weave dap` and you get breakpoints in the gutter (also conditional ones, and function breakpoints on an agent name), the variables with the statement that set each, step over / in / out, **step back**, and *set value* and *evaluate* on hover. Live models ask for your agreement first, with the most they may spend. See [Loom in VS Code](Loom-in-VS-Code.md).

## What this does not do

Real effects while debugging, paging a long value, the agent's attempts and latency (the journal does not hold them), and the branches of a `parallel` block as separate threads.
