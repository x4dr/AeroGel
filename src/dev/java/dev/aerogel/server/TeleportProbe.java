package dev.aerogel.server;

import com.mojang.authlib.GameProfile;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.EventHooks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Headless check of #14 (flag file: &lt;serverDir&gt;/aerogel.teleport), on the real adaptive path (no
 * {@code debugAlwaysOn}). Once the server idles (not enforcing), a FakePlayer fires the same event {@code /tp}
 * fires before the move ({@code EventHooks.onEntityTeleportCommand}) into fresh far land: with
 * {@code engageOnTeleport} false nothing engages (today's behaviour); with it true AeroGel must enforce before
 * the arrival ticket ({@code ServerPlayer.teleportTo} = POST_TELEPORT) and the arrival read must be a
 * placeholder, not generated; then enforcement must end by itself after {@code holdSeconds}. Self-halting.
 */
final class TeleportProbe {
    private static final Logger LOGGER = LoggerFactory.getLogger("aerogel.tpprobe");
    private static final int OFFSET = 6000;
    private static final int IDLE_TIMEOUT = 20 * 120;

    private static ServerLevel level;
    private static FakePlayer fake;
    private static ChunkPos a;
    private static ChunkPos b;
    private static ChunkPos c;
    private static boolean original;
    private static int ticks;
    private static int engagedTick = -1;
    private static String early;

    private TeleportProbe() {
    }

    static void start(MinecraftServer server) {
        level = server.overworld();
        original = AdaptiveMode.engageOnTeleport();
        ChunkPos spawn = new ChunkPos(level.getSharedSpawnPos());
        // earlier runs leave chunks behind: walk -x for two never-generated ones (asked from disk, see PromotionProbe)
        for (int i = 0; i < 64 && c == null; i++) {
            ChunkPos candidate = new ChunkPos(spawn.x - OFFSET - i * 32, spawn.z + OFFSET);
            if (level.getChunkSource().chunkMap.read(candidate).join().isEmpty()) {
                if (a == null) {
                    a = candidate;
                } else if (b == null) {
                    b = candidate;
                } else {
                    c = candidate;
                }
            }
        }
        fake = FakePlayerFactory.get(level, new GameProfile(UUID.fromString("ae0ae0ae-0000-4000-8000-00000000014a"), "aerogel-tp"));
        // The login cache is a positive set: absence means "no AeroGel channels", and hasAeroGel gates optsIn.
        // Without this the fake is never opted in, so no zone is pushed and the target is never gated, whatever
        // the switch says. Every other probe that needs an opted-in player declares it; this one was missed
        // when the set was inverted.
        AdaptiveMode.setHasAeroGel(fake, true);
        if (c == null) {
            fail(server, "no three fresh chunks near offset " + OFFSET);
        }
    }

    static void tick(MinecraftServer server) {
        if (level == null) {
            return;
        }
        ticks++;
        if (engagedTick < 0) {
            if (AdaptiveMode.enforcing() || !PromotionManager.drained()) {
                if (ticks > IDLE_TIMEOUT) {
                    fail(server, "server never went idle (still enforcing at start)");
                }
                return;
            }
            AdaptiveMode.setEngageOnTeleport(false);
            EventHooks.onEntityTeleportCommand(fake, a.getMiddleBlockX(), 200, a.getMiddleBlockZ());
            boolean offEngaged = AdaptiveMode.enforcing() || ProxyChunkGate.isEnforcing(level);
            // exploration mode (client travel) with the key false: the opted-in player's zone must sit at the target
            // before the move. pushZones walks the player list, so the FakePlayer joins it for this call only.
            AdaptiveMode.setPlayerSwitch(fake, "travel", true);
            List<ServerPlayer> players = playerList(server);
            players.add(fake);
            boolean gatedBefore = ProxyChunkGate.isGated(level, c.toLong());
            EventHooks.onEntityTeleportCommand(fake, c.getMiddleBlockX(), 200, c.getMiddleBlockZ());
            boolean optInGated = !gatedBefore && ProxyChunkGate.isGated(level, c.toLong()) && !AdaptiveMode.enforcing();
            players.remove(fake);
            AdaptiveMode.setPlayerSwitch(fake, "travel", false);
            LOGGER.info("teleport probe: opted-in, key false -> target {} gated before the move {} (before {})", c, optInGated, gatedBefore);
            if (!optInGated) {
                fail(server, "opted-in player's target not gated before the move (key false)");
                return;
            }
            AdaptiveMode.setEngageOnTeleport(true);
            EventHooks.onEntityTeleportCommand(fake, b.getMiddleBlockX(), 200, b.getMiddleBlockZ());
            boolean onEngaged = AdaptiveMode.enforcing() && ProxyChunkGate.isEnforcing(level);
            early = "key false -> enforcing " + offEngaged + "; key true -> enforcing before the move " + onEngaged;
            LOGGER.info("teleport probe: {} (A={} B={})", early, a, b);
            if (offEngaged) {
                fail(server, "engaged with the key false: " + early);
                return;
            }
            if (!onEngaged) {
                fail(server, "not enforcing before the arrival request with the key true: " + early);
                return;
            }
            fake.teleportTo(level, b.getMiddleBlockX(), 200, b.getMiddleBlockZ(), Set.of(), 0, 0); // arrival ticket
            long t0 = System.nanoTime();
            ChunkAccess arrival = level.getChunk(b.x, b.z);
            double ms = (System.nanoTime() - t0) / 1e6;
            early += String.format(", arrival read %s in %.2f ms", arrival.getClass().getSimpleName(), ms);
            if (!(arrival instanceof EmptyLevelChunk)) {
                fail(server, "arrival chunk was generated synchronously: " + early);
                return;
            }
            engagedTick = ticks;
            return;
        }
        if (ticks - engagedTick == 20) {
            boolean generated = level.getChunkSource().getChunkNow(b.x, b.z) != null;
            boolean proxy = ProxyChunkGate.isProxy(level, b.toLong());
            early += ", after 20 ticks generated " + generated + " proxy " + proxy;
            if (generated || !proxy) {
                fail(server, "arrival chunk generated under its ticket: " + early);
            }
            return;
        }
        if (ticks - engagedTick > 20 && !AdaptiveMode.enforcing()) {
            double s = (ticks - engagedTick) / 20.0;
            LOGGER.info("teleport probe: VERDICT PASS — {}, enforcing off by itself after {} s (hold {} s)",
                early, String.format("%.1f", s), AdaptiveMode.holdSeconds());
            finish(server);
        } else if (ticks - engagedTick > 20 * (AdaptiveMode.holdSeconds() + AdaptiveMode.windowSeconds() + 60)) {
            fail(server, "enforcing never switched off: " + early);
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

    private static void fail(MinecraftServer server, String why) {
        LOGGER.error("teleport probe: VERDICT FAIL — {}", why);
        finish(server);
    }

    private static void finish(MinecraftServer server) {
        AdaptiveMode.setEngageOnTeleport(original);
        level = null;
        server.halt(false);
    }
}
