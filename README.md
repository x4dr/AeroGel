# AeroGel

A NeoForge 1.21.1 mod that stops chunk-load lag when flying fast, by serving cheap proxy terrain until a chunk is really needed.

## Notes for server owners

- **Chunk pregenerators** (Chunky, Chunk-Pregenerator) push the chunk backlog over the adaptive activate
  threshold, and AeroGel then gates the very chunks they are trying to generate. Switch the gate off while
  pregenerating: `/aerogel switch gate false` (and `true` again afterwards).
- **Physics ships** (Create Aeronautics / Sable) currently collide with proxy terrain as if it were air, so a
  ship can sink into terrain that has not been generated yet. A fix that force-generates a chunk a ship
  collides with is planned.
