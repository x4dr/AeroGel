package dev.aerogel.server;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Headless stress test of {@link PromotionManager} (flag file: &lt;serverDir&gt;/aerogel.promotestress).
 * Force-loads a dense 5x5 of fresh proxy chunks and queues them all at once; the manager must promote them
 * without lag. Halts when every chunk is real (PASS) or after a timeout (FAIL).
 */
final class PromotionStressProbe {
    private static final Logger LOGGER = LoggerFactory.getLogger("aerogel.stressprobe");
    private static final int OFFSET = 4000;
    private static final int RADIUS = 2;
    private static final int MAX_TICKS = 1200;

    private static ServerLevel level;
    private static ChunkPos center;
    private static int expected;
    private static int ticks;

    private PromotionStressProbe() {
    }

    static void start(MinecraftServer server) {
        level = server.overworld();
        ChunkPos spawn = new ChunkPos(level.getSharedSpawnPos());
        center = new ChunkPos(spawn.x + OFFSET, spawn.z + OFFSET);
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                int x = center.x + dx;
                int z = center.z + dz;
                level.setChunkForced(x, z, true);
                level.getChunk(x, z); // instant placeholder
                PromotionManager.debugEnqueue(level, ChunkPos.asLong(x, z));
                expected++;
            }
        }
        LOGGER.info("promote stress: enqueued {} chunks around {}", expected, center);
    }

    static void tick(MinecraftServer server) {
        if (level == null) {
            return;
        }
        if (++ticks % 20 != 0) {
            return;
        }
        int real = 0;
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                if (level.getChunkSource().getChunkNow(center.x + dx, center.z + dz) != null) {
                    real++;
                }
            }
        }
        if (real >= expected) {
            LOGGER.info("promote stress: VERDICT PASS — all {} chunks real after {} ticks", expected, ticks);
            finish(server);
        } else if (ticks > MAX_TICKS) {
            LOGGER.error("promote stress: VERDICT FAIL — only {}/{} chunks real after {} ticks", real, expected, ticks);
            finish(server);
        } else if (ticks % 100 == 0) {
            LOGGER.info("promote stress: {}/{} real after {} ticks", real, expected, ticks);
        }
    }

    private static void finish(MinecraftServer server) {
        if (level != null && center != null) {
            for (int dx = -RADIUS; dx <= RADIUS; dx++) {
                for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                    level.setChunkForced(center.x + dx, center.z + dz, false);
                }
            }
        }
        level = null;
        center = null;
        server.halt(false);
    }
}
