# AeroGel — Work Log

Chronological record of what was built, measured and fixed, newest first. **Current state lives in
[`../AGENTS.md`](../AGENTS.md) and the other `plans/` docs; this file is history and rationale only.**
Measurements that are still live are in `measurements.md` (v4 onward).

---

## 2026-10-15 — promotion and gating rewrite: no opt-out, universal linger, the disengage model

Design: `plans/promotion-and-gating-rewrite.md`. Rewrite of **local** history (nothing pushed), so this is a
rebase off the upstream tip rather than a set of reverts: `0e4a16b` + the survivors of the 2026-10-15 review.

**Three premises that did not hold, from the #15/#16 review.**

1. **The opted-out arm was queued, then un-queued.** `ad386f1` had it as `enqueue(pos, true)` — the queue,
   flagged urgent, which existed to make an uncapped queue behave as if it were capped. `f9a8692` deleted the
   whole `urgent` class, and the same arm became `promoteImmediate`. That un-queuing is the direct ancestor of
   #15's generation storm: one player's `(2*range+1)^2` square handed to vanilla with no budget, hard gate or
   concurrency cap.
2. **Gate engagement and proxy rendering had been fused.** The opted-out arm promoted every nearby proxy, so
   "gate off" meant "proxies disappear". They are now independent axes: `travel` is a *generation* request,
   `proxy` is a *rendering* request.
3. **Opt-out was never needed.** It existed to express "don't gate near people who declined" and carried
   essentially all of the idle-path cost, because the opt-out list holds *most* connected players while the
   opt-in list holds the few who asked.

**Decided during the rewrite, not in the original plan.**

- **The server must not branch on whether a client renders proxies.** `AdaptiveMode.renders` gates *sending
  meshes* and nothing else. An intermediate pass did re-introduce the leftover trigger as a queued arm keyed on
  `!renders`; it was removed again, because it re-protected the one case the plan had already accepted as lost
  and because it was the last place a generation decision read a rendering decision. `d3e7e2a` (the opt-out
  subtraction in `isEngaged`) went with the concept rather than being reverted.
- **Universal linger invalidated the `hasLinger` veto.** That veto read "some chunk has a linger counter". Once
  linger accrues for every player it degenerates into "there are players", which pins enforcement on for the
  rest of the session — the opposite of what it was protecting. The replacement is the **disengage model**:
  accrue → refill → drain → *may disengage* → prune, where the veto is "queue and in-flight both empty and no
  fresh gated stub in a player's range", read
  *after* the refill.
- **`PromotionManager.tick` must run before `AdaptiveMode.tick`.** The veto reads the queue the refill writes;
  in the old order it can see a queue that has not been refilled yet and drop the gate one tick early, which is
  the burst arriving through the new code instead of around it.
- **The refill, not an eviction scan, is what makes nearest win.** The queue is rebuilt each pass from the
  union of what is queued and what just became a candidate, cut to the nearest `queueCap`. `evictOldest`, the
  FIFO `order` deque and `Promotion` went with it. Two things are never dropped for capacity: a chunk holding a
  pending deferred write, and a chunk queued by a direct trigger (no linger entry — a player's action is not
  the refill's to reorder).
- **Admission requires a loaded FULL holder.** Promotion allow-lists a position and forces the FULL future on a
  holder that already exists, so AeroGel can never invent a ticket: it cannot generate what vanilla would not
  have. Without this, fringe chunks at `range = view + 2`, where the player's own ticket demands less than
  FULL, occupy queue slots they can never use — harmless in a 256-deep queue, fatal in a small work buffer,
  because the queue then never empties and the gate can never disengage.
- **Linger duration is a subtraction.** One `lingerStart` timestamp written on first approach (`putIfAbsent`,
  so a chunk oscillating at the edge of range does not have its bank reset each time it drops out for a tick)
  plus `lingerSeen` refreshed each tick. The per-tick `addTo` and the every-100-ticks prune sweep both go; the
  expiry moves to the scan that reads the maps, with a backstop for a level whose players have all left.
- **The drain brake is derived, not configured**: the tighter of `hardGateMs` and the adaptive activate
  threshold, so the drain cannot push tick time to the level that would re-engage enforcement. No new knob.
  The threshold bypasses; **the budget never does** — touchdown, block write, refused right-click and Sable
  body contact remain the only budget bypasses.

**Found by the probes.** `aerogel.drain` caught a hole in the veto on its first run: the gate dropped with 25
gated stubs sitting under the player. The refill keyed "calm" on `!enforcing`, but the drain is *supposed* to
keep enforcement on until it finishes, so the refill could never take the calm path — nothing was queued, the
queue read empty, the gate dropped, and the next tick the calm path queued 24 chunks that were ungated by
then. Two fixes, both needed: the refill now reads the **window's** calm state rather than `enforcing`, and the
veto counts **gated stubs in range** rather than only the queue and in-flight set. The one-tick staleness on
the window is inherent (the refill runs first) and is what the count covers.

Also corrected: `GenerationProbe.checkAdaptiveWindow` was still passing `false` for the veto argument to mean
"no linger" — under the new reading that means "work outstanding", so its "may stop" cases could never pass.
It now tests both directions: a calm window with nothing outstanding stops, and a calm window with work
outstanding does not.

**Verified.** All ten probes in the gate pass: `probe` (5/5 sub-checks), `independence` (all four
`travel`/`proxy` cells), `drain` (peak concurrent generation 2 against a cap of 2, veto held 37 ticks),
`stationary` (one burst of 1681 chunks then 0 sent over 400 ticks), `promotestress`, `writesafety` ×2,
`sablebody`, `teleport`, `promote` (`missing: none` on both bands, nothing left pending) and `vanillaclient`
(peak 2 against a cap of 2 for an unmodded client).

`aerogel.promote`'s late batch drains fully once the probe stops declaring its verdict the moment the bulk
finishes, so **the write loss in the plan's open questions was probe calibration, not a defect** — the `#16`
two-queue split holds, and the bulk band is `6144/6144` with nothing missing.

Three probe defects were found and fixed, all older than this rewrite: `aerogel.teleport` could not pass at all
(it never declared its player's `mesh_batch` channel, so no zone was ever pushed — broken since `adfd53a`
inverted that set); `GenerationProbe.checkAdaptiveWindow` passed the veto argument as "no linger" where it now
means "work outstanding"; and `aerogel.vanillaclient` was passing vacuously because nothing was enforcing for
it, so its square was never gated. `drain` and `vanillaclient` now fail rather than pass when the veto was never
exercised.

**Still not covered by any probe**, and on the in-game list: the refill's calm-threshold bypass filling the
queue, and the gated-stub count in `PromotionManager.drained`. Both need a player present for longer than a
`FakePlayer` may stay in the player list.

**Also open from this session**: the drain-rate budget needs numbers, stationary sampling is unmeasured, and
`PromotionManager.sweepInFlight`'s re-promotion of a stalled chunk is a no-op (it calls `promoteImmediate`,
which returns early for an already-`allowed` position). The last one was found while reading for the late-batch
loss and is real but wrong scale for that symptom.

