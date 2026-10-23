# Open items: what is not done yet

One place for everything deliberately left out so far, to revisit later. Update it in the same commit that adds or closes an item. Detail lives in the files named in each row.

| # | Item | Why it is open | Where it is described | Planned |
|---|---|---|---|---|
| O1 | Options for LC01 (which cheaper model), LC02 (memory bound), LG04 (which version to pin), LR08 (wait, fail or suspend), LP rules (prompt text) | Each is a data change plus examples; not yet written | FIX_CAUTIONS.md §0, §6; RULES_ROADMAP.md | after 9g |
| O2 | An option that edits a second file (prompt file, `review.properties`); `createFile` is parsed but unused | The fix engine edits one script | FIX_CAUTIONS.md §6 | when a rule needs it |
| O3 | Interactive `--assist` does not act on `references: true` (LM01 delete): it should search the project for the agent's name and show hits before asking | Not built; a skill must search by hand | FIX_CAUTIONS.md §3, §6 | with O1 |
| O4 | Anchor that finds a setting inside a multi-line block (LY02 on a multi-line agent has no fix/option) | Not built | FIX_CAUTIONS.md §1, §6 | with O1 |
| O5 | "One statement per line" formatter pass, to unlock fixes on crowded lines | Not built | FIX_CAUTIONS.md §6 | undecided |
| O6 | Deferred rules LR06, LO02, LO03, LD03, LY03 | Need new inputs (enum values, price/window files, branch ends, rewind span, provider-region table) | RULES_ROADMAP.md "Moved to 9g" | 9g where inputs fit |
| O7 | A4 (estimate within 2x on live runs) not met live; only on recorded corpus | Few live runs; shorter real answers than assumed | VERIFICATION.md A4, §7 | recheck after 9g |
| O8 | LP05 is noisy (16 findings on 14 example scripts); kept heuristic INFO | Wording-based | VERIFICATION.md §7 9f | revisit with real usage |
| O9 | Corpus labelled by the rule authors; LY01 name patterns and LP02/LP05 recall unmeasured on real projects | No independent labels | VERIFICATION.md §7 | run on real scripts |
| O10 | Fabric attributes (R55), skills text (R56), rule docs generated from packs (R69), `weave rules diff` / semver policy (R74) | Planned sub-phases 9h and 9i | DESIGN.md §12 | 9h, 9i |
| O11 | Editor-side review (10c) | Phase 10; needs your manual VS Code check | phase plan | phase 10 |
| O12 | Mockup artifact still shows LA05 where the id is LA01 | Cosmetic | — | any time |
