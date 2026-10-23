# Open items that need your decision

Only items that wait on a decision from you are listed here. Everything else that is not done yet is planned in a later phase and tracked in the phase's own VERIFICATION.md and in RULES_ROADMAP.md. We revisit this file once all phases are done. Update it in the same commit that adds or closes an item.

| # | Decision needed | Context | Where it is described |
|---|---|---|---|
| D1 | For each rule that has no options yet, which resolutions should the developer be offered? LC01 (which cheaper model, or none), LC02 (a memory bound or a `context` block, and what size), LG04 (pin to which version), LR08 (`wait`, `fail` or `suspend`), the prompt rules (what text to add) | Options are a small data change per rule, but the sensible choices are product judgement, not mechanics | FIX_CAUTIONS.md §0, §6 |
| D2 | Should review get a "one statement per line" formatter pass, so scripts written on crowded lines can be fixed? | Today a one-line workflow gets findings but no fix, because guessing where a statement ends could corrupt the file | FIX_CAUTIONS.md §1, §6 |
| D3 | Keep LP05 ("prompt does not say what to do when unsure") as an off-by-default info rule, or drop it? | It fired 16 times on 14 repo example scripts, because it judges wording | VERIFICATION.md §7 9f |
| D4 | Who labels an independent set of scripts to measure rule precision? | The rules and the test corpus were both written by me, so the published precision is optimistic. Real scripts from your projects, labelled by you, would be the honest test | VERIFICATION.md §7 |
| D5 | Should an option be allowed to edit a second file (a prompt file, `review.properties`)? | It would let fixes like "create the missing prompt file" exist, at the cost of a fix touching more than the script | FIX_CAUTIONS.md §6 |
| D6 | Should Loom get a fallback model for an agent (used when the provider is down)? | LG03 ("a critical-path agent has a single provider and no fallback") cannot exist until it does | VERIFICATION.md 9h |
| D7 | Should `human_prompt` get an `options:` list (a fixed set of answers)? | LH04 flags free-text answers that are compared exactly, but cannot yet recommend the constrained form | VERIFICATION.md 9h |
