# WS-06 — Cleanup: payload validation, dead payloads, config/common tidy

**Prereq:** none. Run last so it sees the final APIs.
**Read only:** `net/MeshBatchPayload.java`, `net/MeshRequestPayload.java`,
`net/ClientSwitchPayload.java`, `net/ClientSettingPayload.java`,
`net/WireframePayload.java`, `AeroGel.java`, `common/AeroGelConfig.java`,
`common/AeroGelClientConfig.java`, `common/ProxyChunkUtil.java`,
`common/MeshMath.java`. Do not edit other files.

## Tasks

### 1. Validate wire input (crash/DoS)
`MeshBatchPayload.read` (`:49-68`) trusts `count` and `topology` from the wire and
allocates `count * ProxyTopology.count(topology)` shorts. `MeshRequestPayload.read`
(`:28-35`) trusts `count`. A malicious/buggy peer can force huge allocations or an
invalid `topology` (which then indexes wrong in `ProxyTopology.side/spacing`).
- Bound `count` (e.g. ≤ 4096 for a mesh batch, ≤ 256 for a request) and reject /
  clamp. Validate `ProxyTopology.valid(topology)`; fall back to a safe tier or
  drop the payload.
- This is a custom payload, so throwing in `read` is handled by the network
  layer; a clean rejection is better than an OOM.

### 2. Validate block/biome ids before use
The client trusts `blocks[]`/`biomes[]` from the payload.
`ProxyRenderer.spriteFor` (`:218-247`) and `BiomeColors.isLeaves` (`:60-62`) call
`Block.stateById(blockId)` without a null check; an out-of-range id can NPE.
- Guard in the store/handler (drop or clamp invalid ids) or in the consumers.
  Prefer validating once in `ClientMeshStore.handle` so the render path stays
  cheap. `ClientMeshStore.java` is owned by WS-04 — **note the change in your
  report and let the orchestrator apply it**, or ask to edit it.

### 3. Remove the dead payload
`WireframePayload` is registered (`AeroGel.java:48-49`) but never sent — the
`/aerogel switch wireframe` path uses `ClientSwitchPayload`. Remove
`net/WireframePayload.java` and its registration, unless the orchestrator says a
server-push wireframe toggle is wanted.

### 4. Config/common tidy
- `common/ProxyChunkUtil.isAirStub`: fine, but confirm it is the only air-stub
  detector and document the `LevelChunk.isEmpty()` 1.21.1 caveat (it is).
- `common/MeshMath`: `surfaceAt` assumes overworld tier geometry; it is
  dimension-independent (local coords) — confirm and document. If a new tier is
  added, the `grid3` path assumes NINE specifically (`i*3+k`); note that
  `surfaceAt` only supports NINE and FIVE. Make the fallback explicit or throw.
- `common/AeroGelConfig` / `AeroGelClientConfig`: update comments to match any
  new knob WS-01..05 add, and remove config keys that no longer exist. Do not
  renumber/rename existing keys (breaks saved configs) unless necessary.
- `ClientSwitchPayload.FEATURES` vs `ServerHandlers.SWITCH_FEATURES`: the server
  list is owned by WS-01; just make sure the two lists stay consistent and the
  `defaultOn` table matches the client defaults in `ClientFeatures`/config.

### 5. Dead code sweep (non-owned files → report only)
List any remaining unused public methods/fields you notice across the tree and
put them in your report; do not edit files you do not own.

## Verify
`JAVA_HOME=... ./gradlew :compileJava`. Confirm the removed payload does not
break the network registrar.

## Report
- Validation bounds chosen.
- Id-validation change and where it should live.
- Dead code removed/listed.
- Tradeoffs.
