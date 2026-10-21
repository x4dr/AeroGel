package dev.aerogel.server;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Headless check of {@code promotion.refuseStubWrites} (flag file: &lt;serverDir&gt;/aerogel.refuse; #19), the
 * detection-free fallback for the #16 white-box risk. With refusal on, a write into a fresh proxy must be
 * refused ({@code setBlock} false, nothing banked), and that single attempt must promote the chunk; once it is
 * real the same write must land directly. The setting goes through the non-persisting dev hook. Self-halting.
 */
final class RefuseProbe {
    private static final Logger LOGGER = LoggerFactory.getLogger("aerogel.refuseprobe");
    private static final int OFFSET = 7000;
    private static final int MAX_TICKS = 600;

    private static ServerLevel level;
    private static ChunkPos probe;
    private static BlockPos at;
    private static boolean wasRefusing;
    private static String first;
    private static boolean firstOk;
    private static int ticks;

    private RefuseProbe() {
    }

    static void start(MinecraftServer server) {
        level = server.overworld();
        ChunkPos spawn = new ChunkPos(level.getSharedSpawnPos());
        for (int i = 0; i < 64 && probe == null; i++) {
            ChunkPos candidate = new ChunkPos(spawn.x - OFFSET - i * 32, spawn.z + OFFSET);
            if (level.getChunkSource().chunkMap.read(candidate).join().isEmpty()) {
                level.setChunkForced(candidate.x, candidate.z, true);
                level.getChunk(candidate.x, candidate.z);
                probe = candidate;
            }
        }
        if (probe == null) {
            LOGGER.error("refuse probe: VERDICT FAIL — no fresh proxy found near offset {}", OFFSET);
            server.halt(false);
            return;
        }
        at = new BlockPos(probe.getMinBlockX() + 8, 200, probe.getMinBlockZ() + 8);
        wasRefusing = PromotionManager.refusesStubWrites();
        PromotionManager.debugSetRefuseStubWrites(true);
    }

    static void tick(MinecraftServer server) {
        if (level == null || probe == null) {
            return;
        }
        long pos = probe.toLong();
        if (first == null) {
            if (!ProxyChunkGate.hasHolder(level, pos)) {
                return; // as under a player who writes: the forced ticket has reached the holder
            }
            boolean proxy = ProxyChunkGate.isProxy(level, pos);
            boolean result = level.setBlock(at, Blocks.DIAMOND_BLOCK.defaultBlockState(), 3);
            boolean promoting = ProxyChunkGate.isPromoting(level, pos);
            int banked = PromotionManager.debugDeferredWrites(level, pos);
            firstOk = proxy && !result && promoting && banked == 0;
            first = String.format("proxy before=%s, setBlock=%s, promoting after=%s, banked=%d", proxy, result, promoting, banked);
            LOGGER.info("refuse probe: first write {}", first);
            return;
        }
        ++ticks;
        boolean real = level.getChunkSource().getChunkNow(probe.x, probe.z) != null && !ProxyChunkGate.isPromoting(level, pos);
        if (real) {
            // nothing may have been banked and replayed: the refused write must not appear on its own
            boolean absent = !level.getBlockState(at).is(Blocks.DIAMOND_BLOCK);
            int banked = PromotionManager.debugDeferredWrites(level, pos);
            boolean retry = level.setBlock(at, Blocks.DIAMOND_BLOCK.defaultBlockState(), 3);
            boolean landed = level.getBlockState(at).is(Blocks.DIAMOND_BLOCK);
            boolean pass = firstOk && absent && banked == 0 && retry && landed;
            LOGGER.info("refuse probe: VERDICT {} — first write: {}; real after {} ticks, refused write absent={}, "
                    + "banked={}; retry setBlock={}, landed={}", pass ? "PASS" : "FAIL", first, ticks, absent, banked,
                retry, landed);
            finish(server);
        } else if (ticks > MAX_TICKS) {
            LOGGER.error("refuse probe: VERDICT FAIL — first write: {}; chunk {} not real after {} ticks", first, probe, ticks);
            finish(server);
        }
    }

    private static void finish(MinecraftServer server) {
        PromotionManager.debugSetRefuseStubWrites(wasRefusing);
        level.setChunkForced(probe.x, probe.z, false);
        level = null;
        probe = null;
        server.halt(false);
    }
}
