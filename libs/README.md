# Staged dependency jars

Compile-time dependencies are staged here by copying jars out of the "2026 sinking the kitchen"
PrismLauncher instance (`~/.local/share/PrismLauncher/instances/2026 sinking the kitchen/.minecraft/mods/`).
They are NOT committed to git (see .gitignore). Re-stage with:

```
INST=~/.local/share/PrismLauncher/instances/"2026 sinking the kitchen"/.minecraft/mods
cd "$INST"
cp create-1.21.1-6.0.10.jar <this dir>/
cp sable-neoforge-1.21.1-2.0.5.jar <this dir>/
# aeronautics/simulated/offroad are jar-in-jar inside create-aeronautics-bundled-*.jar:
unzip -o create-aeronautics-bundled-1.21.1-1.3.2.jar 'META-INF/jarjar/*' -d /tmp
cp /tmp/META-INF/jarjar/dev.eriksonn.aeronautics.aeronautics-neoforge-*.jar <this dir>/aeronautics-neoforge-<v>.jar
cp /tmp/META-INF/jarjar/dev.simulated_team.simulated.simulated-neoforge-*.jar <this dir>/simulated-neoforge-<v>.jar
cp /tmp/META-INF/jarjar/dev.ryanhcode.offroad.offroad-neoforge-*.jar <this dir>/offroad-neoforge-<v>.jar
```

Expected files (pinned for MC 1.21.1 / NeoForge 21.1.249 / Create 6.0.10 / Sable 2.0.5):
- `create-1.21.1-6.0.10.jar`
- `sable-neoforge-1.21.1-2.0.5.jar`
- `aeronautics-neoforge-1.21.1-1.3.2.jar`
- `simulated-neoforge-1.21.1-1.3.2.jar`
- `offroad-neoforge-1.21.1-1.3.2.jar`

Note: these are the mod's real artifacts. If any ship a newer patch while the pack is still on the
above versions, keep the staged copies in sync with the INSTANCE, not with upstream.
