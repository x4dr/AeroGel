# Investigation: Create Aeronautics 1.3.2 + Sable 2.0.5 + Create Simulated 1.3.2 internals (for AeroGel)

## ⚠️ Relevance to AeroGel — read this first

AeroGel is **not** an Aeronautics mod. It is a general fast-travel mod (works with elytra, trains, anything)
that intercepts chunk generation and supplies a low-poly proxy surface. The investigation below existed only
to answer ONE question: does a flying contraption need special handling for AeroGel's collision/sampling?

**Answer: mostly no.** The only special handling is that a plane's ground collision runs in the Sable/Rapier
physics engine against real uploaded chunk voxels — so the low-poly surface must also be fed to that physics
path as collider geometry (same data used for rendering, near the hull only). Everything else (what the player
sees, chunk generation/lag, streaming) is the ordinary vanilla path and needs no Aeronautics knowledge.

Sections **NOT relevant** to AeroGel's design (informational only): §1 (sublevel plot storage at 20M blocks),
§2 (tick loop details), §6 (sublevel client rendering). Sections **relevant**: §3 (collision model — the crux),
§4 (chunk tickets — no forward corridor, synchronous FULL gen), §5 (failure mode at speed), §7 (hook points),
§8 (config). Full findings below for reference; do not re-derive unless working on those pieces.

---

