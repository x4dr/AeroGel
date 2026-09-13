# AeroGel — surface sampling & streaming measurements

Live measurements from v4 onward: the production sparse sampler, streaming/range, tiers/biomes/worker pool,
and the virtual-proxy gate. The superseded naive/batched PoC (v1–v3) is in `work_log.md`.

## v4 — sparse hinted surface sampler (2026-09-12)

**Insight.** Every mesh point a chunk needs lies on the density cell lattice in xz (chunk corners are
multiples of 16, edge midpoints multiples of 8, centre `+8` — all multiples of `cellW=4`). Vanilla's
trilinear interpolation at an exact lattice xz corner reduces to the single corner value, so the only
interpolation is vertical and linear between two `cellH=8` y-levels. A pointwise scan at those y-levels
therefore reproduces vanilla's interpolated height **exactly** — no `NoiseChunk`, no ~800-eval full lattice.
This is why "4 corners vs 25 corners is the same cost" was true only *inside* the NoiseChunk strategy; the
strategy itself was the waste.

`PointSurfaceSampler` (topology-flexible: `FIVE` = corners+centre, 8×8 triangles; `NINE` = 3×3 grid, 8×8
quads) caches points globally by world `(x,z)` (a corner is shared by up to four chunks) and scans each point
**bidirectionally** from a neighbour/centre hint. Bench, same chunks as the earlier PoC:

```
REAL full gen baseline (this run, warm/disk-loaded):  13.2 ms/chunk  (cold ~250-550)

sampler                     cold ev/chunk  cold ms   sweep ev/chunk  sweep ms   max diff vs batched 5x5
batched NoiseChunk (old)    ~800           3.87      —               —          0
sparse 5-point              16.4           0.326     7.15            0.114      2
sparse 9-point              23.8           0.479     14.06           0.223      0
```

Reading:
1. **~34× (5-point) / ~17× (9-point) faster** than the batched sampler, ~50–100× fewer evals. Both beat the
   architecture target of ~0.2 ms/chunk. At the ~2000 fresh chunks/s a radius-12 sweep demands, 5-point is
   ~0.23 CPU-s/s and 9-point ~0.45 CPU-s/s — **one thread keeps up** (the old sampler needed ~8).
2. "cold" = cache cleared per chunk (no cross-chunk sharing, worst case); "sweep" = a mover walking `+x`,
   sharing corners with the previous chunk. A real 2D window shares corners 4 ways, so in-game amortized cost
   should be lower still.
3. The 5-point max-2 diff is an overhang/cave edge case: the bidirectional scan can settle on a solid below
   an air pocket instead of the topmost solid. The 9-point grid (edge midpoints give the centre a better
   hint) matches the batched reference exactly.

## v5 — wired into production + persistence hole + radius/upload (2026-09-12 late)

The sparse sampler is the production path (`OffThreadSampler` → `PointSurfaceSampler`), with both topologies
wired end-to-end (`ProxyTopology`, per-batch topology byte, renderer/MeshMath/collision both shapes) and a
live `/aerogel detail near [5|9]` switch (default 9). In-game: fast flight smooth, proxies mostly keep up,
standing/collision good on both.

**Persistence hole found by the probe (and fixed, now moot).** A `getChunk(FULL)` pulled in a ~22×22
neighbourhood of dependency chunks that only reached `structure_starts`/`biomes`; the old
`ProxyChunkGenerator` only tagged stubs in `fillFromNoise`, so those dependencies were serialized as empty
chunks (~500 per region). Both save guards were changed to refuse **any all-air chunk while the proxy is
active**. Superseded by the 2026-09-13 gate design (save guards deleted; vanilla persists partial chunks).

**Throughput / range.** The old `MAX_SUBMIT_PER_TICK=64` (=1280/s) was *below* the ~2000/s a radius-12 sweep
demands — that was the "sometimes lagging behind" (not the sampler). Now 512/tick, 4096 pending, 1024 drain.
`rebuildWanted` queues only the leading edge (O(r)/cross, 200k cap), so a large radius cannot stall the tick.
Radius is live-tunable (`/aerogel detail radius <4-1000>`, default 20). Scaling forecast lives in AGENTS.md.

**Client initial-fill trickle.** Login / range change / teleport queue the whole window server-side, and the
client uploaded only 64 chunk buffers per sync (`MAX_UPLOADS_PER_SYNC`) to avoid a driver stall (~112 ms for
625 buffers). In flight only the thin leading edge arrives, so it popped in instantly. Changed to a
**wall-time budget (2 ms, hard cap 512) per sync** (`overUploadBudget`).

## v6 — tiers + biome colours + worker pool (2026-09-12 late-2)

