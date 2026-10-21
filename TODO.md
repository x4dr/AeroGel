# AeroGel — TODO

Open work that is not yet designed. The design lives in `plans/`; this is the short list of things known to be
wrong or missing.

## 0. Batch proxy draw calls

`ProxyRenderer` issues **one `VertexBuffer` and one draw call per chunk** — ~1.7k draws/frame at radius 20,
~40k at radius 100, which is why the radius forecast calls large radii unrenderable. This is the binding
limit on proxy rendering, not CPU: a player standing still asking for proxies at r=20 pays ~1.7k draws every
frame indefinitely, and the 0.15 ms/chunk sampling that produced the mesh is long since done.

DH manages this by baking LOD geometry into large combined meshes per level, so many chunks share a draw.
Do the same:

- **Tile-batch the mid and far field** — 8x8 chunks per buffer, one draw per tile. 1681 draws → ~27 at r=20.
- **Keep per-chunk buffers for the near field**, where geometry changes often and rebuild granularity matters:
  a tile rebuild would re-emit 64 chunks of geometry instead of one.
- Seam walls, tint and the `POSITION_TEX_COLOR` format are unchanged; this is purely how vertices are grouped
  into buffers.

The real trade: today one chunk's change re-uploads one buffer; with tiling a change rebuilds 64 chunks of
geometry. Sampling is the expensive part and geometry emission is cheaper, so it should be a smaller spike than
it looks — but it is a spike, not incremental.

This is the prerequisite for ungated proxy rendering ("player on a tower, fog off, wants the horizon") being
affordable. See `plans/promotion-and-gating-rewrite.md` §6.

## 1. Replace the draw-order workaround with a real visibility decision

The renderer paints the proxy layer **before** the terrain (`AFTER_SKY`, depth-tested, depth write off) so
that whatever the renderer draws covers us, and the layer shows only where nothing else draws. That is a
workaround, not a fix:

- the layer **cannot occlude anything** — real terrain that is genuinely farther away paints over a proxy in
  front of it;
- it **assumes the terrain pass runs after `AFTER_SKY`**. True for the vanilla renderer; an assumption about a
  class of renderers, not a guarantee.

What is actually needed is a per-chunk answer to "is this chunk drawn and visible this frame". Vanilla exposes
it (`LevelRenderer.visibleSections` — what the old hide rule used). **Sodium does not**: it replaces the
terrain renderer and never fills that list, which is exactly why the layer was drawing over real terrain on a
Sodium client to begin with. Find a signal that works for both — a supported Sodium API if one exists, an
occlusion query, or a depth-only prepass — and let the layer occlude correctly again.

## 2. Improve Distant Horizons interop

The two layers currently coexist by accident: DH paints over us where it has LODs, we show where it does not,
and nothing coordinates the handover.

- DH **stops drawing LODs as you approach** and hands off to vanilla. In that band, with promotion off, DH
  draws nothing and vanilla has nothing to draw — the hole is filled only because our layer happens to be
  there. That handoff should be explicit rather than incidental.
- We should be able to ask **whether DH has an LOD for a chunk** and fill only where it does not.
- `streamRadius` and DH's LOD distance are unrelated numbers, and matching them is not achievable with
  on-demand sampling and one `VertexBuffer` per chunk (see the radius forecast in `AGENTS.md`). The useful
  question is what each layer should own, not how to make ours as large as theirs.
- Both are expensive at the same moment: DH generating its LOD backlog while we try to stream the same gap.

## 3. Near-field streaming latency

Flying fast, the proxy for the chunk you are *in* is a few seconds late — the whole point is that it is not.
The draw decision is no longer involved; this is sampling and delivery latency. Suspects, in order:

- the sampler thread count (one thread saturates around r=32);
- the wanted list only enqueuing the **leading-edge ring** on a chunk-cross, instead of the disc the player
  just flew into;
- the per-sync client upload budget.

## Also open

Longer-running items are in `plans/README.md` (collider PoC; in-game verification of detail tiers and
automatic promotion) and the "Next session" list in `AGENTS.md`.

## 4. What one promotion actually costs, and the pacing math that ignores it — RESOLVED (#17)

The pacer now charges a start for the chunk-steps it causes (`ProxyChunkGate.stepsToPromote`, holder statuses
of its FULL square and the pyramid), scaled by `chunkCostMs` as the price of an untouched neighbourhood; the
knob is what the pacer charges again. Measured by `aerogel.footprint`: void 1387 steps vs a frontier 420, CPU
15-19 s vs 2-6 s, prediction exact. The record below is kept as the history.

