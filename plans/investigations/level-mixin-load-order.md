# Incident: AeroGel failed to launch on one client — `LevelWriteGuardMixin` targeted an already-loaded class

**Status: closed — not reproducible, and the change it proposes is against the design.**
Reported 2026-09-27 against a throwaway duplicate test instance that no longer exists. No AeroGel source was
modified for it. The same jar has loaded normally on every instance kept since, including the full target pack
on NeoForge 21.1.252 / FML 4.0.44 with `earlyWindowControl = true`.

## The report

The client died during mod construction, before any AeroGel code ran:

```
Mixin prepare for mod aerogel failed preparing LevelWriteGuardMixin in aerogel.mixins.json:
  MixinTargetAlreadyLoadedException: Critical problem: aerogel.mixins.json:LevelWriteGuardMixin
  from mod aerogel target net.minecraft.world.level.Level was loaded too early.
```

The reasoning recorded at the time: FML loads `net.minecraft.world.level.Level` before it reads any mixin
config (through the early display window's `updateModuleReads`), so a mixin into it is a hard error, and with
`"required": true` that error is fatal. Disabling the early window (`fml.toml`,
`earlyWindowControl = false`) did not avoid it.

## Why it is closed

- **Not reproducible.** Every instance retained since — including the target pack — loads AeroGel on the same
  NeoForge and FML versions with the early window enabled, and the mod runs (streaming, promotion, adaptive
  enforcement all observed).
- **The proposed fix contradicts the design.** The report's first suggestion was to make the config
  non-required, turning a moved injection target into a degraded mode. AeroGel's posture is deliberately the
  opposite: if a mixin cannot apply, the launch must fail loudly rather than silently stop doing its job. See
  the posture note in `../AGENTS.md`.
- **`Level` is the right injection target.** `Level.setBlock` is where every runtime write funnels through
  (worldgen does not, it goes via `WorldGenRegion`), which is exactly the split `LevelWriteGuardMixin` needs.
  Retargeting to `ServerLevel` would miss writes.
- Nothing from the original report survives — no log, no crash report, no instance — so the actual trigger (a
  mod in that one instance whose early load touched `Level` before mixin configs were read) cannot be
  identified. It is recorded so it is not re-derived.

## If it is ever seen again

Capture the failing instance's full `latest.log` and its `fml.toml` before changing anything: the useful
question is which other mod loaded `Level` during the early window, and that answer is in the log. Do not
"fix" it by weakening `aerogel.mixins.json`.

## Appendix — unrelated environment notes from the same session

Not AeroGel's problem, kept only so they are not re-derived:

- **`hyprctl dispatch` is not broken**, but it is easy to think it is. On Hyprland 0.56.2 it rewrites the call
  into `hl.dispatch(...)` Lua internally, and multi-word arguments get flattened without quoting, producing a
  Lua parse error rather than a dispatch error — e.g. `hyprctl dispatch exec true` fails with
  `')' expected near 'true'`. Quote arguments containing spaces, or match windows by address from
  `hyprctl clients -j` rather than by title.
- **`hyprctl eval` is a stdlib-only sandbox.** It exposes the 53 Lua standard globals and nothing else — no
  `hl`, no `hypr` table. It is for evaluating config, not for dispatching.
