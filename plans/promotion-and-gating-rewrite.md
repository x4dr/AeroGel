# Promotion and gating rewrite

Target: still **0.1.1**. Nothing has been pushed, so this is a rewrite of local history, not a set of
reverts. Plan written after reviewing #15/#16 and the promotion path end to end, then reworked in
discussion on 2026-10-15 — the disengage model below is the result of that discussion and replaces
what the first draft said about linger counters.

## Premise — three things we had wrong

1. **The opted-out arm was queued, then un-queued.** At the initial commit (`ad386f1`) it was
   `enqueue(pos, true)` — the queue, flagged urgent. `f9a8692` dropped the whole `urgent` class
   ("existed only to make an uncapped queue behave as if it were capped") and that became
   `promoteImmediate`. The un-queuing is the direct ancestor of the #15 generation storm.
2. **Gate engagement and proxy rendering were separate features treated as one.** The opted-out arm
   promoted every nearby proxy, so "gate off" meant "proxies disappear". They are independent axes:
   `travel` is a *generation* request, `proxy` is a *rendering* request.
3. **Opt-out was never needed as a concept.** It was one way to say "don't gate near people who
   declined", and it carried essentially all of the hot-path cost, because `optOutCenters` holds
   *most* connected players rather than the small opt-in set.

A fourth conclusion, added later in the discussion: **the server must not branch on whether a client
renders proxies.** `AdaptiveMode.renders` gates *sending meshes* and nothing else. Promotion is a
server-side generation decision and takes no interest in what any individual client draws. This is
what removes the leftover arm rather than merely re-pacing it.

## What is thrown away

- `d3e7e2a` (opt-out zone subtraction in `isEngaged`) — the concept goes, not just the implementation.
- **#15's original framing.** The defect was never gating a client that can't render proxies — they
  can't opt into travel mode, so there's no version where they get any. The defect is the **uncapped
  force-generation**, which the pacing fixes by construction. The issue gets rewritten to say that.
- **The nearby-leftover arm**, in every form. It was re-introduced during the rewrite as a *queued*
  trigger for `!renders` players, then dropped again: it re-protected the exact case the plan had
  already accepted as lost, and it was the only remaining place where a generation decision read a
  rendering decision.
- `hasLinger()` as the deactivation veto. It meant "some chunk has a linger counter", which was always
  a leaky proxy for "there is stub terrain in play". See **The disengage model**.

## What survives

- `6a7c90a` — the two-queue deferred-write split (terrain before caller). Independent of this debate.
- `2355c6b` — white-box notes on that split, with the detection-free fallback.
- `adfd53a` — an unconfirmed client is treated as unable to receive meshes.
- `e76f63f` — docs de-localization.

## Workstreams

### 1. Gate model — DONE

```
isEngaged(state, pos):
    if (!active || !gateEnabled) return false;
    if (!adaptiveEnabled)         return true;
    if (enforcing)                return true;      // global: no zone scan at all
    return nearAny(optInCenters, x, z, zoneRadius); // only players who asked for gating
```

- One list, not two. No subtraction.
- `pushZones` reuses a per-dimension scratch and hands the gate the array it already holds unless the
  centres actually moved, so a server at rest allocates nothing per tick.
- Behaviour accepted: a player who declined `travel` is gated if they stand inside someone else's
  opted-in zone. They can render it, so it is a preference being overridden, not a void.
- Consequence: the enforcement hot path is free again, and the idle path costs one scan of a list
  that only ever holds players who opted in.

### 2. Promotion path

**Linger is universal and server-side.** One `startLinger` timestamp per stub, written on first
approach, refreshed (`lingerSeen`) every tick it is near a player. Duration is subtraction; there is no
per-tick counter increment and no periodic prune sweep — an entry that stops being seen ages out when
the scan looks at it. An entry with **pending deferred writes** never ages out: its banked duration is
the only thing between a player's block placement and being re-lingered from zero.

A chunk's threshold is distance-dependent, `t(d) = min + (d/range)·(max − min)`, interpolated between
idle and busy load. Crossing it makes the chunk a *candidate*.

**Touchdown, block writes, right-clicks and Sable body contact stay immediate.** They are player
actions, they bypass the budget, and nothing here changes that.

**The queue is a work buffer, not a backlog.** It holds roughly what the drain rate can consume
between two refill passes — derived from `maxInFlight`, not from a computed drain rate, because a
computed cap couples queue depth to a load-derived rate and that is a feedback loop inside the
component whose job is to break feedback loops. The cap is **soft**: a chunk with pending deferred
writes is never dropped for capacity.

**Admission is nearest-first and requires a startable holder.** Each pass rebuilds the queue from the
nearest candidates. That makes "nearest discards farthest" a property of the refill rather than a
policy with an eviction scan, and it deletes `evictOldest`, the FIFO `order` deque and the lazy
`removed` flag with it. Anything that does not make the cut is still a stub with its `startLinger`
intact, so it re-qualifies on a later pass — "requeued later if the player lingers near them again"
is a consequence, not a mechanism.

