package dev.aerogel.server;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Headless check that a block write is a queued trigger, not an immediate one (flag file:
 * &lt;serverDir&gt;/aerogel.masswrite). One {@code /fill}-sized write — 32768 blocks, vanilla's fill limit, 2048
 * per chunk — over a 4x4 of fresh proxies, no player online, {@code maxInFlight} raised (dev hook, not
 * persisted) so the budget rather than concurrency paces. Asserts: nothing started inside the write (all 16
 * queued), every start came from the pacer, in flight never above the cap, the budget blocked at least once,
 * the global write cap dropped nothing, and all 32768 blocks land. Self-halting.
 */
final class MassWriteProbe {
    private static final Logger LOGGER = LoggerFactory.getLogger("aerogel.masswrite");
    private static final int OFFSET = 6000;
    private static final int SIDE = 4;
    private static final int CHUNKS = SIDE * SIDE;
    private static final int Y = 150;
    private static final int LAYERS = 8;
    private static final int WRITES = CHUNKS * 256 * LAYERS;
    private static final int MAX_IN_FLIGHT = 8;
    private static final int MAX_TICKS = 2400;

    private static ServerLevel level;
    private static ChunkPos corner;
    private static int ticks;
    private static int writeTick = -1;
    private static int oldMaxInFlight;
    private static String atWrite;
    private static boolean writeOk;
    private static int peakInFlight;
    private static int realTick = -1;
    private static long lastNanos;
    private static double worstTickMs;
    private static int stalls;

    private MassWriteProbe() {
    }

    static void start(MinecraftServer server) {
        level = server.overworld();
        ChunkPos spawn = new ChunkPos(level.getSharedSpawnPos());
        // earlier runs leave their chunks real: walk +x for a 4x4 that is still all fresh (asked from disk)
        for (int i = 0; i < 64 && corner == null; i++) {
            ChunkPos candidate = new ChunkPos(spawn.x + OFFSET + i * 32, spawn.z - OFFSET);
            boolean fresh = true;
            for (int c = 0; c < CHUNKS && fresh; c++) {
                fresh = level.getChunkSource().chunkMap.read(chunk(candidate, c)).join().isEmpty();
            }
            if (fresh) {
                corner = candidate;
            }
        }
        if (corner == null) {
            fail(server, "no fresh 4x4 near offset " + OFFSET);
            return;
        }
        for (int c = 0; c < CHUNKS; c++) {
            ChunkPos p = chunk(corner, c);
            level.setChunkForced(p.x, p.z, true);
            level.getChunk(p.x, p.z);
        }
        LOGGER.info("mass write: 4x4 of fresh proxies at {}", corner);
    }

    private static ChunkPos chunk(ChunkPos base, int i) {
        return new ChunkPos(base.x + i % SIDE, base.z + i / SIDE);
    }

    static void tick(MinecraftServer server) {
        if (level == null || corner == null) {
            return;
        }
        ticks++;
        long now = System.nanoTime();
        if (writeTick >= 0 && lastNanos != 0L) {
            double ms = (now - lastNanos) / 1e6;
            worstTickMs = Math.max(worstTickMs, ms);
            stalls += ms >= 100.0 ? 1 : 0;
        }
        lastNanos = now;
        if (writeTick < 0) {
            for (int c = 0; c < CHUNKS; c++) {
                long p = chunk(corner, c).toLong();
                if (!ProxyChunkGate.isProxy(level, p) || !ProxyChunkGate.hasHolder(level, p)) {
                    if (ticks > MAX_TICKS) {
                        fail(server, "4x4 never became loaded proxies");
                    }
                    return;
                }
            }
            write();
            return;
        }
        peakInFlight = Math.max(peakInFlight, PromotionManager.debugInFlight(level));
        int real = 0;
        int pending = 0;
        for (int c = 0; c < CHUNKS; c++) {
            ChunkPos p = chunk(corner, c);
            if (level.getChunkSource().getChunkNow(p.x, p.z) != null && !ProxyChunkGate.isPromoting(level, p.toLong())) {
                real++;
            }
            pending += PromotionManager.debugDeferredWrites(level, p.toLong());
        }
        if (real == CHUNKS && realTick < 0) {
            realTick = ticks;
        }
        if (real == CHUNKS && pending == 0) {
            verdict(server);
        } else if (ticks - writeTick > MAX_TICKS) {
            fail(server, String.format("%d/%d real, %d write(s) pending after %d ticks; at write: %s",
                real, CHUNKS, pending, ticks - writeTick, atWrite));
        }
    }