Research-only report. Sources (CFR-decompiled) live under `/tmp/opencode/src/`:
`aeronautics` (dev.eriksonn.aeronautics), `simulated` (dev.simulated_team.simulated), `sable` (dev.ryanhcode.sable) plus `rapier` (the jarjar'd `sable_rapier` physics impl) and `create` (Create 6.0.10). The companion math library (`Pose3d`, `BoundingBox3d`, …) was extracted from the Sable jar and decompiled to `/tmp/opencode/companion/out/`. All file paths below are relative to `/tmp/opencode/src/…` unless absolute.

> Decompiled with CFR; line numbers refer to the decompiled files. Where behaviour could not be confirmed from source it is flagged **[UNCONFIRMED]**.

---

## 1. What is a sublevel / "sable" structurally?

### Short answer
A **SubLevel** is a **separate block storage region ("plot") carved out of the host dimension's own chunk-coordinate space at very large coordinates**, plus a **pose** that maps that storage into the real world. The aircraft/contraption/blocks live in the plot storage; the *pose* translates/rotates them to their visible/simulated real-world position. It is **not** a separate `ServerLevel`/`ServerLevel` instance, and it is **not** a separate thread.

Key types: `dev.ryanhcode.sable.sublevel.SubLevel` (abstract), `ServerSubLevel`/`ClientSubLevel`, `dev.ryanhcode.sable.sublevel.plot.{LevelPlot,ServerLevelPlot,ClientLevelPlot,PlotChunkHolder,EmbeddedPlotLevelAccessor}`, `dev.ryanhcode.sable.api.sublevel.SubLevelContainer` + `ServerSubLevelContainer`/`ClientSubLevelContainer`.

### Anatomy

`SubLevel` (`sable/.../sublevel/SubLevel.java`) is deliberately small:
- `private final Level level;` (line 36) — the **host level** (the actual overworld `ServerLevel`/`ClientLevel`), *not* a fresh dimension.
- `private final Pose3d pose;` + `lastPose` (lines 37–38) — position/orientation/rotationPoint/scale of the sublevel content relative to the host world.
- `private final LevelPlot plot;` (line 42) — the plot *is* the chunk storage.
- `boundingBox()` = plot-content bounds transformed by the pose → "global" bounds in world space (lines 72–78):

```java
public void updateBoundingBox() {
    BoundingBox3ic plotBounds = this.plot.getBoundingBox();
    ...
    this.globalBounds.set(plotBounds.minX(), ... maxX()+1, ...);
    this.globalBounds.transform(this.pose, this.globalBoundsTransform, this.globalBounds);
}
```

`ServerSubLevel extends SubLevel implements PhysicsPipelineBody` (`sable/.../sublevel/ServerSubLevel.java`) — i.e. **the whole sublevel is one Rapier rigid body** (see §2). It also carries `trackingPlayers`, per-tick `latestLinearVelocity`/`latestAngularVelocity`, heat-map manager (for auto-splitting), floating-block controller, reaction-wheel manager, queued force groups, and a `MergedMassTracker`.

### The plot (chunk storage)

`LevelPlot` (`sable/.../sublevel/plot/LevelPlot.java`):
- Constructor takes `(container, x, z, logSize, subLevel)` and stores `plotPos = new ChunkPos(x, z)` (line 100) — **`plotPos` is a "plot ordinal" in chunk units, and the plot's real chunk extent is `plotPos << logSize`**:

```java
// LevelPlot.java
public ChunkPos getChunkMin() { return new ChunkPos(this.plotPos.x << this.logSize, this.plotPos.z << this.logSize); } // 163
public ChunkPos getChunkMax() { return new ChunkPos((this.plotPos.x + 1 << this.logSize) - 1, ...); }                    // 167
public ChunkPos toLocal(ChunkPos global){ return new ChunkPos(global.x - (this.plotPos.x << this.logSize), ...); }    // 175
public ChunkPos toGlobal(ChunkPos local){ return new ChunkPos(local.x + (this.plotPos.x << this.logSize), ...); }     // 179
```

- The plot owns a fixed 2-D array of `PlotChunkHolder`s of size `(1<<logSize)²` (lines 87, 102), each holder wrapping a real `LevelChunk` (vanilla `LevelChunk`) plus its light engine.
- The grid of plots is allocated by `SubLevelContainer` (`sable/.../api/sublevel/SubLevelContainer.java`):

```java
public static int DEFAULT_LOG_SIZE_LENGTH = 7;   // 128 x 128 plot slots
public static int DEFAULT_LOG_PLOT_SIZE = 7;     // each plot is 2^7 = 128 chunks a side  (2048 blocks a side)
public static final int DEFAULT_ORIGIN = 10000;  // plot grid offset
```

- `SubLevelContainer` is attached to every `ServerLevel`/`ClientLevel` by mixin (`sable/.../mixin/plot/container/ClientLevelMixin.java` line 53 constructs `ServerSubLevelContainer(…, 7, 7, 10000, 10000)`). So the plot storage area of plot slot `(i,j)` starts at chunk coordinate `(i+10000)<<7 ≈ 1,280,000` — **≈ 20 million blocks away from the real terrain**, in the *same* dimension's coordinate space.
- `allocateNewSubLevel`/`allocateSubLevel` (SubLevelContainer lines 150–171) grab the first empty grid slot and register the sublevel; each plot slot holds at most one sublevel. Occupancy persists across restarts (`SubLevelOccupancySavedData`).
- Plot chunk `LevelChunk`s are registered **into the host level's real `ChunkMap`** at their huge coordinates: `ServerLevelPlot.addChunkHolder` does `cache.chunkMap.updatingChunkMap.put(globalChunkPos.toLong(), holder)` (`sable/.../sublevel/plot/ServerLevelPlot.java` line 249). `ChunkMapMixin` (`sable/.../mixin/plot/ChunkMapMixin.java`) makes the chunk map treat plot chunks specially: they are never vanilla-saved (`saveChunkIfNeeded` → false), they are tracked by sublevel players, and chunk-map "has work" ignores them. So the plot region never goes through vanilla world-gen.

### How a Create contraption becomes a sublevel (assembly)

`SubLevelAssemblyHelper.assembleBlocks(ServerLevel, anchor, blocks, bounds)` (`sable/.../api/SubLevelAssemblyHelper.java:119`) is the canonical path:
1. `container.allocateNewSubLevel(pose)` where `pose.position = anchor` (line 126/142).
2. `plot.newEmptyChunk(plot.getCenterChunk())` then `plotAnchor = plot.getCenterBlock()` (lines 144–145) — the plot center, in the ~20 M block area.
3. Blocks are copied block-for-block from their real-world positions into plot-space by `AssemblyTransform.apply`: `pos' = rotate(pos - anchor) + plotAnchor` (lines 427–434). Originals are air-cleared (line 278+ `moveBlocks`).
4. Mass is recomputed from the plot chunks and the pose is fixed so the content renders back at the anchor: `subLevelCenter = anchor - plotAnchor + centerOfMass` (lines 149–156), then `pipeline.teleport(...)`.

Because everything about "where the aircraft is" is **stored as pose**, the plot does not move when the aircraft flies; the pose does. Content that flies out of the *simulation* region gets serialised to disk (see §5).

The Create Simulated layer that triggers this for players is `SimAssemblyHelper` (`simulated/.../util/SimAssemblyHelper.java`), used by the Physics Assembler block and Swivel Bearings:
- `assembleFromSingleBlock(level, selfPos, toAssemble, …)` (line 158) searches the connected structure as a Create `Contraption` (via `SimAssemblyContraption`), disassembles any pre-existing `ControlledContraptionEntity`s it overlaps (`disassembleAndAddCreateContraptions`, line 226), then calls `SubLevelAssemblyHelper.assembleBlocks` (line 165).
- `disassembleSubLevel(...)` (line 65) walks the plot's loaded chunks, reads real block states **from the host level at plot coordinates** (line 79 `level.getBlockState(pos)`), and copies them back out through an inverse `AssemblyTransform`.

On the client the mirror image is created from packets (`ClientSubLevel`, `ClientLevelPlot`) — the client container is fed plot chunk data via `SubLevelPlayerChunkSender.sendChunk` (`sable/.../sublevel/plot/SubLevelPlayerChunkSender.java`).

### `EmbeddedPlotLevelAccessor` and "plot"

`EmbeddedPlotLevelAccessor` (`sable/.../sublevel/plot/EmbeddedPlotLevelAccessor.java`) is a `CommonLevelAccessor`/`ServerLevelAccessor` wrapper **over the real host level** whose every method offsets the query by `plot.getCenterBlock()`:

```java
// EmbeddedPlotLevelAccessor.java:101-105 / 125-127
public EmbeddedPlotLevelAccessor(LevelPlot plot) {
    this.plot = plot; this.level = plot.getSubLevel().getLevel();
    this.center = plot.getCenterBlock(); this.centerChunk = plot.getCenterChunk();
}
public BlockState getBlockState(BlockPos blockPos) { return this.level.getBlockState(blockPos.offset(this.center)); }
```

It is a convenience "treat the plot's centre as origin" view used by Sable's own gizmo/commands/tests to place blocks into plot-space (call sites: `SableSpawnCommands`, `SableTestHelper`). It is **not** the ground-sampling path for flight (§3), but it shows the core trick: *plot-local coordinates are real host-level coordinates plus a large constant offset*.

### `.slvlr` storage

`world/sublevels/r.*.*.slvlr` is a custom region-format (`SubLevelStorageFile`/`SubLevelRegionFile`, `SECTOR_SIZE=128`, 32x32 chunks per file) managed by `SubLevelHoldingChunkMap` (`sable/.../sublevel/storage/holding/SubLevelHoldingChunkMap.java`). It persists two things:
- **Holding chunks** — serialised sublevel block content, keyed at the **real-world chunk where the sublevel last was** (`moveToChunk = chunk of logicalPose.position`, `saveAll()` lines ~96–130).
- **Sublevel data blobs** (`SubLevelSerializer.toData` → `ServerLevelPlot.save()`, which writes full section palettes + block-entity + ticks + light per plot chunk, see `ServerLevelPlot.java:322`).

The running instance in `run/server/world/sublevels/r.0.0.slvlr` is exactly such a parked/serialised sublevel. When the region around the real position of a sublevel unloads, `SubLevelHoldingChunkMap.processUnload`/`moveToUnloaded` serialises the whole sublevel into a holding chunk (and removes it from the physics world) — **a moving aircraft that leaves loaded chunks will be frozen & serialised, not flown through void** (§5).

### Implication for AeroGel
The sublevel is **not** a separate dimension and its blocks are **not** the terrain the aircraft flies over. The aircraft's own hull lives in plot storage ~20 M blocks away; the real overworld below the aircraft is ordinary overworld chunks. Proxy terrain therefore does **not** need to feed Sable's plot storage. It needs to feed (a) the normal chunk pipeline/tickets and (b) the Rapier scene (see §3/§4).

---

## 2. How is the aircraft simulated server-side?

### Tick loop & thread
- Sable hooks `ServerLevel.tick` at HEAD via `ServerLevelMixin.sable$tickPlotContainer` (`sable/.../mixin/plot/ServerLevelMixin.java`) → `SubLevelContainer.tick()`, which ticks all sublevels, then each registered `SubLevelObserver` in registration order.
- Observers are registered in `Sable.defaultSubLevelContainerInitializer` (`sable/.../Sable.java:61`): `SubLevelPhysicsSystem`, `SubLevelTrackingSystem`, `SubLevelTrackingPointObserver`, `SubLevelTicketLoadingSystem`.
- **Everything runs on the main server thread at 20 TPS.** There is no separate simulation thread. The Rapier step is a synchronous native call (below). Server config `sub_level_substeps_per_tick = 2` (see `run/server/config/sable-server.toml`) means the physics loop runs 2 substeps of 0.025 s per tick.
- Plots also tick their own light engine every tick (`ServerLevelPlot.tick` → `runLightUpdates`), and `ServerLevelMixin.sable$shouldTickBlocksAt` forces vanilla block ticking for plot chunks.

### `SubLevelPhysicsSystem` (`sable/.../sublevel/system/SubLevelPhysicsSystem.java`)
- One instance per `ServerLevel`. Holds a `PhysicsPipeline pipeline`, a `PhysicsChunkTicketManager`, config, and sets `currentlySteppingSystem`/`IN_PHYSICS_STEP` during the step.
- `tick(...)` (lines 174–198) each tick:
  1. `ticketManager.update(...)` (chunk loading, §4),
  2. tick sublevel actors,
  3. `pipeline.tick()` (flushes the `LevelAccelerator` block-state cache),
  4. for each substep: `prePhysicsTick` on each sublevel (BlockEntity actors like gyro-bearing/propeller contribute lift/drag/impulses — `ServerSubLevel.prePhysicsTick`, lines 201–261), `applyQueuedForces`, `pipeline.physicsTick(substep)`, then `updateAllPoses` reads the pose back from Rapier.
- `updatePose(...)` (lines 245–269) copies the native pose back into `subLevel.logicalPose()` and derives linear/angular velocity from the pose delta (`latestLinearVelocity`, `*20` scaling).

### Rapier integration (jarjar'd `sable_rapier`)
`PhysicsPipelineProvider` is a Java `ServiceLoader` (`sable/.../api/physics/PhysicsPipelineProvider.java`); the Rapier impl is in `/tmp/opencode/src/rapier/dev/ryanhcode/sable/physics/impl/rapier/`. `RapierPhysicsPipeline`:
- `add(ServerSubLevel, pose)` (lines 227–240): `Rapier3D.createSubLevel(scene, bodyID, pose)`; then `rotationPoint = massTracker.getCenterOfMass()`.
- **Terrain & sublevel voxels are uploaded as 16×16×16 chunk sections** via `handleChunkSectionAddition(LevelChunkSection, x, y, z, uploadDataIfGlobal)` (lines 315–341): it voxelises every non-air block through `RapierVoxelColliderBakery`/`PhysicsColliderBlockGetter` (which reads host-level block state at absolute coordinates) and calls `Rapier3D.addChunk(handle, x, y, z, data, global, bodyID)`. `global = (plot == null)` — i.e. **chunks whose coordinates fall outside the plot grid are registered as static "global" terrain in the same physics world**, at their real coordinates; chunks inside a plot are attached to that sublevel's rigid body (`global=false, id=<body>`).
- Kinematic Create contraptions (propeller-bearing contraption entities etc.) can also be registered as separate bodies: `add(KinematicContraption)` lines 247–289 uploads their local blocks as collider sections.
- `physicsTick(dt)` (lines 209–217): pushes kinematic contraption poses, then `Rapier3D.step(scene, dt)` — a synchronous native Rapier step; afterwards collision effects (particles/sound) are replayed by sampling `level.getBlockState` at the impact point (`processCollisionEffects`, lines 536–599).

So the mapping back to MC is: **native rigid body pose (position+orientation) ⇔ `logicalPose`**, read once per substep by `SubLevelPhysicsSystem.updatePose`, and consumed by (a) `ServerSubLevel.latestLinearVelocity` for networked snapshot packets, (b) entity-stick tracking that re-positions passengers, (c) bounding-box → chunk tickets.

### What is a rigid body / what gets registered
- Each `ServerSubLevel` = one dynamic rigid body with voxel colliders baked from its plot chunks.
- Static terrain = the uploaded "global" chunk sections (real overworld chunks near the sublevel's global bounds).
- Optionally: kinematic contraptions (Create contraptions *inside* sublevels, e.g. rotating propeller bearing structures), boxes, ropes, constraints (staff locks, physics-staff, docking connectors, alignment constraints used at disassembly).

### Implication for AeroGel
Physics is deterministic, main-thread, and tightly coupled to block-state reads from *host-level* coordinates. The sublevel body is tiny (the hull); the expensive part at speed is the "global" terrain upload + the synchronous FULL-chunk loads behind it (§4/§5).

---

## 3. World collision — the crux

There are **two separate collision systems**, and this is where the "offset mini-world" hypothesis gets confirmed for the hull and refuted for the ground:

### (a) Entity-vs-sublevel collision (hull only, in plot space)
`SubLevelEntityCollision.collide(...)` (`sable/.../sublevel/entity_collision/SubLevelEntityCollision.java`) replaces vanilla entity movement when an entity is near/tracking a sublevel:
- It converts the entity's world AABB into **sublevel-local coordinates** with `rotatedContextBounds.transformInverse(logicalPose, …)` (lines 199–200), iterates `BlockPos.betweenClosed(...)` over that local box (line 206), and reads block states **through a `LevelAccelerator` on the host level** (`accel.getBlockState(block)`, lines 218/233) — which hits the *plot storage* (≈20 M offset coords), i.e. **the sublevel's own blocks**.
- Voxel boxes are then pushed back into world space with `subLevelPose.transformPosition(cubeOBB.getPosition())` (line 242) for SAT resolution against the entity OBB.
- A "tracking sublevel" concept sticks the entity: if the surrounding local volume has no solids, tracking ends (`stopTrackingAtEnd`, lines 216–227).

Server players inside a sublevel get a very cheap special case (lines 87–99): they are glued to the floor (`setOnGround(true)`, Y velocity zeroed). Their movement is driven by the sublevel pose, not by vanilla terrain collision.

**Nothing here samples the overworld's real terrain.** The ground a plane lands on is not in the plot, so it never appears in entity-vs-sublevel SAT.

### (b) Body-vs-terrain collision (in the Rapier world, world space)
The aircraft-as-rigid-body collides against **real overworld terrain** only to the extent that real overworld chunks are present in the Rapier scene as "global" sections. `RapierPhysicsPipeline.handleChunkSectionAddition` builds those from actual `LevelChunk` section data at real chunk coordinates (§2). There is **no ground-height sampling method** in Sable that reads overworld heights; ground collision is purely geometric voxel-vs-body in the native world. If a chunk is not loaded & uploaded, there is simply **no collision there** (the aircraft would pass through a not-yet-loaded mountainside, and only gets "stopped" once the chunk is generated & uploaded — see §4/§5).

### Where sublevel *content* samples the real world (the modders' escape hatch)
Blocks and code inside a sublevel that need real-world data call `Sable.HELPER.projectOutOfSubLevel(level, blockPos)` — this converts a (local) position into the real world so the caller can then query real blocks/biome. Example in Aeronautics: the gyroscopic propeller bearing's gravity sense:
`GyroscopicPropellerBearingBlockEntity.updateTilt(...)` uses `DimensionPhysicsData.getGravity(level, projectOutOfSubLevel(level, atCenterOf(this.getBlockPos())))` (`aeronautics/.../gyroscopic_propeller_bearing/GyroscopicPropellerBearingBlockEntity.java:145`). Other Create compat helpers (nozzle hover checks, frogports etc.) use the same pattern. There is **no generic "sample ground height under the sublevel" API in Sable core**; ground truth always comes from the real world, and mods that need it must project themselves.

### Implication for AeroGel
The single most important architectural fact: **terrain collision for a sublevel aircraft is not fed by plot blocks, and not by a dedicated sampler — it is fed by whatever real overworld chunks are loaded and uploaded into the Rapier scene.** A proxy-terrain hack must either (a) place cheap proxy voxels into the Rapier scene for regions ahead of the body, or (b) work at the chunk/rendering level so the real chunks the aircraft needs actually exist ahead of time. There is no "height query" funnel to intercept in the flight loop itself.

---

## 4. Chunk loading around the aircraft — corridor width math

The loading driver is `PhysicsChunkTicketManager.update(...)` (`sable/.../sublevel/system/ticket/PhysicsChunkTicketManager.java:85`), called at the top of each `SubLevelPhysicsSystem.tick` (line 177). It works on **world-space bounding boxes**:

```java
// PhysicsChunkTicketManager.java:135-141
b.set(subLevel.boundingBox());                 // global bounds = pose(content)  → real-world position of the aircraft
b2.set(b);
if (subLevel.lastPose().position().distanceSquared(subLevel.logicalPose().position()) > 0.0025) {
    system.getPipeline().getLinearVelocity(subLevel, velocity.zero()).mul(timeStep); // timeStep = 0.05
    b2.move(0.0, Mth.clamp(velocity.y, -20.0, 20.0), 0.0);   // <-- prediction only along Y
    b.expandTo(b2);
}
b.expand(1.0, b);
BoundingBox3i chunkBounds = b.chunkBoundsFrom();
```

Reading that closely:
- The ticketed footprint is **the aircraft's own world-space hull** (its pose-transformed plot bounds), expanded by 1 block. **There is no horizontal look-ahead.** `MAX_PREDICTION_DISTANCE = 20.0` (line 80) is unused for sublevels; the only displacement added to the box is vertical (falling sublevels load the ground beneath them one tick early).
- Because `boundingBox()` is recomputed from the pose every tick, the box follows the vehicle tick by tick; chunks behind expire after `20` ticks of non-refresh (`expirePhysicsChunkTickets`/`expireForcedInhabitedChunks`, lines 210–245). Chunks *ahead* are only ticketed when the vehicle's hull actually reaches them.

What happens to chunks in the box, per column and per section (lines 146–180):
- For each column: `isChunkLoadedEnough(level, x, z)` returns `true` immediately **if the column is inside the plot grid** (`inBounds`) — the plot's own far-away chunks are always considered loaded (lines 293–299). Otherwise it requires the vanilla `DistanceManager` to report **`inBlockTickingRange`** (block-ticking level of loading).
- If a column is *not* block-ticking:
  - If the sublevel is **force-loaded** (has a ticket recorded in `sable_sub_level_force_load_tickets.dat`, i.e. `container.collectForceLoadedSubLevels()` non-empty), or a **player is on board** and `SUB_LEVELS_WITH_PLAYERS_CANNOT_UNLOAD=true`: `inhabitChunk(...)` (lines 184–208) adds a **vanilla `Ticket` of level `ENTITY_TICKING`** (`TicketType "sable_sub_level_loaded"`) and — critically — calls `level.getChunk(x, z, ChunkStatus.FULL, true)`, which can **block synchronously to generate a FULL chunk**.
  - Otherwise (autonomous, no force-ticket, no player): `holdingChunkMap.moveToUnloaded(subLevel, chunkPos)` (line 164) — the **entire sublevel is serialised out** (§1/§5).
- For every section in the box that *is* loaded, `addTicket(...)` (lines 247–260) reads the real `LevelChunk` section and calls `pipeline.handleChunkSectionAddition(...)` to (re)upload it into the Rapier world. If the chunk is not a FULL chunk yet this also calls `level.getChunk(x, z)` (blocking default FULL).

### Where does this happen — which dimension/coordinates?
- **Sublevel's own translated world**: chunks inside the plot grid (≈1.28 M chunk coords) are always "loaded" by definition and tick/upload only when content changes.
- **Overworld below the aircraft**: the columns under the vehicle's hull in **real world coordinates** are the ones that must reach at least block-ticking; their sections are uploaded as Rapier "global" geometry. So yes — **the ticket machinery can and does force real overworld chunk generation**, but only (a) inside the hull's current columns, (b) synchronously on the main thread, and (c) only when the vehicle is inhabited/force-loaded. There is no pre-emptive corridor in front.

(`sable_sub_level_force_load_tickets.dat` ↔ `SubLevelTicketsSavedData` + `ServerSubLevelContainer.addForceLoadTicket/collectForceLoadedSubLevels`, `sable/.../api/sublevel/ServerSubLevelContainer.java:157+`, and `SubLevelTicketLoadingSystem` reinstates tickets on reload.)

### Implication for AeroGel
Corridor width today ≈ **aircraft length/width (its hull)**, moving with the vehicle; ground chunks matter only once the hull descends into them; every newly-entered chunk forces a synchronous FULL generation when a player rides along. There is currently no "wider corridor" knob — AeroGel would have to widen/predict the ticket set itself (§7) and feed coarse terrain into either the vanilla pipeline or the Rapier upload.

---

## 5. Fast-movement behaviour / failure mode

There is **no LOD, no proxy, no "skip" logic** anywhere in the loading/simulation path. What actually happens at speed is governed by the exact same ticket loop as §4:

1. At tick *t* the sublevel's hull box is over chunks that are already block-ticking (loaded earlier when slower, or because terrain was pre-generated by the player's vanilla movement).
2. At tick *t+k* the box has moved onto columns that are **not** block-ticking (either never generated, or still generating).
3. If a player is aboard (the normal case): `inhabitChunk` → `distanceManager.addTicket(...ENTITY_TICKING)` + **`level.getChunk(x, z, ChunkStatus.FULL, true)`** — a synchronous, main-thread FULL chunk generation of exactly the columns the hull just arrived on. Rapier then uploads those sections the same tick (`addTicket`), so the aircraft "finds" the ground/mountain one tick *after* it arrived — i.e. it can clip into not-yet-generated geometry, then be pushed out/stopped with a hitch.
4. If no player is aboard and the sublevel has no force-load ticket: **the sublevel itself is frozen and serialised to `.slvlr`** (`moveToUnloaded`) as soon as any hull column is not block-ticking; it resumes later via `SubLevelHoldingChunkMap.loadHoldingSubLevel` / `processChanges`. Autonomous long-range flight beyond loaded chunks is therefore effectively *disallowed* by design.
5. There is also a Y-safety removal: `ServerSubLevel.tick()` removes sublevels whose world bounds leave `sub_level_remove_min/max` (`-10000..100000`) — `sable/.../sublevel/ServerSubLevel.java:158`.

### Does Create/Aeronautics translate the world, or move the vehicle through world coords?
**The vehicle moves through real world coordinates conceptually; its blocks are translated** (pose) so that the *storage* never moves. Specifically:
- The hull's world position = `pose` of the sublevel (real coords; the physics integration, the tickets, the bounding box, the passenger tracking all operate in real world space).
- The hull's *blocks* stay parked in the plot grid ~20 M away and are mapped to real coords only by the pose (rendering and SAT both apply `pose.transformPosition(...)`).
- Client-side rendering offsets the plot content with `SublevelRenderOffsetHelper` (`sable/.../util/SublevelRenderOffsetHelper.java`): `ps.translate(pose.position - camera) …` so the parked block geometry appears at the vehicle's real location; `translation(center)` returns the pose translation for entity/BE drawing.
- Passengers/riders are **re-positioned to follow the pose** (`kickRidingEntity` in `EntityRidingSubLevelVehicleHelper` uses `subLevel.logicalPose().transformPosition(position)`; tracking points store local positions and `EntityMixin.sable$load` re-freezes riders to a local anchor `sable$freezeTo(subLevelId, localAnchor)`, `sable/.../mixin/tracking_points/EntityMixin.java`).

So the practical answer: flying at 120 blocks/s = ~7.5 chunks/s; the plane overtakes chunk generation the moment it crosses into ungenerated terrain, the front columns get generated **synchronously as the hull arrives**, causing stutter, and there is no forward-prefetch corridor to help — **the failure mode is "chunk-gen stall right at the nose" (or, un-crewed, auto-serialisation)**.

### Implication for AeroGel
This is exactly the gap AeroGel wants to fill. Because Sable itself only forces the hull footprint, AeroGel adding a *forward/wide corridor* ticket set is additive and safe; because ground collision needs real geometry only at contact, proxy *rendering* terrain can be client-side while real generation stays lazy; but note the **server-side synchronous FULL gen at the hull** cannot be fully avoided unless the hull corridor is pre-generated (or Rapier gets proxy colliders) before the hull arrives.

---

## 6. Client rendering of terrain + sublevels

### Sublevel rendering pipeline (client)
- The client stores sublevels in a `ClientSubLevelContainer` on the `ClientLevel` (`mixin/plot/container/ClientLevelMixin.java:53`).
- Server → client data: `SubLevelTrackingSystem` (`sable/.../sublevel/system/SubLevelTrackingSystem.java`) tracks every player within `SUB_LEVEL_TRACKING_RANGE` (default **320**, `sable-common.toml`) of the sublevel pose. On start it sends a bundle: `ClientboundStartTrackingSubLevelPacket` (UUID, poses, plot bounds), then **one vanilla-style chunk packet per plot chunk** (`SubLevelPlayerChunkSender.sendChunk`, bundle line 136–155), then `ClientboundFinalizeSubLevelPacket`. Movement is streamed continuously as `ClientboundSableSnapshotDualPacket` entries (pose + linear/angular velocity), batched over TCP (and optionally UDP), every tick (~line 239–341).
- On the client these chunks land in the *plot region* (huge coords) but the vanilla renderer must not draw them as normal world chunks at those coords; instead `LevelRendererMixin` (`sable/.../mixin/sublevel_render/impl/vanilla/LevelRendererMixin.java`):
  - `compileSections` TAIL (line ~55): each sublevel's `SubLevelRenderData.compileSections(...)` is run separately;
  - `renderSectionLayer` inject (`sable$renderSubLevels`, line ~88): after the normal vanilla layer pass, `SubLevelRenderDispatcher.get().renderSectionLayer(sublevels, …)` draws the sublevel sections **with the pose applied** so the parked plot geometry appears at the aircraft's real position (see `VanillaSubLevelRenderDispatcher`, `VanillaChunkedSubLevelRenderData`, `VanillaSingleSubLevelRenderData`, and `SublevelRenderOffsetHelper`).
  - Occlusion queries & "is section compiled" are redirected through the container (`sable$cull`, `sable$isSectionCompiled`).
- Renderers are pluggable: `sub_level_renderer` config (default vanilla; a "fancy"/mesh renderer and Sodium-specific impls exist under `render/fancy` and `sublevel_render/impl/sodium`). Aeronautics mixes into the vanilla chunked path only to swap shaders for levitite (`aeronautics/.../mixin/levitite/VanillaChunkedSubLevelRenderDataMixin.java`).

### What the pilot actually sees of the overworld below
The **real overworld terrain is drawn by the normal vanilla chunk renderer through the normal client chunk pipeline**, because the vehicle and its occupants live (for all game-logic purposes) at the pose's real-world coordinates: server-side, occupants are tracked players whose vanilla chunk tickets are served from plot chunks via the container ([UNCONFIRMED nuance: whether the ServerPlayer entity object's coordinates are stored in plot space or world space — see "Biggest unknowns"]); the terrain under/around the aircraft is streamed as ordinary chunks and rendered by ordinary `LevelRenderer` sections with normal (Distant-Horizons-compatible) chunk meshing. The sublevel pass is drawn *on top* of, and located at, the same real position as the terrain. When the pilot looks down at un-generated terrain, what they see is governed by the **normal client chunk load/render** — i.e. the same pop-in/DH behaviour as flying an elytra — plus the server-side synchronous generation described in §4/§5. There is **no custom terrain representation** for the world below an aircraft in Sable; the only non-vanilla terrain rendering is the small offset sublevel overlay for the aircraft itself.