Admission requires a loaded FULL holder (`ProxyChunkGate.hasHolder`). **AeroGel cannot promote a chunk
vanilla would not have generated**: promotion allow-lists a position and forces the FULL future on a
holder that already exists, so it can never invent a ticket. Without this requirement, fringe chunks
at `range = view + 2` — where the player's own ticket demands less than FULL — would occupy queue slots
they can never use, and with a small buffer that pins the gate on forever.

**The drain rate** is derived, not configured: a promotion start must keep the smoothed tick time
below the adaptive activate threshold. Numbers still to be measured — see Open questions.

### 3. The disengage model — the core of this rewrite

Enforcement does not simply switch off when the server calms down. The gate **cannot** disengage while
any gated chunk it is responsible for is still stub, because a stub that becomes ungated while a
player holds a ticket on it generates immediately and in bulk — that burst is the whole thing the
deactivation veto has always been guarding against.

```
every tick:
  accrue     for each player, for each stub in range: touch lingerSeen;
             write startLinger on first approach
  refill     if the WINDOW is calm -> candidates = fresh lingered stubs, threshold BYPASSED
             else                    -> candidates = stubs that passed their distance-dependent threshold
             sort by distance to the nearest player; fill the queue to its soft cap from the nearest
             (admission requires a startable holder)
             count every fresh in-range stub with a startable holder, admitted or not
  drain      while inFlight < maxInFlight and the budget allows, start the nearest queued chunk
  may disengage  <= calm AND queue empty AND in-flight empty AND no counted stub remains
  prune      lazily drop expired linger entries during the scan
```

"Calm" is the existing adaptive test — windowed backlog and tick time below their deactivate values —
read off the **window**, not off `enforcing`. That distinction is load-bearing and was found the hard way:
the drain is supposed to keep enforcement on until it finishes, so a refill keyed on `enforcing` would
never take the calm path and the gate would stay on for ever with nothing draining it.

The gated-stub count is the second half, and it is not redundant with the queue. The refill reads the
window one tick behind the decision, so on the tick the server goes calm the refill can still be on the
busy path with nothing queued; the queue is empty, the gate drops, and the next tick the calm path queues
the whole square — now ungated, so those chunks build their own FULL futures. That is the burst arriving
one tick late, and it is what `aerogel.drain` caught. The invariant, stated directly: **the gate may not
disengage while chunks it gated are still stub terrain.** The count reaches zero because the drain promotes
them, and it cannot pin on a chunk vanilla would not have generated, because only positions with a loaded
FULL holder are counted.

While calm the linger **threshold is bypassed** —
not the budget. The drain arm makes every fresh lingered stub immediately *eligible*; the queue's
existing pacer still decides when each one actually starts, at `maxInFlight` / budget / hard-gate, with
the one-concurrent-chunk floor guaranteeing progress on a server that is already lagging. Only player
actions bypass the budget, and they are not part of this.

Two consequences worth stating rather than discovering:

- The ordering inside a tick is load-bearing, and `AdaptiveMode`'s tick is split for it. `sample` folds the
  load sample into the window **before** `PromotionManager.tick`; `decide` makes the enforcement decision
  **after** it. Both the refill and the decision must read the *same* window maxima — a refill keyed on
  `enforcing` could never take the calm path (the drain keeps enforcement on by design), and a refill keyed on
  maxima that the decision has since recomputed disagrees with it on exactly the tick the maxima step down.
  With the split, "queue empty and in-flight empty" is a complete answer and no stub count is needed.
- Collapsing the threshold while calm means the fringe promotes on an idle server far sooner than the
  30s `max` would suggest. That is the intent — an idle server can afford the corridor at the paced
  rate, and the pacing is what makes it free — but it does mean a lone idle player is barely gated.

The invariant this preserves, in the words the old `AGENTS.md` used without defining: when the gate
disengages, no chunk is mid-linger. Either every lingered chunk was evicted by the drain (because
everything in linger range overwrote the queue) or it was generated, because it had lingered. The
phrase was confusing only because it described an *outcome* with the name of a *mechanism*.

### 4. Proxy streaming independent of the gate

- The streamer keys on `renders(player)` — has the mod and the `proxy` switch on — and nothing else.
  It already did; this is now stated as a contract and covered by a probe.
- `travel` keeps its meaning and nothing to do with proxies: "I will cross a lot of chunks, don't
  generate them". A gating request, not a rendering request.
- Terminology stops using "dormant" for two different states: **gate on/off** and **proxy streaming
  on/off**, used separately.

### 5. History rewrite — DONE

