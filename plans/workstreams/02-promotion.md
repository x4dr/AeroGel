# WS-02 — PromotionManager: bounded queue, player-proximity eviction, in-flight fix

**Prereq:** WS-01 has made the server per-dimension. Adapt to its new signatures.
**Read only:** `server/PromotionManager.java`, `server/ProxyChunkGate.java`
(API), `server/ProxyStreamer.java` (API), `server/AdaptiveMode.java` (API).
Do not edit the other files — if you need a change there, note it in your report.

## What PromotionManager is (for context)
It is the **real-terrain** promotion system: linger counters, touch/write/opt-out
triggers, the generation-time token bucket, and replay of deferred block writes.
It is unrelated to `ProxyStreamer.promoteNearBand`, which is detail-tier
refinement (WS-03 renames it). Do not confuse the two.

## Tasks

### 1. Bound the queue and evict on player departure
- `queue` (`:82`) can grow without bound: `pickNext` skips entries whose holder is
  not loaded (`:496-508`) and `processQueue` starts at most one promotion/tick, so
  a large backlog never drains if players leave.
- Add a cap (derive from `effectiveMaxRange`, or a config knob). When full, evict
  the **oldest non-urgent** entry (FIFO). Urgent entries (touch / block write /
  opt-out) must not be silently dropped: if the urgent set would exceed a small
  cap, still process them (or keep a separate bounded urgent queue) — a dropped
  write is a lost edit. Document the policy in the class javadoc.
- Track, per queued chunk, the last tick a player was within `maxRange`. If no
  player has been near for `LINGER_GRACE_TICKS` (or a new grace), drop the entry
  (and its `lingerTicks`/`lingerSeen`/`deferredWrites`). This is the user's
  requested design: "if the player moves away they should not be kept track of;
  if not generated, evict."
- `deferredWrites` (`:86`) must also be evicted with its queue entry, except when
  an urgent write is still pending.

### 2. Fix `inFlight` leaks (stall bug)
`inFlight` (`:84`) is added at `:476` and removed only in `onComplete` (`:526`).
If the promotion future never completes (holder unloaded first), `inFlight` stays
`>= maxInFlight` and **all promotion stops forever**. Record a start tick per
in-flight chunk; if it exceeds a timeout (e.g. 200-400 ticks), remove it from
`inFlight` and either re-enqueue (if the write/linger still applies) or drop.
Log at warn. Also remove on dimension unload/shutdown.

### 3. Kill the per-tick O(area) scan when there is nothing to do
`accumulateLinger` (`:338-381`) scans `(2*range+1)^2` = **625 cells per player per
tick**, each `ProxyChunkGate.isProxy` (which does `getChunkNow`), even when the
level has zero proxies. Gate the whole scan on the WS-01 `mayHaveProxy(level)` /
`hasProxies()` fast flag (or on `enforcing || anyOptInPlayer`). When a player is
opted out and there are no proxies, the loop does nothing useful.
- Also stop allocating a fresh `ArrayList` every tick in `overworldPlayers`
  (`:324-332`); reuse a scratch list per level.

### 4. `pickNext` complexity
`pickNext` (`:492-509`) linearly scans the whole queue on every promotion start
and calls `getVisibleChunkIfPresent` + `nearestDistance` per entry. With a bounded
queue this is bounded; still, avoid the full scan when the queue is large: keep a
per-chunk cached nearest-distance (updated when the player set changes) or scan
only the first N. Document the tradeoff (strict nearest vs bounded scan).

### 5. Dead/duplicate work
- `thresholdTicks` recomputes `loadFactor` per call; `accumulateLinger` calls it
  once per occupied chunk. Compute the two endpoints once per tick.
- Remove `debugEnqueue` if the stress probe is updated to use a real path (keep
  it if the probe still needs it).

## Verify
`JAVA_HOME=... ./gradlew :compileJava`. The promotion stress probe
(`touch run/server/aerogel.promotestress`) is the best end-to-end check if the
orchestrator asks you to run it — do not start the server unprompted.

## Report
- Queue cap + eviction policy and why.
- In-flight timeout value + re-enqueue vs drop decision.
- Before/after per-tick scan cost estimate.
- Tradeoffs.
