# Staged dependency jars

Compile-time dependencies are staged here by copying jars out of the development game launcher
instance that runs the Create-family test pack. They are NOT committed to git (see `.gitignore`), and
they are not redistributed with AeroGel: the published jar contains no Create/Aeronautics/Sable code,
and the Sable integration is guarded by `requiredMods = ["sable"]` so it is skipped entirely when
Sable is absent.

## Re-staging

Copy the jars out of the test instance's `mods/` folder into this directory, keeping each filename in
the `create-<mc>-<version>.jar` / `<mod>-neoforge-<mc>-<version>.jar` shape. Use `javap -p` on the jar's
own `META-INF/neoforge.mods.toml` (or check the launcher's mod list) to confirm the version you copied.

Expected files for the current target pack (MC 1.21.1 / NeoForge 21.1.252 / Create 6.0.10 / Sable 2.0.6):
- `create-1.21.1-6.0.10.jar`
- `sable-neoforge-1.21.1-2.0.6.jar`
- `aeronautics-neoforge-1.21.1-1.3.2.jar`
- `simulated-neoforge-1.21.1-1.3.2.jar`
- `offroad-neoforge-1.21.1-1.3.2.jar`

`aeronautics` / `simulated` / `offroad` only ship inside the bundled Create Aeronautics jar, so unpack
them first:

```
unzip -o create-aeronautics-bundled-1.21.1-1.3.2.jar 'META-INF/jarjar/*' -d /tmp/aero-jarjar
```

then copy the three `META-INF/jarjar/*.jar` children out of `/tmp/aero-jarjar`.

## Version drift

The declared Sable dependency range in `neoforge.mods.toml` is open (`[2.0.0,3.0.0)`), but the mixin in
`dev/aerogel/sable/` is `required: true` with `defaultRequire: 1` — a moved injection target is a launch
crash, not a degraded feature. Compatibility is therefore only established for versions actually staged
and probed here:

| Sable | status |
|---|---|
| 2.0.5 | `addTicket` descriptor verified; Sable body probe passed |
| 2.0.6 | `addTicket` descriptor verified; Sable body probe passed |

When staging a new Sable, re-check the mixin target before trusting it:

```
javap -p -s -classpath <sable.jar> \
  dev.ryanhcode.sable.sublevel.system.ticket.PhysicsChunkTicketManager | grep -A1 addTicket
```

Then run `:runServer` with `run/server/aerogel.sablebody` and confirm the probe reports
`VERDICT PASS`. Note that `sable_version` in `gradle.properties` is documentation only — nothing
resolves it; the dev classpath is the contents of this directory.

## Keep these in step with the test pack

These are the mod's real artifacts. If any ship a newer patch while the pack is still on the versions
above, update the staged copies to match the instance you test against rather than whatever is newest
upstream.
