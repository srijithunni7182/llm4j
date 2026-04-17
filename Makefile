SHELL := /bin/bash

.PHONY: help build test test-integration smoke-apps verify format-check

help:
	@echo "Targets:"
	@echo "  make build            - Compile all Maven modules via root aggregator"
	@echo "  make test             - Run unit tests for all modules"
	@echo "  make test-integration - Run integration profiles for core and addons"
	@echo "  make smoke-apps       - Compile showcase apps (skip tests)"
	@echo "  make verify           - Full verify lifecycle for all modules"
	@echo "  make format-check     - Check formatting for core and addons"

build:
	mvn -q -DskipTests compile

test:
	mvn -q test

test-integration:
	mvn -q -f ai-agent4j/pom.xml -Pintegration-tests verify
	mvn -q -f ai-agent4j-addons/pom.xml -Pintegration-tests verify

smoke-apps:
	mvn -q -f hexamind-hub/pom.xml -DskipTests compile
	mvn -q -f nirmaan-yantra/nirmaan-yantra-server/pom.xml -DskipTests compile
	mvn -q -f kingini/pom.xml -DskipTests compile
	mvn -q -f gmail-mcp-app/pom.xml -DskipTests compile

verify:
	mvn -q verify

format-check:
	mvn -q -f ai-agent4j/pom.xml spotless:check
	mvn -q -f ai-agent4j-addons/pom.xml spotless:check