---

## 2026-10-14 (late night) — engage enforcing on a teleport into fresh land (#14, opt-in)

- **#14 (Maric's decision: "sounds like a config option").** New `adaptive.engageOnTeleport` (default **false**,
  `/aerogel adaptive teleport <bool>`). A NeoForge `EntityTeleportEvent` listener (LOWEST priority; every subtype:
  `/tp`, `/spreadplayers`, ender pearl, chorus fruit; fired before the move) calls `AdaptiveMode.onTeleport`: if the
  target chunk is neither loaded nor known disk-FULL (`ProxyChunkGate.mayBeUngenerated`, set lookups +
  `getChunkNow`, no disk read), enforcing turns on at once and the zones are pushed before the arrival ticket. It
  counts as an activating sample, so `holdSeconds` applies and the normal window logic ends it.
- An unclassified chunk counts as maybe-ungenerated (the async disk probe cannot answer before the move), so a
  teleport into far *generated* land also engages for `holdSeconds`; its chunks still load real after one probe.
- Not covered: portals / dimension changes (`EntityTravelToDimensionEvent` carries no position); a cross-dimension
  `/tp` is checked against the source level's chunk at the target x/z.
- Probe `aerogel.teleport` (real adaptive path, FakePlayer, same event `/tp` posts): key false → not enforcing; key
  true → enforcing before the arrival ticket, arrival read is the placeholder, still a proxy 20 ticks later,
  enforcing off by itself after ~hold. FAILs with `onTeleport` disabled.
- **Exploration mode across a teleport (found by the STK L4, key false):** an opted-in player (client `travel`) still
  stalled ~8.3 s on `/tp` into fresh land: their zone is centred on `player.chunkPosition()`, refreshed per tick, so
  at the teleport it still sat at the old position and vanilla generated the arrival. `onTeleport` now pushes that
  player's zone at the target chunk before the move (independent of the key; per player, nothing global). Probe
  `aerogel.teleport` gains the opted-in check (FAILs without it: target not gated before the move).

---

## 2026-10-14 (night) — Sable bodies promote the proxy terrain they reach (#9, physics part)

- **#9 item 2 (Maric's decision: "ships colliding should force generate the collided chunk").** Sable (ships,
  Create: Coasters Simulated coasters, any sub-level or physics object) reads each terrain section around a body
  once in `PhysicsChunkTicketManager.addTicket` via `level.getChunk(x, z)` and bakes it into Rapier; for a proxy that
  was the air placeholder, kept while the body stayed, so bodies sank. `sable/PhysicsChunkTicketManagerMixin` hooks
  that read: for a stub chunk (`PromotionManager.onBodySection`) the section is left unregistered (Sable asks again
  next tick, real terrain once generated) and, when the section is within `landingMargin` of the sampled proxy
  surface (or the chunk has no grid), the chunk is promoted immediately (same path as landing/block-write). A body
  flying higher generates nothing. Only Sable's first request per section reaches the hook, behind `mayHaveProxy`.
- Optional dependency: the mixin lives in its own config `aerogel.sable.mixins.json`, declared in
  `neoforge.mods.toml` with `requiredMods = ["sable"]` (FML 4.0.44 skips it when Sable is absent; checked by
  pointing it at a missing mod id: the server ran, the hook was off).
- Probe `aerogel.sablebody`: two `/sable spawn block` bodies, no player. A above an unsampled proxy: promotion starts
  within 15 ticks and the body comes to rest on the generated floor (3/3 PASS); B 80 blocks above a sampled proxy:
  stays a proxy. FAILs with the mixin config off. One early run dropped A from y=250 and it fell through real
  terrain at ~40 m/s (likely tunnelling; not investigated), so A now drops 60 blocks.
