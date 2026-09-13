# WS-01 — Server core: per-dimension state + hot-path fast bail + budgeted promotion plumbing

**Read only:** `server/ProxyChunkGate.java`, `server/ProxyStreamer.java`,
`server/PromotionManager.java`, `server/AdaptiveMode.java`,
`server/ServerHandlers.java`, `server/GenerationProbe.java`,
`server/PromotionProbe.java`, `server/PromotionStressProbe.java`,
`mixin/ChunkHolderGateMixin.java`, `mixin/ChunkHolderAccessor.java`,
`mixin/ServerChunkCachePlaceholderMixin.java`, `mixin/LevelWriteGuardMixin.java`,
`mixin/FlowingFluidMixin.java`, `mixin/ServerGamePacketListenerImplMixin.java`,
`common/AeroGelConfig.java`. Read `plans/proxy-chunk-state-machine.md`
`# IMPLEMENTATION STATUS` only. **Do not re-read vanilla sources** unless a
mixin target fails to resolve; then grep the single named file.

## Goal

Today `ProxyChunkGate`, `ProxyStreamer` and `PromotionManager` are static
singletons bound to `server.overworld()`. Positions are dimension-agnostic, so
an overworld proxy position is treated as a proxy in the nether/end too, and
`ProxyChunkGate.placeholders` (keyed by `pos` only) can return an
`EmptyLevelChunk` bound to the overworld for a nether read. Make the server side
work per-dimension, with the overworld as just the default.

## Tasks

### 1. Per-dimension state (the big one)
- Convert the three managers to per-`ServerLevel` state (e.g. a nested `State`
  per class, held in `Map<ResourceKey<Level>, State>`, or a small
  `ProxyDimension` context object per level — your call, keep it readable).
  Scalar config stays global.
- `ProxyStreamer.init(server)` must initialise **every** `ServerLevel` whose
  `ChunkSource.getGenerator() instanceof NoiseBasedChunkGenerator`; skip others
  with an info log. Per-level: `workerSettings`, `workerBiomes`, `workerSeed`,
  `SamplerPool`, grids/pending/streams.
- Public methods that take a position must take the `ServerLevel` (or resolve it
  from a `Level`). Suggested shape (adapt as needed):
  `isProxy(ServerLevel,long)`, `isEngaged(ServerLevel,long)`,
  `isGated(ServerLevel,long)`, `promote(ServerLevel,ChunkPos,Runnable)`,
  `placeholder(Level,int,int)`, `isActive(ServerLevel)`,
  `ProxyStreamer.isProxyPlaceholder(ServerLevel,long)`,
  `ProxyStreamer.biomeHolder(ServerLevel,long)`, `onMeshRequest(ServerPlayer,payload)`.
- `placeholders` must be per-level (or keyed by dimension+pos). This is the
  actual bug: a nether read at an overworld-proxy (x,z) currently gets the
  overworld placeholder.
- `AdaptiveMode`: the enforcing decision is server-global, but zones/opt-in must
  be pushed to each level's gate. `isOptIn`/`renders` stay per-player (a player's
  `player.level()` decides which gate they interact with).
- Mixin dispatch:
  - `ChunkHolderGateMixin.updateFutures(ChunkMap, Executor)` → get the level from
    the `ChunkMap` (add an `@Accessor`/`@Invoker` if there is no getter).
  - `ServerChunkCachePlaceholderMixin` → `self.getLevel()`.
  - `LevelWriteGuardMixin` / `FlowingFluidMixin` → the `ServerLevel` already in
    scope. **Never** consult another dimension's state.
- Probes (`GenerationProbe`, `PromotionProbe`, `PromotionStressProbe`) may need
  signature updates; keep them working.

### 2. Hot-path fast bail (the headline "dormant = 0" fix)
`ServerChunkCache.getChunk` is on the path of essentially every server-side
`Level.getBlockState`. `Level.setBlock` and `FlowingFluid.spreadTo` are also hot.
All three currently call the full `ProxyChunkGate.isProxy` chain on every call.
- Add a cheap per-level `volatile boolean anyProxy` (or use the existing-but-
  unused `ProxyChunkGate.hasProxies()`), set when the first position is
  classified `proxy`, and expose a cheap `mayHaveProxy(ServerLevel)`.
- `ServerChunkCachePlaceholderMixin`, `LevelWriteGuardMixin`, `FlowingFluidMixin`
  must return before the expensive path when `!mayHaveProxy(level)` and the level
  is not engaged.
- Reorder `isProxy` so the cheap `allowed`/`real`/`proxy` set lookups happen
  before `loadedNow()` (`ProxyChunkGate.java:204-212`).
- The `real`/`allowed`/`proxy` sets are never pruned today. Add a bounded/LRU
  scheme keyed by dimension so a long session cannot grow them without limit.
  `anyProxy` must be recomputed on eviction (or count proxies).

### 3. Budget the probe drain and the promotion resend
- `ProxyChunkGate.tick` drains up to **4096** `finishProbe` per tick
  (`ProxyChunkGate.java:415`); each disk-FULL one runs `forcePrepare` →
  `updateFutures`. A burst = tick stall. Time-budget it (e.g. ~2 ms/tick, always
  do at least one) instead of a raw count.
- `resend` (`ProxyChunkGate.java:371-386`) builds a full
  `ClientboundLevelChunkWithLightPacket`, forces per-section light status and
  propagates light **inside the promotion callback on the server thread**,
  unbudgeted. Move it behind a deferred queue drained with a small per-tick
  budget, or find a cheaper vanilla path. Surface the tradeoff (resend latency
  vs tick stall).
- `ProxyChunkGate.promote` with a null holder does a blocking `lvl.getChunk(...)`
  (`:310-317`). Keep the blocking load only for the manual `/aerogel generate`
  path; from `PromotionManager` it should never happen (holder is pre-checked).

### 4. Per-player switch mirror
`ServerHandlers.switchState` (`:343`, `:361`) is a **global** static map while the
client switches are per-player. Make it per-player (`Map<UUID, Map<String,Boolean>>`)
or derive the toggle direction from the owning subsystem.

### 5. Dead code
Remove `ProxyChunkGate.isRealOrAllowed` and `isAllowed` if still unused after the
refactor.

## Verify
`JAVA_HOME=... ./gradlew :compileJava`. If you can, also confirm the four
headless probes still exist and compile (do not run them). Report any mixin
target you could not resolve.

## Report
- The per-dimension design you chose (one paragraph) and the exact new public
  signatures WS-02/03 must call.
- Which dimensions initialise and which are skipped (nether/end generator types).
- Hot-path fast-bail approach + rough before/after call cost.
- Probe-drain and resend budgets chosen.
- Tradeoffs (e.g. resend latency, pruning granularity).
