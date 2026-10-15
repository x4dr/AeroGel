package dev.aerogel.server;

import com.mojang.authlib.GameProfile;
import java.util.List;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Headless check that a client <b>without AeroGel</b> gets its terrain <b>paced, not uncapped</b>
 * (flag file: {@code <serverDir>/aerogel.vanillaclient}), for #15.
 *
 * <p><b>Re-specified.</b> The old version asserted that a client without AeroGel "keeps a vanilla zone while
 * enforcing" — that described the opt-out subtraction, which no longer exists. Under the current model an
 * unmodded client <b>is</b> gated like everyone else: generation is a server-side cost, excluding a client
 * that cannot see proxies would only move the stall somewhere it cannot be seen coming, and the server does
 * not branch on whether a client renders. What #15 actually filed was the <b>uncapped</b> half of that: the
 * leftover arm walked the player's whole square and called {@code promoteImmediate} on every stub, with no
 * budget, hard gate or concurrency cap.</p>
 *
 * <p>So the assertion is the pacing. A {@code FakePlayer} issues no chunk requests, so the storm cannot be
 * reproduced through the vanilla ticket path; instead the probe force-loads a small square near the fake, so
 * the chunks become real stubs that the linger path sees, and watches how many are generating at once.
 * Uncapped means the whole square at once; paced means at most {@code maxInFlight}. It halts when the square
 * is fully real (PASS) or on a timeout (FAIL).</p>
 *
 * <p><b>This is the failure the probe can catch:</b> reintroduce any path that hands a player's neighbourhood
 * to {@link PromotionManager#debugEnqueue}/{@code promoteImmediate} in bulk and {@code peakInFlight} jumps
 * past {@code maxInFlight}, which is the assertion. The old probe's assertion cannot be restored because the
 * contract it described is gone.</p>
 *
 * <p>The fake is removed from the player list before the next ~20-tick boundary: it has no connection, and
 * {@code MinecraftServer.synchronizeTime} walks every player calling {@code hasChannel}.</p>
 */
final class VanillaClientProbe {
    private static final Logger LOGGER = LoggerFactory.getLogger("aerogel.vanillaprobe");
    private static final int OFFSET = 6000;
    private static final int RADIUS = 1; // 3x3: enough for the pacing assertion, short enough to finish
    private static final int IDLE_TIMEOUT = 20 * 120;
    private static final int MAX_TICKS = 1200;

    private static ServerLevel level;
    private static FakePlayer plain;
    private static ChunkPos center;
    private static int expected;
    private static int ticks;
    private static int peakInFlight;
    private static boolean noMeshes;

    private VanillaClientProbe() {
    }

    static void start(MinecraftServer server) {
        level = server.overworld();
        ChunkPos spawn = new ChunkPos(level.getSharedSpawnPos());
        // walk away from spawn until we find a block that is still entirely fresh, so a persisted run does
        // not read as an instant PASS
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
        plain = FakePlayerFactory.get(level,
            new GameProfile(UUID.fromString("ae0ae0ae-0000-4000-8000-0000000007a2"), "aerogel-plain"));
        AdaptiveMode.setHasAeroGel(plain, false); // #7: no mesh_batch channel, so no meshes are sent to it
        // a FakePlayer has no connection, so teleportTo is a no-op that NPEs; place it explicitly. pushZones
        // reads player.chunkPosition(), so a fake left at its default position would push a zone elsewhere.
        plain.setPos(center.getMiddleBlockX() + 0.5, 200, center.getMiddleBlockZ() + 0.5);
        park(server);
        // force-load the square so the chunks become stubs the linger path can see; a FakePlayer requests
        // nothing of its own
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                level.setChunkForced(center.x + dx, center.z + dz, true);
                level.getChunk(center.x + dx, center.z + dz);
                expected++;
            }
        }
        noMeshes = !AdaptiveMode.renders(plain);
        LOGGER.info("vanilla client probe: {} fresh chunks around {} force-loaded for an unmodded client"
            + " (renders={}), concurrency cap {}", expected, center, AdaptiveMode.renders(plain),
            PromotionManager.maxInFlight());
    }

    static void tick(MinecraftServer server) {
        if (level == null) {
            return;
        }
        ticks++;
        if (ticks > IDLE_TIMEOUT) {
            finish(server, false, "server never went quiet enough to start (still enforcing)");
            return;
        }
        // the gate is globally on under debugAlwaysOn, so the refill sees the whole square immediately and the
        // only thing left to observe is how fast it is allowed to start them
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
            if (peakInFlight > PromotionManager.maxInFlight()) {
                finish(server, false, String.format(
                    "%d chunks generated concurrently for one unmodded client, cap is %d: uncapped (#15)",
                    peakInFlight, PromotionManager.maxInFlight()));
                return;
            }
            if (!noMeshes) {
                finish(server, false, "a client without the mesh_batch channel was treated as mesh-capable (#15)");
                return;
            }
            finish(server, true, String.format("all %d chunks real, peak concurrent generation %d <= cap %d,"
                + " no meshes sent", expected, peakInFlight, PromotionManager.maxInFlight()));
            return;
        }
        if (ticks > MAX_TICKS) {
            finish(server, false, String.format("only %d/%d real after %d ticks (peak in flight %d)",
                real, expected, ticks, peakInFlight));
        }
    }

    /** the mutable backing list ({@code getPlayers()} is unmodifiable); probe-only reflection. */
    @SuppressWarnings("unchecked")
    private static List<ServerPlayer> playerList(MinecraftServer server) {
        try {
            java.lang.reflect.Field f = net.minecraft.server.players.PlayerList.class.getDeclaredField("players");
            f.setAccessible(true);
            return (List<ServerPlayer>) f.get(server.getPlayerList());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void park(MinecraftServer server) {
        playerList(server).add(plain);
    }

    private static void unpark(MinecraftServer server) {
        playerList(server).remove(plain);
    }

    private static void finish(MinecraftServer server, boolean pass, String detail) {
        if (pass) {
            LOGGER.info("vanilla client probe: VERDICT PASS — {}", detail);
        } else {
            LOGGER.error("vanilla client probe: VERDICT FAIL — {}", detail);
        }
        if (center != null) {
            for (int dx = -RADIUS; dx <= RADIUS; dx++) {
                for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                    level.setChunkForced(center.x + dx, center.z + dz, false);
                }
            }
        }
        unpark(server);
        if (plain != null) {
            AdaptiveMode.removePlayer(plain.getUUID());
        }
        level = null;
        plain = null;
        center = null;
        server.halt(false);
    }
}
