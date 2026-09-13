# AeroGel Architecture

Status: agreed design, updated after the Aeronautics/Sable investigation
(`plans/investigations/aeronautics-sable-physics.md`). Goal: fast travel (Create Aeronautics planes,
elytra, anything) must not choke the server/client on full chunk generation. Works in **any NeoForge
pack**; Create/Aeronautics/Sable are **optional** (integration only when present).

## Problem

Flying at ~120 blocks/s (≈8 chunks/s) sweeps ~10 chunks wide ≈ **80 chunks/s**. Vanilla materializes each
swept chunk through the full pipeline (noise → caves → features → structures → lighting → serialization →
network send → client mesh), ~50–200 ms of server + client work per chunk. That is the entire lag: the game
cannot run a real chunk pipeline at 80 chunks/s. This is a **vanilla problem** (chunk loading was never made
for fast movers), not an Aeronautics problem.

## Confirmed model (from investigation)

- A flying contraption is a normal object over normal terrain; the ground below is ordinary overworld
  chunks rendered/streamed the vanilla way. Proxy terrain therefore targets the **normal chunk path**, the
  same one an elytra flyer uses. Nothing special about Create/Aeronautics rendering.
- Chunk tickets around a plane only cover the **hull's current footprint** — no forward corridor exists,
  and nothing pre-generates ahead. Player aboard → synchronous FULL chunk gen exactly where the nose
  arrives (the stutter). This is the vanilla failure mode, triggered by any fast mover.
- Plane collision is **Rapier-vs-real-chunk-voxels** (chunk sections uploaded as colliders each tick). There
  is **no height-sampling funnel** to redirect. So AeroGel must **supply proxy ground as collider geometry**
  near the hull — same data it renders.
- Everything (gen, physics, tickets) runs on the one server thread at 20 TPS — no async physics to hide
  behind. That's precisely what we circumvent by never running full gen in transit.

## Design (agreed)

### Core idea
**Intercept chunk generation itself.** Whenever the game wants a full chunk in transit, we instead supply a
**super-fast low-poly proxy** — never run the full pipeline. Real generation only proceeds when a player
**lingers** or the proxy **collider is hit** (landing). Tune later.

- The **server** samples the worldgen *density/noise router* on a coarse grid per chunk to get a surface
  "hull" (terrain height + waterline), WITHOUT the chunk pipeline (no block storage, caves/features/
  structures, lighting, full-section packets). Probe target: **16×16 points per chunk ≈ 1 ms** (or coarser
  to start: 1×/4×/16× points).
- The server **streams** that tiny payload to clients (≈16 heights × 2 B ≈ 40 B/chunk; at 80 ch/s ≈ 3 KB/s —
  negligible). More detail (finer mesh, biome color) is streamed progressively **only if budget allows**.
- The **client** renders the proxy as a **separate low-detail quad layer** (one quad per cell, corner
  heights, flat shading). Not a vanilla chunk, never a chunk request, not Distant Horizons. "Show nothing"
  is an acceptable fallback if no proxy data yet.
- The **same low-poly surface** is pushed into server physics as coarse colliders **around/near the hull**
  (only what the physics engine actually queries — not the whole corridor).

### Why server-authoritative + streaming (NOT client seed-sampling)
- Collision/physics run server-side; the client has no physics to trust.
- The server must sample heights anyway for collision + promotion → it is already the sampler.
- Multiplayer never sends the seed or worldgen settings to clients; client re-sampling from a seed would
  require replicating the server's full worldgen stack (mods/datapacks) → brittle, subtle regional drift.
- Therefore the server streams coarse heights; the client renders them. Single source of truth. Tradeoff
  (accepted): if the server is too slow to stream, the client sees void until it catches up — the safe
  failure direction.

