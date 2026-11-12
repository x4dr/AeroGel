# Test plan — `trustClient` and the missing-chunk clamp

Two behaviour changes need in-game verification. Both are in the working tree and nothing here is verified
in-game yet. The design record is in `../AGENTS.md` (the **Clamp** and **Fast-flight move check** bullets) and
`work_log.md`.

Build under test:

```bash
JAVA_HOME=<jdk21> ./gradlew :assemble -Pmod_version=<version>
```

The jar also contains the uncommitted #21 rescue work (`ProxyChunkGate.rescueGenerated` enqueues a resend for
every position it moves from proxy to real), so a rescue resend in the log is expected.

## What changed

**A. `trustClient` disables vanilla's movement policing.** `stream.trustClient` (default false) /
`/aerogel switch trustClient true` used to exempt only elytra from "moved too quickly", and its creative arm
was dead code. It now turns off, for every player:

| check | where |
|---|---|
| "moved too quickly" | `handleMovePlayer`, the `d10 - d9 > f2 * i` guard |
| "moved wrongly" | `handleMovePlayer`, the `flag2` branch (via the `isCreative` arm) |
| move into a block that was not there before | `handleMovePlayer`, `isPlayerCollidingWithAnythingNew` |
| "moved too quickly" (ridden vehicle) | `handleMoveVehicle` |
| reject-and-resync (ridden vehicle) | `handleMoveVehicle`, the `flag && (flag2 || !flag3)` branch |
| "flying is not enabled on this server" kick | `tick`, the `getMaximumFlyingTicks` cap, player and vehicle |

Why: vanilla's allowance is a fixed 10 blocks per server tick with no elapsed-time term, compared against the
server position from the start of that tick, so a generation stall of `S` seconds rejects anything faster than
`10/S` blocks/s. The vehicle path has no packet-count scaling at all.

**B. A missing chunk is a proxy chunk (client clamp).** `ClientHandlers.onClientTick` applies wherever the
client has no real chunk (missing, air stub, or proxy):

- mesh present -> stand on `MeshMath.surfaceAt`;
- no mesh yet -> stand on a flat `MISSING_CHUNK_FLOOR_Y = 64.0` plane;
- when the chunk becomes real while the player's box is inside blocks -> one lift to the first free y, once,
  with or without mesh data.

It snaps up, zeroes the vertical delta and claims `onGround` (sprint/jump). The clamp is gated by its own
switch, not by `proxy`, so it works with no proxy stream at all. The descent-triggered promotion is unchanged.

**C. The server through-floor net now skips clients that clamp** (`AdaptiveMode.renders`), because the client
is the sole authority for them and two authorities oscillate. The net is unchanged for players with no
AeroGel.

## Setup

Switches, all live-tunable:

- client: `/aerogel switch proxy true`, `travel true`, `clamp true`;
- client: `/aerogel switch store true` (turn it off only for test 4);
- server (needs op level 2): `/aerogel switch trustClient true|false`.

**Check the join line first.** The client logs `client switches: proxy <b> travel <b> clamp <b>` on every join,
with a WARN if `proxy` is off. `proxy` is the master for the proxy layer: off means no meshes and no
reconciliation. `travel` off means the gate only applies while the server is backlogged, so no proxies appear
on a calm server. `clamp` is independent of both, and still holds the player on the mesh surface or the plane.

Server config equivalent: `stream.trustClient` in `config/aerogel-common.toml` (off by default). Client
equivalents: `proxy`, `travel`, `clamp`, `store` in `config/aerogel-client.toml`.

Useful field state: a fresh area (`/tp` far away so nothing is generated), high altitude, `view-distance 10`,
`allow-flight=true`. `travel` on is what makes the gate apply without waiting for a backlog.

Where the logs are: the server's `logs/latest.log`, the client's `logs/latest.log`. A worldgen mod that spams
`Detected setBlock in a far chunk` can dominate both; filter it out.

Lines to watch:

