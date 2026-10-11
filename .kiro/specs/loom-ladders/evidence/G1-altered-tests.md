# G1: the earlier tests that were changed

Verification G1 says: run the whole earlier suite unchanged; any test altered needs a recorded reason approved by a person. These are all of them (files that exist on `main` and differ on this branch), with the reason and the exact change. **Nothing here has been approved yet.**

A mistake in an earlier stage of this work, now corrected: the first version of this branch overwrote `LadderRunTest` (the earned-autonomy R3 tests) with new tests of the same name, and the results then said "every earlier test unchanged". That file is restored from `main` (changed only as listed below) and the new tests live in `LadderBoundRunTest`.

| File | Added | Removed | Reason |
|---|---|---|---|
| `DecideHarness.java` | 65 | 0 | Adds helpers (seed, levelOf, epochOf, identityOf, reasonOf, scopes, parsed, rawAgent, servedModel) so a test can set and read where a scope stands whichever engine runs the decision. Nothing existing is removed. |
| `DecideResilienceTest.java` | 8 | 9 | Crash-at-every-journal-write now counts `responded` records (for `decided`); the unreadable-store test fails the one store the ladder engine uses (the ledger) instead of the ledger and the level store separately. Same guarantees: one case, one proposal, one verdict; fail closed. |
| `DecideSafetyTest.java` | 13 | 13 | Reads of the original level store (level, epoch, identity) became h.levelOf/epochOf/identityOf; the expected record kinds now include `responded` (for `decided`) and the level and anchor records the ladder engine writes. Same safety scenarios (hostile field, mid-case freeze, mid-case identity change, notification once). |
| `DecideTest.java` | 4 | 4 | Four tests set a level by writing the original level store. A trust block now runs on the ladder engine, whose levels live in the ledger, so they now call h.seed(...) / h.scopes(). Same scenarios, same assertions. |
| `LadderRunTest.java` | 13 | 12 | The earned-autonomy spec R3 tests, as on main, changed only where they bypassed the product: seeding and reading levels through the original level store (h.seed/h.levelOf/h.reasonOf), approving through the ladder engine, and moving a level by hand through its set(). Assertions are unchanged. |
| `ReplayDurabilityTest.java` | 1 | 1 | One line: the old level, epoch and identity are read through the helpers instead of the original level store. |
| `AutonomyCommandsTest.java` | 14 | 6 | The helpers that set/read a level (setLevel/levelOf) write/read the ledger record the engine uses; the `status` expectations are the previous release's own words (status for a trust decision is printed by the original code). |
| `AutonomyCompatTest.java` | 2 | 1 | The check that a scheduled run leaves a level for the scope looks for the level record in the ledger instead of the original level store. |
| `AutonomySweepTest.java` | 1 | 1 | `levels.json` is no longer among the files a trust decision writes (its levels are in the ledger); the secret sweep still reads every file that is written. |
| `ScriptedRun.java` | 4 | 1 | Adds a served-model hook for the test client (the model the provider says answered). Nothing existing changed. |
| `check.integration.test.ts` | 48 | 0 | Adds ladder cases; nothing existing changed. |
| `parseDocument.test.ts` | 26 | 0 | Adds ladder cases; nothing existing changed. |

## The removed and changed lines, exactly

### DecideResilienceTest.java

```diff
diff --git a/src/loom/ai-agent4j-loom/src/test/java/io/github/llm4j/loom/autonomy/DecideResilienceTest.java b/src/loom/ai-agent4j-loom/src/test/java/io/github/llm4j/loom/autonomy/DecideResilienceTest.java
index b05bd33..2af9c6a 100644
--- a/src/loom/ai-agent4j-loom/src/test/java/io/github/llm4j/loom/autonomy/DecideResilienceTest.java
+++ b/src/loom/ai-agent4j-loom/src/test/java/io/github/llm4j/loom/autonomy/DecideResilienceTest.java
@@ -51 +51 @@ class DecideResilienceTest {
-                assertThat(h.ledger.records("Refund").stream().filter(r -> Rec.DECIDED.equals(r.kind()))).as(at).hasSize(1);
+                assertThat(h.ledger.records("Refund").stream().filter(r -> Rec.RESPONDED.equals(r.kind()))).as(at).hasSize(1);
@@ -80 +80,2 @@ class DecideResilienceTest {
-        for (String broken : List.of("ledger", "levels", "both")) {
+        // the levels live in the ledger, so one store to break; it can fail to be read, to be written, or both
+        for (String broken : List.of("reads", "writes", "both")) {
@@ -84,6 +85,4 @@ class DecideResilienceTest {
-            // the ladder says act, but the stores cannot be trusted right now
-            levels.compareAndSet("Refund", "gold", null, new LevelState(Level.ACT, 1, h.hashOfRefund(), false, DecideHarness.T0, "earned", 1));
-            levels.failReads = broken.equals("levels") || broken.equals("both");
-            ledger.failReads = broken.equals("ledger") || broken.equals("both");
-            ledger.failWrites = broken.equals("ledger") || broken.equals("both");
-            if (broken.equals("ledger")) h.script = h.script.replace("never go above act", "never go above act\n always ask a person after 5 cases a day"); // reads the ledger at act
+            // the ladder says act, but the store cannot be trusted right now
+            h.seed("gold", Level.ACT);
+            ledger.failReads = !broken.equals("writes");
+            ledger.failWrites = !broken.equals("reads");
@@ -107 +106 @@ class DecideResilienceTest {
-        assertThat(h.levels.get("Refund", "gold").orElseThrow().level()).isEqualTo(Level.WATCH);
+        assertThat(h.levelOf("gold")).isEqualTo(Level.WATCH);
```

