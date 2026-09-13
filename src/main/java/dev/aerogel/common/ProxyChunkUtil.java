package dev.aerogel.common;

import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Shared chunk-state checks. Note: LevelChunk.isEmpty() is hardcoded to false in 1.21.1 (both sides), so
 * "air stub" detection must scan the sections instead — an intercepted (proxy) chunk is all-air.
 */
public final class ProxyChunkUtil {
    private ProxyChunkUtil() {
    }

    public static boolean isAirStub(ChunkAccess chunk) {
        for (LevelChunkSection section : chunk.getSections()) {
            if (!section.hasOnlyAir()) {
                return false;
            }
        }
        return true;
    }
}
