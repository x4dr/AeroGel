# WS-05 — Sampler: cache budget, per-dimension ceiling, diagnostics waste

**Prereq:** WS-01 makes the pool per-dimension; adapt if signatures change.
**Read only:** `proxy/PointSurfaceSampler.java`, `proxy/OffThreadSampler.java`,
`proxy/SamplerPool.java`, `proxy/BiomeTint.java`, `common/ProxyTopology.java`.
Do not edit other files.

## Tasks

### 1. Bound the point cache (memory blowup)
`PointSurfaceSampler.MAX_CACHE = 512*1024` (`:29`) is **per worker**, and
`samplerThreads` can be 64 (`ProxyStreamer.java:65`). Worst case ~32M entries,
~500 MB+, while the comment claims "~8 MB". A worker pool of N should share a
budget.
- Either lower the per-worker cap (e.g. 64k-128k entries) or make the cap a
  function of thread count so the total is bounded (e.g. `TOTAL_CACHE_BUDGET /
  threads`). State the chosen total budget and the implied RAM.
- `clearCache()` is unused. When the cap is hit the current code clears the whole
  cache (`:111-115`), which throws away all shared-corner locality and causes an
  eval burst. Prefer a cheaper eviction (e.g. clear only when a fraction is
  stale, or a bounded LRU) — surface the tradeoff.

### 2. Per-dimension vertical ceiling
`CEILING = 191` and the `jMax` derivation (`:27`, `:47`) are overworld-shaped
(`minY=-64`, height 384). The nether is 128 tall (minY 0), the end 256. Once
WS-01 runs the sampler per dimension, derive the ceiling from
`settings.noiseSettings()` (`height()` / `minY()` / max y) instead of a constant
so surface detection works in every dimension.

### 3. Diagnostics waste on the worker
`BiomeTint.probe` (`:60`, `:64-67`) calls `describe(...)` (a `String.format` +
`holder.unwrapKey()` + concatenation) for **every sampled chunk**, only to
overwrite a diagnostic map used by `/aerogel biomes`. Move the string build out
of the hot path: cache the description per `Biome` (biomes are few), or build it
lazily in `dump()` from a `Map<Biome, BlockState>`.
- `DIAG` is a static `ConcurrentHashMap` mutated by every worker; fine, but with
  per-dimension biomes it can grow. Bound it or key by biome only (already).

### 4. Error handling
`OffThreadSampler.loop` catches `Throwable` (`:130`) and substitutes sea level.
Catching `Throwable` swallows `OutOfMemoryError`/`LinkageError` and keeps the
thread limping. Catch `Exception` (or `RuntimeException`) instead and let `Error`
propagate/kill the worker loudly.

### 5. Dead code
`PointSurfaceSampler.clearCache` is unused (task 1 either wires it or removes it).
Remove any other unused members you find.

## Verify
`JAVA_HOME=... ./gradlew :compileJava`. If cheap, the generation probe's sampler
self-test (`touch run/server/aerogel.probe`, then run the server) exercises all
tiers — only if the orchestrator asks.

## Report
- Chosen cache budget (total + per worker) and implied RAM.
- Per-dimension ceiling derivation.
- Diagnostics fix.
- Tradeoffs (cache hit rate vs memory, eviction policy).
