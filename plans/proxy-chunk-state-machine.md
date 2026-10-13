# AeroGel — Virtual Proxy Chunks (replaces the stub / backwards-reset architecture)

Status: **IMPLEMENTED and verified in-game (2026-09-13).** All four headless probes PASS; the gate and proxy
rendering are confirmed in-game. See `# IMPLEMENTATION STATUS — READ THIS FIRST` immediately below for the
exact file map and the vanilla signatures you need. This supersedes both the "proxy = all-air `LevelChunk`
stub + `ChunkRegenerator` backwards reset" design **and** the earlier draft's "cap
`GenerationChunkHolder.applyStep` at `INITIALIZE_LIGHT`" mechanism. The mechanism is now an **own-task
gate**: the player ticket never creates a generation task for a proxy, while dependency generation runs
vanilla. Read `# Research done` at the end before touching anything — it records the source-level evidence
for every claim here. The history of the superseded designs is in `work_log.md`.

---

# IMPLEMENTATION STATUS — READ THIS FIRST

## ⚠️ DO NOT RE-READ VANILLA SOURCES

The `# Research done` section below already has every vanilla line number and claim. **You do not need to
open any `net/minecraft/**` file.** If you do, you will burn half your context for nothing. The only vanilla
facts you might need are the **exact signatures** reproduced in "Signatures already verified" below, and the
mixin targets are already written and **compile**. If a mixin fails at runtime, the crash tells you the
target; only then grep the one file it names.

Also do **not** re-read the whole mod tree. The file map below is the complete set of touched files.

## What was done (2026-09-13)

Build: `./gradlew :compileJava` → **BUILD SUCCESSFUL**. Since then: `:assemble` deployed, all four headless
probes PASS, and the gate + proxy rendering verified in-game (2026-09-13).

**Added**
- `server/ProxyChunkGate.java` — the whole state machine:
  - sets `allowed` / `real` / `proxy` / `probing` (all `LongOpenHashSet`, server-thread only).
  - `onUpdateFutures(holder, chunkMap)` — called at `ChunkHolder.updateFutures` HEAD. Unclassified positions
    schedule the cheap EMPTY probe (`holder.scheduleChunkGenerationTask(ChunkStatus.EMPTY, map)`) and mark
    `probing`; the completion is pushed to a `ConcurrentLinkedQueue<Runnable> deferred` so it can never
    re-enter `updateFutures`.
  - `finishProbe` — `holder.getPersistedStatus().isOrAfter(FULL)` → `real` + `forcePrepare`; else `proxy`.
  - `forcePrepare(holder, map)` — resets the three cached futures to `ChunkHolderAccessor.unloadedFuture()`,
    sets `oldTicketLevel = ChunkLevel.MAX_LEVEL`, calls `invokeUpdateFutures(map, server)`.
  - `promote(level, pos[, onComplete])` — add to `allowed`, `forcePrepare`, then
    `holder.getFullChunkFuture().whenComplete(...)` → `pushOutOfTerrain` + `resend` + callback.
  - `isGated(pos)` / `isRealOrAllowed(pos)` / `isProxy(pos)` / `isAllowed(pos)` / `isActive()`.
  - placeholder cache (`Long2ObjectOpenHashMap<EmptyLevelChunk>`), `placeholder(level,x,z)`, biome from
    `ProxyStreamer.biomeHolder(pos)` (fallback plains), pruned to `radius+4` every 200 ticks.
  - `tick(server)` — drains `deferred` (cap 4096) then prunes placeholders.
- `mixin/ChunkHolderGateMixin.java` — `@Mixin(ChunkHolder.class)`:
  - `@Inject(method="updateFutures", at=HEAD)` → `ProxyChunkGate.onUpdateFutures(this, chunkMap)`.
  - three `@Redirect`s on the `ChunkMap.prepareAccessibleChunk` / `prepareTickingChunk` /
    `prepareEntityTickingChunk` calls; for `isGated(pos)` return `ChunkHolderAccessor.unloadedFuture()`.
- `mixin/ServerChunkCachePlaceholderMixin.java` — `@Inject(method="getChunk", at=HEAD, cancellable)`:
  only on the server thread; `getChunkNow != null` → fall through; `isRealOrAllowed` → fall through;
  else `cir.setReturnValue(ProxyChunkGate.placeholder(level,x,z))`.

**Modified**
- `server/ProxyStreamer.java` — generator swap **removed**; reads the real generator via
  `overworld.getChunkSource().getGenerator()`. `noGen`, `virtualChunk(s)`, `virtualBiome`, `isStubChunk`,
  `pruneStubs` deleted. `isActive()`/`isProxyPlaceholder()` now delegate to the gate. Added
  `biomeHolder(pos)`. `onServerTick` calls `ProxyChunkGate.tick(server)`. `onPlayerTick` uses
  `!ProxyChunkGate.isProxy(chunkPos)` instead of the all-air scan.
