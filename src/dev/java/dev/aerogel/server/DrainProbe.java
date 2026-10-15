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
 * Headless integration check of the disengage model (flag file: {@code <serverDir>/aerogel.drain}), the core of
 * the 2026-10-15 rewrite. Real adaptive path, no {@code debugAlwaysOn}, so enforcement actually engages and can
 * actually stop.
 *
 * <p>Three properties, all of which fail on an uncapped or un-vetoed implementation:</p>
 *
 * <ol>
 * <li><b>The veto holds.</b> Enforcement never drops on a tick where {@link PromotionManager#drained} is
 *     false. This is the invariant the whole disengage model exists for: an ungated stub generates on its
 *     player's own ticket, so dropping the gate with work outstanding is the burst.</li>
 * <li><b>Paced, not immediate.</b> The number of chunks generating at once never exceeds
 *     {@code maxInFlight}. The old leftover arm handed a player's whole square to {@code promoteImmediate}
 *     with no budget, hard gate or concurrency cap, and this is that defect as a measurable number.</li>
 * <li><b>Everything generates.</b> The square is fully real at the end. The drain is slow, never lossy.</li>
 * </ol>
 *
 * <p><b>Why the queue is filled with {@code debugEnqueue} instead of by the linger path.</b> A
 * {@code FakePlayer} cannot stay in the player list: {@code MinecraftServer.tickChildren} calls
 * {@code synchronizeTime} every 20 ticks, which walks every player and calls {@code hasChannel} on its
 * connection, and a fake has no channel. The linger path needs a player in the list for as long as the
 * window takes to cool, which is seconds — so it cannot be driven headlessly at all. The veto and the pacing
 * are what this probe covers, and both read the queue. Two things that need a real client are therefore
 * <b>not</b> covered here and belong in the in-game list: the refill's calm-threshold bypass filling the
 * queue, and the gated-stub count in {@link PromotionManager#drained} (which is only computed where players
 * are).</p>
 *
 * <p>{@code holdSeconds} is set to 0 for the run so the activating sample from the teleport does not keep the
 * gate on by itself: without that, enforcement would still be inside its hold for the whole drain and the
 * veto would never be exercised. The verdict says whether the veto was actually tested, so a run where
 * enforcement never had the chance to drop reports that rather than passing silently.</p>
 */
final class DrainProbe {
    private static final Logger LOGGER = LoggerFactory.getLogger("aerogel.drainprobe");
    private static final int OFFSET = 7000;
    private static final int RADIUS = 2; // 5x5: long enough to outlast the window, short enough to finish
    private static final int MAX_TICKS = 1200;
    private static final int IDLE_TIMEOUT = 20 * 120;

    private static ServerLevel level;
    private static FakePlayer pilot;
    private static ChunkPos center;
    private static int expected;
    private static int ticks;
    private static int startTick = -1;
    private static int peakInFlight;
    private static int vetoTicks;      // ticks where the queue was non-empty and enforcement was still on
    private static int violations;     // ticks where the gate was off with work outstanding: the burst
    private static int originalHold;
    private static boolean originalEngage;

    private DrainProbe() {
    }

    static void start(MinecraftServer server) {
        level = server.overworld();
        originalHold = AdaptiveMode.holdSeconds();
        originalEngage = AdaptiveMode.engageOnTeleport();
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
            finish(server, false, "no fresh 5x5 near offset " + OFFSET);
            return;
        }
        pilot = FakePlayerFactory.get(level,
            new GameProfile(UUID.fromString("ae0ae0ae-0000-4000-8000-0000000007b1"), "aerogel-drain"));
        // never added to the player list, so synchronizeTime never sees it: onTeleport fires from the event
        // and the gate reads no per-player state
        AdaptiveMode.setHasAeroGel(pilot, true);
        AdaptiveMode.setPlayerSwitch(pilot, "travel", false);
    }

    static void tick(MinecraftServer server) {
        if (level == null) {
            return;
        }
        ticks++;
        if (startTick < 0) {
            // an enforcing server at boot (the probe's own chunk reads) would move the zone under us
            if (AdaptiveMode.enforcing() || !PromotionManager.drained()) {
                if (ticks > IDLE_TIMEOUT) {
                    finish(server, false, "server never went idle before the probe could start");
                }
                return;
            }
            // engage enforcement on the real path (#14), then force-load the square so its holders can reach
            // FULL, and queue it. debugEnqueue is the dev hook that stands in for "these chunks qualified".
            AdaptiveMode.setHoldSeconds(0);
            AdaptiveMode.setEngageOnTeleport(true);
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
            LOGGER.info("drain probe: {} stubs queued around {} under enforcement, hold 0, cap {}",
                expected, center, PromotionManager.maxInFlight());
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

        // the veto: the gate may not be off while the queue or the in-flight set is non-empty
        boolean pending = !PromotionManager.drained();
        boolean gated = ProxyChunkGate.isEnforcing(level);
        if (pending && !gated) {
            violations++;
            if (violations <= 3) {
                LOGGER.warn("drain probe: gate off while {} queued/in flight ({} real) at t+{}",
                    PromotionManager.drainState(), real, ticks - startTick);
            }
        }
        if (pending && gated) {
            vetoTicks++;
        }

        if (real >= expected) {
            if (violations > 0) {
                finish(server, false, String.format("%d tick(s) with the gate off while %d chunks were still"
                    + " outstanding, peak concurrent generation %d", violations, expected, peakInFlight));
                return;
            }
            if (peakInFlight > PromotionManager.maxInFlight()) {
                finish(server, false, String.format("%d concurrent generations against a cap of %d (uncapped)",
                    peakInFlight, PromotionManager.maxInFlight()));
                return;
            }
            if (vetoTicks == 0) {
                finish(server, false, "the veto was never exercised: enforcement never had a chance to drop,"
                    + " so this run proves nothing about the drain");
                return;
            }
            finish(server, true, String.format("all %d real, peak concurrent %d <= cap %d, veto held on %d"
                + " tick(s) while work was outstanding", expected, peakInFlight,
                PromotionManager.maxInFlight(), vetoTicks));
            return;
        }
        if (ticks - startTick > MAX_TICKS) {
            finish(server, false, String.format("only %d/%d real after %d ticks (queued/in flight %s)",
                real, expected, ticks - startTick, PromotionManager.drainState()));
        }
    }

    private static void finish(MinecraftServer server, boolean pass, String detail) {
        if (pass) {
            LOGGER.info("drain probe: VERDICT PASS — {}", detail);
        } else {
            LOGGER.error("drain probe: VERDICT FAIL — {}", detail);
        }
        if (level != null && center != null) {
            for (int dx = -RADIUS; dx <= RADIUS; dx++) {
                for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                    level.setChunkForced(center.x + dx, center.z + dz, false);
                }
            }
        }
        AdaptiveMode.setHoldSeconds(originalHold);
        AdaptiveMode.setEngageOnTeleport(originalEngage);
        if (pilot != null) {
            AdaptiveMode.removePlayer(pilot.getUUID());
        }
        level = null;
        pilot = null;
        center = null;
        server.halt(false);
    }
}
