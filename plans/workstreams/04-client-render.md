# WS-04 — Client render/store: per-dimension, memory, reload, reconciliation cost

**Prereq:** none strictly, but WS-01 changes the server wire payloads only if it
changes them — assume the `MeshBatchPayload` layout is unchanged. If WS-01
changes it, coordinate via the orchestrator.
**Read only:** `client/ProxyRenderer.java`, `client/ClientMeshStore.java`,
`client/ClientHandlers.java`, `client/BiomeColors.java`,
`client/ClientFeatures.java`, `common/ProxyChunkUtil.java`,
`common/MeshMath.java`, `common/ProxyTopology.java`, `mixin/RenderSectionNeighborsMixin.java`,
`mixin/LevelRendererAccessor.java`, `mixin/ViewAreaInvoker.java`. Do not edit the
mixin files (WS-01 owns them) — note needed changes in your report.

## Tasks

### 1. Per-dimension renderer constants (the #5 requirement)
`ProxyRenderer` hardcodes overworld geometry: `MIN_Y = -64`, `SEA_LEVEL = 63`
(`:91-92`), and `WATER_TINT`. The nether/end have different build ranges and no
water sea level, so walls currently descend below the world and cells are
mis-classified as water.
- Read the build range from `ClientLevel` (`getMinBuildHeight()` /
  `getMaxBuildHeight()`) instead of `MIN_Y`.
- Decide sea-level/water handling per dimension:
  - overworld: 63 (keep).
  - nether: there is no water; treat "below the surface" cells as lava (or no
    special water sprite). Do **not** use the overworld water tint.
  - end: no sea level; islands over the void.
  Keep it simple and documented; the user accepts rough visuals for non-overworld.
- `ClientMeshStore.Mesh` carries no dimension. Meshes are cleared on dimension
  change in `ClientHandlers` (`:69-74`), so the renderer can use the current
  `ClientLevel`'s constants safely — verify and document that invariant.

### 2. Void base plane
`ensureVoidQuad` (`:620-644`) builds one quad at **y=0** sized to the view and
draws it under the camera. Decide per dimension:
- overworld: keep (hides the void below the proxy surface).
- nether: y=0 is above the floor; the quad may be visible through gaps or wrong.
- end: the base plane is exactly what the user called out ("spikes for the
  islands over the base plane ... check if we can remove the base plane").
Either derive the plane height from the dimension (min build height / a per-dim
value) or disable it where it does not help. Report what you chose and why.

### 3. Sprite cache invalidation on resource reload
`spriteCache` and `waterSprite` (`:144-145`) are never invalidated. After F3+T or
a resource-pack change the atlas is rebuilt and the cached sprites are stale
(garbage/black). Add a reload hook (e.g. a NeoForge client resource-reload
listener, or clear from `ClientSetup`) that clears `spriteCache`, `waterSprite`,
and any composite-sprite state. `LeafCompositeSource` rebuilds the atlas itself,
so only the Java-side caches need clearing.

### 4. Client memory
- `STORE_RADIUS = 256` (`:102`) keeps meshes out to 256 chunks even though the
  default server radius is 20; `proxyPositions` is a **boxed `Long`**
  `ConcurrentHashMap.newKeySet()` (`ClientMeshStore.java:27`). At a large server
  radius this is hundreds of thousands of boxed longs.
  - Consider a primitive concurrent set (or a `LongOpenHashSet` guarded by a
    lock / copy-on-write) for `proxyPositions`, or at minimum cap it.
  - Re-evaluate `STORE_RADIUS`; it exists so a chunk the server won't resend can
    be re-shown when it scrolls back. Make it configurable or derive from the
    server radius if it is communicated (it is not today — note that).
- `syncChunks` calls `isRendered` per mesh per sync, and `isReal` runs
  `ProxyChunkUtil.isAirStub` (loops ~24 sections) each time (`:701-704`). Cache
  the "real / air-stub" verdict per chunk and invalidate when the client chunk
  loads/unloads (or at least memoise for the duration of one sync).

### 5. Reconciliation cost + dormant behaviour
`ClientHandlers` reconciliation (`:87-118`) builds a `HashMap` over up to 97² =
9409 cells and sorts it **once per second even when `ClientMeshStore.size()==0`**,
and can request up to 128 chunks the server will then sample. On a normal vanilla
client this can make the server sample proxies for a player who never opted in.
- Early-out when the store is empty and `!ClientFeatures.travel` (or whatever the
  orchestrator decides the dormant predicate is). Coordinate: the user said
  "players requesting streaming is a reason for streaming to happen", so if the
  client has proxies on it *is* requesting — the concern is only the per-second
  O(r²) scan when there is nothing to reconcile. At minimum skip the scan when
  `ClientMeshStore.size()==0` and the last request returned nothing.
- Avoid allocating the `HashMap` + `ArrayList` + sort each second; reuse buffers
  or use a bounded nearest-first scan without the sort.
- `ProxyRenderer.isRendered` is used here; see task 4.

### 6. GL state + dead code
- `ProxyRenderer.render` (`:358-407`) sets shader/cull/blend state but only has a
  `finally` for perf counters. Wrap the GL state changes so an exception mid-draw
  does not leave cull/blend/shader corrupted.
- Remove dead code (`ClientFeatures.anim` defaults false but the morph path is
  always compiled — keep it if it is the documented pop-hiding; just confirm).

## Verify
`JAVA_HOME=... ./gradlew :compileJava` (client classes compile on the common
classpath; `:assemble` if unsure).

## Report
- Per-dimension constants chosen for overworld/nether/end.
- Void-plane decision.
- Reload hook used.
- Memory before/after numbers.
- Reconciliation early-out predicate.
- Tradeoffs.
