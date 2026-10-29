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
  - tag slow tests with JUnit `@Tag("integration")` or `@Tag("fragile")` (they run with `mvn -Pextended verify`; see docs/TESTING_STRATEGY.md)

See `docs/TESTING_STRATEGY.md` for execution model and CI mapping.

## Documentation Expectations

- Update README/wiki/docs when changing setup, coordinates, or API behavior.
- A change to the VS Code extension (`src/loom/vscode-loom`, other than its tests) must bump `version` in `package.json` and `package-lock.json` and add a `## <version>` section to its `CHANGELOG.md`: CI publishes it to the Marketplace when it lands on `main`, and a pull request without a bump fails the "Is there a new version to publish?" check (see `docs/PUBLISHING_VSCODE.md`).
- For compatibility-impacting changes, update:
  - `docs/API_COMPATIBILITY.md`
  - migration guides (for example `docs/MIGRATION_GUIDE_5_0.md`)

## Security and Secrets

- Never commit credentials, API keys, or secrets.
- Use local `secrets.sh` / environment variables and keep secret files ignored.

## Code Ownership and Review

- Reviews are guided by `.github/CODEOWNERS`.
- Changes in core library, CI, and release files should include maintainer review.

## License

By contributing, you agree that your contributions will be licensed under the Apache License 2.0.