- `server/PromotionManager.java` — `processQueue` no longer calls `canResetSafely`/`ChunkRegenerator`; it
  calls `ProxyChunkGate.promote(level, pos, () -> onComplete(pos))`. `replayWrites` accepts a chunk as real
  when `getChunkNow != null` (no all-air check).
- `mixin/FlowingFluidMixin.java` — cancels spread when `ProxyChunkGate.isProxy(ChunkPos.asLong(pos))`.
- `AeroGel.java` — removed the `CHUNK_GENERATOR_TYPES` / `PROXY_CODEC` registration (deleted generator).
- `server/ServerHandlers.java` — removed the `nogen` switch; `/aerogel generate` now calls
  `ProxyChunkGate.promote`.
- `server/GenerationProbe.java`, `PromotionProbe.java`, `PromotionStressProbe.java` — rewritten for the new
  model (placeholder/no-FULL detection, `ProxyChunkGate.promote`, `getChunkNow != null` as "real"). The old
  `RegenProbe` was folded into `PromotionProbe` (its push-up test moved there) and deleted.
- `aerogel.mixins.json` — now lists `ChunkHolderAccessor`, `ChunkHolderGateMixin`, `FlowingFluidMixin`,
  `LevelWriteGuardMixin`, `ServerChunkCachePlaceholderMixin`, `ServerGamePacketListenerImplMixin`.

**Deleted**
- `proxy/ProxyChunkGenerator.java`, `server/ChunkRegenerator.java`, `mixin/ChunkMapAccessor.java`,
  `mixin/ChunkMapNoGenMixin.java`, `mixin/ServerChunkCacheVirtualMixin.java`,
  `mixin/WorldGenRegionWriteGuardMixin.java`, `mixin/GenerationChunkHolderAccessor.java`,
  `mixin/ChunkMapSaveMixin.java`, `mixin/ChunkSerializerGuardMixin.java`.

**Kept deliberately**
- `common/ProxyChunkUtil.java` — still used by the **client** (`ClientHandlers`, `ProxyRenderer`) to tell a
  real client chunk from a proxy mesh hole. Do not delete it.
- `mixin/LevelWriteGuardMixin.java` — unchanged; write = promotion.
- `mixin/ChunkHolderAccessor.java` — unchanged; provides `unloadedFuture()`, the setters and
  `invokeUpdateFutures`.
- `mixin/ServerGamePacketListenerImplMixin.java` — fast-flight move check, untouched.

## Adaptive enforcement + smooth linger + generation budget + config (2026-09-13)

`PromotionManager` was reworked from a single load-adaptive linger radius into a smooth distance-interpolated
threshold over one cumulative per-chunk counter, and its rate limit from "concurrency + load-scaled cooldown +
hard gate" into a generation-time token bucket. A new `server/AdaptiveMode` decides whether the gate is
enforced at all. Nothing about the gate's mechanism or `ProxyChunkGate.promote` changed.

- **Adaptive enforcement.** `AdaptiveMode` turns the gate on globally while the main-thread chunk-task backlog
  (`ServerChunkCache.getPendingTasksCount()`) is ≥ `backlogActivate` (64) or smoothed tick time ≥
  `activateTickMs` (35 ms), and off only once both fall back below the deactivate values **and no linger
  counters remain** (so switching to vanilla never bursts instant generation). While not enforcing, a player
  whose client `travel` switch is on is still gated in their zone; an opted-out player forces vanilla locally
  (their leftovers are promoted immediately), and if the two overlap the opted-out zone wins. The client `proxy`
  switch controls whether that player receives meshes at all (per-player streaming). `ProxyChunkGate.isEngaged`
  is the zone test; `isGated`/`isProxy` layer the allowed/real classification on top, and `isProxy` also checks
  whether the chunk is actually loaded so a vanilla-generating chunk is never mistaken for a proxy.
- **Smooth linger.** A proxy chunk accrues one tick per tick a player is within `maxRange` (default
  `view-distance + 2` = 12 ch — the radius at which vanilla actually reaches FULL: the player ticket at
  `ChunkLevel.byStatus(ENTITY_TICKING)` propagates to `FULL` two chunks out). The threshold is
  `t(d) = min + (d/maxRange)*(max - min)` where `d` is the Chebyshev distance to the nearest opt-in player;
  `min`/`max` are each interpolated between idle and busy by `loadFactor` (idle 10..30 s, busy 20..60 s). So a
  chunk banked at the fringe promotes the moment it is walked into range. Real chunks are skipped.
