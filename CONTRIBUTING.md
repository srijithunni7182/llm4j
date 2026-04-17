# Contributing to llm4j

Thanks for contributing. This document applies to the full monorepo.

## Development Workflow

1. Create a branch from `main`.
2. Make focused changes with tests/docs updated together.
3. Run local checks:
   - `make test`
   - `make smoke-apps`
4. Open a pull request using the repository template.

## Commit Style

- Prefer conventional prefixes: `feat:`, `fix:`, `chore:`, `docs:`, `refactor:`, `test:`.
- Keep each commit scoped to a single concern.
- Explain the motivation ("why"), not just the file list.

## Testing Expectations

- Unit tests are required for behavior changes.
- Integration tests should be added for provider/protocol edge cases.
- Follow naming:
  - `*Test.java` for unit tests
  - `*IntegrationTest.java` for profile-gated tests

See `TESTING_STRATEGY.md` for execution model and CI mapping.

## Documentation Expectations

- Update README/wiki/docs when changing setup, coordinates, or API behavior.
- For compatibility-impacting changes, update:
  - `API_COMPATIBILITY.md`
  - migration guides (for example `MIGRATION_GUIDE_5_0.md`)

## Security and Secrets

- Never commit credentials, API keys, or secrets.
- Use local `secrets.sh` / environment variables and keep secret files ignored.

## Code Ownership and Review

- Reviews are guided by `.github/CODEOWNERS`.
- Changes in core library, CI, and release files should include maintainer review.
