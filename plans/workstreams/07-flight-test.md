# WS-07 — scripted flight test (capture the post-remediation numbers)

Goal: capture the server- and client-side cost of a fast flight sweep so the
"~1000x cheaper transit chunks / near-zero idle" claim is measured, not modelled.
This is a measurement harness, not a code change to the mod.

## Harness

`server/FlightProbe.java`, flag file `<serverDir>/aerogel.flight`.

It runs a **list of configurations back-to-back** on the first overworld player.
Each run: apply the stream settings, teleport to a fresh terrain band, settle
until the start chunk is loaded and the server is quiet, then fly `speed`
chunks/tick along +x for `duration` ticks, sampling server tick time and
chunk-task backlog. At the end it logs a comparison table.

Flag format, one run per line (`#` comments / blank lines ignored):

```
radius nearTier farTier nearDistance speedChunksPerTick durationTicks zOffsetChunks
```

If the file is missing/empty, a default matrix is used: radius 20 at tiers
5/9/25/81, then radius 50 and 100 at tier 9, each 600 ticks at 4 ch/tick on a
fresh z-band. It does **not** force any switch (real adaptive path) and never
halts the server.

## Run

```bash
# server (the dev server is loaded from build classes, so rebuild + restart)
printf '20 9 5 32 4 600 0\n20 25 5 32 4 600 2000\n' > run/server/aerogel.flight
scripts/deploy.sh                     # stop -> build -> deploy client jar -> start

# client: launch/restart the AeroGelDemo Prism instance and connect to localhost
# (the probe arms on join and runs the matrix)
```

Two profiles, run on **fresh terrain each time** (fly a different direction, or
reset the region) so the second run does not find the first run's chunks on disk:

- **A — gate off (vanilla baseline):** before joining, in the server console or
  in-game: `/aerogel switch gate false`. Expect `server tick stall` and rising
  backlog as the sweep demands real generation.
- **B — AeroGel (adaptive):** `/aerogel switch gate true` (default). Watch
  `adaptive: … ENFORCING` and `promote: … queued/in flight`.
- **B2 — AeroGel (always-on gate, deterministic):** `/aerogel switch adaptive
  false` so the gate is always engaged regardless of load.

## Report

```bash
scripts/flight-report.sh
# or: scripts/flight-report.sh <server-log> <client-log>
```

Prints server tick min/avg/max + backlog, ENFORCING sample count, tick-stall
count, `stream perf`, and the client `render perf` / stall lines.

## What to look at

- **Server:** avg/max tick ms and backlog for A vs B/B2. The claim is that A
  stalls (real generation) and B/B2 stays bounded (placeholders + sampling).
- **Engagement lag:** how many seconds after the sweep starts before
  `ENFORCING` appears (the reactive-gate caveat).
- **Client:** `render perf` draws/frame, `render()` ms, sync/upload ms, and any
  `render hitch` / `client frame stall` lines.
- **Streamer:** `stream perf` ms/tick while flying (should stay sub-ms; sampling
  is on the worker threads).

## Notes

- The probe teleports the player; the client follows via position packets. The
  chunk-ticket sweep is identical to real flight, but the client's own movement
  prediction is not exercised (its render perf is still valid).
- Aeronautics/Sable vehicles are not moved by this probe — it moves the player.
  The chunk-loading cost is the same; the physics path is a separate PoC.
- Remove `run/server/aerogel.flight` when done, or every server start re-arms it.