- **Immediate promotion = vanilla generation.** Touching the surface (landing), a runtime block write, and the
  leftovers around an opted-out player are not queued: `PromotionManager.promoteImmediate` hands the chunk to
  `ProxyChunkGate.promote` the moment it is triggered, with no budget, hard gate or `maxInFlight`. The only
  guard is `inFlight`, so the same chunk is started once (a mass write starts one promotion per chunk, not one
  per write — after the first, the chunk is `allowed` and `isProxy` is false, so vanilla handles the rest).
  If the holder is not loaded yet, the trigger falls back to the linger queue rather than being lost.
- **Linger budget.** Only linger promotion is paced. `budgetMs` refills at `maxGenFraction` ms/ms (default
  0.5 → half the wall time), capped at `burstMs`; every linger start costs `chunkCostMs` (default 100) and is
  blocked while smoothed tick time ≥ `hardGateMs`. `maxInFlight` (default 2) bounds concurrent linger
  regenerations. `pickNext` takes the strictly nearest player's chunk.
- **Config.** `dev.aerogel.common.AeroGelConfig` (COMMON, `config/aerogel-common.toml`) holds every server
  knob; `dev.aerogel.client.AeroGelClientConfig` (CLIENT, `config/aerogel-client.toml`) holds the client
  switches + draw radius. Commands write the value back and `ModConfigSpec.save()`, so tuning persists.
  `AeroGelConfig.validate()` runs at server start and corrects cross-field violations (idle/busy tick time,
  adaptive activate/deactivate, backlog, linger ordering) with a warning, so a hand-edited file cannot produce
  NaN thresholds. The client syncs its persisted switch state to the server on join (bidirectional
  `ClientSwitchPayload`) so a bare `/aerogel switch <feature>` still flips the right way. The headless probes
  call `AdaptiveMode.debugAlwaysOn()` (non-persisting) so they still exercise the gate with no players online;
  they live in the `dev` source set and are in no published jar.

## Client render integration (2026-09-13, verified in-game)

Vanilla's chunk renderer assumes contiguous client chunks; a proxy has no client `LevelChunk`/`RenderSection`,
so three separate vanilla behaviours dropped real chunks or the proxies at the boundary. All patched
client-side, gated on `ClientFeatures.neighbors` (client mixin config `aerogel.mixins.json` `"client"`):

- `mixin/RenderSectionNeighborsMixin` (`SectionRenderDispatcher.RenderSection`):
  - `doesChunkExistAt` — return true for a position AeroGel has a proxy mesh for (concurrent
    `ClientMeshStore.proxyPositions` mirror; the main map is not thread-safe for the compile worker). Else a
    real chunk next to a proxy fails `hasAllNeighbors()` past 24 blocks and is parked in
    `chunksWaitingForNeighbors` + its rebuild cancelled.
  - `getCompiled` — report a proxy section as `CompiledSection.EMPTY` (transparent). A proxy is never
    compiled so it returns `UNCOMPILED`, whose `facesCanSeeEachother` is false; `runUpdates`'s smart-cull
    then stops the occlusion walk at the proxy, so real chunks beyond it are never reached.