### Implication for AeroGel
Client-side, AeroGel's proxy terrain can simply target **normal chunk rendering** for the region under/near the aircraft (which is exactly where Distant Horizons already operates), while keeping the sublevel overlay untouched. It does not need to understand Sable's render dispatcher at all — only the real chunk region around the pilot.

---

## 7. Extension / hook surfaces for AeroGel

### Mixin configs present
- Sable: `sable.mixins.json` and `sable-neoforge.mixins.json` (top-level in `sable-neoforge-1.21.1-2.0.5.jar`). Compatibility contraption mixins live under `dev.ryanhcode.sable.neoforge.mixin.compatibility.create.*` (e.g. `AbstractContraptionEntityMixin`, `ContraptionColliderMixin`, `VisualizationEventHandlerMixin`).
- Create Simulated: `simulated.mixins.json` / `simulated-neoforge.mixins.json` (`create_assembly`, `assembly_preventer`, `accessor` groups; `SimAssemblyHelper` is the assembly entry).
- Aeronautics: `aeronautics.mixins.json` / `aeronautics.neoforge.mixins.json` (groups: `propeller_collision`, `propeller_bearing`, `balloon`, `sable_hooks`, `levitite`, `render/vanilla|sodium|iris`).

### Public API / service-loader extension points (Sable)
- `dev.ryanhcode.sable.api.*`:
  - `sublevel.SubLevelContainer` / `ServerSubLevelContainer` / `SubLevelObserver` (add your own observer next to the physics/tracking systems — `addObserver`).
  - `sublevel.ticket.{SubLevelLoadingTicket, SubLevelLoadingTicketType, ...}` + `ServerSubLevelContainer.addForceLoadTicket(...)` — **this is the documented mechanism to force a sublevel (and its dependency chain) to stay loaded**. Tickets are persisted to `sable_sub_level_force_load_tickets.dat`. Only one built-in type: `COMMAND_FORCED`. This is the cleanest "keep the aircraft loaded" hook.
  - `sublevel.KinematicContraption` (registering extra Create contraptions as physics bodies), `ServerSubLevelContainer` expose `getPipeline()` etc.
  - `physics.PhysicsPipelineProvider` (Java `ServiceLoader`, `LoadPriority`-ordered) — a mod could substitute/extend the pipeline (Rapier is the impl; there is also `physics.impl.none.StaticPhysicsPipelineProvider`).
  - `physics.collider.VoxelColliderData`, `physics.collider.SableCollisionContext`, `block.BlockSubLevelDynamicCollider`, `block.BlockWithSubLevelCollisionCallback`, `block.BlockSubLevelCollisionShape` — **collider shape control for sublevel blocks** (the shape-level place AeroGel could add "fake ground colliders" if proxy collisions are wanted inside the sim).
  - `entity.EntitySubLevelUtil`, `SubLevelHelper` (`projectOutOfSubLevel`), `SubLevelAssemblyHelper` (assembly/disassembly).
  - `event.SablePrePhysicsTickEvent` / `SablePostPhysicsTickEvent` / `SableSubLevelContainerReadyEvent` (NeoForge wrappers: `neoforge.event.ForgeSable*`, published through `SableEventPublishPlatform`). Aeronautics subscribes exactly this way (`aeronautics/.../Aeronautics.java:89-90`).
