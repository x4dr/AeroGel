package dev.aerogel.server;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Headless smoke test of {@link PromotionManager} (flag file: &lt;serverDir&gt;/aerogel.promote). Loads a fresh
 * proxy chunk, writes a block into it (must be cancelled + deferred), lets the manager promote the chunk,
 * then verifies the write was replayed onto the real terrain. Self-halting.
 */
final class PromotionProbe {
    private static final Logger LOGGER = LoggerFactory.getLogger("aerogel.promoteprobe");
    private static final int OFFSET = 3000;
    private static final int MAX_TICKS = 600;

    private static ServerLevel level;
    private static ChunkPos probe;
    private static BlockPos writePos;
    private static int ticks;

    private PromotionProbe() {
    }

    static void start(MinecraftServer server) {
        level = server.overworld();
        ChunkPos spawn = new ChunkPos(level.getSharedSpawnPos());
        // a previous run persists the promoted chunk, so scan along +x for a chunk that is still a fresh
        // proxy, keeping the probe repeatable without wiping the world
        for (int i = 0; i < 64 && probe == null; i++) {
            ChunkPos candidate = new ChunkPos(spawn.x + OFFSET + i * 32, spawn.z + OFFSET);
            level.setChunkForced(candidate.x, candidate.z, true);
            level.getChunk(candidate.x, candidate.z);
            if (level.getChunkSource().getChunkNow(candidate.x, candidate.z) == null) {
                probe = candidate;
            } else {
                level.setChunkForced(candidate.x, candidate.z, false);
            }
        }
        if (probe == null) {
            LOGGER.error("promote probe: no fresh proxy found near offset {}", OFFSET);
            server.halt(false);
            return;
        }
        writePos = new BlockPos(probe.getMinBlockX() + 8, 100, probe.getMinBlockZ() + 8);
        LOGGER.info("promote probe: chunk {} loaded as proxy (no FULL future)", probe);
        // the write must be intercepted (cancelled + remembered) because the chunk is a proxy
        boolean deferred = level.setBlock(writePos, Blocks.STONE.defaultBlockState(), 3);
        LevelChunk after = level.getChunkSource().getChunkNow(probe.x, probe.z);
        LOGGER.info("promote probe: write into proxy -> deferred={}, still proxy={}", deferred, after == null);
    }

    static void tick(MinecraftServer server) {
        if (level == null || probe == null) {
            return;
        }
        if (++ticks % 10 != 0) {
            return;
        }
        LevelChunk chunk = level.getChunkSource().getChunkNow(probe.x, probe.z);
        if (chunk != null) {
            BlockState at = level.getBlockState(writePos);
            boolean replayed = at.is(Blocks.STONE);
            LOGGER.info("promote probe: VERDICT {} — chunk {} real after {} ticks, deferred write replayed={} (block {})",
                replayed ? "PASS" : "PARTIAL", probe, ticks, replayed, at.getBlock());
            testPushUp();
            finish(server);
        } else if (ticks > MAX_TICKS) {
            LOGGER.error("promote probe: VERDICT FAIL — chunk {} still a proxy after {} ticks", probe, ticks);
            finish(server);
        }
    }

    /** place a zombie inside a block and check the push-up frees it. */
    private static void testPushUp() {
        BlockPos at = new BlockPos(probe.getMinBlockX() + 8, 200, probe.getMinBlockZ() + 8);
        net.minecraft.world.entity.monster.Zombie zombie = net.minecraft.world.entity.EntityType.ZOMBIE.create(level);
        if (zombie == null) {
            return;
        }
        zombie.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        zombie.setNoGravity(true);
        level.addFreshEntity(zombie);
        level.setBlockAndUpdate(at, Blocks.STONE.defaultBlockState());
        double before = zombie.getY();
        ProxyChunkGate.pushOutOfTerrain(level, probe);
        LOGGER.info("promote probe: push-up test — y {} -> {}, free={}",
            before, zombie.getY(), level.noCollision(zombie, zombie.getBoundingBox()));
    }

    private static void finish(MinecraftServer server) {
        if (level != null && probe != null) {
            level.setChunkForced(probe.x, probe.z, false);
        }
        level = null;
        probe = null;
        server.halt(false);
    }
}
