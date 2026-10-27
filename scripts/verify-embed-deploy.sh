#!/usr/bin/env bash
# Offline verification of phase 15: bindgen over the corpus (generate, compile, run a facade), the
# Maven goal, the Spring beans, and weave deploy (structure, safety, no secrets, reproducible).
# No model, no network beyond the build's own dependencies.
set -eu
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root/src/loom/ai-agent4j-loom"
mvn -B -o -q test -Dtest='BindgenTest,BindgenCommandTest,BindgenCorpusTest,DeployTest,DeployCommandTest,DeploySkillDriftTest' -Dsurefire.failIfNoSpecifiedTests=false
cd "$root/src/loom/loom-maven-plugin"
mvn -B -o -q test
cd "$root/src/spring/llm4j-spring-boot-starter"
mvn -B -q test -Dtest='LoomWorkflowsTest'
echo "phase 15 offline verification passed"