| line | level | means |
|---|---|---|
| `moved too quickly!` | WARN | vanilla speed check, player |
| `(vehicle of ...) moved too quickly!` | WARN | vanilla speed check, ridden vehicle |
| `moved wrongly!` | WARN | vanilla collision-divergence check |
| `was kicked for floating too long` | WARN | the "flying is not enabled" kick |
| `clamp: teleporting` | INFO | the server through-floor net (should not appear for a clamping client) |
| `lifted out of terrain on chunk arrival` | INFO | the client's one-shot lift |
| `pushed ... out of freshly generated terrain` | INFO | the server's promotion push-out (once per promotion) |
| `proxy diag:` | INFO | client, every 5 s: store size, mesh/loaded state, y |
| `server tick stall:` | WARN | AeroGel's own tick-time witness |

`/aerogel diag` dumps the one-shot client state (`store`, `within R: meshes/real/hidden/drawn`, `here`).

## Tests

### 1. Baseline — switch off, healthy server

1. `/aerogel switch trustClient false`.
2. Fly at 100-150 blocks/s over generated land for ~30 s.

PASS: no visible snap-back, no `moved too quickly` for you. Vanilla starts rejecting above ~200 blocks/s at
20 TPS; that is expected. Proves the off path did not change.

### 2. The reported bug — switch on, stalled server

1. `/aerogel switch trustClient true`.
2. Make the server stall: fly into fresh land at speed, or run a wide `/fill` over gated chunks. Watch for
   `server tick stall:` (> 200 ms) or `Can't keep up!`.
3. During and after the stall, fly 100+ blocks/s.

PASS: no teleport/reset, and no `moved too quickly` / `moved wrongly` for you, even though the stall lines are
there.

FAIL: any snap-back or any of those WARN lines. Re-run with the switch off: it should snap back, which is the
contrast that proves the switch. If it does not snap back with the switch off, the stall was not present and
the test is inconclusive.

### 3. Broom / ridden vehicle (the path the old switch never touched)

1. `/aerogel switch trustClient true`.
2. Ride a Hexerei broom or any client-controlled vehicle fast over fresh/gated land, through a stall.

PASS: no `(vehicle of ...) moved too quickly` and no rubber-band. With the switch off the same run should log
those lines.

### 4. Missing chunk, no mesh — the flat 64 plane

1. `/aerogel switch store false` (stop accepting new meshes).
2. With `proxy`, `travel`, `clamp` on, fly into land the gate has not streamed yet (or stall the server).
3. Watch `proxy diag` and y.

PASS: `hasMesh false, loaded none` (or `stub`) and y settles at 64 and stays. You can stand and jump. No
falling, no void. Also try it with `/aerogel switch proxy false`: the clamp must still hold at 64.

### 5. Mesh arrives

1. From test 4, `/aerogel switch store true` and stay put (reconciliation asks for the missing meshes within
   ~1 s; the server also serves them from its grid cache).
2. Watch y and `proxy diag`.

PASS: the mesh arrives (`hasMesh true`), y snaps up to the sampled surface once and stays; sprint and jump
work. A large pop up is expected under a mountain, because the plane is flat at 64.

### 6. Real chunk arrives — the lift

1. From test 5, stay until the chunk becomes real (linger, or touchdown by standing on the surface).
2. Watch the server log for `drained promotion ... push=` / `pushed ... out of freshly generated terrain`, and
   the client log for `lifted out of terrain on chunk arrival`.

PASS: at most one upward correction per arrival, then normal vanilla collision. No repeated teleports and no
`clamp: teleporting` for your player. If the client was inside the new terrain, it is lifted once (this is the
path that matters when there is no mesh at all, e.g. with `store false`).

### 7. A client that cannot clamp (vanilla)

1. Join with a client without AeroGel.
2. Have another player opted into `travel` gate the area, or make the server backlog so adaptive engages.
3. Fly/walk over the gated land at low altitude.