- NeoForge events used by Aeronautics: container-ready → `BalloonMap.BalloonSubLevelObserver` (`aeronautics/.../events/AeronauticsCommonEvents.java:41-52`); physics tick → balloon map physics (`onSubLevelContainerReady` / `physicsTick`).
- `SubLevelTrackingPlugin` (`api/sublevel/SubLevelTrackingPlugin.java`) + `SubLevelTrackingSystem.addTrackingPlugin(...)` — add extra per-player tracking data to the sublevel snapshot pipeline (useful if AeroGel wants to piggy-back coarse-height payloads onto the existing low-latency movement channel).

### Network pattern (server → client) worth copying
- Packet infra is not vanilla; Sable uses **Veil's `CustomPacketPayload` bridge** (`foundry.veil.api.network.VeilPacketManager`), TCP for chunk/full sync, optional UDP for pose snapshots (`SableUDPServer`).
- Aeronautics has its own thin payload layer (`network/AeroPacketManager.java`, `network/packets/*`) and Simulated likewise (`simulated/.../network/packets/**`) — these are the patterns to mimic for streaming coarse heights (register a payload type + play context in a NeoForge mod bus event, or reuse Veil).
- For sublevel pose sync the cadence is ~every tick with `ClientboundSableSnapshotInfoDualPacket` + batched `ClientboundSableSnapshotDualPacket` entries (16/batch) — a natural template for AeroGel's cheap per-chunk height columns.

