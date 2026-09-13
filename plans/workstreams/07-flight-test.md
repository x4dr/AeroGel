# WS-07 — scripted flight test (capture the post-remediation numbers)

Goal: capture the server- and client-side cost of a fast flight sweep so the
"~1000x cheaper transit chunks / near-zero idle" claim is measured, not modelled.
This is a measurement harness, not a code change to the mod.

## Harness

`server/FlightProbe.java`, flag file `<serverDir>/aerogel.flight`.

- Waits for the first overworld player, puts them in creative flight at y=180,
  and teleports them `speed` chunks/tick along +x for `duration` ticks.
- The flag file may contain `"<speedChunksPerTick> <durationTicks>"` (defaults
  `4 1200` = 80 chunks/s for 60 s).
- It does **not** force any switch and does **not** call `debugAlwaysOn`, so it
  exercises the real adaptive path. Logs one line per second:
  `flight: t=… chunk=(…) swept…ch | tick … ms, backlog … | adaptive … | promote …`.
- It never halts the server; after the run it stops moving.

## Run

```bash
# server (the dev server is loaded from build classes, so rebuild + restart)
printf '4 1200' > run/server/aerogel.flight
scripts/deploy.sh                     # stop -> build -> deploy client jar -> start

# client: launch/restart the AeroGelDemo Prism instance and connect to localhost
# (the probe arms on join and flies you)
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