### Scope (all fast movers, not just planes)
- elytra, creative flight, Create trains, Aeronautics planes, whatever. Same system.
- Create/Aeronautics-specific code is additive and loaded only when those mods are present (optional deps).
- This is why the mod is safe in "any NeoForge config" — it degrades to a general fast-travel helper.

### Budget
- Target ~**0.2 ms/chunk** server-side for proxy sampling; accept up to ~1 ms at high density for PoC.
  80 ch/s × 0.2 ms = 16 ms/s ≈ ~1.6% of a server thread. Full gen is ~50–200 ms/chunk — orders cheaper.
- Self-calibrating detail: measure actual per-chunk cost against a rolling budget; raise/lower detail tier
  (cell size, color/biome, surface bits). Prioritize terrain near-and-in-front of the mover.

### Promotion rules (rough, tune later)
- Full generation runs only when: the mover **lingers** (> ~10 s in a chunk neighborhood), or the
  **low-poly collider is contacted** (landing/descent) — promote the landing area at altitude, before it
  matters.

### Accepted glitches (feature, not bug)
- Mover can stop a few blocks above true ground, then settle/fall on promotion.
- Coarse-surface mismatch → nudge-up / teleport-up until clear.
- Pop-in where real chunks replace proxy on promotion. Terrain visibly "resolves" as you slow.

### Out of scope (v0)
- Persistence/caching of proxy data (regenerate per pass; cache later).
- Biome fidelity / features / mob AI / redstone in transit chunks (proxy chunks behave as ungenerated).
- The Nether/End unless explicitly enabled per dimension.
- Client-side seed sampling (parked).
- Pulling Distant Horizons LOD data for finer granularity/color (parked until PoC works; DH already has
  a coarse-terrain mesh we might later reuse/feed).

## Open items / PoCs

0. **Off-thread sampling — DONE, pooled:** `SamplerPool` owns N `OffThreadSampler` workers (each own
   `RandomState`/`PointSurfaceSampler`/`BiomeTint`); main thread only submits `(pos, tier)` and drains.
   `PointSurfaceSampler` — sparse, hint-driven pointwise evals on the density cell lattice, ~7 evals/chunk
   (5-point) / ~14 (9-point), ~0.1–0.2 ms (see measurements.md v4); no `NoiseChunk`/full lattice. Tiers and
   worker count are live via `/aerogel detail`.
1. **Seam rendering against REAL terrain — DONE (user-verified):** walls drawn wherever the neighbour is NOT
   proxy-rendered (real / unloaded / missing), with `SEAM_EPS` inward offset.
2. **Noise sampling PoC — DONE.** Production is `PointSurfaceSampler`; the naive/batched PoC samplers were
   deleted after their numbers were recorded (measurements v1–v3, work_log).
3. **Collider PoC — crude only:** the player clamp fakes standing. Still to do: feed the low-poly surface
   into the Sable Rapier path (`RapierPhysicsPipeline.handleChunkSectionAddition` / VoxelColliderData) and
   vanilla entity collision; verify it stops a fast hull without real chunks.
