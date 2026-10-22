# A workflow with no agent

A classifier decides, tasks (plain Java) act. No step writes text, so there is no agent, no prompt and no chat model: the only model call is one Jev
classification per message (about 420 tokens, a fraction of a cent).

```
export JEV_API_KEY=...
./run.sh "Where is my parcel? It was due Monday"               # order_status -> LookupOrder
./run.sh "I want my money back for the headphones" A-1 40       # refund -> CheckRefund approved
./run.sh "I want a refund for my laptop" A-77 450               # refund -> CheckRefund needs_review -> OpenTicket
./run.sh "Fix this today or I am leaving you" A-9 10            # urgent -> OpenTicket (retention)
```

`run.sh` compiles the three tasks in `tasks/` (registered through `META-INF/services`) and runs `router.loom` with `weave`. `weave check router.loom` needs the
tasks on the class path too, as `run.sh` does. A doubtful classification (`unsure`) goes to a `triage` ticket.

What the classifier decides is only a label; the refund limit is in code (`CheckRefund`), so a wrong or manipulated label cannot move money, and the amount
comes from the inputs, never from the classifier. Run on 2026-10-21 with the real Jev API: five messages, every branch taken except `unsure`, confidence 0.87 to 1.0.