### Three AeroGel-specific interception points (ranked for effort)
1. **Terrain collision** — inside the Rapier world. Either (a) register additional "global" chunk sections *ahead* of the vehicle (call `SubLevelPhysicsSystem.getPipeline().handleChunkSectionAddition` with coarse synthetic sections), or (b) intercept at `RapierVoxelColliderBakery`/`VoxelColliderData` so proxy blocks produce colliders. No vanilla sampler exists to redirect.
2. **Chunk ticketing/generation** — add a widening/predictive ticket around the sublevel each tick, or register an extra `SubLevelObserver` that drives `DistanceManager.addTicket` / `level.getChunk` for the flight corridor; combine with `addForceLoadTicket` for the hull itself. Nothing in Sable today pre-ticks the corridor, so this is additive.
3. **Client fake/coarse terrain** — normal chunk pipeline/DH on the client, fed either by standard level-chunk packets AeroGel streams itself (following the Aeronautics payload pattern) or by client-side heightmap injection; Sable's sublevel overlay is irrelevant here.

---

## 8. Config surface

### `run/server/config/aeronautics-server.toml`
- `[physics]` thrust/airflow scaling for each propeller (wooden/andesite/smart/propeller-bearing `propellerBearingThrust=0.2`, `*Airflow=0.05`…), `hotAirStrength=1.5`, `steamStrength=1.5`, `recoil_magnitude`.
- `[blocks]` burner max/range, steam vent values, levitite crystallization toggle.
- `[kinetics]` stress values only. **No view distance / loading / corridor knobs.**

