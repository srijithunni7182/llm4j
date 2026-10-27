#!/usr/bin/env bash
# Offline verification of phase 14: the hostile-journal masking tests, the capture round trip over
# the generated corpus, regress over the synthetic store, gate on a sample change, and Fabric's
# capture routes. No model, no network.
set -eu
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root/src/loom/ai-agent4j-loom"
mvn -B -o -q test -Dtest='MaskerTest,CaptureTest,CapturePassesTest,LoopStateTest,SamplerTest,RegressTest,RegressLiveTest,CaptureCommandTest,RegressCommandTest,GateCommandTest,FabricBridgeTest,CaptureInjectionTagTest' -Dsurefire.failIfNoSpecifiedTests=false
cd "$root/src/loom/loom-inspect-core"
mvn -B -o -q test -Dtest='FabricCapture*Test' -Dsurefire.failIfNoSpecifiedTests=false
cd "$root/src/loom/loom-fabric"
mvn -B -o -q test -Dtest='FabricLoopTest' -Dsurefire.failIfNoSpecifiedTests=false
echo "phase 14 offline verification passed"
