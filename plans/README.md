# AeroGel — Plan Index

- **`../AGENTS.md` — CURRENT TASK + living status. Read this first.**
- `architecture.md` — agreed design: server-authoritative coarse proxy terrain streamed to clients in fast
  flight corridors; promotion on linger/descent.
- `proxy-chunk-state-machine.md` — the current virtual-proxy gate design (verified in-game): mechanism,
  verified vanilla signatures, and the source-level research.
- `measurements.md` — live sampler/streaming measurements (v4 onward) and the budget model.
- `target-env.md` — exact MC/NeoForge/Create/Aeronautics/Sable versions, instance paths, install procedure.
- `work_log.md` — chronological history and rationale (superseded designs, session results). History only.
- `investigations/` — research reports feeding the design.

## Status / next steps

1. ~~Noise sampling PoC~~ — DONE. Production is `PointSurfaceSampler` (sparse, hinted) on `SamplerPool`;
   tiers 5/9/25/81 via `/aerogel detail near`. See `measurements.md` v4/v6.
2. ~~Proxy payload + handshake + client quad layer~~ — DONE (`aerogel:mesh_batch` / `aerogel:mesh_request`).
3. ~~Sampler worker pool + detail tiers + biome colours/textures~~ — DONE (`SamplerPool`, `BiomeColors`).
4. ~~Chunk lifecycle (virtual-proxy gate)~~ — DONE (`server/ProxyChunkGate`, verified in-game).
5. ~~Automatic promotion + block-write guard~~ — DONE (`PromotionManager`, `LevelWriteGuardMixin`); in-game
   verification pending.
6. **Collider PoC** — feed the low-poly surface into Sable Rapier
   (`RapierPhysicsPipeline.handleChunkSectionAddition` / VoxelColliderData) and vanilla entity collision;
   verify a fast hull stops without real chunks. Only the crude player clamp exists.
7. **In-game checks** — detail tiers, automatic promotion, rider chunk-ticket coordinate space, FULL-gen
   stall timing. See `../AGENTS.md` CURRENT TASK.

See `../AGENTS.md` for standing decisions.
