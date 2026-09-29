# Test Strategy Patterns
- Follow the test pyramid: many fast unit tests, fewer integration tests, a handful of end-to-end journeys.
- Contract-test every API boundary between teams or services.
- Test the unhappy paths deliberately: invalid input, timeouts, duplicates, concurrency.
- Performance tests state a load profile and pass/fail thresholds before they run.
- Coverage targets apply to changed code; untested risk is listed explicitly.