### DecideSafetyTest.java

```diff
diff --git a/src/loom/ai-agent4j-loom/src/test/java/io/github/llm4j/loom/autonomy/DecideSafetyTest.java b/src/loom/ai-agent4j-loom/src/test/java/io/github/llm4j/loom/autonomy/DecideSafetyTest.java
index 9984ec0..01c2c99 100644
--- a/src/loom/ai-agent4j-loom/src/test/java/io/github/llm4j/loom/autonomy/DecideSafetyTest.java
+++ b/src/loom/ai-agent4j-loom/src/test/java/io/github/llm4j/loom/autonomy/DecideSafetyTest.java
@@ -43 +43 @@ class DecideSafetyTest {
-        assertThat(h.levels.get("Refund", "gold").orElseThrow().level()).isEqualTo(Level.WATCH);
+        assertThat(h.levelOf("gold")).isEqualTo(Level.WATCH);
@@ -48 +48,2 @@ class DecideSafetyTest {
-        assertThat(kinds(h.ledger)).containsExactly(Rec.LEVEL, Rec.CASE, Rec.PROPOSED, Rec.DECIDED);
+        assertThat(kinds(h.ledger)).containsOnly(Rec.LEVEL, Rec.SEAL_BASE, Rec.CASE, Rec.PROPOSED, Rec.RESPONDED);     // the level records (the first case, the served model) and the anchor that seals them
+        assertThat(kinds(h.ledger).stream().filter(k -> !k.equals(Rec.LEVEL) && !k.equals(Rec.SEAL_BASE)).toList()).containsExactly(Rec.CASE, Rec.PROPOSED, Rec.RESPONDED);
@@ -62 +63 @@ class DecideSafetyTest {
-        assertThat(h.levels.get("Refund", "gold").orElseThrow().level()).isEqualTo(Level.WATCH);
+        assertThat(h.levelOf("gold")).isEqualTo(Level.WATCH);
@@ -109,2 +110,2 @@ class DecideSafetyTest {
-        assertThat(h.levels.get("Refund", "gold").orElseThrow().level()).isEqualTo(Level.SUGGEST);
-        String before = h.levels.get("Refund", "gold").orElseThrow().identity();
+        assertThat(h.levelOf("gold")).isEqualTo(Level.SUGGEST);
+        String before = h.identityOf("gold");
@@ -115,3 +116,2 @@ class DecideSafetyTest {
-        LevelState state = h.levels.get("Refund", "gold").orElseThrow();
-        assertThat(state.epoch()).isEqualTo(2);
-        assertThat(state.identity()).isNotEqualTo(before);
+        assertThat(h.epochOf("gold")).isEqualTo(2);
+        assertThat(h.identityOf("gold")).isNotEqualTo(before);
@@ -132 +132 @@ class DecideSafetyTest {
-        assertThat(h.levels.get("Refund", "gold").orElseThrow().epoch()).isEqualTo(2);
+        assertThat(h.epochOf("gold")).isEqualTo(2);
@@ -146 +146 @@ class DecideSafetyTest {
-        String began = h.levels.get("Refund", "gold").orElseThrow().identity();
+        String began = h.identityOf("gold");
@@ -153 +153 @@ class DecideSafetyTest {
-        assertThat(h.levels.get("Refund", "gold").orElseThrow().epoch()).as("the resumed case did not start an epoch").isEqualTo(1);
+        assertThat(h.epochOf("gold")).as("the resumed case did not start an epoch").isEqualTo(1);
@@ -178 +178 @@ class DecideSafetyTest {
-            assertThat(h.levels.get("Refund", "gold").orElseThrow().level()).isEqualTo(Level.SUGGEST);
+            assertThat(h.levelOf("gold")).isEqualTo(Level.SUGGEST);
@@ -195 +195 @@ class DecideSafetyTest {
-        h.levels.compareAndSet("Refund", "gold", null, new LevelState(Level.ACT, 1, h.hashOfRefund(), false, DecideHarness.T0, "earned", 1));
+        h.seed("gold", Level.ACT);
```

