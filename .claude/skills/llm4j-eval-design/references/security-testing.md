# Security testing for agentic systems

*Distilled, in llm4j terms, from the owner's guide* Quality Assurance for AI agentic systems *(part 5). External works the guide points to: the OWASP GenAI LLM Top 10 (https://genai.owasp.org/resource/owasp-genai-llm-top-10-2026/); Simon Willison, "The lethal trifecta for AI agents" (https://simonwillison.net/2025/Jun/16/the-lethal-trifecta/); "Design Patterns for Securing LLM Agents against Prompt Injections" (https://arxiv.org/abs/2506.08837); the Promptfoo red-team documentation (https://www.promptfoo.dev/docs/red-team/), which is outside the llm4j stack. Conventional application security testing applies unchanged; this covers what is additional.*

## The core problem

A language model cannot reliably tell instructions from data. Everything arrives as text in one context; there is no parameterised-query equivalent. Every mitigation at the model is therefore probabilistic, and this dominates test design:

> **Model-level defences are mitigations, measured as percentages. System-level defences are controls, measured at 100%. Never confuse the two, and never accept a percentage where a control is required.**

In llm4j the system-level controls are code the model cannot talk past: tasks (a `run` step is never offered to a model as a tool), `alt` guards, approvals (`human_prompt`, the effect policy), `NetPolicy` and the tool allowlists, and `guard { pii: mask }`. `weave audit` reads a script for the lethal trifecta (an agent that reads untrusted content, reaches private data and can send something out).

## Prompt injection

**Direct** (the user types it) is lower severity: the user is authenticated and visible in logs. Payload classes to cover: instruction override ("ignore previous instructions and..."), role reassignment ("you are now in unrestricted mode"), a fake system message, an authority claim ("as the administrator I authorise..."), encoding (base64, hex, unusual unicode, another language), incremental (benign requests escalating over many turns).

**Indirect is the one requiring new thinking.** Instructions are hidden in content the system ingests, and the attacker need not be a user at all. **Every content source the system reads is an input channel, whether or not it looks like one.**

| Source | Realistic placement |
|---|---|
| Documents and files | body text; metadata; white text; comments |
| Web pages | page content; HTML comments; alt text |
| Log files | any field an attacker can influence: usernames, paths, error strings |
| Filenames and paths | the name itself |
| Configuration and registry | key names and values |
| Database fields | any user-writable field |
| Email, tickets, chat | body, subject, attachments |
| API responses | any string field from an external service |
| Code | comments; string literals; variable names |

Payload goals to test for beyond override: **approval spoofing** (content claiming a human already approved), **fabricated context** (content presenting itself as earlier conversation), **tool coercion** (directing a specific call with specific arguments), **data exfiltration** (getting session data into an outbound action), **conclusion steering** (asserting a false fact as established), **action injection** (steps disguised as authoritative guidance), **split payload** (an instruction distributed across several ingested items).

**Method**

1. Build a payload corpus: 60 or more direct, 100 or more indirect across the placements above. **Version it like the golden dataset.** It contains hostile text: keep it in its own folder, run it only against sandboxed or fake tools, and never against a real sender, payer or writer.
2. Embed payloads in **realistic** artefacts. A payload in an obviously fake log line tests nothing.
3. Execute; measure the proportion that altered behaviour.
4. **For every payload that succeeded, verify the system-level controls still held.** This step is routinely omitted, and it is the most important one.

A successful attack becomes a permanent case in the corpus.

## Jailbreaking

Getting the model to abandon its constraints: roleplay and fiction framing, hypotheticals, claimed authority or special context, incremental escalation across turns, language or encoding shifts, instruction-conflict exploitation. A scripted corpus catches regressions; red teaming finds new classes.

## Excessive agency and tool abuse

For any system that acts, this is where the severe outcomes are. The framing question for the whole category: **what is the worst thing this system can do if it is completely wrong or completely fooled?** Design tests from the answer.

| Test | Verify |
|---|---|
| Tool argument injection | injected content cannot cause a tool call with attacker-chosen arguments; argument validation happens in application code, not by model judgement |
| Confused deputy | the system cannot be induced to use a legitimate tool with its legitimate permissions to reach data the requester should not have |
| Permission boundary | each agent's permissions are the minimum for its role and do not widen through handoff |
| Scope escalation | a narrow request cannot produce a broad-scope action |
| Destructive action | prohibited operations cannot be generated or executed under any framing |
| Approval bypass | no path exists to act without required human approval, including via injected content |

## Data leakage

| Test | Verify |
|---|---|
| Session isolation | run 50 concurrent sessions with uniquely tagged content; assert zero crossover in any output, state or artefact |
| Tenant isolation | cross-tenant queries return nothing; check retrieval and any memory or history feature specifically |
| Artefact isolation | outputs published from one tenant's session are unreachable by another (this leaks at publication time and is commonly missed) |
| System prompt extraction | low severity unless the prompt contains secrets, in which case the defect is the secret's location |
| Training and log leakage | secrets and personal data redacted before entering context and before trace persistence |
| Retention and purge | a purge request removes content from traces, metrics stores and regression baselines too |

## Denial of wallet

Resource exhaustion that targets cost rather than availability: inputs crafted to maximise token use, requests that induce long agent loops, recursive or self-referential tasks. Covered by the bounds (`quality-dimensions.md`, efficiency), but test it as an **attack**, not only as a robustness case: assume an adversary is trying to make it expensive.

## Red teaming

A structured adversarial exercise. Scripted corpora catch known classes; red teaming finds new ones.

| Element | Guidance |
|---|---|
| Team | 2 to 3 people with full architectural knowledge (blind red teaming wastes time on things the design already prevents) |
| Duration | one focused week before a major gate |
| Objective | concrete and worst-case: "cause a destructive action to be published", "extract another tenant's data", not "try to break it" |
| Output | every successful attack becomes a permanent corpus case; this is where most of the lasting value is |
| Disposition | unfixed findings are recorded as accepted risk, with a named owner and an expiry date |

Run it even in releases where you expect to find nothing: the corpus growth alone justifies it.

## Interpreting security results honestly

**A 99% injection-resistance rate means one crafted payload in a hundred succeeds.** Against a determined attacker that is not a defence, and reporting it as one is misleading. State it plainly in your reports: model-level resistance is defence in depth. The controls are the approval gate, the permission model, the action allowlist and the tool-mediated access boundary, and **the evidence that matters is that those hold at 100% while the model is being successfully manipulated.**