- Not proven: a real Aeronautics ship or coaster landing in game; the edge-voxel faces Rapier bakes against a
  proxy neighbour (read through the placeholder, per the decompiled code) are not refreshed when it promotes.

---

## 2026-10-14 (evening) — player placement into a proxy refused; pending writes finished on stop (#6)

- **#6 (Maric's decision: refuse player placement into a proxy + finish pending writes on stop).** A player's
  right-click (`PlayerInteractEvent.RightClickBlock`) on a block in a proxy, a chunk still promoting, or one with
  writes awaiting replay is cancelled (`FAIL`, item kept); when only the block on the clicked face lies in such a
  chunk (the path a real client takes: it has no proxy chunks to click), only the item use is denied
  (`setUseItem(FALSE)`: no placement, item kept, a chest at the border still opens). Either way that chunk is
  promoted at once, so the next click works. Reason: the deferred `setBlock` returned true, the caller then filled a
  block entity that did not exist, and the replay restored an empty chest. Non-player writes keep the deferral.
- `ServerStoppingEvent` first runs `PromotionManager.finishPendingWrites`: adds a FULL ticket where a pending chunk
  has no holder, drives the chunk tasks itself (the tick loop has ended, so `haveTime()` is false), replays every
  write, bounded at 10 s; leftovers are logged as lost. Without it the chunk was not even on disk after the stop.
- Not done (not cheap): block-entity NBT on deferred non-player writes — the caller fills it after `setBlock`
  returns, so capturing it needs a hook after every caller. A crash (no stopping event) still loses pending writes.
- Probe: `aerogel.writesafety` (two starts): fake-player placement refused + promoted + works after; placement
  through a real block's face into a proxy refused (`VERDICT face`); a pending write survives stop/restart. Each
  FAILs without its part of the change.

---

## 2026-10-13 (night) — server-only mode: optional channels, vanilla clients can join (#7)

- **#7 (Maric's decision: server-only support where it costs nothing).** The four payloads are registered on an
  `optional()` registrar, so a vanilla client can join an AeroGel server and an AeroGel client a server without it.