### DecideTest.java

```diff
diff --git a/src/loom/ai-agent4j-loom/src/test/java/io/github/llm4j/loom/autonomy/DecideTest.java b/src/loom/ai-agent4j-loom/src/test/java/io/github/llm4j/loom/autonomy/DecideTest.java
index 22d4807..89db173 100644
--- a/src/loom/ai-agent4j-loom/src/test/java/io/github/llm4j/loom/autonomy/DecideTest.java
+++ b/src/loom/ai-agent4j-loom/src/test/java/io/github/llm4j/loom/autonomy/DecideTest.java
@@ -83 +83 @@ class DecideTest {
-        h.levels.compareAndSet("Refund", "gold", null, new LevelState(Level.SUGGEST, 1, new DecideHarness(dir, h.ledger, h.levels, h.script).hashOfRefund(), false, DecideHarness.T0, "test", 1));
+        h.seed("gold", Level.SUGGEST);
@@ -101 +101 @@ class DecideTest {
-        h.levels.compareAndSet("Refund", "gold", null, new LevelState(Level.ACT, 1, h.hashOfRefund(), false, DecideHarness.T0, "test", 1));
+        h.seed("gold", Level.ACT);
@@ -118 +118 @@ class DecideTest {
-        h.levels.compareAndSet("Refund", "gold", null, new LevelState(Level.ACT, 1, h.hashOfRefund(), false, DecideHarness.T0, "test", 1));
+        h.seed("gold", Level.ACT);
@@ -125 +125 @@ class DecideTest {
-        assertThat(h.levels.scopes("Refund")).containsOnlyKeys("gold", "basic");
+        assertThat(h.scopes()).containsExactlyInAnyOrder("gold", "basic");
```

### LadderRunTest.java

```diff
diff --git a/src/loom/ai-agent4j-loom/src/test/java/io/github/llm4j/loom/autonomy/LadderRunTest.java b/src/loom/ai-agent4j-loom/src/test/java/io/github/llm4j/loom/autonomy/LadderRunTest.java
index db2b78a..87ceda7 100644
--- a/src/loom/ai-agent4j-loom/src/test/java/io/github/llm4j/loom/autonomy/LadderRunTest.java
+++ b/src/loom/ai-agent4j-loom/src/test/java/io/github/llm4j/loom/autonomy/LadderRunTest.java
@@ -33 +33 @@ class LadderRunTest {
-        return h.levels.get("Refund", scope).orElseThrow().level();
+        return h.levelOf(scope);
@@ -63 +63,2 @@ class LadderRunTest {
-        Engine engine = new Engine(DecisionParseTest.parse(h.script).getDecisions().get(0), h.ledger, h.levels, h.clock);
+        io.github.llm4j.loom.ladder.LadderEngine engine = new io.github.llm4j.loom.ladder.LadderEngine(
+                io.github.llm4j.loom.ladder.LadderPolicy.of(h.parsed(), h.parsed().getDecisions().get(0)), h.ledger, h.clock);
@@ -65,2 +66,2 @@ class LadderRunTest {
-        Engine.Change change = engine.approve("gold", "risk-owner", "the numbers look right");
-        assertThat(change.to()).isEqualTo(Level.SUGGEST);
+        var change = engine.approve("gold", "risk-owner", "the numbers look right");
+        assertThat(change.to()).isEqualTo("suggest");
@@ -68 +69 @@ class LadderRunTest {
-        assertThat(engine.ladder().proposals("gold").get(0).by()).isEqualTo("risk-owner");
+        assertThat(h.ledger.recordsOfKind("Refund", Rec.PROMOTION_DECIDED).get(0).str("by")).isEqualTo("risk-owner");
@@ -76 +77 @@ class LadderRunTest {
-        h.levels.compareAndSet("Refund", "gold", null, new LevelState(Level.SUGGEST, 1, h.hashOfRefund(), false, DecideHarness.T0, "test", 1));
+        h.seed("gold", Level.SUGGEST);
@@ -87 +88 @@ class LadderRunTest {
-        assertThat(h.levels.get("Refund", "gold").orElseThrow().reason()).contains("2 dangerous mistakes in the last 2 cases");
+        assertThat(h.reasonOf("gold")).contains("2 dangerous mistakes in the last 2 cases");
@@ -96 +97 @@ class LadderRunTest {
-        h.levels.compareAndSet("Refund", "gold", null, new LevelState(Level.ACT, 1, h.hashOfRefund(), false, DecideHarness.T0, "test", 1));
+        h.seed("gold", Level.ACT);
@@ -111 +112 @@ class LadderRunTest {
-        h.levels.compareAndSet("Refund", "gold", null, new LevelState(Level.ACT, 1, h.hashOfRefund(), false, DecideHarness.T0, "test", 1));
+        h.seed("gold", Level.ACT);
@@ -139 +140 @@ class LadderRunTest {
-        h.levels.compareAndSet("Refund", "gold", null, new LevelState(Level.ACT, 1, h.hashOfRefund(), false, DecideHarness.T0, "test", 1));
+        h.seed("gold", Level.ACT);
@@ -171,2 +172,2 @@ class LadderRunTest {
-        LevelState now = h.levels.get("Refund", "gold").orElseThrow();
-        h.levels.compareAndSet("Refund", "gold", now, now.withLevel(Level.ACT, true, DecideHarness.T0, "someone moved it"));
+        new io.github.llm4j.loom.ladder.LadderEngine(io.github.llm4j.loom.ladder.LadderPolicy.of(h.parsed(), h.parsed().getDecisions().get(0)), h.ledger, h.clock)
+                .set("gold", "act", true, "someone moved it", "test");
```

