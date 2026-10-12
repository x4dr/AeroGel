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
   verification pending. Immediate promotion is plain vanilla generation (see
   `proxy-chunk-state-machine.md`), linger is budget-paced.
6. ~~Hardening pass~~ — DONE (2026-10-13). Void plane deleted, config validated, probes moved to the `dev`
   source set, outbound mesh batches split and `mesh_request` rate-limited, promotion de-urgented. Verified:
   loads on NeoForge 21.1.252 and the `aerogel.promote` probe PASSes. See `work_log.md`.
7. **In-game checks** — detail tiers, adaptive enforcement, automatic promotion, and the mass-write path. See
   `../AGENTS.md` CURRENT TASK.
8. **Collider PoC** — feed the low-poly surface into Sable Rapier
   (`RapierPhysicsPipeline.handleChunkSectionAddition` / VoxelColliderData) and vanilla entity collision;
   verify a fast hull stops without real chunks. Only the crude player clamp exists.

`investigations/` holds the research reports; `level-mixin-load-order.md` is a closed incident record (the
mixins are required on purpose — read it before "fixing" a load failure).

See `../AGENTS.md` for standing decisions.