- Reset to the upstream tip `0e4a16b`.
- Recommit, in order: #16 split → white-box notes → unconfirmed-client default → docs de-localization →
  promotion/gating redesign → `0.1.1`.
- #15 and #16 are rewritten to the correct defects, not closed as-is.

### 6. Docs

- `AGENTS.md`: the gate/proxy split, the disengage model, and the dropped opt-out concept.
- `plans/README.md`, `plans/work_log.md`: the reasoning chain, including that `f9a8692` un-queued the
  arm and that universal linger invalidated the old `hasLinger` veto.
- `TODO.md`: mesh/draw-call batching (below).

### 7. TODO — mesh and draw calls

Proxy rendering is currently one `VertexBuffer` and one draw call **per chunk**: ~1.7k draws at r=20,
~40k at r=100, which is why `AGENTS.md` calls large radii unrenderable. This is the binding limit on
ungated proxy rendering, not CPU.

- Tile-batch the mid/far field (8×8 chunks per buffer) — 1681 draws → ~27 at r=20.
- Keep per-chunk buffers for the near field, where geometry changes are frequent and rebuild granularity
  matters.
- This is the prerequisite for "player standing on a tower wants distant proxies" being affordable:
  without it, that case costs ~1.7k draws every frame forever.

## Open questions — must be resolved, not reasoned about

- **Late-batch write loss.** A probe writes ~512 blocks at tick 20, while the chunk is still promoting,
  so the writes are *deferred* rather than applied. Only 267/512, 257/512 and 70/512 survived across
  three runs. Leading hypothesis: deferred but not yet replayed, since replay is paced at ~10.7
  writes/tick (issue #16's own measurement of the 1.5 ms/tick budget) and 512 writes need ~48 ticks.
  That would make it a probe-calibration bug rather than a product bug — but it is a hypothesis. Needs
  the discriminating instrumentation and a verdict before #16 can close.
- **Stationary sampling.** Requirement is one burst then nothing. The structure says it already holds
  (rebuild only fires on chunk-cross; refinement only re-samples chunks entering the near band) but that
  has not been measured. Needs a probe that leaves a player still and counts sampler submissions.
- **Drain-rate budget numbers.** What "under the adaptive activate threshold" means concretely for the
  drain needs measurement, not a guess.

## Verification gate

Per `AGENTS.md`, the posture is compiles / loads / probes PASS. Required before 0.1.1:

- `probe`, `promote`, `promotestress`, `writesafety` (×2), `sablebody`, `teleport`, `vanillaclient` — all
  PASS. `vanillaclient` is re-specified below.
- New/updated probes for: the drain arm (queued, not immediate); the disengage veto (queue and in-flight
  empty, checked after the refill); stationary sampling; gate/proxy independence.
- `promote` carries the restored late-batch write phase and therefore **fails until the write loss is
  diagnosed** — it is not a side quest, it gates the release.
- Both mixin descriptors re-verified against the NeoForge version in use (done for 21.1.252, recorded in
  `libs/README.md`).

### `vanillaclient` is re-specified, not deleted

Its old assertion — "a client without AeroGel keeps a vanilla zone while enforcing" — describes the
opt-out subtraction and cannot survive. Under the new model that client **is** gated: generation is a
server-side cost and excluding them would only move the stall somewhere they cannot see it. What the
probe must now assert is the actual defect: its square is **paced, not uncapped**. A `FakePlayer` never
issues chunk requests, so the storm cannot be reproduced directly; the probe asserts the mechanism
instead — that the client's in-range stubs enter the paced queue rather than being started immediately.

## Still undecided

- **Decided: `proxy` and `travel` stay two separate switches.** They are genuinely orthogonal —
  `proxy` is a *rendering* request (has the mod, draw proxy terrain), `travel` is a *generation*
  request ("I will cross a lot of chunks, don't generate them"). Both combinations are legitimate:
  proxies without gating (looking at the horizon) and gating without proxies (saving server work
  regardless of what you draw).
- **Consequence, accepted:** a client without AeroGel on an enforcing server is in a globally gated
  world and sees void until the linger threshold promotes it. That is #15's experience minus the storm.

## Dropped from an earlier draft

The draft floated reintroducing an "opt-out subtraction in the non-enforcing branch" — checking an
opt-out list so a player who declined `travel` keeps vanilla terrain even inside another player's
opted-in zone.

**Dropped.** It would keep the large opt-out list alive on the idle path, which is most of the cost this
whole rewrite exists to remove. The case it uniquely protected — a `proxy`-off player standing inside
someone else's opted-in zone — is now covered by a *different* and smaller mechanism (nothing: the
threshold is simply what it is), and the cheap failure is the same one the design already accepts for
unmodded clients. A `proxy`-off client has opted out of the proxy layer including its collision
surface; it gets vanilla terrain when vanilla hands it over.