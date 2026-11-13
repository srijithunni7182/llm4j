# Phase 35: the framework: self-describing tools, restore on rewind and a wider tool set

Status: **draft; nothing is decided and nothing is built.** Every choice is in [DECISIONS.md](DECISIONS.md), open, with a recommendation. The facts about the code are in [DESIGN.md](DESIGN.md).
The principles of phases [33](../33-async-workflows/PRINCIPLES.md) and [34](../34-supervision-signals/PRINCIPLES.md) all still hold (in particular: the model reasons and the code holds the authority; fail closed; every effect is journaled; a construct is finished only when it can be tested without a model, a real clock or racing threads). This file adds what is new.

## What this phase is for

Loom can already say what an agent may do, bound it, audit it, rewind it and supervise it. What it cannot yet do is give an agent **the means to do real work and still be safe**: run a program, edit files it can get back, look at a web page, check a result. Those means are tools, and today adding a tool kind costs more than writing the tool, because the runtime keeps its own lists of kinds in about eight places. This phase does two things:

1. **The framework (35a, 35c).** A tool kind describes itself to the audit, so a new kind needs no change in Loom; and a rewind can **restore** what a tool can restore, instead of only choosing whether to repeat or keep an effect.
2. **The tools (35b, 35d, 35e).** A set of general-purpose kinds built on that: a sandbox, a workspace, pure compute, checks, a scan, and, as optional modules, a browser, search, documents and git. Each is justified by more than one kind of workflow, not by one product.

A third, small part (35f) gives eval4j what these tools need to be evaluated: steps in a scenario, an evidence record, an oracle that runs a check.

## The new principles

23. **We own the contract; the consumer owns the isolation.** llm4j defines what a tool may be asked, what it may answer, the limits it declares and what it reports. How a process is actually contained (a container, a microVM, a remote service) is the consumer's choice, behind an interface. llm4j ships honest reference backends and a fake, never a promise it cannot keep.
24. **A tool describes itself.** What a kind reaches, whether it changes anything, whether it can be undone and how isolated it is are answered by the kind, from its options. The runtime keeps no list of kind names. A kind that does not answer is treated as reaching everything, as today.
25. **The weight stays out.** The core and Loom gain interfaces and one refactor, nothing else. A tool with no dependency beyond the JDK and Jackson goes in `ai-agent4j-tools`; anything heavier is an optional module the user adds. A pinned test fails if the tools module gains a dependency. (Principle 8 of the repository, made a test.)
26. **Report what was enforced, not what was asked.** A backend says which isolation it actually applied (`none`, `process`, `container`, `vm`). It never falls back to a weaker level on its own: a request for `container` with no container runtime fails closed. The audit reads the reported level.
27. **Undo only what can be undone.** `restore` returns files in a workspace to a checkpoint. It never claims to unsend a message or unmake a payment: an effect that cannot be restored still follows `ask first`, `keep` or `repeat`. The script says which tools are restorable, and the audit shows it.
28. **A verdict is evidence about an artifact, parsed by the host.** A test count, an exit code or a schema result is typed data parsed by code. Any text the run produced (a test name, a message, a log line) stays `unchecked`. Evidence says the artifact passed a check; it does not say the requirement was met, and the check itself must be out of the agent's reach (protected paths).
29. **Every tool ships its fake.** A kind is finished only when a workflow that uses it can be tested with a scripted fake (no container, no browser, no network), and a contract test runs the same cases against the fake and, tagged `integration`, against the real backend.
30. **General first.** A tool is justified by at least two unrelated workflows. A coding agent, a report writer, a data analyst and a researcher all edit files, run programs, read pages and check results; the tool's design follows what they share, not what one product needs.
31. **Whatever a tool starts, a run owns.** A process, a served port, a browser: each belongs to one run, is recorded in its journal, has a deadline, and is stopped when the run ends, is cancelled, or is recovered after a crash. A tool never leaves a process behind.
32. **Bounded, and truncation is said.** Every operation has declared limits (time, output, files, bytes, processes, memory). A result that was cut says so in a field the script can read.
