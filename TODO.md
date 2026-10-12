# AeroGel — TODO

Open work that is not yet designed. The design lives in `plans/`; this is the short list of things known to be
wrong or missing.

## 1. Replace the draw-order workaround with a real visibility decision

The renderer paints the proxy layer **before** the terrain (`AFTER_SKY`, depth-tested, depth write off) so
that whatever the renderer draws covers us, and the layer shows only where nothing else draws. That is a
workaround, not a fix:

- the layer **cannot occlude anything** — real terrain that is genuinely farther away paints over a proxy in
  front of it;
- it **assumes the terrain pass runs after `AFTER_SKY`**. True for the vanilla renderer; an assumption about a
  class of renderers, not a guarantee.

What is actually needed is a per-chunk answer to "is this chunk drawn and visible this frame". Vanilla exposes
it (`LevelRenderer.visibleSections` — what the old hide rule used). **Sodium does not**: it replaces the
terrain renderer and never fills that list, which is exactly why the layer was drawing over real terrain on a
Sodium client to begin with. Find a signal that works for both — a supported Sodium API if one exists, an
occlusion query, or a depth-only prepass — and let the layer occlude correctly again.

## 2. Improve Distant Horizons interop

The two layers currently coexist by accident: DH paints over us where it has LODs, we show where it does not,
and nothing coordinates the handover.

- DH **stops drawing LODs as you approach** and hands off to vanilla. In that band, with promotion off, DH
  draws nothing and vanilla has nothing to draw — the hole is filled only because our layer happens to be
  there. That handoff should be explicit rather than incidental.
- We should be able to ask **whether DH has an LOD for a chunk** and fill only where it does not.
- `streamRadius` and DH's LOD distance are unrelated numbers, and matching them is not achievable with
  on-demand sampling and one `VertexBuffer` per chunk (see the radius forecast in `AGENTS.md`). The useful
  question is what each layer should own, not how to make ours as large as theirs.
- Both are expensive at the same moment: DH generating its LOD backlog while we try to stream the same gap.

## 3. Near-field streaming latency

Flying fast, the proxy for the chunk you are *in* is a few seconds late — the whole point is that it is not.
The draw decision is no longer involved; this is sampling and delivery latency. Suspects, in order:

- the sampler thread count (one thread saturates around r=32);
- the wanted list only enqueuing the **leading-edge ring** on a chunk-cross, instead of the disc the player
  just flew into;
- the per-sync client upload budget.

## Also open

Longer-running items are in `plans/README.md` (collider PoC; in-game verification of detail tiers and
automatic promotion) and the "Next session" list in `AGENTS.md`.
