# AeroGel — AGENTS.md

## ▶ CURRENT TASK: promotion and gating rewrite — compiles, loads, all probes PASS

The virtual-proxy gate and the client render integration (2026-09-13,
`plans/proxy-chunk-state-machine.md`) are unchanged and still verified in-game. What is being rewritten is
everything downstream of it: the 2026-10-15 review of #15/#16 found the promotion path built on three
premises that did not hold. The design is `plans/promotion-and-gating-rewrite.md` and the three changes it
makes are:

- **The opt-out concept is gone** (#15). There is no opt-out zone list: the gate is global while enforcing,
  and otherwise the union of the zones of players whose client `travel` switch is on. A player who declined
  `travel` and stands inside someone else's opted-in zone is gated anyway — generation is shared, so that is
  a preference being overridden, not a void. The list also cost the most on the idle path precisely because it
  held most connected players rather than the small opt-in set.
- **The server never branches on whether a client renders proxies.** `AdaptiveMode.renders` (has the mod and
  the `proxy` switch on) gates *sending meshes* and nothing else; promotion is a server-side generation
  decision with no interest in what any client draws. This is what deletes the nearby-leftover arm instead of
  merely re-pacing it, and with it #15's uncapped generation storm.
- **The disengage model replaces the linger-counter veto.** Per tick: sample the window → retry → accrue →
  refill → drain → *decide*. Refill runs before the emptiness check, so "queue empty" can only mean "nothing
  left to generate", which is why disengaging cannot burst instant generation. Refill bypasses the linger
  **threshold** while calm, never the budget; only Sable body contact and the server stop's pending-write finish
  bypass the budget (touchdown, block writes, refused right-clicks and refused stub writes queue for the same pacer
  since 2026-10-20, Maric: "enqueue these where possible"). The adaptive tick is **split in two** — `AdaptiveMode.sample`
  before the promotion tick and `AdaptiveMode.decide` after it — because the refill and the decision must read
  the **same** window maxima. They are a max over `windowSeconds`, so they hold and then step down in one tick;
  read at two points, the refill saw "busy" and queued nothing while the decision saw "calm" and disengaged,
  which is the burst. That ordering is what lets the veto be plain "queue and in-flight empty".

**Verified: compiles, loads, probes PASS (2026-10-15).** `:compileJava :compileDevJava` clean; the server
reaches `Done` on NeoForge **21.1.252 / FML 4.0.44**; and the whole probe set passes, re-run after the
`AdaptiveMode.sample`/`decide` split, the merged refill and the in-flight retry:

| probe | result |
|---|---|
| `probe` | PASS 5/5, including the reworked adaptive-window check |
| `drain` | PASS — 25 real, peak concurrent 2 against a cap of 2, veto held 44 ticks |
| `promotestress` | PASS — 25 real in 60 ticks |
| `promote` | PASS at `MAX_TICKS = 100` — real at 60 ticks, 6144/6144 writes over 27 ticks, `missing: none`, late 512/512, nothing pending |
| `vanillaclient` | PASS — 9 real, peak 2 against a cap of 2, no meshes sent |
| `sablebody` | PASS |
| `stationary` | PASS — one burst of 1681 chunks, then 0 sent over 400 ticks |
| `independence` | PASS — all four `travel`/`proxy` combinations |
| `footprint` | PASS — one promotion: the 24 chunks outside it at FULL, a partial pyramid out to ring 13, 0 pacer starts |
| `inflight` | PASS — 9 real despite 65 re-drives, peak 2 against a cap of 2 |
| `teleport` | PASS — the opted-in target gated before the move, enforcement engaged then self-cleared |
| `writesafety` ×2 | PASS — placement refused, then a deferred write survives the stop |

`promote` at 100 ticks is the answer to the bound itself: the chunk goes real at 60 ticks, so issue #16's own
~575-tick estimate for the same work was simply wrong, and the bound is now tight enough to be a tripwire
rather than a formality.

**Probe rules learned the hard way — every new probe must satisfy these.** A `FakePlayer` used to be barred
from the player list on a tick where `MinecraftServer.tickChildren` calls `synchronizeTime` (`tickCount % 20 == 0`),
which walks every player calling `hasChannel` on a connection that has none; `ProbeFakePlayer` (dev
source set) attaches a draining `EmbeddedChannel` to the fake's shared `Connection` through the private
`channel` field, so that is gone: park the fake with `ProbeFakePlayer.park` for the whole run and use
`ProbeFakePlayer.get` instead of `FakePlayerFactory.get`. Two things about it are not obvious. The attachment
is once per JVM, because every fake shares one static `DUMMY_CONNECTION`. And the drain counts nothing in
normal operation: `FakePlayerNetHandler` overrides both `Connection.send` overloads to no-ops, so
`synchronizeTime` updates, chunk sends and broadcasts never reach the `Connection` at all, and the handler's
job is only to keep a path open for writers that bypass the listener — which is why it carries a self-check
packet instead of waiting for traffic. A fake is now *connected but declared nothing*: its payload setup is
empty, the same as an unmodded client, so a probe still states what the fake receives through
`AdaptiveMode.setHasAeroGel` rather than relying on a channel handshake. A probe that asserts on the gate's
per-position state must also **pin the adaptive state** for its duration — a zone gives the gate work to do,
which can push tick time over `activateTickMs` and stall the probe mid-sequence, and a stalled probe is still
a parked one. And probes must never call a config-persisting setter: a run that does not reach its cleanup
leaves the dev instance configured differently, and reads the altered value back as its "original" next time.
That is what `debugSetHoldSeconds` / `debugSetEngageOnTeleport` / `debugSetActivateThresholds` exist for.

**Two traps in running the suite itself, both of which cost a long misdiagnosis.** A probe that halts the
server leaves the world lock held briefly, and the next run then dies at boot with
`DirectoryLock$LockException`; the summary reports *no VERDICT line*, which points at the probe rather than the
runner. And `timeout` kills the `gradlew` **wrapper**, not the server — the game JVM is a child of the
persistent Gradle daemon and survives, at 99% CPU and holding the lock, poisoning every later run. Stop it by
command-line match on **`fml.modFolders`** (not `serverRunProgramArgs`, which matches nothing and makes a
liveness check silently report zero). Do not clear flag files with a bare `aerogel.*` glob either:
`aerogel.writesafety.pos` is the state its two runs hand between them, and deleting it makes the probe redo
its first half for ever while reporting success.

**Probe defects found and fixed on the way, all older than this rewrite:** `aerogel.teleport` could not pass at
all (it never declared its player's `mesh_batch` channel, so `optsIn` was false and no zone was ever pushed —
broken since `adfd53a` inverted that set); `GenerationProbe.checkAdaptiveWindow` passed the veto argument as
"no linger" where it now means "work outstanding", so its may-stop cases could never pass; `aerogel.vanillaclient`
was passing vacuously because nothing was enforcing for it, so its square was never gated; and `aerogel.promote`
scored itself the moment the *bulk* band finished, reading the late band mid-replay. `drain`, `vanillaclient`
and `inflight` now fail rather than pass when the mechanism was never exercised.

**Not covered by any probe**, and therefore on the in-game list: the refill's calm-threshold bypass under real
play, and the tick order's effect on a live server — both want a player present for longer than a `FakePlayer`
may stay in the list, so `drain` fills its queue with `debugEnqueue` instead and asserts the veto and the pacing
rather than the refill itself.

**Previous verified state** was the 2026-10-13 hardening pass: loads to the main menu and
`:runServer` + `touch run/server/aerogel.promote` reporting **VERDICT PASS**.

**Posture — work, or crash, never silently nothing.** `aerogel.mixins.json` stays `"required": true` with
`"defaultRequire": 1` on purpose: a moved injection target is a launch failure, not a silently disabled
feature, and the config is validated rather than trusted. The flip side is the gate-off path — while no
mechanic is enabled or triggered, the mod has to be undetectable, so every hot path keeps its cheap per-level
fast bail (`ProxyChunkGate.mayHaveProxy` first).

Previous list, still open: in-game verification of detail tiers, then the rewritten enforcement + promotion
path (`/aerogel adaptive status`, `/aerogel promote status`), then morphing if the pop ever reads badly.

## ✅ PERFORMANCE REMEDIATION — 2026-09-13 (all workstreams landed, probes PASS)

The review + remediation plans live in `plans/workstreams/` (index + 6 specs). **They are implemented** and
committed; the headless gate/promote/stress probes all PASS after the refactor. Do not redo them. Summary:

- **Per-dimension.** `ProxyChunkGate`, `ProxyStreamer` and `PromotionManager` state is now per-`ServerLevel`
  (`Map<ResourceKey<Level>, State>`); mixins dispatch on the level in scope. Fixes the bug where an overworld
  proxy `(x,z)` was treated as a proxy in the nether/end and `placeholders` returned an overworld chunk.
  Overworld/Nether/End all use `NoiseBasedChunkGenerator`, so all three stream; non-noise dimensions skip.
- **Gate-off fast bail.** `ProxyChunkGate.mayHaveProxy(ServerLevel)` is checked first on the
  `ServerChunkCache.getChunk` / `Level.setBlock` / `FlowingFluid.spreadTo` hot paths; `isProxy` does the
  cheap set checks before `getChunkNow`. `real`/`allowed`/`proxy` sets are pruned (bounded, 200-tick).
- **Budgeted promotion plumbing.** `ProxyChunkGate.tick` drains classification completions at ~2 ms/tick and
  chunk resends at ~1 ms/tick (was a raw 4096 cap / inline callback).
- **Promotion queue** was bounded here by an oldest-first eviction scan plus a 10 s no-player-near grace
  sweep, and stuck `inFlight` entries are timed out (300 ticks). The 2026-10-15 rewrite replaced the
  eviction with a refill that rebuilds the queue from the nearest candidates each pass — see
  `plans/promotion-and-gating-rewrite.md`.
- **Streamer**: `promoteNearBand` renamed **`refineNearBand`** (it is mesh detail refinement, not terrain
  promotion); wanted-list dedup via a per-stream membership set; refinement gated on `mayHaveProxy`.
- **Client**: per-dimension floor/sea (nether/end get no water classification); sprite caches cleared on
  resource reload; `proxyPositions` is a locked primitive set; reconciliation early-outs when proxy
  streaming is off.
- **Sampler**: point cache bounded per pool (~48 MB/dimension cap) with FIFO eighth-eviction; vertical ceiling
  derived from the dimension's noise settings; biome diagnostic string built once per biome.
- **Removed dead code**: `ProxyTopology.upsample`, `WireframePayload`, `ViewAreaInvoker` +
  `LevelRendererAccessor.viewArea`, unused sampler stat counters.

**What changed:** the `ProxyChunkGenerator`/`ChunkRegenerator` backwards-reset design, the save guards, the
`nogen` test scaffolding and the `WorldGenRegion` write guard are **deleted**. `server/ProxyChunkGate` gates
`ChunkHolder.updateFutures` so a proxy never builds its own FULL task, classifies disk-FULL vs proxy with a
cheap EMPTY probe, serves reads from an `EmptyLevelChunk` placeholder, and promotes by adding to an
allow-list and forcing the FULL future (forward generation, no reset). `LevelWriteGuardMixin`,
`FlowingFluidMixin`, `PromotionManager`, `ChunkHolderAccessor` and the fast-flight move check are kept.
`common/ProxyChunkUtil` is client-side — keep it.

**Client render integration (2026-09-13, verified in-game).** Vanilla's chunk renderer assumes contiguous
client chunks; proxies are not client chunks, so three separate vanilla behaviours broke. All patched
client-side under the `neighbors` switch:
- `RenderSectionNeighborsMixin` — `doesChunkExistAt` counts a proxy position as an existing chunk (else a
  real chunk next to a proxy is parked in `chunksWaitingForNeighbors` and its rebuild is cancelled), and
  `getCompiled` reports a proxy section as `CompiledSection.EMPTY` (transparent; else the occlusion walk
  stops at the proxy and real chunks beyond it never render).
- Proxy hide is exact: hide only when the real chunk is in the renderer's actual drawn set
  (`LevelRenderer.visibleSections`, via `LevelRendererAccessor`); compiled-but-culled
  chunks keep the proxy. `isCompiled` checks the `ViewArea` slot origin because `getRenderSectionAt` wraps
  its index.
- Client reconciliation requests a proxy mesh whenever `ProxyRenderer.isRendered` is false, including a
  real-but-uncompiled/culled chunk (the earlier skip of real chunks left permanent boundary gaps).

Proxy water at sea level is sunk `WATER_SINK=0.15` (real surface is at `seaLevel-0.125`) so it sits just below
the real water surface and does not z-fight it.

**Bisect switches** — `/aerogel switch <feature> [true|false]` (Brigadier `bool`, not `on`/`off`): client
`proxy`, `travel`, `anim`, `sync`, `store`, `clamp`, `reconcile`, `wireframe`, `neighbors`, `colors`;
server `movecheck`, `promote`, `gate`, `stream`, `resend`, `adaptive`.

Next session, priority order:

1. **Verify detail tiers in-game** — tiers render at the sampled tier (`/aerogel detail near 25`;
   `detail neardist 3` for the coarse fan); `/aerogel detail threads 4` under a radius-50 sweep; biome blocks
   on plains/forest/jungle/desert/snow/ocean/windswept hills; F3 biome without generating; `/aerogel detail draw`.
2. **Verify the rewritten enforcement + promotion in-game** — the gate is off until the chunk-task backlog
   exceeds `adaptive backlog` (default 64) or tick time hits `activateTickMs` (35 ms); while it is off it
   applies inside the zone of every player whose client `travel` switch is on (`/aerogel switch travel true`
   opts a client in). `/aerogel adaptive status` shows backlog/tick, hold left, the opt-in count and the
   promotion queue/in-flight its veto reads. Stand still: while the server is busy the chunk under you
   promotes after ~10 s (`d=0` min), the fringe (view-distance+2 = 12 ch) after ~30 s (max), linearly
   between; while the server is **calm** the threshold is bypassed and every fresh lingered stub is eligible
   at once, still paced by `maxInFlight` and the budget (`/aerogel promote status`). Touching/landing, a
   block write and a refused right-click queue the chunk for that pacer (no linger threshold); only Sable body
   contact generates at once, uncapped. Watch
   `server tick stall` / `stream perf`. Tune
   `promote range/linger/landing/concurrency/budget/cost` (the budget and concurrency knobs shape **linger**
   promotion only).
3. **Watch the write→promotion path under a mass write** — a write queues its chunk inside `Level.setBlock`,
   one entry per chunk (not one per write), and the pacer starts them. Confirm a wide `/fill` over proxies stays
   smooth and appears chunk by chunk (`aerogel.masswrite` proves it headless).
4. **Sable/Rapier collider PoC** — feed the proxy surface into the physics path near the hull (hook points in
   `plans/investigations/aeronautics-sable-physics.md` §7). Only the crude player clamp exists today.
5. **Morphing (optional)** — the user accepts pop-over on promotion; a vertex slide is only worth it if the
   pop ever reads badly. The client already renders at the source tier, so a promotion changes triangulation.

A NeoForge 1.21.1 mod that stops chunk-load lag when flying Create Aeronautics contraptions very fast.
Concept: replace the full-chunk pipeline in a fast flight corridor with cheap **coarse proxy terrain** —
a per-chunk low-detail surface derived from the worldgen noise functions (density/router sampling), streamed
server→client as tiny height packets. Full chunks are only generated when a player/vehicle lingers (the
threshold interpolates from ~10 s under you to ~30 s at the generation-range fringe, and is bypassed
entirely while the server is calm) or touches the ground. Adaptive by default: the gate is off unless the
server is backlogged or a player opts into proxy travel. Priority: **fastness above all** (server must not
choke at ~80 chunks/s swept).

## Project facts

- Target pack: MC 1.21.1, NeoForge 21.1.252 (FML 4.0.44), Create 6.0.10, Create Aeronautics 1.3.2 (bundled: aeronautics+simulated+offroad), Sable 2.0.6 (Rapier physics), Distant Horizons present.
- Mod id: `aerogel`. Group: `dev.aerogel`. Java 21. Single NeoForge module (server + client, one jar).
- Build: gradle 9.4.1 wrapper, ModDevGradle `2.0.140`, Parchment 1.21/2024.11.10, NeoForm `1.21.1-20240808.144430`.
- Dev deps (Create/Aeronautics/Sable) are staged in `libs/` from the kitchen instance — NOT pulled from maven, NOT committed. See `libs/README.md` for re-staging.
- No JVM parameters. Every knob has a compiled-in default, a NeoForge config entry, and a live `/aerogel`
  command. Config: `config/aerogel-common.toml` (server/shared) and `config/aerogel-client.toml` (client).
  Commands write the new value back and `ModConfigSpec.save()`, so tuning persists across restarts.

## Implementation — what exists now

- **Interception / gate**: `server/ProxyChunkGate` + `mixin/ChunkHolderGateMixin` +
  `mixin/ServerChunkCachePlaceholderMixin`. A proxy never builds its own FULL task; reads are served from an
  `EmptyLevelChunk` placeholder; disk-FULL vs proxy is classified with a cheap EMPTY probe
  (`getPersistedStatus` is the source of truth). `ProxyStreamer.init` no longer swaps the generator — the real
  generator stays. Only a read that would have to **generate** gets the placeholder: a not-loaded chunk on disk
  at FULL is classified once by an EMPTY load (one IOWorker read, pending writes included) and loaded real,
  `requireChunk=false` gets vanilla's `null`, and statuses below `BIOMES` (structure lookups) go to vanilla.
  Verified headless: disk chunks load real, fresh chunks are instant placeholders.
- **Sampling (off-thread)**: `SamplerPool` owns N `OffThreadSampler` workers (`aerogel-sampler-<i>`), each
  with its own `RandomState`, `PointSurfaceSampler` and `BiomeTint`; the main thread only submits
  (pos, tier) and drains grids. Chunks are routed by an 8x8-chunk region so neighbours share the corner
  cache. `PointSurfaceSampler` (sparse, hint-driven; see measurements.md v4): every mesh point is on the
  density cell lattice in xz, so a pointwise scan at cellH-spaced y-levels reproduces vanilla's
  interpolated height exactly — no `NoiseChunk`, no full lattice. Points are cached per worker (corners
  shared by up to four chunks) and scanned bidirectionally from a neighbour hint: ~7 evals/chunk (5) /
  ~14 (9). Tiers are `ProxyTopology.FIVE`/`NINE`/`25`/`81` (nested), chosen per task by the streamer.
- **Streaming**: `ProxyStreamer`. `rebuildWanted` runs on every player chunk-cross: it drops `sent` entries
  outside the radius (so re-approaching resends) and queues the window nearest-first on a fresh
  login/teleport, or only the leading-edge band incrementally during flight (O(r)/cross, 200k cap). A 20-tick
  `refineNearBand` sweep re-samples/re-sends chunks entering the near tier band (detail refinement, **not**
  terrain promotion; gated on `mayHaveProxy`). **Radius is live-tunable**
  (`/aerogel detail radius <4-1000>`; default 20) — the client's render distance is the other cap.
  **Budgets:** `MAX_SUBMIT_PER_TICK=512` / `MAX_PENDING=4096` / `MAX_DRAIN_PER_TICK=1024`.
  `sendAndSubmit` sends every completed in-radius chunk drained this tick as `aerogel:mesh_batch` (one packet
  per tier: topology byte + long positions + `ProxyTopology.count` shorts + varint biome id + varint block id
  per chunk). Client→server reconciliation: `aerogel:mesh_request` (nearest-first missing-mesh requests every
  1 s; server replies from grid cache or front-queues them) — heals join drops/reconnects. Outbound batches are
  **split at `MAX_BATCH_CHUNKS=2048` per packet** (a refinement sweep can otherwise build one batch past the
  client's 4096 read cap, which would drop the connection), and `mesh_request` is **per-player token-bucketed**
  (2/s sustained, burst 4) so one client cannot drain the level-shared pending queue.
- **Radius scaling forecast** (sampler ~0.15 ms/chunk, sweep ~80 chunks/s → leading edge `2r+1`/cross;
  demand `(2r+1)*80`/s, CPU = demand*0.15 ms, initial fill `(2r+1)^2*0.15 ms`, client draws `(2r+1)^2`/frame):
  r=20 → ~3.3k/s ≈ 0.5 core, 0.25 s fill, 1.7k draws · r=32 → ~5.2k/s ≈ 0.8 core, 0.6 s, 4.2k draws ·
  r=50 → ~8.1k/s ≈ 1.2 cores, 1.5 s, 10k draws · r=100 → ~16k/s ≈ 2.4 cores, 6 s, 40k draws ·
  r=500 → ~80k/s ≈ 12 cores, 150 s, 1M draws · r=1000 → ~160k/s ≈ 24 cores, 600 s, 4M draws. Binding limits:
  one sampler thread saturates near r=32; r=100 wants ~3 workers; **r≥~200 is not renderable** (draw calls +
  GPU `VertexBuffer`s) regardless of sampling. The wanted list is built incrementally (leading-edge
  enqueue, O(r)/cross, 200k cap) so a large radius cannot stall the tick on an O(r²) rebuild.
  `MAX_RADIUS_LIMIT=1000` is a testing knob, not a target; normal range is 8-32. **The worker pool is in**
  (`/aerogel detail threads <n>`): the "cores" figures are the *requirement*, so radius >~32 now just needs N
  threads, each its own `RandomState` + `PointSurfaceSampler`; the renderer still caps the useful radius
  (~100-200). **Do not try to match Distant Horizons' LOD distance with this layer.** DH streams precomputed,
  cached LODs it only has to upload, while every proxy chunk is sampled on demand (~0.15 ms) and drawn as its
  own `VertexBuffer`: at r=512 the fill is ~1M chunks, ~12 cores to keep up and ~1M draw calls. AeroGel owns
  the near/transit field and the chunks DH has no LOD for; DH owns the far field. Tiers cut the far-band
  sampling cost on top.
- **Persistence**: vanilla persists **partial** chunks, so a proxy that received a neighbour's feature writes
  is written on unload and reloaded at its partial status — cross-border features survive a restart. AeroGel's
  old proxy save guards are **deleted** (they existed only for the old all-air FULL stubs). See
  `plans/proxy-chunk-state-machine.md` §6.
- **Adaptive enforcement**: `server/AdaptiveMode` decides whether the gate applies at all. The server enforces
  globally while the main-thread chunk-task backlog (`ServerChunkCache.getPendingTasksCount()`, max over every
  gated level) is ≥ `backlogActivate` (64) or smoothed tick time ≥ `activateTickMs` (35 ms), both taken as
  the max over the last `windowSeconds` (5). It **may** stop only once both windowed values fall back below the
  deactivate values, `holdSeconds` (10) have passed since the last activating sample, **and the promotion queue
  and the in-flight set are both empty** — read *after* the refill, so "empty" can only mean "nothing left to
  generate", which is what makes disengaging safe. That is a complete answer only because of the tick order:
  the window is sampled, then `refill` admits every fresh in-range stub the gate could promote, then the
  decision is taken. The refill's calm flag used to be recomputed by the decision, so on the tick the window
  maxima stepped down the refill read "busy", queued nothing, and the decision read "calm" against an empty
  queue. `aerogel.drain` caught that; `AdaptiveMode.sample`/`decide` is the fix and there is no longer a
  separate count of gated stubs. The old veto was "no linger counter
  remains"; universal
  linger invalidated it, because a predicate on those counters degenerates into "there are players" and pins
  enforcement on for the rest of the session. With `adaptive.engageOnTeleport` (default false, #14) a player
  teleporting (any `EntityTeleportEvent`: `/tp`, pearl, chorus) to a chunk that is neither loaded nor known
  disk-FULL engages enforcing before the move, as an activating sample. While not enforcing, the gate is the
  **union of the zones of players whose client `travel` switch is on** (on a teleport, an opted-in player's
  zone moves to the target before the move). There is no opt-out list and no subtraction: a player who
  declined `travel` and stands inside someone else's opted-in zone is gated anyway — generation is shared,
  so a preference is being overridden, not a void. `pushZones` reuses a per-dimension scratch and hands the
  gate the array it already holds unless the centres actually moved, so a server at rest allocates nothing.
  A player whose client `proxy` switch is off receives no meshes at all, and that is the *only* thing
  `AdaptiveMode.renders` gates — promotion is a server-side generation decision and takes no interest in
  what any client draws. A client without AeroGel can join (the payloads are registered `optional()`, #7): it
  is recorded once at login, receives no meshes and does no streamer work, and its chunks are gated and
  promoted like everyone else's — excluding a client that cannot see proxies would only move the stall
  somewhere it cannot be seen coming. An AeroGel client on a server without the mod sends nothing and leaves
  the proxy layer idle. See `plans/proxy-chunk-state-machine.md` §Adaptive enforcement and
  `plans/promotion-and-gating-rewrite.md` §1, §4.
- **Promotion**: `server/PromotionManager` + `ProxyChunkGate.promote`. **Linger is universal and
  server-side**: every player near a stub accrues, including clients without AeroGel and players whose `proxy`
  switch is off. One `startLinger` timestamp per stub is written on first approach and refreshed
  (`lingerSeen`) every tick the stub is near a player, so duration is a subtraction — there is no per-tick
  counter and no prune sweep; expired entries are dropped lazily when the scan looks at them. An entry with
  **pending deferred writes** never ages out: its banked duration is the only thing between a player's block
  placement and being re-lingered from zero. The threshold is distance-dependent,
  `t(d) = min + (d/maxRange)*(max - min)` (Chebyshev distance to the nearest player), both endpoints
  load-adaptive (idle 10..30 s, busy 20..60 s), `maxRange` auto = view-distance + 2 (12 ch here). Crossing it
  makes the chunk a *candidate*.
  **The queue is a work buffer, not a backlog.** Each refill rebuilds it from the nearest candidates and fills
  it to a **soft** cap derived from `maxInFlight` (a computed drain-rate cap would couple queue depth to a
  load-derived rate, a feedback loop inside the component whose job is to break feedback loops), so
  "nearest discards farthest" is a property of the refill rather than a policy with an eviction scan — no
  FIFO `order` deque, no `evictOldest`, no no-player-near grace sweep. A chunk with pending deferred writes is
  never dropped for capacity. Admission requires a **loaded FULL holder**: AeroGel cannot promote a chunk
  vanilla would not have generated, because promotion allow-lists a position and forces the FULL future on a
  holder that already exists, so it can never invent a ticket. Anything that misses the cut is still a stub
  with its `startLinger` intact and re-qualifies on a later pass.
  **Touching the surface (landing), a block write, a refused right-click (#6) and a Sable physics body's
  section request near the proxy surface (#9; the section stays out of Rapier until real,
  `sable/PhysicsChunkTicketManagerMixin`, applied only with Sable present) are not queued at all**: the chunk
  goes straight to vanilla generation on the trigger, with no budget, hard gate or `maxInFlight`, deduped only
  so the same chunk is not started twice (a mass write = one promotion per chunk, not one per write). These
  are the only budget bypasses.
  **What one promotion costs, measured** (`aerogel.footprint`, 2026-10-15). Promoting one proxy fully generates
  its **5x5** — the chunk plus 24 around it — because a chunk near a player is a ticking chunk and a ticking
  chunk requires its vicinity at FULL. Around that, vanilla raises a **partial pyramid** from the same
  dependencies: ring 3 to `initialize_light`, ring 4 to `carvers`, ring 5 to `biomes`, then rings 6 to 13 all at
  `structure_starts` and nothing beyond ring 13. So one promotion is 25 FULL chunks plus ~700 partial
  chunk-stages, and that work is real main-thread chunk-task backlog. The dependency path is
  `ChunkMap.getChunkRangeFuture` → `scheduleChunkGenerationTask` on each neighbour, which **deliberately goes
  ungated**: intercepting it is what "not being more invasive" buys, so the pacing below prices each start by
  the chunk-steps it will cause instead (#17). Two consequences worth knowing before tuning: the pyramid's
  backlog is what raises the adaptive backlog signal during a drain (measured 92 and 576 against
  `adaptive backlog` 64, while
  smoothed tick time peaked near 6 ms against 35 ms), and because the pyramid leaves that whole area partially
  generated, the marginal cost of the next promotion in the same place collapses — a promotion spends the
  proxies over roughly a 25x25 area.
  **Linger promotion is the only paced path**: a token bucket refilled at `maxGenFraction` ms/ms (default 0.5 =
  at most half the server's wall time) charged **what a start will make vanilla generate** (#17):
  `ProxyChunkGate.stepsToPromote` counts the chunk-steps (status advances) still missing in the start's FULL
  square (5x5 for an entity-ticking holder, 3x3 block-ticking, 1 border) and vanilla's pyramid around it, read
  from holder statuses; the charge is `chunkCostMs` × steps / `VOID_STEPS` (1387, an untouched entity-ticking
  neighbourhood) plus the measured finish cost (`ProxyChunkGate.postCostMs`). Measured by `aerogel.footprint`:
  a void promotion is 1387 steps, 65-180 ms server thread and 15-19 s CPU over all threads; a frontier one four
  chunks over is 420 steps, 25-80 ms and 2-6 s (six runs); the prediction matched the generated steps exactly. A start dearer
  than `burstMs` is let in at a full bucket and runs the budget into debt. Starts are blocked while smoothed
  tick time ≥ `hardGateMs`, and bounded by `maxInFlight`; the queue drains strictly nearest-player-first. While the
  server is **calm** the linger threshold is bypassed — the budget is not — so every fresh lingered stub is
  eligible at once and the same pacer decides when each starts. Promotion **forces the FULL future forward**
  from the chunk's persisted status — no backwards reset. `ProxyChunkGate.forcePrepare` first resets the three
  cached futures to the **exact** unloaded sentinel object (`ChunkHolderAccessor.unloadedFuture()`), because
  `updateFutures`'s ENTITY_TICKING branch throws `IllegalStateException` on an identity mismatch (a throw there
  is swallowed, leaving the holder half-demoted and crashing the next vanilla `DistanceManager.runAllUpdates`).
  `/aerogel generate` is the manual entry point. The adaptive tick is split — `AdaptiveMode.sample` runs before
  `PromotionManager.tick`, `AdaptiveMode.decide` after it — so the refill and the disengage decision read the
  same window maxima; see the disengage model above. All knobs live in `config/aerogel-common.toml`
  (`AeroGelConfig`); the client switches/draw radius in `config/aerogel-client.toml` (`AeroGelClientConfig`).
  Cross-field pairs (idle/busy tick time, adaptive activate/deactivate, backlog, linger ordering) are checked at
  server start by `AeroGelConfig.validate()` and corrected with a warning, so a hand-edited file cannot produce
  NaN thresholds.
- **Block-write guard**: `mixin/LevelWriteGuardMixin` intercepts `Level.setBlock`; for a proxy it cancels the
  write, records it, and queues the chunk for the pacer, then `PromotionManager.replayWrites` re-applies it after promotion
  (returning true preserves the client's prediction). `mixin/FlowingFluidMixin` cancels fluid spread into
  proxies (fluid does not promote). Caller writes are capped **globally**, not per chunk
  (`MAX_PENDING_WRITES` = 2^19, 16 full `/fill`s; beyond it the newest is dropped with a rate-limited WARN), because a
  `/fill` over queued proxies is the normal case; they replay in order
  under `promotion.rate.replayBudgetMs` (1.5 ms/tick, `/aerogel promote replay`), the rest carried to the next tick.
  **Each chunk's log is two queues, not one** (#16): `vanilla` (this chunk's own terrain, replayed first) and
  `caller` (the caller's writes, replayed last). Feature placement never reaches the guard — `WorldGenRegion`
  overrides `setBlock` with a 4-arg signature and writes straight to the chunk — but two vanilla paths do, and
  used to arrive after the caller's writes and erase them: `LevelChunk.postProcessGeneration`, and the block-tick
  queue, where a leaf that worldgen placed schedules a decay tick that fires *after* promotion. They are flagged
  by `mixin/VanillaChunkWriteMixin` / `mixin/VanillaBlockTickMixin` with a thread-local depth. Vanilla writes do
  not count against the global drop cap.
  **This is white-box**: it works by hooking the vanilla paths that write into a chunk after generation reports
  done. A future vanilla version adding a third such path reintroduces the bug silently — no probe catches it,
  because the trigger is a vegetated chunk. If that happens, the fallback is `promotion.refuseStubWrites` /
  `/aerogel promote refuse true`, which has no detection at all: refuse every write into a chunk that is not real
  terrain instead of deferring it (the placement fails and the actor retries; the first attempt queues the chunk).
  Default off.
  A **player's** right-click into such a chunk is refused instead (click on it cancelled; placement through a real
  block's face into it: item use denied, block use still runs; item kept, chunk queued for the pacer and clicks
  refused until it is real; #6), and
  server stop finishes every chunk with pending writes before the save (bounded 10 s, leftovers logged).
- **Promotion in flight reads like a proxy**: until the promoted chunk's FULL future completes
  (`ProxyChunkGate.isPromoting`), main-thread reads get the placeholder (`requireChunk=false`: `null`), fluid
  spread is cancelled and the clamp net stays on; vanilla `getChunk` would `managedBlock` on the generation (#13).
- **Clamp**: client `ClientTickEvent.Post` owns standing (snap + onGround claim → sprint/jump work); server
  `PlayerTickEvent.Post` is a through-floor net only (2-block deadzone, skipped over REAL chunks so you can
  cave underground). Per-tick server teleports caused rubber-banding — don't reintroduce.
- **Fast-flight move check**: vanilla `ServerGamePacketListenerImpl.handleMovePlayer` rejects moves with
  `d10 - d9 > 100` (≈10 blocks/packet) and teleports the player back, exempting only the singleplayer owner —
  on a dedicated server this rubber-bands ~3×/s at speed. `mixin/ServerGamePacketListenerImplMixin` exempts
  fast creative flight / elytra (`/aerogel switch movecheck`; **off by default** — on a server with untrusted
  players it is a speed-hack hole, so the admin opts in via `stream.bypassMoveCheck` in `aerogel-common.toml`).
- **Renderer**: `ProxyRenderer` — **one static `VertexBuffer` per chunk** (POSITION_TEX_COLOR), uploaded once on
  mesh arrival and re-uploaded only when its geometry actually changes (grid update, or a neighbour
  gains/loses a proxy so its seam walls change). The "coming up from the bottom" rise / sink is a per-chunk
  vertical **draw-matrix offset** (per-chunk `riseStart`/`removeStart` on the GPU record), never a geometry
  edit — so a moving mesh costs nothing and collision (settled grid) is untouched. **First-time uploads are
  budgeted by wall time (2 ms, hard cap 512) per sync** (`overUploadBudget`). Drawn per chunk at
  `RenderLevelStageEvent.Stage.AFTER_SKY` — before the terrain, depth-tested and **not** depth-writing — so
  real geometry covers the proxy wherever it exists; draw with
  `new Matrix4f(event.getModelViewMatrix()).translate(-cam)` (the event's PoseStack is an IDENTITY stack in
  1.21.1 — camera rotation lives in getModelViewMatrix). Every chunk is baked at **exactly the tier the
  server sent**. Tops are slanted quads in `POSITION_TEX_COLOR`, **textured with the chunk's representative
  block's UP-face sprite** (block atlas bound; `BlockModelShaper.getBlockModel(state).getQuads(state, UP, rnd)`,
  fallback `getParticleIcon()`, cached per `BlockState`) and tinted by the client-computed biome colour:
  grass → grass colour, leaves → foliage colour, water → water colour, sand/snow/stone → white. Leaf chunks
  get an opaque grass underlay 0.05 below the top so the cutout holes show grass, not the void.
  `position_tex_color` discards alpha < 0.1, so cutout textures (leaves) work. Border walls where the neighbour
  is NOT proxy-rendered (real/unloaded/missing), `SEAM_EPS` inward offset to avoid z-fighting with a real
  neighbour's side face; real chunks filtered out via `ProxyChunkUtil.isAirStub` (`LevelChunk.isEmpty()` is
  hardcoded false in 1.21.1!). Draw radius = `max(render-distance option, /aerogel detail draw)`. **The mesh
  store is pruned only at `STORE_RADIUS=256` chunks**, not at the draw radius: the server streams its own
  (larger) radius and will not resend a chunk it thinks is sent, so pruning at the draw radius left permanent
  holes that only the slow `mesh_request` reconciliation healed. GPU buffers are still freed at the draw
  radius; the cheap height grids are kept. Optional debug wireframe via `/aerogel switch wireframe`, off by
  default.
- **Renderer under Sodium / Distant Horizons — read before trusting the renderer.** The layer is drawn
  **before the terrain** (at `AFTER_SKY`), depth-tested but not depth-writing, so real geometry painted
  afterwards covers it and the layer shows exactly where nothing else draws. That replaces the old "hide the
  proxy where the real chunk is drawn" rule, which had to know what the renderer draws — a question Sodium
  cannot answer, because it replaces the terrain renderer and never fills vanilla's
  `LevelRenderer.visibleSections`. Two consequences to keep in mind: the layer cannot occlude anything (real
  terrain genuinely farther away paints over it), and **border walls are not emitted under Sodium** (without a
  per-section visibility signal a wall cannot be aimed, and an unaimed one runs to `minY` and hangs over air).
  It also composes with Distant Horizons, which owns the far field and paints over us where it has LODs — a
  chunk the server never generated has no LOD either, which is exactly the gap we fill. The client render
  integration was verified on a **vanilla-renderer** instance (Create family only, no Sodium/Iris/DH); a
  Sodium + DH client is a different renderer, not a detail.
- **Deploy**: `scripts/deploy.sh` (stop -> build -> deploy -> start); or `./gradlew :deployDemo` to copy the
  jar into a Prism Launcher instance's `mods/` — pick the instance with `-PaerogelInstance=<name>` or
  `AEROGEL_INSTANCE=<name>` (default `AeroGelDemo`). The test client also carries the Create-family jars
  (create, aeronautics-bundled, sable) — mandatory channels. See "Build / run commands".
- **Commands** (no JVM args needed for tuning):
  - `/aerogel flyspeed <0.05-10>`, `/aerogel generate` (real chunk at the player's position),
    `/aerogel biomes` (dump the server's `Biome -> block` cache).
  - `/aerogel switch <feature> [true|false]` — **all toggles**, client + server (the argument is a Brigadier
    `bool`, so `true`/`false`, not `on`/`off`; omit it to toggle; the client features are open to every player,
    the server-wide ones need permission level 2): client `proxy` (master: draw + clamp +
    reconcile + neighbour override), `travel` (request proxy travel from the server), `anim`, `sync`,
    `store`, `clamp`, `reconcile`, `wireframe`, `neighbors` (count a proxy neighbour as an existing chunk for
    vanilla's render visibility graph), `colors` (biome tint on the wire; the block texture is always sent);
    server `movecheck` (true = exempt fast creative flight/elytra from vanilla's "moved too quickly"
    rubber-band), `promote`, `gate` (own-task gate + placeholder; false = chunks generate vanilla), `stream`
    (proxy mesh sampling/sending), `resend` (manual chunk packet on promotion), `adaptive` (adaptive
    enforcement).
  - `/aerogel detail near <5|9|25|81>` (near-tier; default 9), `detail far <tier>` (default 5),
    `detail neardist <1-1000>` (near band in chunks; default 32), `detail radius <4-1000>` (proxy view
    radius; default 20), `detail draw <0-1000>` (client proxy draw radius; 0 = vanilla render-distance
    option), `detail threads <1-64>` (sampler workers; default 1).
  - `/aerogel promote range <-1-64>` (linger range; -1 = auto view-distance+2), `promote linger <minIdle>
    <minBusy> <maxIdle> <maxBusy>` (seconds; default 10 20 30 60), `promote landing <0-64>` (blocks above the
    proxy surface; default 8), `promote concurrency <1-16>` (concurrent **linger** regenerations; default 2),
    `promote budget <0.05-1.0>` (fraction of wall time for linger; default 0.5), `promote cost <ms>` (budget
    charged for a linger promotion in untouched land; a start pays its share of those chunk-steps; default
    100), `promote hardgate <ms>` (linger tick-time brake; default 60),
    `promote replay <ms>` (per-tick wall time for replaying deferred writes; default 1.5),
    `promote status` (effective thresholds, budget, queue/in-flight). Every setter writes the COMMON config.
    Touchdown, writes and refused clicks go through the same queue and knobs; only Sable body contact (no proxy
    collider, #9) ignores `concurrency`, `budget` and `hardgate`.
  - `/aerogel adaptive status` (backlog/tick now + window max, hold left, opt-in count, and the promotion
    queue/in-flight the disengage veto reads), `adaptive activate <1-200 ms>`, `adaptive deactivate <1-200 ms>`,
    `adaptive backlog <activate> <deactivate>` (default 64/16), `adaptive hold <0-600 s>` (default 10),
    `adaptive window <0-60 s>` (default 5),
    `adaptive teleport <true|false>` (engage on a teleport into maybe-ungenerated land; default false).
  - `draw` is client-only: the server command relays a `ClientSettingPayload` to the player.
- **Diagnostics**: client `proxy diag` (a `proxy diag:` log line every 5 s, now carrying the renderer signal —
  `visible drawn=N` or `visible unavailable(sodium)`), a one-shot dump relayed by **`/aerogel diag`**
  (`renderer=… visibleChunks=… store=… | within R: meshes=… real=… hidden=… drawn=… | here: mesh=… real=…
  rendered=…` — the first thing to read when proxies draw over real terrain or leave holes), `render perf` (per-200-frame summary: frame ms/fps/worst, `render()` ms, sync count/ms, chunk uploads, draws/frame, active morphs, payload-handle ms, GC count/ms) and `render hitch` warnings; `aerogel.perf` `client tick stall` / `client frame stall` (always-on, independent of the proxy switches; the frame-stall line names the slowest vanilla render stages); server `clamp diag`/`stream perf`/`stream state` + `server tick stall` log lines.

**Headless tools** (all self-halt; delete the flag before a normal run). The probe code lives in the `dev`
source set (`src/dev/java`, contributed through `DevProbe`/`DevProbes` as a `ServiceLoader` provider), so it is
compiled into every dev run and into **no published jar**. There is no runtime flag that decides whether it
exists — the code either is in the build or is not. `ProbeFakePlayer` is the shared fake-player helper (parking,
and the draining channel described under the probe rules above):
`touch run/server/aerogel.probe` (gate smoke test), `touch run/server/aerogel.promote` (promotion +
deferred-write + push-up smoke test), `touch run/server/aerogel.promotestress` (25-chunk promotion burst),
`touch run/server/aerogel.flight` (fast-flight benchmark matrix), `touch run/server/aerogel.writesafety` (#6;
start twice: player placement refused, then a pending write must survive the stop), `touch
run/server/aerogel.sablebody` (#9: Sable bodies over proxies promote near the surface, not when flying high),
`touch run/server/aerogel.teleport` (#14: engage on teleport, real adaptive path; waits for the server to idle),
`touch run/server/aerogel.vanillaclient` (#15: a client without AeroGel is gated like anyone else, so its
in-range stubs must enter the paced queue rather than being force-generated — assert *paced, not uncapped*;
a `FakePlayer` issues no chunk requests, so the probe asserts the mechanism, not the storm), `touch
run/server/aerogel.drain` (the disengage model on the real adaptive path: the drain is paced, the gate never
drops with work outstanding, and everything eventually generates), `touch run/server/aerogel.stationary`
(sampling is one burst then nothing while a player stands still), `touch
run/server/aerogel.independence` (gating and proxy rendering are independent axes — all four
`travel`/`proxy` combinations), `touch run/server/aerogel.masswrite` (a 32768-block `/fill`-sized write over a 4x4 of proxies: every chunk
queued, not started, at the write; all 16 starts from the pacer under a raised `maxInFlight`, the budget binds,
nothing dropped, every block lands), `touch run/server/aerogel.footprint` (what one promotion generates: its 5x5 at
FULL plus the partial pyramid around it, and that the pyramid terminates; then a frontier promotion beside it,
#17: chunk-steps, CPU and backlog of both against the pacer's prediction and charge), `touch
run/server/aerogel.rate` (the linger drain's rate, the refill's calm-threshold bypass, and the pacer's brake
and starvation floor under an
injected load).

## Build / run commands (run from repo root, use the Prism Java 21)

**Fastest path — the deploy script** (`scripts/deploy.sh`): stops the dev server, builds, deploys the client
jar into the `AeroGelDemo` Prism instance, and restarts the server.

```bash
scripts/deploy.sh            # stop -> build -> deploy to AeroGelDemo -> start (the normal loop)
scripts/deploy.sh build      # assemble + deploy only (server MUST be stopped: Gradle project lock)
scripts/deploy.sh stop|start|restart|status|logs
```

Manual equivalents:

```bash
JAVA_HOME=<jdk21> ./gradlew :compileJava   # compile only (fast)
JAVA_HOME=<jdk21> ./gradlew :assemble     # build the mod jar
JAVA_HOME=<jdk21> ./gradlew :deployDemo   # assemble + copy to AeroGelDemo
JAVA_HOME=<jdk21> ./gradlew :runServer    # dev server (boots Create+Aero+Sable)
```

**Releases are tag-driven and built on GitHub.** Pushing a `v*` tag runs `.github/workflows/release.yml`,
which builds that exact tag and attaches the jar (plus `SHA256SUMS.txt`) to a GitHub Release and to the
workflow run. Two things about it are easy to trip over: the release version comes from the **tag**
(`-Pmod_version=<tag>` overrides `gradle.properties`, which keeps only the local dev default), and `libs/`
is gitignored, so the workflow runs `scripts/stage-libs.sh` first — that script fetches Create/Sable/the
Create Aeronautics bundle from upstream (createmod + ryanhcode maven, Modrinth for the bundle, unpacking
its `META-INF/jarjar` children) and is a no-op wherever the jars are already staged, so it is safe to run
locally too. There is no branch or PR trigger; the workflow only ever publishes.

**Dev-loop facts (so a new agent does not have to rediscover them):**
- The **test client is the launcher instance `AeroGelDemo`** (launched from the launcher, not `:runClient`). It
  loads `aerogel-*.jar` from that instance's `mods/`; **restart the client** to pick up a
  new jar. Never deploy to the pack the `libs/` jars were staged from.
- The **dev server is `:runServer`** with gameDir `run/server`; it loads AeroGel from `build/classes` /
  `build/resources` (the `sourceSet`, via `-Dfml.modFolders`), so **the server needs no jar copy** — a rebuild
  + restart is enough. `run/server/mods/` is intentionally empty; Create/Aeronautics/Sable come from `libs/`
  on the runtime classpath.
- The launched game process is a **child of the persistent Gradle daemon, not of the `gradlew` wrapper**. Stop
  it by command-line match (`serverRunProgramArgs.txt`), not by killing the wrapper — `scripts/deploy.sh` does
  this. A second Gradle build while `:runServer` is up **blocks on the project lock**, so always stop before
  building.
- Script-started server log: `run/aerogel-server.log`. Vanilla log: `run/server/logs/latest.log`.
  `run/server/server.properties`: port `25565`, `online-mode=false`.
- The built jar lands in `build/libs/aerogel-1.21.1-<version>.jar`.
- **Decompiled sources for this exact build are on disk**: `build/moddev/artifacts/neoforge-21.1.252-sources.jar`
  (NeoForm `sources with NeoForge` — both `net/minecraft/**` and `net/neoforged/**`). `unzip -p <jar> <path>`
  reads one file, `unzip -p <jar> <path> | grep -n` locates a line. Use it instead of guessing an API or paying
  for a search: it is how the `Connection.channel`/`hasChannel` and deprecation questions were settled.

## Design docs

`plans/` holds the living design. Read before coding:
- `plans/architecture.md` — the agreed architecture + hard constraints + terms (proxy/corridor/promotion).
- `plans/proxy-chunk-state-machine.md` — the current virtual-proxy gate design (verified in-game), the
  mechanism, verified vanilla signatures, and the source-level research. Read before touching promotion.
- `plans/promotion-and-gating-rewrite.md` — the gate model and the disengage model: no opt-out, universal
  linger, the queue as a work buffer, and why the server must not branch on whether a client renders
  proxies. Read before touching `AdaptiveMode` or `PromotionManager`.
- `plans/measurements.md` — the live sampler/streaming measurements (v4 onward).
- `plans/target-env.md` — exact versions, instance paths, dependency coordinates.
- `plans/work_log.md` — chronological history + rationale for decisions that are now baked in. History only.
- `plans/investigations/` — research reports (aeronautics physics internals, chunk gen API surface).

## Standing decisions (do not silently reverse)

1. **Server is the single source of truth.** The server samples proxy heights anyway (collision + promotion); stream those to the client. NO client-side noise re-sampling from a seed (multiplayer never sends seed/settings → brittle). No seed handshake.
2. **Intercept chunk generation itself.** Whenever the game wants a full chunk during fast transit, supply a super-fast low-poly proxy instead — never run the full pipeline. Real generation runs only on linger (>~10s) or proxy-collider contact (landing). Transit chunks behave as ungenerated (no mob AI/redstone/features).
3. **Rendering = a custom quad layer, separate from the vanilla chunk pipeline.** Proxy heights are NOT fake `LevelChunk`s, do NOT request chunks, do NOT feed Distant Horizons. Rough flat-shaded quads are fine; "show nothing" is an acceptable fallback.
4. **Fidelity < speed.** Coarse mesh / flat colors / plains biome default. Progressive detail (finer mesh, biome color) only from leftover budget. Self-calibrating against a rolling ms/chunk budget.
5. **Collision = supply the same low-poly surface as collider geometry** (near the hull / what the physics engine queries). For Create/Sable planes that means feeding it into the Sable Rapier path; for normal entities the vanilla collision path. Accepted glitches: stop a few blocks high, nudge-up, fall-on-promotion. Do not invent a fake-chunk block-access path.
6. **Aeronautics is OPTIONAL.** Create/Aeronautics/Sable are optional deps. AeroGel core works in any NeoForge pack (elytra, etc.); Create-family integration is additive and loaded only when those mods are present.

## Architecture questions — status

1. **Noise sampling PoC — DONE.** Production is `PointSurfaceSampler` (sparse, hinted) on `SamplerPool`;
   see `plans/measurements.md` v4/v6.
2. **Collider PoC — only crude player clamp done.** Still to do: feed the low-poly surface into the Sable
   Rapier path (`RapierPhysicsPipeline.handleChunkSectionAddition` / VoxelColliderData) and vanilla entity
   collision; verify it stops a fast hull without real chunks.
3. **Proxy payload + handshake + client quad layer — DONE** (`aerogel:mesh_batch` / `aerogel:mesh_request`),
   one static `VertexBuffer` per chunk + a draw-matrix rise/sink; verified smooth in-game.
4. **Promotion — DONE** (manual + automatic linger/landing/block-write, verified headless; in-game check
   pending). **Being rewritten** — see `plans/promotion-and-gating-rewrite.md`; the new model is unverified.
5. **Chunk lifecycle — DONE** (`ProxyChunkGate`, verified in-game); see `plans/proxy-chunk-state-machine.md`.
6. **Biome colours + detail tiers — DONE** (client-side tint, textures, nested 5/9/25/81 tiers).
7. **Verify later in-game:** rider chunk-ticket coordinate space (real vs parked ~20M-block plot position),
   collider contact timing, and the remaining in-game checks in CURRENT TASK.

Findings: `plans/investigations/aeronautics-sable-physics.md` (top section = relevance map). Full design: `plans/architecture.md`.
