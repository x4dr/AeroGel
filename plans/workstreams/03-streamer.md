# WS-03 — ProxyStreamer: wanted-list cost, refinement scan, streaming cost analysis

**Prereq:** WS-01 (per-dimension) and WS-02 (PromotionManager) landed. Adapt to
their APIs.
**Read only:** `server/ProxyStreamer.java`, `server/ProxyChunkGate.java` (API),
`client/ClientMeshStore.java` (for the memory numbers), `common/ProxyTopology.java`.
Do not edit other files.

## First: answer the user's question
`promoteNearBand` (`ProxyStreamer.java:690-717`) is **not** real-terrain
promotion. Every 20 ticks it walks the `nearDistance` square around each player
and ensures every chunk is sampled/sent at the finer `nearTier`, re-submitting
chunks that were sampled coarse while far. It is **detail-tier refinement**.
Rename it (`refineNearBand`) and update the javadoc so it stops colliding with
`PromotionManager`. (The user thought promotion only happened on linger/touch/
write — correct for real terrain; this is the mesh-detail mechanism.)

## Tasks

### 1. Optimise `rebuildWanted` for existing chunks
Current behaviour (`:524-607`):
- On a `fresh` jump (`> FRESH_JUMP_THRESHOLD=16`) it rebuilds the **entire**
  radius window ring-by-ring, even for chunks already sent/known.
- The incremental path enqueues the leading-edge x-band **and** z-band; on a
  diagonal move the corner row/column is enqueued twice, and `enqueue` only
  checks `sent` (not "already in `wanted`"), so duplicates sit in the queue until
  the submit loop dedups them via `pending`.
- The initial ring loop (`:546-557`) has no queue-cap check in the outer loop; at
  large radius it runs O(r²) even after `MAX_WANTED_QUEUE` is hit.

Do:
- For the `fresh` path, only enqueue positions that are not already `sent` **and**
  not already in `wanted`. If the client already has the mesh (`sent` persists
  until it leaves the radius), a re-approach should still resend (that's why
  `sent` is pruned outside the radius) — keep that, but don't re-enqueue known
  chunks on a *small* jump. Consider raising `FRESH_JUMP_THRESHOLD` / treating a
  moderate jump incrementally instead of a full rebuild.
- Dedup the diagonal double-enqueue (e.g. check a `wanted` membership set, or
  enqueue the x-band then the z-band excluding the already-covered x range).
- Add the queue-cap check to the outer ring loop so a huge radius can't stall the
  tick.
- Keep the "drop the consumed prefix" compaction (`:599-606`) but note it copies
  the tail; a `LongArrayFifo`/ring buffer avoids the copy if it matters.

### 2. Gate the refinement scan
`refineNearBand` is O((2*nearDistance+1)²) per player every 20 ticks (4225 at the
default nearDistance=32), each `ProxyChunkGate.isLoadedReal` → `getChunkNow`,
even when the level has zero proxies/grids. Gate it on the WS-01
`mayHaveProxy(level)` / `hasProxies()` flag, or on `!grids.isEmpty() || !pending.isEmpty()`.

### 3. Per-tick allocations / complexity
- `sendAndSubmit` allocates a `HashMap<Byte,Batch>` per player per tick
  (`:632`). Reuse a per-player or per-level scratch map (clear it each tick).
- `pruneGrids` (`:375-401`) is O(grids × players) every 200 ticks; fine, but skip
  entirely when `grids.isEmpty()` (already) and consider a spatial key.
- The drain loop stores grids without an in-radius check; acceptable, but confirm
  `pruneGrids` can keep up after a teleport.

### 4. Streaming cost analysis (deliverable — the user asked)
Produce a table for radius 20 / 50 / 100 and tiers 5/9/25/81, using the real
constants (`MAX_SUBMIT_PER_TICK=512`, `MAX_DRAIN_PER_TICK=1024`,
`MAX_WANTED_QUEUE=200_000`, `MAX_PENDING=4096`, `ProxyTopology.count`,
`MeshBatchPayload` byte layout, `ClientMeshStore.Mesh` = `byte + short[] + int +
int`, `ProxyRenderer` ~12 quads/chunk GPU):
- one-time sampler CPU per player (evals/chunk × 0.15 ms; state the source of the
  evals/chunk figure),
- one-time network bytes per player (pos long + `count` shorts + biome/block
  varints),
- steady-state per-tick CPU/network while flying (leading-edge `2r+1` per cross ×
  80 chunks/s),
- client RAM (store + boxed `proxyPositions`) and GPU (VertexBuffers).
State assumptions explicitly; mark estimates as estimates. The measurements.md
file has prior sampler figures — use it, don't re-measure unless cheap.

### 5. Dead code
Remove `probeSampleBlocking` only if the generation probe no longer needs it.

## Verify
`JAVA_HOME=... ./gradlew :compileJava`.

## Report
- The `promoteNearBand` answer (short).
- `rebuildWanted` change + before/after enqueue counts on a diagonal move.
- The cost table with assumptions.
- Tradeoffs.
