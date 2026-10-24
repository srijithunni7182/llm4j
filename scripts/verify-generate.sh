#!/usr/bin/env bash
# Offline verification of phase 12: replay benchmark, gate-only over every example, the injection and
# event tests. No model, no network.
set -eu
cd "$(dirname "$0")/../src/loom/ai-agent4j-loom"
mvn -B -o -q test -Dtest='GenerateBenchmarkTest,GateOnlyExamplesTest,GenerateCommandTest,GenerateBuildTest,GenerateEditTest,GenerateEventsTest,GenerateSkillDriftTest,PlanTest,RepairLoopTest,TestsGateTest,PatchSetTest,TranscriptTest' -Dsurefire.failIfNoSpecifiedTests=false
echo "phase 12 offline verification passed"
