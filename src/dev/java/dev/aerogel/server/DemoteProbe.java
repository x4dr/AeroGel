package dev.aerogel.server;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A promotion whose holder is demoted before it finishes (flag file: {@code <serverDir>/aerogel.demote}).
 *
 * <p>{@code ChunkHolder.updateFutures} completes the cached FULL future of a demoted holder with an error
 * <b>result</b> and no exception, and that is the future a promotion waits on. Read by its exception alone,
 * it logged "promoted ... to real terrain" and pushed and resent a chunk that is not real (#20). The probe
 * force-loads one fresh proxy chunk, promotes it, and drops its only ticket in the same tick, so the holder
 * leaves FULL long before generation can finish. PASS: the completion arrives, the chunk is not real, and
 * the gate counted it as a failed promotion (so it was neither pushed nor resent).</p>
 */
final class DemoteProbe {
    private static final Logger LOGGER = LoggerFactory.getLogger("aerogel.demoteprobe");
    private static final int OFFSET = 13000;
    private static final int MAX_TICKS = 400;

    private static ServerLevel level;
    private static ChunkPos target;
    private static int ticks;
    private static int promotedTick = -1;
    private static int completedTick = -1;
    private static int failedBefore;

    private DemoteProbe() {
    }

    static void start(MinecraftServer server) {
        level = server.overworld();
        ChunkPos spawn = new ChunkPos(level.getSharedSpawnPos());
        for (int i = 0; i < 64 && target == null; i++) {
            ChunkPos candidate = new ChunkPos(spawn.x - OFFSET - i * 8, spawn.z + OFFSET);
            if (level.getChunkSource().chunkMap.read(candidate).join().isEmpty()) {
                target = candidate;
            }
        }
        if (target == null) {
            finish(server, false, "no fresh chunk near offset " + OFFSET);
            return;
        }
        level.setChunkForced(target.x, target.z, true);
        level.getChunk(target.x, target.z); // instant placeholder; the holder reaches FULL once the ticket lands
    }

    static void tick(MinecraftServer server) {
        if (level == null) {
            return;
        }
        ticks++;
        long pos = target.toLong();
        if (promotedTick < 0) {
            if (!ProxyChunkGate.hasHolder(level, pos)) {
                if (ticks > MAX_TICKS) {
                    finish(server, false, "the forced chunk never got a FULL holder");
                }
                return;
            }
            if (!ProxyChunkGate.isProxy(level, pos)) {
                finish(server, false, "the forced chunk is not a proxy, so there is nothing to promote");
                return;
            }
            failedBefore = ProxyChunkGate.debugFailedPromotions();
            if (!ProxyChunkGate.promote(level, target, () -> completedTick = ticks)) {
                finish(server, false, "the gate refused the promotion");
                return;
            }
            level.setChunkForced(target.x, target.z, false); // its only ticket: the holder demotes next update
            promotedTick = ticks;
            LOGGER.info("demote probe: promoted {} and dropped its ticket in the same tick", target);
            return;
        }
        if (completedTick < 0) {
            if (ticks - promotedTick > MAX_TICKS) {
                finish(server, false, "no completion within " + MAX_TICKS + " ticks of the demotion");
            }
            return;
        }
        boolean real = level.getChunkSource().getChunkNow(target.x, target.z) != null;
        int failed = ProxyChunkGate.debugFailedPromotions() - failedBefore;
        if (real) {
            finish(server, false, "the chunk finished before the demotion, so the demoted path was not exercised");
        } else if (failed != 1) {
            finish(server, false, String.format("completion after %d tick(s), chunk not real, but %d failed"
                + " promotion(s) counted: the demoted holder was taken as promoted (pushed and resent)",
                completedTick - promotedTick, failed));
        } else {
            finish(server, true, String.format("completion after %d tick(s), chunk not real, counted as a failed"
                + " promotion: not pushed, not resent", completedTick - promotedTick));
        }
    }

    private static void finish(MinecraftServer server, boolean pass, String detail) {
        if (pass) {
            LOGGER.info("demote probe: VERDICT PASS — {}", detail);
        } else {
            LOGGER.error("demote probe: VERDICT FAIL — {}", detail);
        }
        if (level != null && target != null) {
            level.setChunkForced(target.x, target.z, false);
        }
        level = null;
        target = null;
        server.halt(false);
    }
}