### `run/server/config/sable-server.toml`
- `sub_level_substeps_per_tick = 2` (physics accuracy/perf).
- `sub_level_storage_pruning = false`.

### `run/server/config/sable-common.toml` (the interesting knobs)
- `sub_level_tracking_range = 320.0` — network/render tracking radius for sublevels.
- `sub_levels_with_players_cannot_unload = true` — inhabited sublevels never serialise-out; forces synchronous chunk loads instead (§5).
- `sub_level_remove_min/max = -10000/100000` — Y bounds; sublevels outside get deleted.
- `sub_level_velocity_retained_on_load = 0.9` — momentum kept across load/serialisation cycles.
- `sub_level_splitting`, `sub_level_punch_*`, UDP toggles, logging toggles.

### `run/server/config/simulated-server.toml`
- `[assembly]`: `maxBlocksMoved = 128000`, `honeyGlueRange=48`, `maxDisassemblyTicks=20`, `disassemblyDegreeTolerance=4.0`, `disassemblyMaxVelocity=5.0`, `disassemblyMaxAngularVelocity≈1.5708`, **`disallowMidAirDisassembly = true`** (must be near terrain to disassemble — checked by walking real chunks for non-air sections), `"Primary Disassembly"=false`.
- `[physics]` forces for magnets/docking/swivel joints/handles/physics staff, e.g. `handleMaxForce=120`, `swivel_stiffness=1600`.
- `[blocks]`, `[equipment]` – ranges for sensors/ropes/plungers.