- Server: `AdaptiveMode.onLogin` caches per player whether the connection has the `mesh_batch` channel. A player
  without it is `!renders` (the streamer skips it before any stream/sampling work) and `!isOptIn` even while the
  server enforces, so `PromotionManager` treats them as opted out and forces their nearby leftovers to real terrain;
  they also count as opt-out in the adaptive zones. Reason: a vanilla client has no proxy layer and will not move
  into a chunk it was never sent (#10), so a gated zone around it would be a wall. `/aerogel diag`, `detail draw`
  and the client `switch` features answer "your client does not have AeroGel installed" instead of sending.
- Client: `ClientHandlers` checks once per join whether the server has the channel; without it the switch sync and
  the 1 s mesh-request reconciliation never run.
- Probe: `aerogel.probe` adds a `vanilla client` verdict (flag alone turns `renders`/`isOptIn` off, the switch
  command is refused, with the channel the player opts in). A real vanilla join is not covered headlessly.

---

## 2026-10-13 (night) — promotions never block the main thread; in-flight writes kept and budgeted (#13, #5, #12)

- **#13.** A promoted chunk left the proxy set while its FULL future was still generating, so the player's own
  entity tick (fluid state at its feet) and `getNoiseBiome` peeks went to vanilla `getChunk` and `managedBlock`ed
  until it finished (12-13 s per linger promotion in the real pack). `ProxyChunkGate` now tracks `promoting` (set in
  `promote`, cleared in the completion callback); the read mixin answers those positions like a proxy (placeholder,
  `null` for `requireChunk=false`, vanilla once the requested status is in memory), `mayHaveProxy` stays true while
  any is promoting, fluid spread into them is cancelled and the server clamp net stays on.
- **#5 (Maric's decision).** Writes into a chunk whose promotion is running are logged uncapped and never dropped;
  only a proxy whose promotion has not started (no holder) keeps the 256 cap. Logged writes replay oldest first once
  the chunk is real, under `promotion.rate.replayBudgetMs` (1.5 ms/tick, `/aerogel promote replay <ms>`), the rest
  carried to the next tick; later writes into a chunk with replay pending queue behind it so order holds. A promoted
  chunk already real in memory takes writes directly (its own post-processing wrote hundreds of blocks into the log
  in the stress probe). The replay is decoupled from the completion callback, so manual and timed-out promotions
  replay too.
- **#12.** `pickNext` takes the first candidate when no player is online (every distance is `MAX_VALUE`).
- Probes: `aerogel.promote` times a `getChunk(FULL)` of the generating chunk (placeholder, no block), peeks it with
  `requireChunk=false` (null), writes 6144 blocks into it and checks all land over more than one tick and the read is
  real afterwards; `aerogel.promotestress` walks to a still-fresh 5x5 (a persisted one passed instantly) and logs
  that no player is online.

---

## 2026-10-13 (night) — placeholders only for generation, never for existing chunks (#3, #4)

- `ServerChunkCachePlaceholderMixin` serves a placeholder only when the main-thread read would have to generate:
  a not-loaded, unclassified chunk is classified once by an EMPTY load (`ProxyChunkGate.isRealOnDisk`; one IOWorker
  read incl. pending writes, never generation) and a FULL one on disk loads real. Before, every engaged position not
  yet probed read as void air (teleport targets, far mod reads).
- `requireChunk=false` returns `null` instead of a placeholder; a chunk already in memory at the requested status is
  returned; statuses below `BIOMES` (`/locate`, explorer maps: structure starts/references) go to vanilla, since they
  never build terrain. The dormant fast bail stays first.
- `promote`/`hasHolder`/`pickNext` require a holder whose ticket reaches FULL: a lower holder (now also left by the
  EMPTY load) made `forcePrepare` keep the completed unloaded sentinel, which read as an instant finished promotion.
- Probes: `aerogel.probe` adds an on-disk read-back, a `requireChunk=false` peek and a `STRUCTURE_STARTS` check;
  `aerogel.promote` writes once its forced ticket reached the holder (its candidate scan now skips on-disk chunks,
  which it used to take for proxies).

---

## 2026-10-13 (night) — adaptive enforcement stops flapping; backlog from every level (#2, #8)

- `AdaptiveMode` decides on the max backlog and max tick time over the last `adaptive.windowSeconds` (5) instead
  of one sample, and keeps enforcing for `adaptive.holdSeconds` (10) after the last activating sample. A single
  sample swung 558 → 0 → 234 → 0 between ticks, so the hysteresis never held (123 on/off lines in 10 min).
- The backlog input is the max over all gated levels, not the overworld only: lag in any dimension counts.
- New commands `/aerogel adaptive hold|window`; `adaptive status` shows the windowed values and hold left.
- The gate probe (`aerogel.probe`) feeds the decision the issue's swing and checks it flips on once, holds,
  then turns off (and that a linger counter still keeps it on).

---

## 2026-10-13 (late) — switch permissions, movecheck default, server-owner notes (#1, #11, #9)

- `/aerogel switch` now refuses the server-wide features (`gate`, `promote`, `stream`, `resend`, `adaptive`,
  `movecheck`, `colors`) below permission level 2; the per-player client toggles stay open to everyone.
- `stream.bypassMoveCheck` defaults to **false** (was true): skipping vanilla's move check is an opt-in.
- `README.md` created with notes for server owners (pregenerators vs. the gate; ships vs. proxy terrain).
- The gate probe (`aerogel.probe`) now also checks the switch permission split and the movecheck default.

---

## 2026-10-13 — hardening pass + release prep

A review of the whole mod, aimed at making a build that is either working or loudly broken. Compiles; the mod
loads to the main menu on NeoForge 21.1.252 / FML 4.0.44 with the Create family present; `:runServer` +
`aerogel.promote` PASSes. No mechanism was redesigned — this is bounds, dead code and honesty in the docs.

- **Immediate promotion is plain vanilla generation.** Touch/landing, block writes and opted-out leftovers no
  longer enter the promotion queue; `PromotionManager.promoteImmediate` starts them at once with no budget,
  hard gate or `maxInFlight`, deduped only by `inFlight` (one promotion per chunk, not per write). The
  `urgent` class and its budget/hard-gate bypass, `markUrgent`, the urgent-first `pickNext` and the
  urgent-eviction exemption are gone. The concurrent-collection hazard the old design carried (uncapped urgent
  queue) is gone with them.
- **Void plane deleted.** `/aerogel switch void` had toggled nothing since the y=0 quad was hard-disabled; the
  quad, its buffer, its build path, its perf counters and its config key are removed rather than left dead.
- **Config validated.** `AeroGelConfig.validate()` runs at server start and corrects cross-field violations
  (idle/busy tick time, adaptive activate/deactivate, backlog, linger ordering) with a warning; `loadFactor`
  guards the degenerate span too. First attempt hung it off `ModConfigEvent.Loading`, which NeoForge fires
  while the other configs are still unloaded — reading a value there throws and killed the launch. Validating
  where the values are consumed is both correct and simpler.
- **Probes are a dev-only source set.** The four probes moved to `src/dev/java` behind a
  `DevProbe`/`DevProbes` `ServiceLoader` hook registered only for dev runs; the jar task takes `main` alone,
  so no probe class is packaged and no runtime JVM flag decides whether they exist. Flag files still arm them.
  `ProxyChunkGate.pushOutOfTerrain` and `PromotionManager.debugEnqueue` became public so the probes can call
  them from the other source set.
- **Both packet paths bounded.** Outbound batches are split at `MAX_BATCH_CHUNKS=2048` per packet (the
  refinement sweep could build one batch past the client's 4096 read cap and disconnect the player);
  `mesh_request` is per-player token-bucketed (2/s, burst 4) so one client cannot drain the level-shared
  pending queue.
- **Smaller fixes.** `MeshMath.surfaceAt` falls back on the grid's own shape instead of throwing on the client
  tick path; sampler threads are named `aerogel-sampler-<i>`; `deployDemo` takes `-PaerogelInstance=<name>`
  instead of a hardcoded instance; NeoForge pinned to 21.1.252 and Sable to 2.0.6 to match the pack.
- **Docs corrected against the code.** The `void` switch is out of every list, promotion/rate-limit wording
  matches the de-urgented code, and the load-order incident is closed as unreproducible with the mixins left
  `required` on purpose (the earlier text suggested weakening them, which contradicts the fail-loud posture).

---

## 2026-09-13 (later-2) — adaptive enforcement, smooth linger, per-player streaming

Made AeroGel inert when it is not needed, per the "invisible mod" goal. Compiles; `aerogel.probe` and
`aerogel.promote` PASS; jar built + deployed.

- **`server/AdaptiveMode`** — the server enforces the gate globally only while the main-thread chunk-task
  backlog (`ServerChunkCache.getPendingTasksCount()`) is ≥ `backlogActivate` (64) or smoothed tick time ≥
  `activateTickMs` (35 ms); it stops once both fall back below the deactivate values (16 / 22 ms) **and no
  linger counters remain**, so flipping to vanilla cannot burst instant generation. While not enforcing, a
  player whose client `travel` switch is on is gated in their zone; an opted-out player forces vanilla locally
  (their leftovers are queued immediate); opt-out wins when the two overlap. `ProxyChunkGate.isEngaged` is the
  zone test, `isGated`/`isProxy` layer classification on top, and `isProxy` now also returns false when the
  chunk is actually loaded (so a vanilla-generating chunk is never mistaken for a proxy). The placeholder read
  intercept now keys on `isProxy`.
- **Smooth linger** replaces the near/far bands: `t(d) = min + (d/maxRange)*(max - min)`, endpoints
  load-adaptive (idle 10..30 s, busy 20..60 s), `maxRange` auto view-distance+2. Touching/landing and block
  writes stay immediate; a player's own chunk while flying uses `min`.
- **Per-player streaming** — the streamer skips players whose client `proxy` switch is off, and skips chunks
  that are already real (the client gets them from vanilla). Sampling stays globally shared. Fast flight now
  uses the incremental `rebuildWanted` path; only a >16-chunk single-tick jump (teleport) does the full O(r²)
  rebuild.
- **Client rename** `render` → `proxy` (the master gates draw + clamp + reconcile + neighbour override, not
  just drawing); new `travel` per-player request, default off. Client config + switches persist.
- **Gotchas:** the headless probes run with no players, so `AdaptiveMode.debugAlwaysOn()` (non-persisting) is
  called when a probe flag is present. `AdaptiveMode.debugAlwaysOn` must push to `ProxyChunkGate` immediately,
  not wait for the next tick, or the probe's first `getChunk` generates vanilla and blocks.

---

## 2026-09-13 (later) — promotion bands, generation budget, persistent config

Reworked automatic promotion and added the mod's first real config. Compiles; headless `aerogel.promote` and
`aerogel.promotestress` probes PASS; jar built + deployed.

- **Two-band linger.** `PromotionManager` now accumulates one cumulative counter per proxy chunk over the
  *far* band (default `view-distance + 2`, the true FULL range) and picks the threshold by the band the chunk
  is in: near (`nearRadius`, default 3 ch) 10..30 s, far 30..60 s, both load-interpolated. Walking a
  far-banked chunk into the near band promotes it immediately (cumulative counter ≥ the shorter threshold).
  Replaces the old single `lingerRange` (default 2, way inside the generation range) + `lingerMin/Max`.
- **Generation-time budget.** The old `maxInFlight=1` + load-scaled cooldown + `HARD_GATE_MS` was replaced by
  a token bucket refilled at `maxGenFraction` ms/ms (default 0.5 = half the wall time) charged `chunkCostMs`
  (100) per promotion, capped at `burstMs`. Ordinary far-band promotions need budget and respect `hardGateMs`;
  urgent (near band / block write / landing) bypass both but still debit the bucket, so they slow far-band
  promotion. `maxInFlight` (default 2) bounds concurrency for all classes. `pickNext` is urgent-first then
  nearest.
- **Config.** `AeroGelConfig` (COMMON, `config/aerogel-common.toml`) + `AeroGelClientConfig` (CLIENT,
  `config/aerogel-client.toml`); commands call `ModConfigSpec.save()`, so tuning persists. The client syncs its
  persisted switches to the server on join via a now-bidirectional `ClientSwitchPayload`, so bare toggles flip
  correctly. **Gotcha:** `defineInList` with `List.of(...)` NPEs during config correction (immutable lists are
  null-hostile) — use `Arrays.asList`.
- New `/aerogel promote` args: `near`, `nearLinger <min> <max>`, `far <-1..32>` (-1 = auto), `farLinger
  <min> <max>`, `budget <fraction>`, `cost <ms>`, `hardgate <ms>`; `concurrency` range widened to 16.



The `ProxyChunkGenerator` / `ChunkRegenerator` backwards-reset design, the save guards, the `nogen` test
scaffolding and the `WorldGenRegion` write guard were **deleted**. `server/ProxyChunkGate` gates
`ChunkHolder.updateFutures` so a proxy never builds its own FULL task, classifies disk-FULL vs proxy with a
cheap EMPTY probe, serves reads from an `EmptyLevelChunk` placeholder, and promotes by adding to an allow-list
and forcing the FULL future (forward generation, no reset). `LevelWriteGuardMixin`, `FlowingFluidMixin`,
`PromotionManager`, `ChunkHolderAccessor` and the fast-flight move check are kept. `common/ProxyChunkUtil` is
client-side — kept. Full mechanism and verified vanilla signatures: `proxy-chunk-state-machine.md`.

Client render integration: vanilla's chunk renderer assumes contiguous client chunks; proxies are not client
chunks, so three separate vanilla behaviours broke. All patched client-side under the `neighbors` switch:
`RenderSectionNeighborsMixin` counts a proxy position as an existing chunk and reports a proxy section as
`CompiledSection.EMPTY`; proxy hide is exact (only when the real chunk is in `LevelRenderer.visibleSections`);
client reconciliation requests a proxy mesh whenever `isRendered` is false. Proxy water at sea level is sunk
`WATER_SINK=0.15` (the real surface is at `seaLevel-0.125`) so it sits just below the real water.

**Why the old design was debt**

- A player-ticketed chunk **self-progresses to FULL**: `ChunkLevel.generationStatus(level <= 33) == FULL`, and
  `GenerationChunkHolder.updateHighestAllowedStatus` reschedules a FULL generation task from the ticket alone —
  independent of any `getChunk` read. The proxy generator made that FULL pass cheap by no-op'ing every step, so
  the chunk landed as an all-air `LevelChunk` **stub**.
- Making a stub real meant pushing its status **backwards** (`ChunkRegenerator`), which vanilla never does. A
  neighbouring `ChunkGenerationTask` that already resolved the stub as a satisfied dependency then read it at
  EMPTY and vanilla threw `Requested chunk unavailable during world generation` (hard crash). The
  `canResetSafely` gate was synchronisation for this self-inflicted invariant break — real debt.
- The stub also broke cross-border features: a tree in a promoted chunk writes leaves into a neighbour, the
  `WorldGenRegionWriteGuardMixin` cancelled the write because the neighbour was a stub, and the neighbour never
  regenerated them.

**What the experiments proved**

- With **all** generation frozen (`/aerogel switch nogen`) and a central `ServerChunkCache.getChunk` intercept
  returning an in-memory placeholder, the server and client ran fine over a long flight (no watchdog, no
  crash). The player kept moving (packet-driven `handleMovePlayer`).
- Every blocking path — `Level.getBlockState`, `Level.getChunkForCollisions`, `Entity.setPosRaw`'s "ensure
  loaded" — funnels through `ServerChunkCache.getChunk`, which unconditionally `managedBlock`s on the
  generation future. **One intercept covers all of them.**
- `EmptyLevelChunk` (void-air `LevelChunk`, no storage, reports FULL) is a valid placeholder. It costs ~8 KB
  (4 heightmaps + tick containers + section array); a custom read-only placeholder would be lighter.

---

## 2026-09-12 (late-5) — fast-flight "missing row orthogonal to travel"

The incremental leading-edge enqueue only queued the single final column/row at `center ± radius`, but a full
rebuild only fires when the per-tick jump exceeds 2 chunks, so a 2-chunk jump left one intermediate row
unqueued (healed ~1 s later by the client `mesh_request` reconciliation). It now enqueues the whole swept band
(`ProxyStreamer.rebuildWanted`), still O(r·|delta|).

## 2026-09-12 (late-4) — automatic promotion + block-write guard

Compiles; the headless `aerogel.promote` probe passed (write deferred then replayed, chunk regenerated via the
vanilla pipeline). Not yet verified in-game at the time.

- **PromotionManager** — a chunk accrues one linger tick per tick a player is within `lingerRange` (Chebyshev,
  default 2 → 5x5) and decays when unoccupied; past a load-adaptive threshold (default **10 s idle .. 40 s
  loaded**, interpolated by the server's smoothed tick time) it is queued. **Landing** (player within
  `landing` blocks above the sampled surface, descending) and **block writes** queue immediately. The queue is
  drained nearest-to-player first under a never-lag budget: `concurrency` regenerations in flight (default 1),
  a cooldown that grows with load, and no new start once tick time is near the 20 TPS budget
  (`HARD_GATE_MS=60`). Negative feedback: promotion raises tick time → longer linger → fewer promotions.
- **Block write = promotion** — `LevelWriteGuardMixin` intercepts `Level.setBlock`; for a stub it cancels the
  write, records it, and queues the chunk, then `PromotionManager.replayWrites` re-applies it onto the real
  terrain after regeneration. Returning true preserves the client's prediction.
  `WorldGenRegionWriteGuardMixin` cancelled *worldgen* writes into stubs; fluid stayed cancelled by
  `FlowingFluidMixin` (fluid does not promote). Bounded write log (`MAX_WRITE_LOG=256`).
- **Reset safety gate** — `ChunkRegenerator.canResetSafely` refused to reset unless no holder within
  `ChunkStatus.MAX_STRUCTURE_DISTANCE` (8) had an active generation task. Deleted 2026-09-13 with the
  backwards-reset design.

## 2026-09-12 (late-2/3) — detail tiers, biome textures, sampler worker pool

Compiles; headless probe + bench passed. Fixed the black-biome / leaf-hole / water-on-mountain / missing-row
bugs.

- **Sampler worker pool** — `SamplerPool` owns N `OffThreadSampler`s, each with its own `RandomState`,
  `PointSurfaceSampler` and `BiomeTint`. Chunks routed by an 8x8-chunk region so neighbours share the corner
  cache; `poll()` round-robins so no worker starves. This is the radius >~32 lever; it does **not** help r≥200
  (renderer-bound). At radius 20 one thread runs ~0.11 ms/chunk with a near-empty backlog.
- **Biome textures** — one `BiomeSource.getNoiseBiome` probe per chunk at the centre surface y. The server
  caches `Biome -> representative BlockState` (snowy→snow, badlands→red sand, beach/desert→sand,
  jungle/forest/taiga→the matching **leaves**, windswept hills & mountains→**stone**, ocean/river→**sand** as
  their land fallback, else grass) and sends the **biome registry id + block-state id** per chunk. **The tint
  is computed client-side** (`BiomeColors`): `GrassColor`/`FoliageColor` read a pixel array only filled by the
  client's colormap reload listener, so on a dedicated server `Biome.getGrassColor` returns **0 (black)** — the
  "black biome" bug. The renderer draws the block's **top sprite** (block atlas, cached per `BlockState`),
  tinted by the client colour; leaf chunks get an opaque grass underlay 0.05 below the top so the cutout holes
  show grass, not the void.
- **Detail tiers** — `ProxyTopology` is a nested family: **5** (corners+centre), **9** (3x3), **25** (5x5),
  **81** (9x9); ids equal sample counts, coarser tiers are subsets of finer ones. The server samples `nearTier`
  within `nearDistance` (default 32) and `farTier` (default 5) beyond; a 20-tick promotion sweep re-samples and
  re-sends chunks entering the near band. The client bakes each chunk at **exactly the tier the server sent**,
  so the debug wireframe matches the textured geometry; a promotion is a pop.
- **Client streaming** — the mesh store is pruned only at `STORE_RADIUS=256` chunks (not the draw radius), and
  a chunk-cross forces a sync, so leading-edge meshes survive until the player reaches them. This fixed the
  transient rows perpendicular to travel; GPU buffers are still freed at the draw radius.

## 2026-09-12 (pm) — client flight path

- **The old renderer was the fast-flight hitch.** A single giant `VertexBuffer` was re-emitted + re-uploaded
  every frame while any morph played. Measured ~1400 chunks, ~37k quads, 1.5 ms avg with **49 ms spikes**.
  Fixed by one static `VertexBuffer` per chunk, uploaded once; the rise/sink is now a per-chunk **draw-matrix
  offset**, so geometry never changes for animation. Steady-state `render()` dropped to **0.05–0.9 ms**.
- **The void filler was a second stall.** A per-chunk flat mesh out to 2× render distance, rebuilt every
  500 ms, called `getChunkNow` per cell — measured **1427 ms avg** at join at render distance 32. Replaced by
  **one quad** moved under the camera by its matrix.
- **The remaining flight stall is NOT the proxy layer** — with `/aerogel switch render off` the hitch
  persisted; `aerogel.perf` showed the render thread stalling (vanilla chunk-mesh compilation, `after_sky`).
- **The "invisible wall every chunk" was vanilla anti-cheat.** `ServerGamePacketListenerImpl.handleMovePlayer`
  rejects moves with `d10 - d9 > 100` (≈10 blocks/packet) and teleports the player back, exempting only the
  singleplayer owner. Exempted fast creative flight / elytra via `ServerGamePacketListenerImplMixin`
  (`/aerogel switch movecheck`).
- **Skipping empty stub chunk sends did not help** and left skipped chunks permanently unsent; the
  `PlayerChunkSenderMixin` / `/aerogel stubs` / `ProxyStreamer.skipStubSends` feature was deleted.
- Also fixed: `/aerogel generate` server crash — `ChunkHolder.updateFutures`'s ENTITY_TICKING branch
  identity-checks `entityTickingChunkFuture` against the unloaded sentinel; the cached futures must be reset to
  that **exact** object before forcing re-promotion, and the completion callback must not swallow a throw.

## 2026-09-12 (late) — sparse sampler + persistence hole

- Replaced the batched sampler with the sparse `PointSurfaceSampler` (5/9-point, ~7–14 evals/chunk,
  ~0.1–0.2 ms; measurements v4). The old sampler needed ~8 threads to keep up; the sparse one keeps up on
  one.
- **Persistence hole found by the probe (and fixed).** A `getChunk(FULL)` pulled in a ~22×22 neighbourhood of
  dependency chunks that only reached `structure_starts`/`biomes`; the old `ProxyChunkGenerator` only tagged
  stubs in `fillFromNoise`, so those dependencies were serialized as empty chunks (~500 per region). Both save
  guards were changed to refuse **any all-air chunk while the proxy is active** (content-based, immune to tag
  pruning). Superseded by the 2026-09-13 gate design, which removed the save guards entirely.
- **Throughput / range.** The old `MAX_SUBMIT_PER_TICK=64` (=1280/s) was below the ~2000/s a radius-12 sweep
  demands — that was the "sometimes lagging behind" (not the sampler). Raised to 512/tick, 4096 pending,
  1024 drain. `rebuildWanted` queues only the leading edge (O(r)/cross) instead of sorting the whole window.
- **Client initial-fill trickle.** The client uploaded only 64 chunk buffers per sync; changed to a
  **wall-time budget (2 ms, hard cap 512) per sync** so a login burst fills as fast as the machine allows.

## 2026-09-12 — proxies are RAM-only; manual regeneration works

In-game verified: proxy terrain renders correctly (slope follows worldgen), `/aerogel generate` produces a real
chunk, the lattice dump works, the wireframe toggle works.

1. **Batched-sampler x-axis bug.** `BatchedSurfaceSampler` never called `chunk.swapSlices()`. `NoiseChunk`
   interpolates x between two noise slices and vanilla swaps them once per cell-X; without it slice0 stayed on
   cell 0 and every interior sample read the first x column. Symptom: one sloped quad then three flat ones,
   repeating. Fixed (and later made moot by the sparse sampler).
2. **Proxy chunks never persist** (old design) — `ChunkMapSaveMixin` + `ChunkSerializerGuardMixin`.
3. **`/aerogel generate`** — `ChunkRegenerator` allow-listed the chunk, seeded a fresh `ProtoChunk` at EMPTY,
   reset `startedWork`/`task`, then scheduled FULL; vanilla ran its own generation pyramid. On completion it
   forced `updateFutures` and pushed entities/players out of the new geometry.
4. **Commands/diagnostics** — `/aerogel grid`, `/aerogel wireframe`, `/aerogel flyspeed`.
5. **Renderer gotcha.** `Tesselator.begin` returns a `BufferBuilder` sharing one `ByteBufferBuilder`; two live
   builders corrupt each other. Build the edge wireframe in a separate pass.
6. **Headless tool** — `touch run/server/aerogel.regen` → `RegenProbe` (later merged into `aerogel.promote`).

## 2026-09-08 (2nd) — demo playable, user-verified

In-game verified: interception, streaming, mesh rendering (slope shading + directional walls), walking clamp,
Create flight, water guard.

1. **Fluid dirty-stub bug (root cause of "permanent holes")** — water flowing from real chunks wrote blocks
   into air stubs → stub counted as real → mesh skipped. `FlowingFluidMixin` cancels `FlowingFluid.spreadTo`
   when the target chunk is an air stub. Other block-writing paths were not guarded at the time.
2. **Packet-loss holes** — client→server reconciliation (`MeshRequestPayload`, nearest-first missing meshes
   every 1 s). Self-heals drops/reconnects within ~1–2 s.
3. **Walking feel** — server clamp is a through-floor net only (deadzone 2.0, skipped over REAL chunks); client
   clamp owns standing. Per-tick server teleports caused rubber-banding — don't reintroduce.
4. **Renderer transform** — NeoForge passes a NULL PoseStack at AFTER_TRANSLUCENT_BLOCKS; draw with
   `new Matrix4f(event.getModelViewMatrix()).translate(-cam)`.
5. **Perf numbers (under load, upper bounds)** — sampling ~15–16 ms/chunk with the old lattice sampler (THE
   bottleneck then); client mesh rebuild ≤1.1 ms for ~1000 chunks.

## 2026-09-08 (1st) — interception implemented + server-verified

All built; interception verified headless (`touch run/server/aerogel.probe`): control chunk from disk = real
terrain; fresh chunk = instant all-air stub (7 ms vs ~500 ms real gen). Server boots clean to Done with
Create+Aeronautics+Sable.

1. **`LevelChunk.isEmpty()` is hardcoded `return false` in 1.21.1** — used `ProxyChunkUtil.isAirStub`
   (scans `LevelChunkSection.hasOnlyAir()`) instead.
2. **mods.toml mixin declaration** must be a list of TABLES, not strings.
3. **NeoForge bus registration is per-bus strict** — `AeroGel` (mod bus) + `ServerHandlers` (game bus,
   static) + `ClientHandlers` (game bus, static, client dist only).
4. Console commands via stdin FIFO do NOT pass through gradle JavaExec → use the flag-file probe pattern
   (self-halts via `server.halt(false)`).
5. The proxy no-op'd all generation steps: createBiomes, fillFromNoise, buildSurface, applyCarvers,
   applyBiomeDecoration, createStructures, createReferences, spawnOriginalMobs.

## Early PoC — surface sampling (measurements v1–v3, superseded)

Kept for the reasoning that produced the production sampler.

- **v1 (naive per-column scan, superseded).** Density eval ~6–18 µs under load; per-column coarse scan
  ~80–420 µs. 16×16 pts/chunk ≈ 30–70 ms/chunk — too slow. No pointwise 2D caching (`cache2d`/`cacheOnce`
  are built for `fillArray` traversal, not pointwise `compute`).
- **v2 (vanilla-style cell lattice).** Sampling on the density cell lattice (cellWidth 4 × cellHeight 8,
  ~1225 evals/chunk) and trilinearly interpolating matches real terrain: rugged-chunk avg error 12.3 → 2.0,
  worst 7–9 (features). ~16.5 ms/chunk full grid, ~2.7 ms for 4 corners, and corners are shared across
  chunks.
- **v3 (client flight path).** See 2026-09-12 (pm). The old single giant `VertexBuffer`, the per-chunk void
  mesh, and the vanilla anti-cheat move check were the culprits, not the proxy layer.

---

## Pitfalls / gotchas learned (operational)

- SLF4J has NO printf specifiers — `{}` only; pre-format via `String.format`.
- The flag files (`run/server/aerogel.*`) trigger self-halting headless probes; delete them before a normal
  run.
- `rm -rf run/server/world` resets the world — only for timing baselines / after water-damaged stubs, never
  for a demo run.
- Machine-under-load inflates all timings ~2–3×; treat timings as upper bounds, relative comparisons matter.
- NeoForge mixin runtime = Mojmap names (no refmap needed).
- `ChunkGenerator.createState`/`randomState` are built from the ORIGINAL generator at `ChunkMap` construction;
  do not try to rebuild them.
- Dev world was wiped 2026-09-08 (water-damaged stubs) and again 2026-09-12 (headless tests); the fresh spawn
  circle is the baseline.