- Proxy hide is exact (`ProxyRenderer.isRendered`): hide only when the real chunk is in
  `LevelRenderer.visibleSections` (the renderer's actual drawn set, via `mixin/LevelRendererAccessor`).
  Compiled is not enough — frustum/occlusion culling still drops chunks (the render-distance corners).
  `isCompiled` checks the `ViewArea` slot origin (`mixin/ViewAreaInvoker`) because `getRenderSectionAt`
  wraps its index and would otherwise return a different chunk's state.
- `ClientHandlers` reconciliation requests a mesh whenever `isRendered` is false, including a
  real-but-uncompiled/culled chunk; the earlier "skip real chunks" left permanent boundary gaps.
- Proxy water at sea level is sunk `WATER_SINK=0.15` (`emitTopGrid`/`fanLayer`); the real surface is at
  `seaLevel-0.125`, so this sits just below it and does not z-fight.

## Signatures already verified (do not re-open the source to check)

- `ChunkHolder.updateFutures(ChunkMap, Executor)` (protected); the three calls to redirect are
  `ChunkMap.prepareAccessibleChunk(ChunkHolder)`, `prepareTickingChunk(ChunkHolder)`,
  `prepareEntityTickingChunk(ChunkHolder)`, all returning `CompletableFuture<ChunkResult<LevelChunk>>`.
- `ChunkHolder.UNLOADED_LEVEL_CHUNK_FUTURE` is private — access via `ChunkHolderAccessor.unloadedFuture()`.
- `GenerationChunkHolder.scheduleChunkGenerationTask(ChunkStatus, ChunkMap)` returns
  `CompletableFuture<ChunkResult<ChunkAccess>>`; `getPersistedStatus()` is `@Nullable ChunkStatus`.
- `ChunkLevel.MAX_LEVEL`, `ChunkStatus.FULL`, `ChunkStatus.EMPTY` are public.
- `ServerChunkCache.getChunk(int,int,ChunkStatus,boolean)`, `getChunkNow(int,int)`, `getLevel()`,
  `getGenerator()`; `MinecraftServer.isSameThread()`; `ServerLevel.getServer()`.
- `Registry.getHolder(int)` → `Optional<Holder.Reference<Biome>>`.
- `ChunkPos.asLong(BlockPos)` exists.

## Known risks to watch (ranked)

1. **Classification probe cost.** `onUpdateFutures` starts an EMPTY probe for every newly-ticketed
   unclassified chunk (a full radius on login/teleport ≈ 1681 tasks + async disk reads). If that stalls a
   tick, add the plan §2.B region-header pre-filter (`RegionFile.hasChunk`) to skip the probe for fresh
   chunks, or a per-tick probe cap with a retry queue (note: `updateFutures` does not re-fire on its own).
2. **`forcePrepare` from the tick.** It runs `updateFutures` outside `runAllUpdates`, from
   `ServerTickEvent.Post`. That is the same pattern the old `ChunkRegenerator.refreshHolder` used and was
   safe, but confirm no `ConcurrentModificationException`/distance-manager complaint under the stress probe.
3. **Unclassified chunks read as placeholders.** The read intercept returns a placeholder for anything not
   `real`/`allowed`, including the one-tick window before classification. Expected; if a real chunk visibly
   fails to load, check the classification actually completes (`aerogel.gate` has no log yet — add one if
   needed). *Superseded 2026-10-13 (#3/#4):* a main-thread read classifies an unclassified position itself by
   an EMPTY load, so an on-disk FULL chunk loads real; only a read that would generate gets the placeholder.
4. **Placeholder is an `EmptyLevelChunk` reporting FULL.** Acceptable for now (plan §4); the read-only
   collision getter for landing/Sable is still the separate collider PoC.
5. **Partial proxies now persist** (save guards deleted). If an all-air EMPTY chunk ever hits disk, that is
   vanilla's own refusal; verify a partial proxy round-trips (plan §6 / test plan).

## Do-not-re-read list (the research already covers these)

`ChunkHolder`, `GenerationChunkHolder`, `ChunkGenerationTask`, `ChunkMap`, `ServerChunkCache`,
`DistanceManager`, `ChunkLevel`, `ChunkPyramid`, `ChunkStep`, `ChunkStatus`, `WorldGenRegion`,
`EmptyLevelChunk`, `Level`, `Registry`. Line numbers are in `# Research done` below.

## Design: generation stays vanilla; only the player ticket is gated

- **Default:** a never-generated chunk gets **no generation task from its own ticket**. Reads are served from
  an in-memory placeholder.
- **Dependency generation is untouched.** When a real (promoted) chunk generates, its task pulls neighbours
  through the normal pyramid via `applyStep`, running the **real** generator. A tree's leaves therefore land
  in the neighbour's live `ProtoChunk`, exactly as vanilla — and vanilla persists partial chunks, so they
  survive a restart (see §6).
- **Allow-list (= promotion):** remove the gate for that position; its FULL future is created and it
  generates **forward** from whatever status it already reached. No backwards move, no stubs.
- **Already-generated (disk) chunks are never gated.** They take vanilla's LOADING pyramid and surface
  normally.
- There is **no `claimed` set** — ownership is the default, the allow-list the exception.

## Mechanism

### 1. Own-task gate: the player ticket never generates a proxy

`ChunkHolder.updateFutures` (`ChunkHolder.java:260`) reacts to a ticket level ≥ FULL by calling
`prepareAccessibleChunk` (`:267`), `prepareTickingChunk` (`:280`) and `prepareEntityTickingChunk` (`:297`).
**Mixin it: for a proxy position, skip those three calls.** Leave `fullChunkFuture`/`tickingChunkFuture`/
`entityTickingChunkFuture` at the unloaded sentinels, so the chunk is never accessible/ticking and
`getChunkNow` returns null (`ServerChunkCache.java:173,192`).

**Why not the old `applyStep` cap.** `prepareAccessibleChunk` → `ChunkMap.getChunkRangeFuture`
(`ChunkMap.java:293`) → `scheduleChunkGenerationTask(FULL)` (`GenerationChunkHolder.java:42`). The task's
layer radii come from the *target* (`ChunkGenerationTask.getRadiusForLayer`, `:126`), so the proxy's own FULL
task still schedules EMPTY/STRUCTURE_STARTS out to the deep structure radius and the noise/surface/carvers/
features rings — the full fan-out — and only then does an `applyStep` cap refuse LIGHT. Capping `applyStep`
cannot stop a task that was already created and fanned out, and `applyStep` is also the dependency path, so
capping it breaks the cross-border generation we want. **The gate must be on task creation, not on steps.**

Dependency generation is `GenerationChunkHolder.applyStep` (`:60`), which only gates on
`isStatusDisallowed` (i.e. `highestAllowedStatus`). **Leave `highestAllowedStatus` vanilla**, so proxies are
generated as dependencies normally. `ChunkMap.acquireGeneration` (`:606`) requires an existing holder, so a
generation task can only touch ticketed chunks (a proxy with no ticket has no holder and cannot be pulled).

### 2. Classify proxy vs disk-FULL

At `updateFutures` time the chunk is not loaded, so `persistedStatus` is unknown. Two options:

**A. Two-phase probe (recommended).** For a candidate (not allow-listed, not known-real) schedule
`scheduleChunkGenerationTask(EMPTY)`. Target EMPTY has accumulated radius 0 (`ChunkGenerationTask.create`,
`:32`), so it is a single `applyStep(EMPTY)` → `ChunkMap.applyStep` EMPTY branch → `scheduleChunkLoad`
(`:548`) — one async disk read, no fan-out. On completion: if `persistedStatus.isOrAfter(FULL)`, mark
known-real and run the vanilla `prepareAccessibleChunk`, chaining it into `fullChunkFuture`; otherwise
complete `fullChunkFuture` with `UNLOADED_LEVEL_CHUNK` (proxy). Cache results per session (a `Long2ByteMap`
like `ChunkMap.chunkTypeCache`).

**B. Header pre-filter.** `RegionFile.hasChunk` (`:348`) is `getOffset(pos) != 0` — an in-memory lookup once
the region is open; `doesChunkExist` (`:220`) reads 5 bytes. Use it to skip the probe entirely for fresh
chunks. Do **not** use `ChunkMap.isExistingChunkFull` (`:778`) on the hot path — it does a blocking
`readChunk().join()` on a cache miss.

**No persistent DB.** The region header / persisted `Status` is the source of truth; a persistent DB is
redundant and stale-prone (map imports / world edits → false negatives; deleted chunks → false positives).
False negative = a real chunk hidden until lingered (acceptable, rare). False positive = a fresh chunk
generates when a player is near (fine if not en masse).

### 3. Central read intercept: `ServerChunkCache.getChunk(x, z, status, require)`

```
loaded = getChunkNow(x, z)
if (loaded != null)            -> return loaded                 // real/FULL, vanilla
else if (allowList/knownReal)  -> fall through to vanilla       // generate/load forward (promotion)
else                           -> return placeholder(pos)        // virtual proxy chunk
```

Covers `Level.getBlockState`, `getChunkForCollisions`, `Entity.setPosRaw`, dependency lookups, everything.
**Do not route a proxy through `getChunkFutureMainThread`** — it adds a `TicketType.UNKNOWN` ticket
(`ServerChunkCache.java:233`) and would create a task (`:248`).

### 4. Placeholder

- **Collision path** — `Level.getChunkForCollisions` returns a `BlockGetter`: return a **read-only proxy
  getter** that interpolates the sampled surface from the streamer's grid (real collision for landing).
  Cache per position; never mutate.
- **General `ChunkAccess` path** — `EmptyLevelChunk` (void air, biome holder for F3) for now; optionally a
  custom read-only `ChunkAccess` later to cut the ~8 KB/instance.
- Must **not** be a mutable chunk we write into (that reintroduces chunk updates + the save path).
- Prune the placeholder cache to the player radius (+margin).

### 5. Promotion

- Add the position to the allow-list; then **force the FULL future** — call `prepareAccessibleChunk`
  directly or force `updateFutures` to re-evaluate. Note `updateFutures`' `!flag && flag1` branch will *not*
  fire on its own, because `oldTicketLevel` already reads ≥ FULL.
- The proxy continues forward from its persisted status (FEATURES/INITIALIZE_LIGHT if a neighbour's task
  already ran it), so it does **not** re-run FEATURES — the features are already there.
- If the chunk is **not held**, the next request generates it real.
- No backwards move. This is the "a player walked near and vanilla took over" path.

### 6. Persistence — why cross-border features survive a restart

Vanilla persists **partial** chunks, so a proxy that received a neighbour's feature writes is written on
unload and reloaded at its partial status with the leaves:

- `ChunkMap.save` handles non-LEVELCHUNK (`:752-760`; refuses only EMPTY-without-structure-starts, or a
  disk-FULL chunk already present).
- `scheduleUnload` → `save(getLatestChunk())` (`:517`) has **no** type filter.
- Shutdown removes all tickets and ticks until `hasWork()` is false (`MinecraftServer.java:600-620`), so all
  chunks unload and save.
- `Status` tag: `ChunkSerializer.write` (`:295`), `read` (`:207-208`).

**Consequence: remove AeroGel's proxy save guards** (`ChunkMapSaveMixin`, `ChunkSerializerGuardMixin`). They
exist only to stop the old all-air FULL stubs; the new proxies are real partial ProtoChunks and vanilla
already refuses the all-air EMPTY case itself. Keep the guards only as a fallback if a regression appears.

### 7. Cleanup of the old machinery

- **Delete:** `ChunkRegenerator`, `canResetSafely`, `ProxyChunkGenerator` + the `ChunkMapAccessor`
  `worldGenContext` generator swap (restore the real generator — a leaked FULL task then just generates real,
  a safety net), `WorldGenRegionWriteGuardMixin`, `stubPositions`/`tagStub`/`isStub`, `ChunkMapNoGenMixin`,
  `ServerChunkCacheVirtualMixin`, `ProxyStreamer.virtualChunk`/`noGen`, `/aerogel switch nogen`, and the
  old-draft `applyStep` cap.
- **Keep / re-point:** `LevelWriteGuardMixin` (runtime writes on a proxy defer + promote), `FlowingFluidMixin`
  (no fluid spread into proxies), `PromotionManager`, the read intercept, the allow-list. Keep the save guards
  only as a fallback.
- `ProxyChunkGenerator` may be retained purely as a safety net for a leaked FULL task; with the own-task gate
  it should never run for a proxy.

## Clarifications

- **The gate is on the *own* task, not on `applyStep`.** Dependency generation must stay vanilla.
- **`highestAllowedStatus` stays vanilla.** Clamping it hides disk-FULL chunks from
  `getChunkIfPresent`/`getChunkNow` — the exact thing we must not do.
- **The proxy's FULL future is never created**, rather than completed UNLOADED. A permanently-absent future
  is the intended state.
- **Partial chunks are persisted by vanilla**; no write buffering / replay is needed.
- **Writes never schedule generation; only the ticket/task frontier does.** A feature write of radius 1 lands
  in a chunk that is at CARVERS (the CARVERS ring is scheduled one wider than the FEATURES ring), never in a
  chunk that is itself running FEATURES — so it cannot trigger a chain. See `# Research done` §2.

## Open questions / risks

- **Ticking:** a proxy never becomes block/entity-ticking, so no redstone, fluids, random ticks, block
  entities, mob AI or item/vehicle ticks until promotion. Player movement survives (packet-driven; the
  `nogen` run proved it); survival mechanics in the player's own chunk until promotion are unverified.
  Global time/weather, other dimensions/players, and **all already-FULL chunks are unaffected**.
- **Client chunk stream:** proxies are not sent as chunks; the client renders the proxy mesh. Verify no
  permanent holes (the earlier "skip stub sends" experiment). May need mesh-store reconciliation to own it.
- **Mods:** Sable/Create chunk queries get placeholders for proxies. Verify the Sable physics/collision path
  (this is the collider PoC).
- **`getChunkNow` callers** still return null for proxies; callers treat it as unloaded — verify none break.
- **F3 biome:** give the placeholder the sampled biome so F3 reads correctly.
- **Gate coverage:** every path that creates a FULL task for a proxy must be caught — the ticket path
  (`updateFutures`) and external `getChunk(FULL)` (intercept). A mod calling
  `scheduleChunkGenerationTask` directly would bypass the gate.
- **Promotion must force the future** even though `oldTicketLevel` already reads ≥ FULL (see §5).
- **`wasAccessibleSinceLastSave`** is set before the `prepare*` calls (`ChunkHolder.java:265`), so a proxy is
  marked save-worthy. Vanilla `save` refuses all-air EMPTY, so that is fine — just ensure any retained guards
  do not block partial proxies.

## Test plan

- **Headless:** a proxy never creates its own FULL task (assert no fan-out / no generation tasks for
  non-allow-listed positions); after allow-listing it reaches FULL; a disk-FULL chunk surfaces via the probe;
  a partial proxy round-trips through save → load; the read intercept returns the placeholder while
  `getChunkNow` returns null.
- **In-game:** fly with the gate active (no `nogen`) — transit chunks stay non-FULL, no watchdog; promote on
  linger and confirm forward generation **with features**; verify a tree spanning a promoted chunk and a
  proxy keeps its leaves after promotion **and after a server restart**; block-write promotion; landing;
  long-flight memory (placeholder cache pruned).

---

# Research done

Recorded 2026-09-13. Decompiled sources used: **NeoForge 21.1.249 / MC 1.21.1** (NeoForm
`1.21.1-20240808.144430`); line numbers below are from
`build/moddev/artifacts/neoforge-21.1.249-sources.jar` (unpacked at
`~/JustBuild/build/neoForm/neoFormJoined1.21.1-20240808.144430/steps/unzipSources/unpacked/`). This version
uses the 1.21.2-style chunk-status system (`net.minecraft.world.level.chunk.status.ChunkPyramid`,
`GenerationChunkHolder`, `ChunkGenerationTask`) — not the old `ChunkStatus` task list.

### 1. Tickets vs generation (the key model correction)

- Generation steps **never** add or raise ticket levels. Ticket levels come only from `DistanceManager`
  (`TicketType.PLAYER`/`FORCED`/`UNKNOWN`). `DistanceManager.getPlayerTicketLevel()` = `max(0, 31 - simDistance)`
  (`DistanceManager.java:250`), spread by the `ChunkTracker` min-graph to `MAX_LEVEL = 33 + RADIUS_AROUND_FULL_CHUNK`
  (`ChunkLevel.java:15`).
- A generation task can only reach a chunk that already has a holder: `ChunkMap.acquireGeneration` (`:606`)
  NPEs otherwise. So the player ticket is what makes neighbours *reachable*; it is not merely a trigger.
- The only read path that adds a ticket is `ServerChunkCache.getChunkFutureMainThread` (`:233`,
  `TicketType.UNKNOWN` when `require=true`), which is why the read intercept must not go through it.
- Consequence: "the player doesn't cause generation" is implemented as "the player ticket creates holders,
  but a proxy never gets an **own** task". A whitelisted chunk's task then drives neighbours via `applyStep`.

### 2. The pyramid, write radius, and why there is no infinite chain

- `ChunkPyramid.GENERATION_PYRAMID` (`ChunkPyramid.java:9-46`): `FEATURES` is
  `.addRequirement(STRUCTURE_STARTS, 8).addRequirement(CARVERS, 1).blockStateWriteRadius(1)`.
- `WorldGenRegion.setBlock` (`WorldGenRegion.java:276`) writes into the target's live chunk via
  `getChunk` (`:113`), which requires the target at the step's `directDependencies().get(distance)`; for
  FEATURES at distance 1 that is **CARVERS**.
- The task pre-schedules dependency rings from the *target's* accumulated deps
  (`ChunkGenerationTask.getRadiusForLayer`, `:126`; `ChunkStep.buildAccumulatedDependencies`). For a FULL
  target: `getStatusAroundFullChunk(0)=FULL`, `(1)=INITIALIZE_LIGHT`, `(2)=CARVERS`, then `BIOMES`, then the
  deep `STRUCTURE_STARTS` ring. So the FEATURES layer is radius 1 and the CARVERS layer is radius 2: a
  radius-1 feature write from a radius-1 FEATURES chunk lands in a radius-2 chunk that is at **CARVERS**, not
  FEATURES. That chunk's own FEATURES does not run in this task.
- A write never schedules anything (`setBlock` just writes). Only the ticket/task frontier advances status.
  Hence the forest cannot chain: each chunk's FEATURES runs once when scheduled, its writes land in
  already-scheduled CARVERS neighbours, and those neighbours' FEATURES runs later in their own task, which
  again pre-builds its own CARVERS ring.
- `ChunkStep.apply`/`completeChunkGeneration` (`ChunkStep.java:21-41`) only ever move `persistedStatus`
  **forward**; a chunk that already ran FEATURES is never re-run.

### 3. Why the old draft's `applyStep` cap fails

- A player ticket at level ≤ 33 calls `prepareAccessibleChunk` (`ChunkHolder.java:267`) →
  `getChunkRangeFuture` (`ChunkMap.java:293`) → `scheduleChunkGenerationTask(FULL)`. The created task
  fans out by the **target's** radii, so capping `applyStep` at `INITIALIZE_LIGHT` still lets the proxy's own
  task run EMPTY/STRUCTURE_STARTS at the deep radius and the noise/surface/carvers/features rings before the
  cap cancels it. It also cannot distinguish the proxy's own task from a real neighbour's dependency task.
- Correct gate: skip the `prepare*` calls in `updateFutures` for proxies; leave `applyStep` unguarded.
- The gate must be on **task creation**. Do not gate `scheduleChunkGenerationTask` globally either: a real
  chunk's `getChunkRangeFuture` calls it on its proxy neighbours, and returning UNLOADED there fails the whole
  range future and the real chunk never becomes accessible.

### 4. Proxy vs disk-FULL classification and probe cost

- Before the EMPTY load, `persistedStatus` is unknown; `ChunkMap.applyStep`'s EMPTY branch is
  `scheduleChunkLoad` (`:548`), which reads disk (`readChunk`) and deserializes, or `createEmptyChunk`
  (`:592`).
- `scheduleChunkGenerationTask(EMPTY)` builds a task whose radius is
  `GENERATION_PYRAMID.getStepTo(EMPTY).getAccumulatedRadiusOf(EMPTY)` = 0 (`ChunkGenerationTask.create`,
  `:32`), so it touches only the center — the cheap probe.
- `RegionFile.hasChunk` (`:348`) = `getOffset != 0`, in-memory once the region is open; `doesChunkExist`
  (`:220`) reads 5 bytes. `ChunkMap.isExistingChunkFull` (`:778`) does `readChunk().join()` on a miss — avoid.
- Performance estimate: an unconditional full EMPTY probe per ticketed chunk is not 2000/s on the server
  thread (task + holder access + ProtoChunk allocation + main-thread deserialize). With the `hasChunk`
  header filter, fresh chunks never take the task path, so 2000/s is plausible. No in-memory DB.

### 5. Persistence of partial chunks (corrected)

- Earlier drafts (and this reviewer) wrongly said "vanilla saves only FULL chunks". **Wrong.** Vanilla
  persists partial chunks:
  - `ChunkMap.save` (`:742`) handles `ChunkType.PROTOCHUNK` (`:752-760`): refuses only if the disk already
    has a FULL chunk (`isExistingChunkFull`, `:778`) or if it is `EMPTY` with no valid structure starts.
  - `scheduleUnload` → `save(getLatestChunk())` (`:517`) has no `LevelChunk` filter.
  - `saveAllChunks(true)` (`:430`) and `saveChunkIfNeeded` (`:719`) **do** filter to
    `ImposterProtoChunk || LevelChunk`, so partial chunks are not written on the periodic cadence — only on
    unload.
  - Shutdown: `MinecraftServer` removes all tickets and ticks until `hasWork()` is false
    (`MinecraftServer.java:600-620`), then `saveAllChunks`; every chunk unloads and saves.
- `Status` tag round-trips: `ChunkSerializer.write` (`:295`) and `read` (`:207-208`); the Neo MC-308222 patch
  (`ChunkStatus.getChunkSaveHeightmaps`) exists precisely to load non-FULL chunks correctly.
- Therefore the leaves a real chunk wrote into a proxy neighbour persist to the region file on unload and
  reload with the neighbour at its partial status. No replay/buffering mechanism is needed; the old AeroGel
  save guards (which refuse proxies) must be removed so partial proxies can save.

### 6. Relevant constants / classes

- `ChunkLevel.java`: `FULL_CHUNK_LEVEL=33`, `RADIUS_AROUND_FULL_CHUNK = FULL_CHUNK_STEP.accumulatedDependencies().getRadius()`,
  `MAX_LEVEL=33+that`, `generationStatus` (`:18`), `fullStatus` (`:40`).
- `GenerationChunkHolder.java`: `scheduleChunkGenerationTask` (`:42`), `applyStep` (`:60`),
  `updateHighestAllowedStatus` (`:77`), `isStatusDisallowed` (`:227`), `getPersistedStatus` (`:270`).
- `ChunkGenerationTask.java`: `create` (`:32`), `scheduleLayer` (`:113`), `getRadiusForLayer` (`:126`),
  `scheduleChunkInLayer` (`:131`, picks GENERATION vs LOADING pyramid from `persistedStatus`).
- `ChunkMap.java`: `prepareEntityTickingChunk` (`:365`), `getChunkRangeFuture` (`:293`),
  `prepareTickingChunk` (`:671`), `prepareAccessibleChunk` (`:704`), `acquireGeneration` (`:606`),
  `applyStep` (`:617`), `scheduleGenerationTask` (`:650`), `save` (`:742`), `saveAllChunks` (`:413`),
  `scheduleUnload` (`:505`), `saveChunkIfNeeded` (`:716`), `isExistingChunkFull` (`:778`).
- `ServerChunkCache.java`: `getChunkNow` (`:173`), `getChunkFutureMainThread` (`:227`).
- `ChunkHolder.java`: `updateFutures` (`:260`), `prepareAccessibleChunk` (`:267`).
- `WorldGenRegion.java`: `getChunk` (`:113`), `ensureCanWrite` (`:244`), `setBlock` (`:276`).
- `ChunkPyramid.java`: CARVERS (`:31`), FEATURES (`:35-41`), FULL (`:45`).

### 7. Things explicitly ruled out during review

- Clamping `highestAllowedStatus` (hides disk-FULL chunks from `getChunkIfPresent`/`getChunkNow`).
- Capping `GenerationChunkHolder.applyStep` (does not stop the already-created fan-out; breaks dependency
  generation).
- Gating `scheduleChunkGenerationTask` globally (fails a real chunk's range future on its proxy neighbours).
- A persistent known-real DB (stale-prone; the disk/region header is the source of truth).
- Deferred cross-border write buffering / replay (vanilla already persists the partial chunk).
- "Vanilla saves only FULL chunks" (false; see §5).