### Implication for AeroGel
No existing config gives a "loading corridor width / view distance" for aircraft. The nearest substitutes to tune in an AeroGel config are: Sable's `sub_level_tracking_range`, `sub_levels_with_players_cannot_unload`, substeps, and the `create-common/create-server` view distances. AeroGel will need its own new knobs.

---

## Confirmed model (text diagram)

```
        REAL OVERWORLD (host ServerLevel, one world, one coordinate space)
   ┌────────────────────────────────────────────────────────────────────┐
   │                                                                    │
   │   "plot grid" (128x128 slots) lives at chunk x ≈ 1,280,000-1,296,384│
   │   (= (slotOrdinal+10000) << 7), i.e. block x ≈ 20,480,000 …        │
   │   Slot i,j can hold ONE sublevel. These LevelChunks are inside the │
   │   host ChunkMap but are PlotChunkHolders (never vanilla-gen'd,     │
   │   never vanilla-saved; saved to world/sublevels/*.slvlr instead).  │
   │                                                                    │
   │   ServerSubLevel = Rapier rigid body.                              │
   │      blocks stored : plot grid coords (static while flying)        │
   │      pose (position/quat/rotationPoint≈COM) maps plot → real world │
   │      world hull bbox  = pose(plot content bounds)                  │
   │                                                                    │
   │   real terrain chunks under the hull must be BLOCK-TICKING:        │
   │      . uploaded each tick as Rapier "global" colliders             │
   │      . collision of the body against ground = ONLY place terrain   │
   │        is sampled; no height query exists                          │
   │      . arrival into not-yet-loaded columns → synchronous FULL gen  │
   │        (player aboard)  or  sublevel serialised to .slvlr (empty)  │
   │                                                                    │
   │   occupant / pilot: follows pose at real-world position;           │
   │      server feeds them plot chunks (sublevel) + normal real        │
   │      chunks around the vehicle (terrain).                          │
   │      Client renders real terrain with vanilla LevelRenderer;       │
   │      sublevel (aircraft) drawn as extra offset render pass on top. │
   └────────────────────────────────────────────────────────────────────┘

   Flight = physics pose advancing through the real world at real coords.
   "Mini-world" = ONLY the aircraft's block storage (parked 20M blocks away),
   shifted visually by the pose. NOT the ground below.
```

Key confirmations:
- ✅ Sublevels are offset mini-worlds *of the aircraft itself*, translated through the world by a pose.
- ✅ The aircraft does **not** fly inside its own translated copy of the terrain; terrain comes from real overworld chunks.
- ✅ Ground is never "sampled through the plot"; it is voxelised from real overworld chunks into the Rapier scene, or (by content mods) via explicit `projectOutOfSubLevel` helpers.
- ✅ Chunk tickets do force real overworld generation, synchronously, at the hull's current footprint only.

