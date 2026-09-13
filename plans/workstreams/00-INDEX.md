# AeroGel performance-remediation workstreams

Baseline commit: `04496a8` (see `git log`). Source of findings: the 2026-09-13
performance review in this session. This directory exists so a fresh agent can
resume any workstream after a session loss.

## Ordering (run in series — one agent at a time, main working tree)

Collisions are avoided by **serial execution**, not worktrees. Each workstream
ends with `JAVA_HOME=~/.local/share/PrismLauncher/java/java-runtime-delta ./gradlew :compileJava`
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
JAVA_HOME=~/.local/share/PrismLauncher/java/java-runtime-delta ./gradlew :compileJava
```

The dev server is stopped, so the Gradle project lock is free. Do not start it.
