# AeroGel

A NeoForge 1.21.1 mod that stops chunk-load lag when flying fast, by serving cheap proxy terrain until a chunk is really needed.

## Notes for server owners

- **Chunk pregenerators** (Chunky, Chunk-Pregenerator) push the chunk backlog over the adaptive activate
  threshold, and AeroGel then gates the very chunks they are trying to generate. Switch the gate off while
  pregenerating: `/aerogel switch gate false` (and `true` again afterwards).
- **Physics bodies** (Create Aeronautics ships, Create: Coasters Simulated coasters, anything on Sable) force-generate
  a proxy chunk as soon as they come within `promotion.landingMargin` (default 8 blocks) of its surface; until
  that chunk is generated it has no collision, so a fast descent can still dip in briefly.