---

## Top 5 hook points for AeroGel (ranked)

1. **`ServerSubLevelContainer.addForceLoadTicket` / `collectForceLoadedSubLevels`** (`api/sublevel/ServerSubLevelContainer.java:157,194`) — keep the aircraft's sublevel loaded no matter what (and its whole dependency chain via `SubLevelHelper.getLoadingDependencyChain`). Safest, fully supported.
2. **A custom `SubLevelObserver` + a per-tick corridor ticket** widening `PhysicsChunkTicketManager` behaviour (`sublevel/system/SubLevelPhysicsSystem.java:177` calls `ticketManager.update`; vanilla `DistanceManager` tickets are added in `PhysicsChunkTicketManager.inhabitChunk`, lines 184–208). AeroGel would add its own forward/wider corridor ticket each tick instead of the current hull-only footprint.
3. **Pipeline/collider layer for proxy ground** — `PhysicsPipelineProvider` (ServiceLoader) or, less invasively, feeding synthetic sections through `SubLevelPhysicsSystem.getPipeline().handleChunkSectionAddition(...)` (`RapierPhysicsPipeline.java:315`) so the Rapier scene contains coarse colliders ahead of the vehicle; collider shapes via `api/physics/collider/VoxelColliderData` / `api/block/BlockSubLevelDynamicCollider`.
4. **Network/streaming of coarse heights** — mirror `simulated/.../network/packets/**` + `aeronautics/.../network/packets/*` (register custom payloads on the NeoForge play channel) or attach to Sable's snapshot pipeline via `SubLevelTrackingPlugin` + `SubLevelTrackingSystem.addTrackingPlugin`. The client applies heights via the ordinary chunk pipeline/DH (§6).
5. **Client-side terrain feeding** — since the ground below is normal vanilla rendering, AeroGel's client hook is the standard server-chunk/heightmap path (e.g. intercept at the level-chunk/`Heightmap` or DH integration), *not* Sable's render dispatcher.

---

## Biggest unknowns / risks

- **[UNCONFIRMED] Occupant coordinate space.** Server code shows a rider's position is pose-projected to real coords for rendering/raycasts, while several systems (plot-chunk tracking for players, `ChunkMapMixin.getPlayers`, tracking-point persistence storing `transformPositionInverse(player.pos)`, `sable$freezeTo` local anchors, `PlayerList` broadcast using `distanceSquaredWithSubLevels`) are consistent with the ServerPlayer *entity* being physically stored in plot-space coordinates. Which of the two is the true server-side storage of a pilot, and how vanilla chunk tickets for that player are redirected, is not fully resolved from the decompiled source. Both models agree that real chunks near the vehicle are required and are what get generated; they differ on *which* machinery (player tickets vs physics tickets) supplies them. AeroGel should verify empirically (debug world, `/sable` + `debug_draw_loaded_chunks`) before betting on one.
- **Whether the Rapier "global" terrain upload needs full overworld chunks to be FULL (not just block-ticking).** `addTicket` uploads via `level.getChunk(x,z)`; the exact interplay of FULL status vs. `inBlockTickingRange` when chunks are mid-generation needs a live test — it determines exactly when the synchronous stall fires.
- **Native side not decompiled.** `Rapier3D` calls (`createSubLevel`, `addChunk`, `step`, collision output) are native; the coordinate conventions for plot coords vs local collider coords and the collision-feedback cadence are inferred, not read.
- **Client camera/world compositing details** for the interior view (how far the pilot's view is offset, where the "outside" frustum seam is) are spread across Veil/Flywheel/Sodium mixins and were not fully traced. Risk only if AeroGel needs to distort the pilot's view rather than the terrain.
- **Rendering of "flying over nothing":** if real chunks under the plane are absent client-side, the pilot may currently see pop-in/void; that is the exact experience AeroGel must replace — but its severity depends on server view-distance and the (unknown) player ticket redirection above.
- **Sable is under active design (sublevels can split via heatmaps, contraptions can be kinematic bodies in sublevels).** Any AeroGel hook must respect that a "sublevel" and a "contraption entity in a sublevel" are different physics objects.

---

## Verbatim notes on surprising findings

1. "Sublevels are offset mini-worlds" is **confirmed** — but the offset storage is ~20 million blocks away in the *same* dimension (`plotPos << logSize`, origin 10000, size 2^7), not a second dimension, and the sublevel content is only ever the aircraft/contraption, never a copy of terrain.
2. `EmbeddedPlotLevelAccessor` — despite its promising name — is **not** the ground-sampling accessor; it offsets every query by the plot centre and simply re-reads the host level. (It is used by gizmo/commands, not flight.) Any decompiler summary that implies "terrain is sampled through an embedded accessor" is misleading.
3. Terrain collision for sublevel aircraft = **Rapier body vs uploaded real-world chunk sections**. There is **no ground-height sampling method** in the flight path. AeroGel cannot "intercept the height query" because none exists; it must supply geometry (chunks or colliders).
4. The chunk tickets force **synchronous `level.getChunk(x, z, ChunkStatus.FULL, true)` generation of exactly the hull footprint** when a player is aboard, with **no forward corridor** (`b2.move` is Y-only; `MAX_PREDICTION_DISTANCE=20` is effectively unused for sublevels). Uncrewed sublevels that outrun loaded chunks are simply serialised to `.slvlr` and frozen, not flown through void.
5. Everything — assembly, voxel upload, Rapier `step`, chunk tickets — runs **on the main server thread at 20 TPS** (substep config = 2). There is no async physics thread to hide chunk-gen cost behind.
6. The client keeps two independent render feeds: **normal vanilla chunks for the real terrain** the pilot sees below, and an **extra offset render pass for the sublevel (aircraft)** whose sections come from plot chunks streamed per-tracking-player. Aeronautics only adds shader swaps on the sublevel pass (levitite); it does not touch real-terrain rendering.
7. `EmbeddedPlotLevelAccessor` name aside, the *actually* useful public "escape hatch" for content mods is `Sable.HELPER.projectOutOfSubLevel(...)` — used by Aeronautics' gyro bearing to sense gravity/position of the real world from inside a sublevel.