Measured (`aerogel.footprint`, 2026-10-15): one promotion fully generates its 5x5 (25 chunks) and raises a
partial pyramid around it — ring 3 `initialize_light`, ring 4 `carvers`, ring 5 `biomes`, rings 6-13
`structure_starts`, nothing beyond ring 13 — so ~728 chunks are touched by one request.

Two things follow that the code does not account for:

- **The pacer charges one start's cost while a start generates up to 25 chunks plus the pyramid.** `maxInFlight`
  bounds how many sites convert per tick, not how much generation each conversion brings. Tuning
  `promote concurrency/budget` therefore does not bound the work the way the docs implied.
- **`chunkCostMs` is not what the pacer charges.** `processQueue` charges `ProxyChunkGate.postCostMs` (an EMA of
  the measured finish cost, seeded at 5 ms); the `chunkCostMs` config value and its `/aerogel promote cost` knob
  are only read back into the status line. Either the knob should go or the pacer should use it — worth deciding
  before anyone tunes it.

The pyramid's dependency path (`ChunkMap.getChunkRangeFuture` → `scheduleChunkGenerationTask`) deliberately goes
ungated, so this is the accepted price of not intercepting vanilla's dependency generation, not a bug. It is
filed so the gating logic can be made to account for the pyramid and its flat top.

## 5. A mid-generation chunk reads as real

`ProxyChunkGate.loadedNow` (used by `isProxy` and `isLoadedReal`) asks `ServerChunkCache.getChunkNow`, which
NeoForge patches to return `chunkHolder.currentlyLoading` for a chunk that is merely *being* generated. So a
chunk with no data yet answers "vanilla has it loaded", and `isProxy` returns false for it on the reasoning that
a vanilla-loaded chunk is real. It is why a partially generated neighbour counted as real in the first
footprint measurement, and why that probe reads holder statuses instead.

Not a one-line fix: tightening `loadedNow` makes `isProxy` report true for a position whose promotion is in
flight, which changes which path serves reads during a promotion (`isPromoting` vs the placeholder) — verified
behaviour, so it needs its own change and a re-run of the gate probes. Recorded as a comment at the accessor.

**Measured (`aerogel.midgen`, 2026-10-20): a mid-generation neighbour does not stall a read.** The premise is
narrower than stated: NeoForge sets `currentlyLoading` only inside the FULL step's own main-thread work
(`ChunkStatusTasks.full`: `runPostLoad`, block-entity registration, `ChunkEvent.Load`), so `getChunkNow` is null
for a chunk that is merely generating. A partial neighbour therefore stays a proxy and gets the placeholder (a
peek gets `null`) unless it is already in memory at the requested status. Over one promotion and its settle — 80
ticks, ~67k timed main-thread reads on its 5x5 and pyramid rings 3-6 through `getChunk(FULL, false/true)`,
`getChunk(STRUCTURE_REFERENCES)`, `getChunkNow` and `Level.getBlockState`, ~12k of them on partial chunks — no
read went through vanilla's future chain and no chunk task ran inside a read; per-path maxima 0.1-1.5 ms, one
first-tick 18.5 ms `STRUCTURE_REFERENCES` read that returned an already available chunk. Inside the 24 load
windows, where `getChunkNow` does run ahead of FULL, reads of the loading chunk's 3x3 took at most 0.05 ms.
Sabotaged so that any started holder reads as loaded (the premise above), the same probe saw 168 chunks generated
inside its reads and a 1.08 s `getChunk(FULL, true)`. No production change was made.

## 6. Drain-rate numbers — RESOLVED, and the answer was not a number

Measured (`aerogel.rate`, 2026-10-15): the drain's own starts do not move smoothed tick time near the brake
(peaks ~6 ms against 35 ms; at an injected load the brake blocked 259 of 260 ticks and the starvation floor was
the only start that still got through). What does move is the **adaptive backlog signal**, because a promotion's
neighbourhood work is real main-thread chunk tasks — measured peaks 92 and 576 against `adaptive backlog` 64. So
"the drain stays under the adaptive activate thresholds" is true for tick time and false for backlog, and the
backlog half is inherent to promotion rather than a pacing failure. Recorded in
`plans/promotion-and-gating-rewrite.md`.

