# Testing Strategy

This repository uses a layered test strategy to keep pull requests fast while preserving deep validation in scheduled and release flows.

## Test Taxonomy

- `unit`: deterministic tests that do not require network access.
- `integration`: profile-gated tests that may call real providers or infrastructure.
- `smoke`: quick compile-level validation for showcase applications.
- `security`: dependency and vulnerability scanning.

## Naming and Execution Rules

- Unit tests should follow `*Test.java`.
- Integration tests should follow `*IntegrationTest.java`.
- Core modules exclude integration tests from default `surefire` runs.
- Integration suites are executed via Maven profile `integration-tests`.

## Standard Commands

- Fast local loop:
  - `make build`
  - `make test`
- Pre-merge validation:
  - `make test`
  - `make smoke-apps`
- Nightly/deep validation:
  - `make test-integration`
  - OWASP dependency checks

## CI Mapping

- Pull requests:
  - Build and unit-test core modules.
  - Smoke-compile showcase apps.
- Scheduled and manual deep validation:
  - Run integration profiles for core/addons.
  - Run dependency vulnerability scans.
