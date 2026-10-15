# AeroGel — Plan Index

- **`../AGENTS.md` — CURRENT TASK + living status. Read this first.**
- `architecture.md` — agreed design: server-authoritative coarse proxy terrain streamed to clients in fast
  flight corridors; promotion on linger/descent.
- `proxy-chunk-state-machine.md` — the current virtual-proxy gate design (verified in-game): mechanism,
  verified vanilla signatures, and the source-level research.
- `promotion-and-gating-rewrite.md` — the gate model and the disengage model: no opt-out, universal linger,
  the queue as a work buffer rather than a backlog, and why the server must not branch on whether a client
  renders proxies. Living design for the 2026-10-15 rewrite; unverified.
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
   `proxy-chunk-state-machine.md`); linger is budget-paced, and the gate may only disengage once the server is
   calm **and** the promotion queue and the in-flight set are both empty — read after the refill, so "empty"
   can only mean "nothing left to generate". The adaptive tick is split into `AdaptiveMode.sample` (before the
   promotion tick) and `AdaptiveMode.decide` (after it) so the refill and the decision read the same window
   maxima; the count of gated stubs that used to cover the mismatch is gone. Superseded in part by entry 7.
6. ~~Hardening pass~~ — DONE (2026-10-13). Void plane deleted, config validated, probes moved to the `dev`
   source set, outbound mesh batches split and `mesh_request` rate-limited, promotion de-urgented. Verified:
   loads on NeoForge 21.1.252 and the `aerogel.promote` probe PASSes. See `work_log.md`.
7. **Promotion and gating rewrite** — DONE and verified (2026-10-15): compiles, loads, all eleven probes PASS. The opt-out zone list is gone (the gate is
   global while enforcing, otherwise the union of opted-in `travel` zones), linger is universal and
   server-side, and the server no longer branches on whether a client renders proxies — `AdaptiveMode.renders`
   gates mesh *sending* only, which is what removes the uncapped leftover arm behind #15. The disengage model
   replaces the `hasLinger` veto: sample the window → refill (threshold bypassed while calm, budget never) →
   drain → may-disengage on queue *and* in-flight empty → lazy prune. `AdaptiveMode.sample` runs before
   `PromotionManager.tick` and `AdaptiveMode.decide` after it. `vanillaclient` is re-specified to assert
   *paced, not uncapped*. Design and open questions: `promotion-and-gating-rewrite.md`.
8. **In-game checks** — detail tiers, then the rewritten enforcement + promotion, and the mass-write path. See
   `../AGENTS.md` CURRENT TASK.
9. **Collider PoC** — feed the low-poly surface into Sable Rapier
   (`RapierPhysicsPipeline.handleChunkSectionAddition` / VoxelColliderData) and vanilla entity collision;
   verify a fast hull stops without real chunks. Only the crude player clamp exists.

`investigations/` holds the research reports; `level-mixin-load-order.md` is a closed incident record (the
mixins are required on purpose — read it before "fixing" a load failure).

See `../AGENTS.md` for standing decisions.
