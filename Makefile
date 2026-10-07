SHELL := /bin/bash

.PHONY: help build test test-extended test-live smoke-apps release-check skill-package verify format-check

help:
	@echo "Targets:"
	@echo "  make build          - Compile the published libraries"
	@echo "  make test           - The default build: fast, hermetic tests and the coverage gates (this is what a release is gated on)"
	@echo "  make test-extended  - Everything: integration and fragile tests too, plus engram, tantrik and the example applications"
	@echo "  make test-live      - Only the live tests, against real provider APIs (needs keys, costs money)"
	@echo "  make smoke-apps     - Compile engram, tantrik and the example applications (no tests)"
	@echo "  make skill-package  - Build dist/llm4j-workflow-guide.zip: the skill with its references and the weave jar, and check it from an empty folder"
	@echo "  make release-check  - Build the sources and javadoc jars exactly as a release does, without signing"
	@echo "  make format-check   - Check formatting for core and addons"

build:
	mvn -q -DskipTests compile

test:
	mvn -q verify

verify: test

test-extended:
	mvn -q -Pextended verify

test-live:
	mvn -q -Plive verify

smoke-apps:
	mvn -q -Pextended -DskipTests compile

release-check:
	mvn -q -Prelease -Dgpg.skip=true verify

skill-package:
	mvn -q -DskipTests package -pl loom/ai-agent4j-loom -am
	scripts/package-skill.sh
	scripts/verify-skill-package.sh

format-check:
	mvn -q -pl ai-agent4j,ai-agent4j-addons spotless:check
