# G3: sabotage runs

Each row breaks one rule in the real source, runs the channel tests, and restores the source.
**DETECTED** means every expected check failed.

| # | Sabotage | Expected to fail | Result | Failing checks | Failing tests |
|---|---|---|---|---|---|
| M3 | Match a coded reply to the wrong question | V3.4 | **DETECTED** | failing checks ['V3.4'] | TelegramTest.aReplyIsMatchedByReplyToByCodeByTheOnlyOpenQuestionOrElseHelpIsGiven |