Expected today: the server net is enabled for this player but needs a `Grid` for the chunk, and nothing samples
a chunk for a client that does not render proxies (`ProxyStreamer.tick` skips them before submitting). So the
net returns early and nothing holds them; the linger refill promotes the chunk and `pushOutOfTerrain` lifts them
out. That is a pre-existing hole, not caused by change C. Record what happens.

### 8. Normal movement with the switch on

With `trustClient true`: walk, sprint, jump, climb, land from a fall, walk into a wall, ride a boat.

PASS: identical to vanilla for a normal client (its own collision does the work); no clipping through a wall
you can see, no fall-damage anomaly, no stuck-in-block.

### 9. Optional — the floating kick

Needs `allow-flight=false` in `server.properties`. A survival player standing on a proxy has no blocks under
them server-side. With the switch off, expect `was kicked for floating too long` after ~4 s; with it on,
expect no kick. Secondary, because a server that wants fast flight usually allows flight anyway.

## Expected behaviour, not bugs

- With `trustClient` on, a modified client can noclip and fly; that is the documented price and the reason the
  switch is opt-in.
- With `trustClient` on, a client whose world lacks terrain the server has can sink into it until the chunk
  arrives; the vertical correction is one of the checks the switch removes. The client lift covers the
  arrival, not the descent.
- The flat 64 plane applies in every dimension, including the End (invisible floor over the void until the
  mesh arrives).
- While standing on the 64 plane below the sampled surface, `onPlayerNearSurface` sees the player below the
  surface and queues the chunk for promotion. That is the intended "inside the terrain" response.
- Large upward pops when the mesh or the real terrain arrives are accepted (same as promotion pop-over).

## Not covered here

Rendering: whether the quad layer draws, covers holes, or composes with Sodium/Distant Horizons. That is the
external client bed.

## Automated testing — TODO

A dev-only `ClientProbe` in `src/dev` (the dev source set is on the `:runClient` mod classpath) can drive a
headless client and assert the clamp, but it needs orchestration:

1. **`aerogel.clamp` server probe (small, deterministic).** In a gated fresh-land chunk, park two fakes: one
   with `AdaptiveMode.setHasAeroGel(true)` and the proxy switch on (`renders` true), one vanilla. Put both
   below the sampled surface and tick. Assert the rendering fake is not teleported and the vanilla fake is.
   Needs no client. Add it to `DevProbeImpl` and the probe table.
2. **JUnit for the clamp decision (small, needs build work).** No `src/test` and no JUnit dependency today.
   Extract the rule from `ClientHandlers.onClientTick` as a pure function over
   `(realChunk, hasMesh, meshSurface, y)` and cover: mesh above -> y becomes the surface; mesh below ->
   unchanged; no mesh and no chunk -> 64; real chunk -> unchanged.
3. **Real client end to end.** `ClientProbe` plus `--quickPlayMultiplayer 127.0.0.1:25565`: place the player
   over a gated chunk, assert y held at 64 for N ticks with no mesh, snapped to the mesh surface when it
   arrives, and lifted once out of terrain when the chunk becomes real. The server must run outside Gradle:
   `:runServer` and `:runClient` are two builds and the second blocks on the project lock.

FPS does not matter for any of this, only ticks and positions.

## Results

| # | test | build | result | evidence (log line / diag) |
|---|---|---|---|---|
| 1 | baseline, switch off | | | |
| 2 | stall + 100 blocks/s, switch on | | | |
| 2b | stall + 100 blocks/s, switch off (contrast) | | | |
| 3 | broom, switch on | | | |
| 3b | broom, switch off (contrast) | | | |
| 4 | no mesh -> flat 64, with and without `proxy` | | | |
| 5 | mesh arrives -> snap | | | |
| 6 | real chunk arrives -> lift | | | |
| 7 | vanilla client -> net holds? | | | |
| 8 | normal movement with switch on | | | |
| 9 | floating kick (optional) | | | |
