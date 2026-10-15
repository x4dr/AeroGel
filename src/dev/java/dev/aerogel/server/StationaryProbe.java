package dev.aerogel.server;

import com.mojang.authlib.GameProfile;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.common.util.FakePlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Headless check that sampling is a <b>burst, not a drip</b> (flag file:
 * {@code <serverDir>/aerogel.stationary}).
 *
 * <p>A player standing still must cost nothing after the initial fill: the wanted list is rebuilt on a chunk
 * cross, and refinement only re-samples chunks entering the near band, so a stationary player should send its
 * first window of meshes and then stop. That was assumed from the code structure and had never been measured,
 * which is why this probe exists.</p>
 *
 * <p>Measured from {@link ProxyStreamer#totalChunksSent}, the same cumulative counter the flight probe uses.
 * Phase 1 waits for the counter to stop growing (the burst). Phase 2 holds the player still for
 * {@link #HOLD_TICKS} and requires the counter not to move at all. A single mesh re-sent in that window fails
 * it, so the number is reported in the verdict.</p>
 */
final class StationaryProbe {
    private static final Logger LOGGER = LoggerFactory.getLogger("aerogel.stationaryprobe");
    private static final int OFFSET = 8000;
    private static final int BURST_TIMEOUT = 20 * 120;
    private static final int STABLE_TICKS = 100;  // ticks with no growth that count as "burst finished"
    private static final int HOLD_TICKS = 400;    // then hold still this long and require nothing at all
    private static final int IDLE_TIMEOUT = 20 * 120;

    private static ServerLevel level;
    private static FakePlayer viewer;
    private static int ticks;
    private static long lastSent;
    private static long burstEnd = -1;
    private static int stable;
    private static int phase;

    private StationaryProbe() {
    }

    static void start(MinecraftServer server) {
        level = server.overworld();
        ChunkPos spawn = new ChunkPos(level.getSharedSpawnPos());
        ChunkPos target = new ChunkPos(spawn.x + OFFSET, spawn.z + OFFSET);
        viewer = ProbeFakePlayer.get(level,
            new GameProfile(UUID.fromString("ae0ae0ae-0000-4000-8000-0000000007c1"), "aerogel-stationary"));
        AdaptiveMode.setHasAeroGel(viewer, true);
        AdaptiveMode.setPlayerSwitch(viewer, "proxy", true);  // it receives meshes
        AdaptiveMode.setPlayerSwitch(viewer, "travel", false); // but does not ask for gating: sampling only
        viewer.setPos(target.getMiddleBlockX() + 0.5, 200, target.getMiddleBlockZ() + 0.5);
        // parked for the whole run: the fake's shared Connection now carries a draining channel, so it can
        // stay in the player list across the tickCount % 20 boundary this probe used to dodge
        if (!ProbeFakePlayer.park(server, viewer)) {
            finish(server, false, "the fake player has no draining channel, so it cannot stay in the player"
                + " list and this run would assert nothing");
        }
    }

    static void tick(MinecraftServer server) {
        if (level == null) {
            return;
        }
        ticks++;
        if (ticks > IDLE_TIMEOUT + BURST_TIMEOUT + HOLD_TICKS) {
            finish(server, false, "probe itself timed out");
            return;
        }
        long sent = ProxyStreamer.totalChunksSent();
        switch (phase) {
            case 0 -> { // wait for the initial burst to finish
                if (sent > lastSent) {
                    stable = 0;
                    lastSent = sent;
                    return;
                }
                if (sent == 0) {
                    if (ticks > IDLE_TIMEOUT) {
                        finish(server, false, "no meshes were ever streamed to a proxy-rendering client");
                    }
                    return;
                }
                if (++stable >= STABLE_TICKS) {
                    burstEnd = sent;
                    LOGGER.info("stationary probe: burst settled at {} chunk(s) after {} ticks; holding still"
                        + " for {} ticks", burstEnd, ticks, HOLD_TICKS);
                    lastSent = sent;
                    phase = 1;
                }
            }
            case 1 -> { // standing still must cost nothing
                if (sent != lastSent) {
                    finish(server, false, String.format("%d chunk(s) sent while the player stood still"
                        + " (burst was %d)", sent - lastSent, burstEnd));
                    return;
                }
                if (ticks - stable >= HOLD_TICKS) {
                    finish(server, true, String.format("one burst of %d chunk(s), then 0 sent over %d"
                        + " stationary ticks (%s)", burstEnd, HOLD_TICKS, ProbeFakePlayer.drainSummary()));
                }
            }
            default -> finish(server, false, "unreachable phase");
        }
    }

    private static void finish(MinecraftServer server, boolean pass, String detail) {
        if (pass) {
            LOGGER.info("stationary probe: VERDICT PASS — {}", detail);
        } else {
            LOGGER.error("stationary probe: VERDICT FAIL — {}", detail);
        }
        ProbeFakePlayer.unpark(server, viewer);
        AdaptiveMode.removePlayer(viewer.getUUID());
        level = null;
        viewer = null;
        server.halt(false);
    }
}
