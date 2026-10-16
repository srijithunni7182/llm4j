# G10: safety

commit 116786ea737a350f287b405d9314e5d321e9ce5a

checks in verification.md: 142; with at least one passing test: 140
tagged test methods: 171; executed: 170

Tests for the safety checks (executed and passed in the G1 run):

          1 @Tag("SB-V2.10")
          1 @Tag("SB-V8.9")
          1 @Tag("SB-V9.5")

Secrets sweep: the canary strings used in tests appear in no file under any test's temp store (asserted by V2.10, V8.9, V10.7).

A /security-review of the diff is recorded in G10-security-review.md.
