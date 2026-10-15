package dev.aerogel.server;

import com.mojang.authlib.GameProfile;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.EventHooks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tests the in-flight retry path (flag file: {@code <serverDir>/aerogel.inflight}).
 *
 * <p>{@link PromotionManager#retryInFlight} re-drives a promotion whose completion callback has not fired,
 * without releasing the concurrency slot. That is untested behaviour and it carries a risk worth naming: the
 * re-drive goes through {@code ProxyChunkGate.promote}, which runs {@code forcePrepare} — resetting the
 * holder's three cached futures and calling {@code updateFutures} — <b>while the generation may still be in
 * progress</b>. Whether that disturbs a live generation was not known, so this probe finds out.</p>
 *
 * <p>The retry interval is set to <b>one tick</b> for the run, which makes every in-flight chunk be re-driven
 * on every tick while it generates. That is far harsher than the real 15 s interval: if re-driving a live
 * generation were damaging, this is where it shows. Three things are asserted, and the first is the one the
 * risk is about:</p>
 *
 * <ol>
 * <li><b>Re-driving does not break generation.</b> Every chunk in the square still becomes real. If resetting
 *     the futures mid-generation were fatal, they would never finish.</li>
 * <li><b>The slot is never released on the retry.</b> Peak concurrent generation never exceeds
 *     {@code maxInFlight}. This is the property that separates retrying from the timer it replaced: the old
 *     sweep freed the slot on elapsed time, which let concurrency climb past the cap on a server already
 *     behind.</li>
 * <li><b>The retry path actually ran.</b> The retry counter is non-zero, so a pass here is not a pass because
 *     nothing happened.</li>
 * </ol>
 *
 * <p>Runs like {@code aerogel.drain}: real adaptive path, no player in the list (a {@code FakePlayer} cannot
 * stay in it for more than 19 ticks), queue filled with {@code debugEnqueue}.</p>
 */
final class InFlightProbe {
    private static final Logger LOGGER = LoggerFactory.getLogger("aerogel.inflightprobe");
    private static final int OFFSET = 11000;
    private static final int RADIUS = 1; // 3x3 keeps the hammering short
    private static final int MAX_TICKS = 1200;
    private static final int IDLE_TIMEOUT = 20 * 120;

    private static ServerLevel level;
    private static FakePlayer pilot;
    private static ChunkPos center;
    private static int expected;
    private static int ticks;
    private static int startTick = -1;
    private static int peakInFlight;
    private static int originalRetry;
    private static boolean originalEngage;

    private InFlightProbe() {
    }

    static void start(MinecraftServer server) {
        level = server.overworld();
        originalEngage = AdaptiveMode.engageOnTeleport();
        originalRetry = 300; // the compiled-in default; restored in finish
        ChunkPos spawn = new ChunkPos(level.getSharedSpawnPos());
        for (int i = 0; i < 96 && center == null; i++) {
            ChunkPos candidate = new ChunkPos(spawn.x + OFFSET + i * 32, spawn.z - OFFSET);
            boolean fresh = true;
            for (int dx = -RADIUS; dx <= RADIUS; dx++) {
                for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                    if (!level.getChunkSource().chunkMap.read(new ChunkPos(candidate.x + dx, candidate.z + dz))
                        .join().isEmpty()) {
                        fresh = false;
                    }
                }
            }
            if (fresh) {
                center = candidate;
            }
        }
        if (center == null) {
            finish(server, false, "no fresh 3x3 near offset " + OFFSET);
            return;
        }
        pilot = FakePlayerFactory.get(level,
            new GameProfile(UUID.fromString("ae0ae0ae-0000-4000-8000-0000000007e1"), "aerogel-inflight"));
        AdaptiveMode.setHasAeroGel(pilot, true);
        AdaptiveMode.setPlayerSwitch(pilot, "travel", false);
    }

    static void tick(MinecraftServer server) {
        if (level == null) {
            return;
        }
        ticks++;
        if (startTick < 0) {
            if (AdaptiveMode.enforcing() || !PromotionManager.drained()) {
                if (ticks > IDLE_TIMEOUT) {
                    finish(server, false, "server never went idle before the probe could start");
                }
                return;
            }
            // one tick: harsher than any real interval, so the risk is actually exercised
            PromotionManager.debugSetInFlightRetryTicks(1);
            AdaptiveMode.debugSetEngageOnTeleport(true);
            EventHooks.onEntityTeleportCommand(pilot, center.getMiddleBlockX(), 200, center.getMiddleBlockZ());
            if (!AdaptiveMode.enforcing() || !ProxyChunkGate.isEnforcing(level)) {
                finish(server, false, "enforcement did not engage on a teleport into fresh land (#14)");
                return;
            }
            for (int dx = -RADIUS; dx <= RADIUS; dx++) {
                for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                    long pos = ChunkPos.asLong(center.x + dx, center.z + dz);
                    level.setChunkForced(center.x + dx, center.z + dz, true);
                    level.getChunk(center.x + dx, center.z + dz);
                    PromotionManager.debugEnqueue(level, pos);
                    expected++;
                }
            }
            startTick = ticks;
            LOGGER.info("in-flight probe: {} chunks queued around {} with the retry interval forced to 1 tick"
                + " (cap {})", expected, center, PromotionManager.maxInFlight());
            return;
        }

        int inFlight = 0;
        int real = 0;
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                long pos = ChunkPos.asLong(center.x + dx, center.z + dz);
                if (ProxyChunkGate.isPromoting(level, pos)) {
                    inFlight++;
                }
                if (level.getChunkSource().getChunkNow(center.x + dx, center.z + dz) != null
                    && !ProxyChunkGate.isProxy(level, pos)) {
                    real++;
                }
            }
        }
        peakInFlight = Math.max(peakInFlight, inFlight);

        if (real >= expected) {
            int retries = PromotionManager.debugInFlightRetries();
            if (retries == 0) {
                finish(server, false, "no retry was ever issued, so this run proves nothing about the retry"
                    + " path (was the interval forced?)");
                return;
            }
            if (peakInFlight > PromotionManager.maxInFlight()) {
                finish(server, false, String.format("%d concurrent generations against a cap of %d: the retry"
                    + " released a slot while its generation was still running", peakInFlight,
                    PromotionManager.maxInFlight()));
                return;
            }
            finish(server, true, String.format("all %d real despite %d re-drive(s), peak concurrent %d <= cap"
                + " %d: re-driving a live generation did not disturb it and never freed a slot",
                expected, retries, peakInFlight, PromotionManager.maxInFlight()));
            return;
        }
        if (ticks - startTick > MAX_TICKS) {
            int retries = PromotionManager.debugInFlightRetries();
            finish(server, false, String.format("only %d/%d real after %d ticks with %d re-drive(s): if the"
                + " re-drives are what stalled it, forcePrepare on a live generation is destructive",
                real, expected, ticks - startTick, retries));
        }
    }

    private static void finish(MinecraftServer server, boolean pass, String detail) {
        if (pass) {
            LOGGER.info("in-flight probe: VERDICT PASS — {}", detail);
        } else {
            LOGGER.error("in-flight probe: VERDICT FAIL — {}", detail);
        }
        PromotionManager.debugSetInFlightRetryTicks(originalRetry);
        AdaptiveMode.debugSetEngageOnTeleport(originalEngage);
        if (level != null && center != null) {
            for (int dx = -RADIUS; dx <= RADIUS; dx++) {
                for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                    level.setChunkForced(center.x + dx, center.z + dz, false);
                }
            }
        }
        if (pilot != null) {
            AdaptiveMode.removePlayer(pilot.getUUID());
        }
        level = null;
        pilot = null;
        center = null;
        server.halt(false);
    }
}