### ReplayDurabilityTest.java

```diff
diff --git a/src/loom/ai-agent4j-loom/src/test/java/io/github/llm4j/loom/autonomy/ReplayDurabilityTest.java b/src/loom/ai-agent4j-loom/src/test/java/io/github/llm4j/loom/autonomy/ReplayDurabilityTest.java
index 8aa5058..8d0e880 100644
--- a/src/loom/ai-agent4j-loom/src/test/java/io/github/llm4j/loom/autonomy/ReplayDurabilityTest.java
+++ b/src/loom/ai-agent4j-loom/src/test/java/io/github/llm4j/loom/autonomy/ReplayDurabilityTest.java
@@ -121 +121 @@ class ReplayDurabilityTest {
-            LevelState old = live.levels.get("Refund", "gold").orElseThrow();
+            LevelState old = new LevelState(live.levelOf("gold"), live.epochOf("gold"), live.identityOf("gold"), false, DecideHarness.T0, "earned", 1);
```

### AutonomyCommandsTest.java

```diff
diff --git a/src/loom/ai-agent4j-loom/src/test/java/io/github/llm4j/loom/cli/AutonomyCommandsTest.java b/src/loom/ai-agent4j-loom/src/test/java/io/github/llm4j/loom/cli/AutonomyCommandsTest.java
index 47989a1..3f8f468 100644
--- a/src/loom/ai-agent4j-loom/src/test/java/io/github/llm4j/loom/cli/AutonomyCommandsTest.java
+++ b/src/loom/ai-agent4j-loom/src/test/java/io/github/llm4j/loom/cli/AutonomyCommandsTest.java
@@ -225,2 +224,0 @@ class AutonomyCommandsTest {
-        var levels = new io.github.llm4j.loom.autonomy.FileLevelStore(store.resolve("autonomy"));
-        var old = levels.get("Refund", scope).orElseThrow();
@@ -228,2 +226,8 @@ class AutonomyCommandsTest {
-        String identity = io.github.llm4j.loom.autonomy.AgentIdentity.of(loaded, loaded.getDecisions().get(0), dir);
-        levels.compareAndSet("Refund", scope, old, new io.github.llm4j.loom.autonomy.LevelState(level, old.epoch(), identity, false, old.since(), "set up by the test", old.version() + 1));
+        var decision = loaded.getDecisions().get(0);
+        var policy = io.github.llm4j.loom.ladder.LadderPolicy.of(loaded, decision);
+        var log = new io.github.llm4j.loom.ladder.LevelLog(decision.getName(), policy.rungs(), new io.github.llm4j.loom.autonomy.FileLedger(store.resolve("autonomy")));
+        var old = log.current(scope).orElseThrow();
+        String identity = io.github.llm4j.loom.autonomy.AgentIdentity.of(loaded, decision, dir);
+        int rung = policy.indexOf(level.word());
+        log.write(scope, old.version(), new io.github.llm4j.loom.ladder.LevelLog.Standing(rung, level.word(), old.version() + 1, old.epoch(), identity, old.servedModel(), false, old.at(),
+                "set up by the test", policy.fingerprint()), "set", old.rungName(), "test", old.at(), policy.rulesText());
@@ -232,2 +236,6 @@ class AutonomyCommandsTest {
-    private String levelOf(String scope) {
-        return new io.github.llm4j.loom.autonomy.FileLevelStore(store.resolve("autonomy")).get("Refund", scope).orElseThrow().level().word();
+    private String levelOf(String scope) throws Exception {
+        var loaded = new io.github.llm4j.loom.execution.LoomLoader().load(script.getAbsolutePath());
+        var decision = loaded.getDecisions().get(0);
+        var policy = io.github.llm4j.loom.ladder.LadderPolicy.of(loaded, decision);
+        return new io.github.llm4j.loom.ladder.LevelLog(decision.getName(), policy.rungs(), new io.github.llm4j.loom.autonomy.FileLedger(store.resolve("autonomy")))
+                .current(scope).orElseThrow().rungName();
```

