# H4: sabotage runs

Each row breaks one safeguard in the real source, runs the ladder tests, and restores the source.
**DETECTED** means at least one test failed; a safeguard whose removal fails nothing is untested.

| # | Sabotage | Result | Detail | Failing tests |
|---|---|---|---|---|
| S15 | Count a superseded generation of a case | **DETECTED** | 1 test(s) failed | LedgerFoldTest.aSupersededGenerationOfACaseIsNotInTheHistory |
