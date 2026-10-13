package dev.aerogel.server;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Headless smoke test of {@link PromotionManager} (flag file: &lt;serverDir&gt;/aerogel.promote). Loads a fresh
 * proxy chunk, writes a block into it (must be cancelled + deferred), lets the manager promote the chunk,
 * then verifies the write was replayed onto the real terrain. While the promotion is generating it also checks
 * that a main-thread read returns the placeholder at once instead of blocking (#13), and writes more than the
 * 256-per-chunk cap into the chunk, which must all land, replayed over more than one tick (#5). Self-halting.
 */
final class PromotionProbe {
    private static final Logger LOGGER = LoggerFactory.getLogger("aerogel.promoteprobe");
    private static final int OFFSET = 3000;
    private static final int MAX_TICKS = 600;
    /** in-flight writes: 16x16 columns x 24 layers, far above the 256 cap and above one tick's replay budget. */
    private static final int BULK_Y = 101;
    private static final int BULK_LAYERS = 24;
    private static final int BULK = 16 * 16 * BULK_LAYERS;

    private static ServerLevel level;
    private static ChunkPos probe;
    private static BlockPos writePos;
    private static int ticks;
    private static boolean written;
    private static String inFlightRead = "not checked";
    private static boolean inFlightOk;
    private static int bulkFirstTick = -1;

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
    }

    /** the write, once the forced ticket has reached the holder (as it has under a player who writes). */
    private static void write() {
        // the write must be intercepted (cancelled + remembered) because the chunk is a proxy
        boolean deferred = level.setBlock(writePos, Blocks.STONE.defaultBlockState(), 3);
        LevelChunk after = level.getChunkSource().getChunkNow(probe.x, probe.z);
        LOGGER.info("promote probe: write into proxy -> deferred={}, still proxy={}", deferred, after == null);
        written = true;
        if (!ProxyChunkGate.isPromoting(level, probe.toLong())) {
            inFlightRead = "promotion not in flight after the write";
            return;
        }
        // #13: a read of the generating chunk must not managedBlock on its FULL future
        long t0 = System.nanoTime();
        ChunkAccess read = level.getChunk(probe.x, probe.z, ChunkStatus.FULL, true);
        double ms = (System.nanoTime() - t0) / 1_000_000.0;
        ChunkAccess peek = level.getChunkSource().getChunk(probe.x, probe.z, ChunkStatus.FULL, false);
        inFlightOk = read instanceof EmptyLevelChunk && peek == null && ms < 5.0;
        inFlightRead = String.format("placeholder=%s in %.3f ms, peek null=%s", read instanceof EmptyLevelChunk, ms, peek == null);
        // #5: more than the cap into the generating chunk; none may be dropped
        for (int y = BULK_Y; y < BULK_Y + BULK_LAYERS; y++) {
            for (int dx = 0; dx < 16; dx++) {
                for (int dz = 0; dz < 16; dz++) {
                    level.setBlock(new BlockPos(probe.getMinBlockX() + dx, y, probe.getMinBlockZ() + dz),
                        Blocks.GOLD_BLOCK.defaultBlockState(), 3);
                }
            }
        }
        LOGGER.info("promote probe: in-flight read {}; {} writes into the generating chunk", inFlightRead, BULK);
    }

    private static int bulkPresent() {
        int present = 0;
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int y = BULK_Y; y < BULK_Y + BULK_LAYERS; y++) {
            for (int dx = 0; dx < 16; dx++) {
                for (int dz = 0; dz < 16; dz++) {
                    if (level.getBlockState(at.set(probe.getMinBlockX() + dx, y, probe.getMinBlockZ() + dz))
                        .is(Blocks.GOLD_BLOCK)) {
                        present++;
                    }
                }
            }
        }
        return present;
    }

    static void tick(MinecraftServer server) {
        if (level == null || probe == null) {
            return;
        }
        if (!written && ProxyChunkGate.hasHolder(level, probe.toLong())) {
            write();
        }
        ++ticks;
        LevelChunk chunk = level.getChunkSource().getChunkNow(probe.x, probe.z);
        int present = chunk == null ? 0 : bulkPresent();
        if (present > 0 && bulkFirstTick < 0) {
            bulkFirstTick = ticks;
        }
        if (chunk != null && (present == BULK || ticks > MAX_TICKS)) {
            BlockState at = level.getBlockState(writePos);
            boolean replayed = at.is(Blocks.STONE);
            int spread = present > 0 ? ticks - bulkFirstTick + 1 : 0;
            boolean realAfter = !(level.getChunk(probe.x, probe.z) instanceof EmptyLevelChunk);
            boolean pass = replayed && inFlightOk && present == BULK && spread > 1 && realAfter;
            LOGGER.info("promote probe: VERDICT {} — chunk {} real after {} ticks, deferred write replayed={} (block {}); "
                    + "in-flight read {}; {}/{} in-flight writes landed over {} tick(s); real read after={}",
                pass ? "PASS" : "FAIL", probe, ticks, replayed, at.getBlock(), inFlightRead, present, BULK, spread, realAfter);
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
