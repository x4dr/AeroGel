# AeroGel — Target Environment

Everything AeroGel builds against. Versions are the ones the mod declares and tests against; the jars
themselves are staged locally per `libs/README.md` and are not committed or redistributed.

## Version stack

| Component | Version | Notes |
|---|---|---|
| Minecraft | 1.21.1 | |
| NeoForge | 21.1.252 | FML 4.0.44; dev: moddevgradle `2.0.140`, NeoForm `1.21.1-20240808.144430` |
| Create | 6.0.10 | jar: `create-1.21.1-6.0.10.jar`; maven coordinate `com.simibubi.create:create-1.21.1:6.0.10-280` (build -280) |
| Create Aeronautics | 1.3.2 | modrinth slug `create-aeronautics`; distributed as `create-aeronautics-bundled-...jar` |
| — aeronautics (inner) | 1.3.2 | `dev.eriksonn.aeronautics`, modId `aeronautics` |
| — simulated (inner) | 1.3.2 | `dev.simulated_team.simulated`, modId `simulated` |
| — offroad (inner) | 1.3.2 | `dev.ryanhcode.offroad`, modId `offroad` |
| Sable (physics) | 2.0.6 | `dev.ryanhcode.sable`, modId `sable`, Rapier native via `.sable/natives/` |
| Distant Horizons | 3.2.0-b | present in pack (rendering only; not part of AeroGel) |

Aeronautics deps (from its `neoforge.mods.toml`): neoforge `[21.1.228,)`, create `[6.0.10,)`,
sable `[2.0.0,3.0.0)`, simulated `[1.3.2,)`, flywheel client `[1.0.6,)`.

## Local paths

Kept out of this repo on purpose — per-machine, and not useful to anyone else. Set your own:

- Test client instance: the launcher instance that runs the Create-family pack, used by
  `scripts/deploy.sh` and `:deployDemo` (override with `-PaerogelInstance=<name>` or `AEROGEL_INSTANCE`).
- JDK 21 for the build: `JAVA_HOME=... ./gradlew`
- Sable natives (auto-extracted at runtime): `<gameDir>/.sable/natives/sable_rapier_<platform>.so`

## Dependency mavens (from the Create Aeronautics team's own build)

Used only as reference; AeroGel compiles against jars staged in `libs/` (see `libs/README.md`).

- `https://maven.createmod.net` → `com.simibubi.create:create-1.21.1:6.0.10-280` (also Ponder, Flywheel)
- `https://maven.ryanhcode.dev/releases` → `dev.ryanhcode.sable:sable-neoforge-1.21.1:2.0.6`, sable-companion
- Aeronautics/Simulated/Offroad are ONLY in the bundled jar (not on public maven) → staged from jarjar.

## Dependency stance

Create / Aeronautics / Sable are **OPTIONAL** runtime deps (see `neoforge.mods.toml`). AeroGel core works in
any NeoForge pack (elytra, etc.). We compile against the staged jars (see `libs/README.md`) only to build the
Create-family integration code; it must be loaded lazily / guarded so absence of these mods is fine.

## How AeroGel is installed (server AND client)

1. `./gradlew :assemble` → `build/libs/aerogel-1.21.1-<version>.jar`
2. Client: copy the jar into the test client instance's `mods/` folder — `scripts/deploy.sh` or
   `./gradlew :deployDemo` does this (default instance name `AeroGelDemo`).
3. Server: the dev server loads AeroGel from `build/classes` / `build/resources` (no jar copy); a dedicated
   server gets the same jar in its `mods/` folder.

Because both sides run the same NeoForge mod (side=BOTH), one jar serves both.
**Deploy to the dedicated test client instance only, never to the pack the `libs/` jars were staged from.**

## Verified during setup
- `./gradlew :compileJava` and `:assemble` succeed with staged libs.
- `./gradlew :runServer` boots MC 1.21.1 + NeoForge 21.1.249 with Create 6.0.10 + Sable (Rapier scenes
  init for all 3 dims) + Aeronautics + AeroGel; server reaches "Done" cleanly.
