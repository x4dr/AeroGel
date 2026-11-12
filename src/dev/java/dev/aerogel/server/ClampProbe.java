package dev.aerogel.server;

import com.mojang.authlib.GameProfile;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Headless check of the through-floor net when it has <b>no sampled grid</b> (flag file:
 * {@code <serverDir>/aerogel.clamp}).
 *
 * <p><b>The gap this covers.</b> {@code ProxyStreamer.onPlayerTick} used to return as soon as the grid for
 * the player's chunk was missing, on the assumption that a real chunk handles standing. For a player the
 * server does not stream to (no AeroGel client, or the {@code proxy} switch off) no grid is ever submitted,
 * so a gated chunk has no grid by construction: the net did nothing and the player fell through terrain that
 * had not generated until linger promoted the chunk. The answer is the same flat plane the client clamp uses
 * before a mesh arrives: assume {@code y = 64}, lift the player onto it, and let the descent trigger promote
 * the chunk from there.</p>
 *
 * <p><b>What it asserts.</b> A {@link FakePlayer} declared non-rendering is parked in a fresh gated chunk at
 * {@code y = 40} with no grid. The net must fire while that chunk is still proxy, queued or in flight, and
 * the chunk must then become queued, in flight or real, which is the terrain kick. Before the fix the player
 * stays at 40 and nothing is promoted, so the run FAILs. This probe is deliberately short: the linger
 * threshold would promote the chunk on its own after tens of seconds, and that would let a missing net read
 * as a pass. It also fails if the chunk becomes real before the net fired, which would make the run
 * vacuous.</p>
 *
 * <p><b>Why a counter and not the player's y.</b> A parked {@code FakePlayer} is in the player list but not
 * ticked, so {@code PlayerTickEvent} never reaches it and the probe drives {@code onPlayerTick} directly.
 * Worse, {@code ServerPlayer.teleportTo(double, double, double)} does not move a fake (measured: 40 -> 40),
 * so the position cannot witness the call. {@code ProxyStreamer.debugClampTeleports} counts the net's own
 * decision at the same point the production path takes it.</p>
 */
final class ClampProbe {
    private static final Logger LOGGER = LoggerFactory.getLogger("aerogel.clampprobe");
    private static final int OFFSET = 7500;
    private static final double DROP_Y = 40.0;
    private static final int MAX_TICKS = 120;

    private static ServerLevel level;
    private static FakePlayer plain;
    private static ChunkPos probe;
    private static int ticks;
    private static boolean sawHolder;
    private static boolean noGrid;
    private static int baseline;
    private static int firedTick = -1;
    private static String failure;

    private ClampProbe() {
    }

    static void start(MinecraftServer server) {
        level = server.overworld();
        ChunkPos spawn = new ChunkPos(level.getSharedSpawnPos());
        for (int i = 0; i < 96 && probe == null; i++) {
            ChunkPos candidate = new ChunkPos(spawn.x - OFFSET - i * 32, spawn.z - OFFSET);
            if (level.getChunkSource().chunkMap.read(candidate).join().isEmpty()) {
                probe = candidate;
            }
        }
        if (probe == null) {
            finish(server, false, "no fresh chunk near offset " + OFFSET);
            return;
        }
        plain = ProbeFakePlayer.get(level,
            new GameProfile(UUID.fromString("ae0ae0ae-0000-4000-8000-000000000c1a"), "aerogel-clamp"));
        AdaptiveMode.setHasAeroGel(plain, false); // a vanilla client: the server net is its only authority
        plain.setPos(probe.getMiddleBlockX() + 0.5, DROP_Y, probe.getMiddleBlockZ() + 0.5);
        if (!ProbeFakePlayer.park(server, plain)) {
            finish(server, false, "the fake player has no draining channel, so it cannot stay in the player list");
            return;
        }
        baseline = ProxyStreamer.debugClampTeleports();
        level.setChunkForced(probe.x, probe.z, true);
        level.getChunk(probe.x, probe.z);
        LOGGER.info("clamp probe: {} at y {} in fresh chunk {} (renders={})",
            plain.getGameProfile().getName(), DROP_Y, probe, AdaptiveMode.renders(plain));
    }

    static void tick(MinecraftServer server) {
        if (level == null || probe == null) {
            return;
        }
        if (failure != null) {
            finish(server, false, failure);
            return;
        }
        long pos = probe.toLong();
        if (!sawHolder) {
            if (!ProxyChunkGate.hasHolder(level, pos)) {
                return; // the forced ticket has not reached the holder yet
            }
            sawHolder = true;
            noGrid = ProxyStreamer.dumpGrid(level, pos).contains("no proxy grid");
            if (!noGrid) {
                failure = "a grid exists for the chunk, so the no-grid path is not being exercised";
                return;
            }
            LOGGER.info("clamp probe: holder reached for {}, no grid stored", probe);
        }
        ++ticks;
        // A parked fake is in the player list but not in the level's ticked player set, so PlayerTickEvent
        // never fires for it. Drive the production handler directly: the wiring is one line, the net is what
        // this probe is about.
        ProxyStreamer.onPlayerTick(new PlayerTickEvent.Post(plain));
        if (ProxyStreamer.debugClampTeleports() > baseline && firedTick < 0) {
            firedTick = ticks;
        }
        boolean proxy = ProxyChunkGate.isProxy(level, pos);
        boolean promoting = ProxyChunkGate.isPromoting(level, pos);
        boolean queued = PromotionManager.debugIsQueued(level, pos);
        boolean real = level.getChunkSource().getChunkNow(probe.x, probe.z) != null && !proxy && !promoting;
        if (real && firedTick < 0) {
            failure = String.format(
                "the chunk became real after %d ticks before the net ever fired on the no-grid path,"
                    + " so the run asserts nothing", ticks);
            return;
        }
        if (firedTick > 0 && (promoting || queued || real)) {
            finish(server, true, String.format(
                "no-grid chunk: the net fired at tick %d with the surface assumed at 64.0, and the chunk is %s"
                    + " after %d ticks (renders=false)", firedTick,
                real ? "real" : promoting ? "in flight" : "queued", ticks));
            return;
        }
        if (ticks > MAX_TICKS) {
            finish(server, false, String.format(
                "after %d ticks the net has %s on a no-grid proxy chunk (proxy=%s, queued=%s, in flight=%s, real=%s)",
                ticks, firedTick > 0 ? "fired" : "never fired", proxy, queued, promoting, real));
        }
    }

    private static void finish(MinecraftServer server, boolean pass, String detail) {
        if (pass) {
            LOGGER.info("clamp probe: VERDICT PASS - {}", detail);
        } else {
            LOGGER.error("clamp probe: VERDICT FAIL - {}", detail);
        }
        if (probe != null) {
            level.setChunkForced(probe.x, probe.z, false);
        }
        if (plain != null) {
            ProbeFakePlayer.unpark(server, plain);
            AdaptiveMode.removePlayer(plain.getUUID());
        }
        level = null;
        plain = null;
        probe = null;
        server.halt(false);
    }
}