    /** the /fill: every block of 8 layers over the 4x4, flags 2 like the fill command. */
    private static void write() {
        PromotionManager.debugResetRateCounters();
        oldMaxInFlight = PromotionManager.debugSetMaxInFlight(MAX_IN_FLIGHT);
        long t0 = System.nanoTime();
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int y = Y; y < Y + LAYERS; y++) {
            for (int x = 0; x < SIDE * 16; x++) {
                for (int z = 0; z < SIDE * 16; z++) {
                    level.setBlock(at.set(corner.getMinBlockX() + x, y, corner.getMinBlockZ() + z),
                        Blocks.GOLD_BLOCK.defaultBlockState(), 2);
                }
            }
        }
        double ms = (System.nanoTime() - t0) / 1e6;
        int started = 0;
        int queued = 0;
        for (int c = 0; c < CHUNKS; c++) {
            long p = chunk(corner, c).toLong();
            started += ProxyChunkGate.isPromoting(level, p) || !ProxyChunkGate.isProxy(level, p) ? 1 : 0;
            queued += PromotionManager.debugIsQueued(level, p) ? 1 : 0;
        }
        writeOk = started == 0 && queued == CHUNKS;
        atWrite = String.format("%d writes in %.1f ms, started %d, queued %d/%d", WRITES, ms, started, queued, CHUNKS);
        writeTick = ticks;
        LOGGER.info("mass write: {}", atWrite);
    }

    private static int landed() {
        int present = 0;
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int y = Y; y < Y + LAYERS; y++) {
            for (int x = 0; x < SIDE * 16; x++) {
                for (int z = 0; z < SIDE * 16; z++) {
                    if (level.getBlockState(at.set(corner.getMinBlockX() + x, y, corner.getMinBlockZ() + z)).is(Blocks.GOLD_BLOCK)) {
                        present++;
                    }
                }
            }
        }
        return present;
    }

    private static void verdict(MinecraftServer server) {
        int landed = landed();
        int paced = PromotionManager.debugLingerStarts();
        int budgetBlocks = PromotionManager.debugBudgetBlocks();
        int dropped = PromotionManager.debugDroppedWrites();
        boolean pass = writeOk && landed == WRITES && paced == CHUNKS && peakInFlight <= MAX_IN_FLIGHT
            && budgetBlocks > 0 && dropped == 0;
        LOGGER.info("mass write: VERDICT {} — at write: {}; landed {}/{}, dropped {}; pacer starts {}/{} "
                + "(forced {}), peak in flight {}/{}, budget blocks {}, brake blocks {}; real after {} ticks, "
                + "replayed after {} ticks; worst tick {} ms, ticks >= 100 ms {}",
            pass ? "PASS" : "FAIL", atWrite, landed, WRITES, dropped, paced, CHUNKS,
            PromotionManager.debugForcedStarts(), peakInFlight, MAX_IN_FLIGHT, budgetBlocks,
            PromotionManager.debugBrakeBlocks(), realTick - writeTick, ticks - writeTick,
            String.format("%.1f", worstTickMs), stalls);
        finish(server);
    }

    private static void fail(MinecraftServer server, String why) {
        LOGGER.error("mass write: VERDICT FAIL — {}", why);
        finish(server);
    }

    private static void finish(MinecraftServer server) {
        if (writeTick >= 0) {
            PromotionManager.debugSetMaxInFlight(oldMaxInFlight);
        }
        if (corner != null) {
            for (int c = 0; c < CHUNKS; c++) {
                ChunkPos p = chunk(corner, c);
                level.setChunkForced(p.x, p.z, false);
            }
        }
        level = null;
        server.halt(false);
    }
}
