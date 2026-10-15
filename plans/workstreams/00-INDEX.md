# AeroGel performance-remediation workstreams

Baseline commit: `04496a8` (see `git log`). Source of findings: the 2026-09-13
performance review in this session. This directory exists so a fresh agent can
resume any workstream after a session loss.

## Ordering (run in series — one agent at a time, main working tree)

Collisions are avoided by **serial execution**, not worktrees. Each workstream
ends with `JAVA_HOME=<jdk21> ./gradlew :compileJava`
and a report. The orchestrator reviews + commits between workstreams.

1. `01-core-dimensions.md` — **do first.** Server state becomes per-dimension;
   this changes APIs every other server workstream consumes.
2. `02-promotion.md` — `PromotionManager` queue/linger/in-flight/budget.
3. `03-streamer.md` — `ProxyStreamer` wanted-list/refinement scans + cost analysis.
4. `04-client-render.md` — `ProxyRenderer` + client store/handlers.
5. `05-sampler.md` — `PointSurfaceSampler`/`OffThreadSampler`/`SamplerPool`/`BiomeTint`.
6. `06-cleanup.md` — net payloads, configs, dead code.

## File ownership (do not edit files outside your workstream)

| WS | Owns |
|----|------|
| 01 | `server/ProxyChunkGate.java`, `server/AdaptiveMode.java`, `server/ServerHandlers.java`, `server/*Probe.java`, all `mixin/*.java`; may touch `server/ProxyStreamer.java` + `server/PromotionManager.java` **only** for the per-dimension signature change (WS-02/03 own their optimisation afterwards) |
| 02 | `server/PromotionManager.java` |
| 03 | `server/ProxyStreamer.java` |
| 04 | `client/ProxyRenderer.java`, `client/ClientMeshStore.java`, `client/ClientHandlers.java`, `client/BiomeColors.java` |
| 05 | `proxy/PointSurfaceSampler.java`, `proxy/OffThreadSampler.java`, `proxy/SamplerPool.java`, `proxy/BiomeTint.java` |
| 06 | `net/*.java`, `AeroGel.java`, `common/AeroGelConfig.java`, `common/AeroGelClientConfig.java`, `common/ProxyChunkUtil.java`, `common/MeshMath.java` |

## Hard constraints (all workstreams)

- The virtual-proxy gate is **verified in-game**. Do not change the gate's
  fundamental mechanism (own-task gate via `ChunkHolder.updateFutures` redirect,
  EMPTY probe classification, forward-only promotion). Optimise around it.
- Mixin targets/`defaultRequire=1`: if you change a mixin target, update
  `aerogel.mixins.json` if needed and make sure the target still exists. A wrong
  target is a hard crash at load.
- Do **not** commit. The orchestrator commits after review.
- Do **not** run the dev server or the headless probes unless the plan says so.
- Keep every knob's compiled-in default + config entry + `/aerogel` command in
  sync if you add one.
- Report back: files changed, what you did, what you measured, what you could
  not verify, and any tradeoff you found (the user explicitly wants tradeoffs
  surfaced).

## Build command

```bash
JAVA_HOME=<jdk21> ./gradlew :compileJava
```

The dev server is stopped, so the Gradle project lock is free. Do not start it.

## STATUS — 2026-09-13: ALL WORKSTREAMS COMPLETE

Commits (oldest first): `8646439` WS-01, `1603ac6` WS-02, `808d250` WS-03, `ff84b8a` WS-04,
`3df8646` WS-05, `8364e6f` WS-06, `efea20c` dead-code sweep. All `:compileJava`/`:assemble` green.

**Verified headless after the refactor** (flag files, server self-halts):
- `aerogel.probe` → `VERDICT: PASS` (disk chunks real, fresh chunks instant placeholders) + all four
  sampler tiers (5/9/25/81) sample.
- `aerogel.promote` → `VERDICT PASS` (deferred write cancelled then replayed onto real terrain).
- `aerogel.promotestress` → `VERDICT PASS` (all 25 chunks real in 20 ticks).

**Not yet verified in-game**: nether/end proxy rendering, resource-reload sprite re-bake, the
per-dimension void-plane/sea decisions, and the new promotion eviction/timeout under real flight. See
`AGENTS.md` CURRENT TASK for the in-game checklist.

### Streaming cost table (WS-03 model — estimates marked)

Assumptions: uniform tier; `N=(2r+1)²`; per-chunk wire `= 8 + 2·count + biome + block` bytes; sampler
`0.15 ms/chunk @ tier 9` (5→0.083, 9→0.15, 25→0.42, 81→1.35; 25/81 estimated); steady state `(2r+1)·80`
chunks/s; client RAM `≈ 120 + 2·count` B/chunk; GPU ≈ 1.2 KiB/chunk.

| r | N | one-time CPU @9 | one-time net @9 | steady CPU cores @9 | steady net @9 |
|---|---|---|---|---|---|
| 20 | 1,681 | 0.25 s | 46 KiB | 0.49 | 90 KiB/s |
| 50 | 10,201 | 1.53 s | 279 KiB | 1.21 | 221 KiB/s |
| 100 | 40,401 | 6.06 s | 1.08 MiB | 2.41 | 440 KiB/s |

Client one-time RAM/GPU: r20 ≈ 227 KiB / ~2.0 MiB · r50 ≈ 1.34 MiB / ~12 MiB · r100 ≈ 5.3 MiB / ~47 MiB.
The dominant cost is sampler CPU (one thread saturates near r≈32); network and client memory are small at
normal radii. The always-on per-tick cost was the real problem, addressed by the WS-01 fast bail and the
WS-02/03 scan gating.
