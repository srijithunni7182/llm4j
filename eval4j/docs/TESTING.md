# Testing eval4j itself

How to run eval4j's own unit, integration and calibration suites.

[← eval4j README](../README.md) · [All docs](README.md)

---

```bash
cd eval4j
mvn test                              # unit tests — no API key needed
mvn -P integration-tests verify       # + live judge round-trips (skipped when no judge is configured)
```

The live suites pick a judge from the environment: `GEMINI_API_KEY`/`GOOGLE_API_KEY` (Gemini),
`EVAL4J_ANTHROPIC_API_KEY` (+ optional `EVAL4J_ANTHROPIC_MODEL`), or `EVAL4J_JUDGE=ollama` (+ optional
`OLLAMA_MODEL`, `OLLAMA_BASE_URL`). `CalibrationStudyIntegrationTest` and
`CalibrationStudyV2IntegrationTest` (Claude judge) reproduce the calibration results.
