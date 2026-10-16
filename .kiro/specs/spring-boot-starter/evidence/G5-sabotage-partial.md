# G5: sabotage runs

Each row breaks one rule in the real source, runs the starter and host tests, and restores the source.
**DETECTED** means every expected check failed.

| # | Sabotage | Expected to fail | Result | Failing checks | Failing tests |
|---|---|---|---|---|---|
| S1 | drop @ConditionalOnMissingBean from the primary LLMClient | V0.3 | **DETECTED** | failing checks ['V0.3'] | ZeroMagicTest.aBeanOfYourOwnAlwaysTakesThePlace |
| S7 | skip kind.check before create | V6.2 | **DETECTED** | failing checks ['V6.2'] | ToolsTest.aToolWhoseOptionsAreWrongFailsStartUpWithTheLibrarysReason |
