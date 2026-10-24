#!/usr/bin/env bash
# Verifies phase 13 (time-travel debugging) from the Java module's tests: the replay equals the run for the
# corpus and for generated workflows, nothing a past run holds is written, and nothing has an effect.
# Usage: scripts/verify-travel.sh [seeds]   (seeds: generated workflows, default 500; the nightly run uses 10000)
set -euo pipefail
cd "$(dirname "$0")/../src/loom/ai-agent4j-loom"
seeds="${1:-500}"
mvn -B -o -q test -Dloom.seeds="$seeds" -Dtest='\
ObserveReplayEqualsRunTest,GeneratedWorkflowsTest,DecisionEventTest,\
WhyTest,WhyNotRanTest,DivergenceTest,WhyStatesTest,WhyCommandTest,\
WhatIfTest,WhatIfCandidateTest,WhatIfCommandTest,\
StepperTest,BreakpointTest,StepBackTest,StepCommandTest,\
OverlayCountsTest,OverlayAggregateTest,OverlayMermaidTest,OverlayJsonSchemaTest,GraphRunOverlayCommandTest,\
DapProtocolTest,DapLiveConfirmTest'
echo "phase 13: replay equals run for the corpus and $seeds generated workflows; nothing written, nothing sent."