### AutonomyCompatTest.java

```diff
diff --git a/src/loom/ai-agent4j-loom/src/test/java/io/github/llm4j/loom/cli/AutonomyCompatTest.java b/src/loom/ai-agent4j-loom/src/test/java/io/github/llm4j/loom/cli/AutonomyCompatTest.java
index 4f564dc..741fa84 100644
--- a/src/loom/ai-agent4j-loom/src/test/java/io/github/llm4j/loom/cli/AutonomyCompatTest.java
+++ b/src/loom/ai-agent4j-loom/src/test/java/io/github/llm4j/loom/cli/AutonomyCompatTest.java
@@ -110 +110,2 @@ class AutonomyCompatTest {
-        assertThat(AutonomySupport.levels(store).get("Refund", "gold")).isPresent();
+        assertThat(AutonomySupport.ledger(store).recordsOfKind("Refund", io.github.llm4j.loom.autonomy.Rec.LEVEL)).as("the level of the scope is in the ledger")
+                .anyMatch(r -> "gold".equals(r.str("scope")));
```

### AutonomySweepTest.java

```diff
diff --git a/src/loom/ai-agent4j-loom/src/test/java/io/github/llm4j/loom/cli/AutonomySweepTest.java b/src/loom/ai-agent4j-loom/src/test/java/io/github/llm4j/loom/cli/AutonomySweepTest.java
index dd91cc6..6c3cdc7 100644
--- a/src/loom/ai-agent4j-loom/src/test/java/io/github/llm4j/loom/cli/AutonomySweepTest.java
+++ b/src/loom/ai-agent4j-loom/src/test/java/io/github/llm4j/loom/cli/AutonomySweepTest.java
@@ -94 +94 @@ class AutonomySweepTest {
-        assertThat(files.stream().map(p -> p.getFileName().toString())).contains("ledger.jsonl", "levels.json", "log.jsonl", "report.md", "report.json", "journal.json", "run.json");
+        assertThat(files.stream().map(p -> p.getFileName().toString())).contains("ledger.jsonl", "log.jsonl", "report.md", "report.json", "journal.json", "run.json");
```

### ScriptedRun.java

```diff
diff --git a/src/loom/ai-agent4j-loom/src/test/java/io/github/llm4j/loom/generic/support/ScriptedRun.java b/src/loom/ai-agent4j-loom/src/test/java/io/github/llm4j/loom/generic/support/ScriptedRun.java
index d931bcc..84ae994 100644
--- a/src/loom/ai-agent4j-loom/src/test/java/io/github/llm4j/loom/generic/support/ScriptedRun.java
+++ b/src/loom/ai-agent4j-loom/src/test/java/io/github/llm4j/loom/generic/support/ScriptedRun.java
@@ -57,0 +58,3 @@ public final class ScriptedRun {
+    /** The model the provider says answered, as reported in each reply. */
+    public volatile String served = "m";
+
@@ -78 +81 @@ public final class ScriptedRun {
-                return LLMResponse.builder().content(content).model("m").tokenUsage(10, 5, 15).build();
+                return LLMResponse.builder().content(content).model(served).tokenUsage(10, 5, 15).build();
```

