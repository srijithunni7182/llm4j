# Phase 29 test strategy: architecture you can review

How each requirement of [DESIGN.md](DESIGN.md) is tested. The phase is documentation and probes, so the tests are about **drift** and **evidence**: the page must match the code, and every claim about another framework must match a probe someone can rerun.

## 1. Principle: generate what you show

Output on the page is never pasted. The documentation test runs each example and produces the `weave` output the page shows, then compares it with the page. When a rule's wording changes, the test fails and the page is regenerated in the same change, which is what [CLAUDE.md principle 7](../../../CLAUDE.md) asks for.

## 2. Devices

| Device | Used for |
|---|---|
| **`ReviewableArchitectureDocsTest`**: loads every `loom` block on the page, runs `weave check` / `weave review` on it, and compares the output with the block that follows it on the page | R1, R2, R6 |
| **Rule catalogue lookup**: every `L[A-Z]\d\d` id on the page exists in `RuleCatalog` | R6 |
| **Link and probe resolver**: every probe link resolves to a heading in `probes/README.md`; every framework name appears only in a sentence with a probe link | R3, R6 |
| **The probes themselves**: each probe's `run.sh` per side, with fake models and tools, as `probes/*/` already do | R3 |
| **`ProbesReadmeVersionsTest`** (new): the versions at the top of the probes README match each probe's pinned `requirements.txt` | R3 |

## 3. Per requirement

| Requirement | Tests |
|---|---|
| **R1, R2** | `ReviewableArchitectureDocsTest` (blocks load; the output beside each block is the output the tool prints); a manual read (VERIFICATION M1, M2) |
| **R3** | Each probe runs on each framework and its result table is in the probes README; `ProbesReadmeVersionsTest`; the probe scripts run in CI's optional probe job where it exists, and by hand before release otherwise |
| **R4** | `WhyLlm4jLinksProbesTest` (every new row links a probe section) |
| **R5** | Link check over the stack-level pages; `verify-skill-package.sh` for the workflow guide |
| **R6** | `ReviewableArchitectureDocsTest` (rule ids, probe links, framework sentences) |

## 4. Deliberately not tested

- The persuasiveness of the page: people read it (VERIFICATION M1, M2).
- Other frameworks' behaviour beyond the probes' scenarios.