4. **Proxy payload + handshake — DONE** (`aerogel:mesh_batch` + `aerogel:mesh_request`).
5. **Chunk lifecycle — DONE (verified in-game):** `server/ProxyChunkGate` is the whole state machine. It
   gates `ChunkHolder.updateFutures` so a proxy never builds its own FULL task, classifies disk-FULL vs
   proxy with a cheap EMPTY probe (the persisted status is the source of truth), serves reads from an
   `EmptyLevelChunk` placeholder, and promotes by adding to an allow-list and forcing the FULL future —
   forward generation from the chunk's persisted status. **No backwards reset, no `canResetSafely` gate, no
   save guards**: vanilla persists partial chunks, so cross-border features survive. The
   `ProxyChunkGenerator` / `ChunkRegenerator` / save guards / `WorldGenRegionWriteGuardMixin` / `nogen`
   machinery is **deleted**.
   **Automatic promotion** is `PromotionManager`: one cumulative counter per chunk whose threshold interpolates
   with distance, `t(d) = min + (d/maxRange)*(max - min)` (endpoints load-adaptive, idle 10..30 s / busy
   20..60 s, `maxRange` auto view-distance+2), plus touching the surface and block writes queueing immediately.
   The queue is drained immediate-first then nearest-first under a generation-time budget (a token bucket
   refilled at `maxGenFraction` ms/ms, default half the wall time, charged `chunkCostMs` per promotion);
   immediate promotions bypass the budget/hard gate but still debit it, and `maxInFlight` bounds concurrency.
   **Adaptive enforcement** (`server/AdaptiveMode`): the gate is enforced globally only while the chunk-task
   backlog or tick time is high (and no linger remains), otherwise a per-player `travel` opt-in gates locally
   and an opted-out player forces vanilla; the client `proxy` switch controls mesh streaming per player.
   **Write = promote:** `LevelWriteGuardMixin` cancels+records a runtime write
   into a proxy and replays it after promotion; `FlowingFluidMixin` cancels fluid spread into proxies.
   All knobs persist in `config/aerogel-common.toml` / `config/aerogel-client.toml`.
   **Client render integration:** proxies have no client `LevelChunk`, so vanilla's contiguous-chunk
   assumptions dropped chunks; `RenderSectionNeighborsMixin` (a proxy counts as an existing chunk for
   `hasAllNeighbors`, and a proxy section reports transparent `getCompiled`) plus an exact proxy hide keyed
   on `LevelRenderer.visibleSections` fix it — see `proxy-chunk-state-machine.md` §Client render integration.
6. **Biome colors + textures on the mesh — DONE:** one `BiomeSource.getNoiseBiome` probe per chunk at the
   centre surface y (the worker's own `Climate.Sampler`), cached `Biome -> representative BlockState` (grass
   default; snow / sand / red-sand / leaves / stone special-cased), **biome registry id + block-state id**
   per chunk on the wire. The **client** computes the tint (`BiomeColors`) because the grass/foliage
   colormaps only exist client-side (server `Biome.getGrassColor` returns 0 = black — the "black biome" bug).
   The renderer draws the block's top sprite (`POSITION_TEX_COLOR` + block atlas), tinted client-side. In the
   gate design the placeholder (`EmptyLevelChunk`) carries the sampled biome for server-side reads; proxy
   water at sea level is sunk `WATER_SINK=0.15` (real surface at `seaLevel-0.125`) so it does not z-fight the
   real water.
7. **Detail tiers — DONE:** `ProxyTopology` is a nested family (5 / 9 / 25 / 81). The server samples
   `nearTier` within `nearDistance` and `farTier` beyond, with a promotion sweep; the client bakes each chunk
   at **exactly the tier the server sent**, so the wireframe matches the geometry and a promotion is a pop.
   **Worker pool DONE:** `SamplerPool` (region-sharded N workers, own
   `RandomState`/`PointSurfaceSampler`/`BiomeTint`).

## Verify later (in-game tests, not design work)

- **Rider coordinate space [UNCONFIRMED]:** whether a player riding a Sable sublevel has their vanilla chunk
  tickets driven from real-world position or from the parked plot position (~20M away). Affects which
  machinery supplies the transit corridor. Debug world + `/sable` + `debug_draw_loaded_chunks`.
- **FULL vs block-ticking:** exactly when the synchronous `getChunk(FULL)` stall fires vs a chunk already
  block-ticking mid-generation.
- **Collider contact timing:** whether proxy colliders stop a fast hull before it clips, at what margin.

## Priority ordering (fastness above all)
1. Server proxy sampling cost ≪ full-gen (verify ~0.2–1 ms target at high density).
2. Transit chunks must NOT go through the full pipeline at speed.
3. Client must not stutter (quad layer, no full-chunk mesh compile).
4. Landing/collision correctness (safety over fidelity).