- **Worker pool.** `SamplerPool` owns N `OffThreadSampler`s; chunks routed by 8x8-chunk region (neighbours
  share the corner cache). `/aerogel detail threads <n>` (default 1). Removes the single-thread r≈32 ceiling;
  r=100 wants ~3 workers (see AGENTS forecast).
- **Detail tiers.** `ProxyTopology` = nested 5/9/25/81 (5 = corners+centre, else n×n); tier id = sample
  count. Server samples `nearTier` within `nearDistance` (default 32), `farTier` (default 5) beyond; a
  20-tick promotion sweep re-samples entering the near band (point cache makes the extra points cheap).
  Client bakes each chunk at **exactly the tier the server sent**, so the wireframe matches the geometry
  and promotion is a pop. Cost model: 5 ≈ 5 samples vs 9 ≈ 9 → ~44% less far-band sampling; the biome
  probe is tier-independent.
- **Biome colours.** One `getNoiseBiome` probe/chunk (≈6 evals) at the centre surface y + a `Biome -> block`
  cache; **biome registry id + block-state id** on the wire (the tint is computed client-side, see v7).
  `stream perf` at radius 20 measured ~0.11 ms/chunk with a near-empty backlog (one thread is not the
  bottleneck at that radius).

## v7 — late-3 fixes (implemented 2026-09-12)

- **Black biome root cause.** `GrassColor`/`FoliageColor` read a static pixel array filled only by the
  client's colormap reload listener, so on a dedicated server `Biome.getGrassColor` returns **0 = black**.
  Fix: the server streams the biome registry id; the client computes the tint (`BiomeColors`).
- **Textures.** The renderer draws the representative block's UP-face sprite (`POSITION_TEX_COLOR` + block
  atlas, cached per `BlockState`), tinted client-side. Leaves use the biome **foliage** colour and sit over
  a grass underlay (cutout holes show grass, not the void). Ocean/river chunks use **sand** as their land
  fallback; water is per cell by height, so islands/mountains above sea level are not water.
- **F3.** The placeholder (`EmptyLevelChunk`) carries the sampled biome, so the client chunk reports it — no
  client-side F3 patch.
- **Missing rows.** The client pruned leading-edge meshes at its draw radius while the server (larger radius)
  would not resend; and it only re-synced every 250 ms. Store now prunes at `STORE_RADIUS=256` and a
  chunk-cross forces a sync.
- **Naming.** User-facing tier names are `5`/`9`/`25`/`81` (the parser still accepts `five`/`nine`).

## v8 — virtual-proxy gate + client render integration (2026-09-13, verified in-game)

The backwards-reset stub design is gone (see `proxy-chunk-state-machine.md`). A proxy is now a **virtual
chunk**: `server/ProxyChunkGate` stops its own FULL task, serves reads from an `EmptyLevelChunk` placeholder,
and promotes forward. The client still renders the streamed mesh; the hard part was that vanilla's chunk
renderer assumes **contiguous client chunks**, and a proxy has no client `LevelChunk`/`RenderSection`. Three
separate vanilla behaviours dropped chunks, all fixed client-side (`ClientFeatures.neighbors`):

- **Parked real chunks.** `RenderSection.hasAllNeighbors()` returns true only within 24 blocks, else it
  requires the four neighbour chunks to exist client-side (`doesChunkExistAt`). A real chunk next to a proxy
  failed this past 24 blocks: `SectionOcclusionGraph` parked it in `chunksWaitingForNeighbors` and its
  rebuild was cancelled. Fix: a position AeroGel has a proxy mesh for counts as an existing chunk.
- **Blocked occlusion walk.** A proxy section is never compiled, so `getCompiled()` is `UNCOMPILED`, whose
  `facesCanSeeEachother` is false; `runUpdates`'s smart-cull uses the current section's compiled visibility
  to decide propagation, so the walk stopped at any proxy. Fix: report a proxy section as `CompiledSection.
  EMPTY` (transparent).
- **Proxy hide one chunk early.** The proxy hid when the real chunk was compiled, but compiled ≠ drawn
  (frustum/occlusion culling). Fix: hide only when the chunk is in `LevelRenderer.visibleSections`, the
  renderer's actual drawn set. `ViewArea.getRenderSectionAt` wraps its index, so the compiled check also
  requires the slot's origin to match.

Also: the reconciliation requested meshes only for chunks with no real chunk, so a real-but-uncompiled chunk
had no proxy — it now requests whenever `isRendered` is false. Proxy water at sea level is sunk 0.15
(`WATER_SINK`; real surface at `seaLevel-0.125`) so it sits just below the real water surface.

The earlier `proxy holes` / `mesh request` / `gate perf` diagnostics were removed after use.
