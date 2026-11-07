SHELL := /bin/bash

.PHONY: help build test test-core test-fast test-oracle test-full test-extended test-live smoke-apps release-check skill-package quick verify format-check

help:
	@echo "Targets:"
	@echo "  make build          - Compile the published libraries"
	@echo "  make test-core      - The Loom language core, about 70 seconds, no coverage gates (not the weave commands, starters, bindings or editor server): run after every sub-phase"
	@echo "  make test-fast      - Every test except the slow ones, with the coverage gates (about 2 minutes for Loom): run after every phase"
	@echo "  make test-oracle    - The provenance oracle alone (about 16 seconds): run once per session, at the end"
	@echo "  make test-full      - Everything, slow tests included, with the coverage gates (about 5 to 6 minutes for Loom): CI runs it on every pull request; run by hand only when asked"
	@echo "  make test           - The same as test-full (the default build: hermetic tests and the coverage gates; a release is gated on it)"
	@echo "  make test-extended  - Everything: integration and fragile tests too, plus engram and the example applications"
	@echo "  make test-live      - Only the live tests, against real provider APIs (needs keys, costs money)"
	@echo "  make smoke-apps     - Compile engram and the example applications (no tests)"
	@echo "  make skill-package  - Build dist/llm4j-eval-design.zip, dist/llm4j-harness-design.zip, dist/llm4j-workflow-guide.zip and dist/llm4j-loom-deployer.zip (the skills with their references, scripts and jars), and check them from an empty folder"
	@echo "  make quick          - Build and test the core libraries (no live, integration or fragile tests, no examples), package the skill, build and install the VS Code extension"
	@echo "  make release-check  - Build the sources and javadoc jars exactly as a release does, without signing"
	@echo "  make format-check   - Check formatting for core and addons"

build:
	mvn -q -DskipTests compile

test:
	mvn -q verify

test-core:
	mvn -q -pl src/loom/ai-agent4j-loom -Pcore test

test-fast:
	mvn -q -Pfast verify

test-oracle:
	mvn -q -pl src/loom/ai-agent4j-loom -Dtest=CanaryProvenanceOracleTest -Djacoco.skip=true test

test-full:
	mvn -q -Pfull verify

verify: test

test-extended:
	mvn -q -Pextended verify

test-live:
	mvn -q -Plive verify

smoke-apps:
	mvn -q -Pextended -DskipTests compile

release-check:
	mvn -q -Prelease -Dgpg.skip=true clean verify
	java scripts/CheckJars.java src

quick:
	scripts/quick-build.sh

skill-package:
	mvn -q -DskipTests package -pl src/loom/ai-agent4j-loom,src/loom/loom-inspect,src/loom/loom-fabric -am
	scripts/package-skill.sh
	scripts/verify-skill-package.sh

format-check:
	mvn -q -pl src/ai-agent4j,src/ai-agent4j-addons spotless:check
