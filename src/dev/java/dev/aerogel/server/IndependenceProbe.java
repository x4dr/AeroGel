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
 * Headless check that gating and proxy rendering are independent axes (flag file:
 * {@code <serverDir>/aerogel.independence}).
 *
 * <p>{@code travel} is a <i>generation</i> request — "I will cross a lot of chunks, don't generate them".
 * {@code proxy} is a <i>rendering</i> request — "draw proxy terrain for me". Both combinations are
 * legitimate, and the whole point of the two-switch split is that either one works without the other:</p>
 *
 * <table>
 * <tr><th></th><th>{@code travel} off</th><th>{@code travel} on</th></tr>
 * <tr><th>{@code proxy} off</th><td>vanilla, nothing streamed</td><td><b>gated, nothing streamed</b>:
 *     saving server work regardless of what the client draws</td></tr>
 * <tr><th>{@code proxy} on</th><td><b>not gated, streamed</b>: looking at the horizon from inside
 *     someone else's zone or with the server enforcing</td><td>gated and streamed</td></tr>
 * </table>
 *
 * <p>The two off-diagonal cells are the ones that fail if the axes are coupled — the state before this
 * rewrite, where "gate off" meant "proxies disappear" because the opted-out arm promoted every nearby
 * proxy. So the probe drives a {@code FakePlayer} through all four combinations and reads the two predicates
 * directly: {@link ProxyChunkGate#isEngaged} (the generation axis, read at the player's own chunk with the
 * server not enforcing) and {@link AdaptiveMode#renders} (the rendering axis).</p>
 *
 * <p>Needs the real adaptive path with enforcement off, since while enforcing every position is engaged and
 * the generation axis cannot be observed.</p>
 */
final class IndependenceProbe {
    private static final Logger LOGGER = LoggerFactory.getLogger("aerogel.independenceprobe");
    private static final int OFFSET = 9000;
    private static final int IDLE_TIMEOUT = 20 * 120;

    private static ServerLevel level;
    private static FakePlayer player;
    private static int ticks;
    private static int step;
    private static boolean fails;
    private static final StringBuilder cells = new StringBuilder();

    private IndependenceProbe() {
    }

    static void start(MinecraftServer server) {
        level = server.overworld();
        player = FakePlayerFactory.get(level,
            new GameProfile(UUID.fromString("ae0ae0ae-0000-4000-8000-0000000007d1"), "aerogel-independence"));
        AdaptiveMode.setHasAeroGel(player, true); // confirmed channel: both switches are meaningful
        ChunkPos spawn = new ChunkPos(level.getSharedSpawnPos());
        player.setPos((spawn.x + OFFSET) * 16.0 + 8.0, 200, (spawn.z + OFFSET) * 16.0 + 8.0);
        park(server);
    }

    static void tick(MinecraftServer server) {
        if (level == null) {
            return;
        }
        ticks++;
        // enforcement is global and makes every position engaged, which hides the generation axis
        if (AdaptiveMode.enforcing()) {
            if (ticks > IDLE_TIMEOUT) {
                finish(server, false, "server never stopped enforcing, so the generation axis is unobservable");
            }
            return;
        }
        if (++step > 8) {
            return;
        }
        long here = player.chunkPosition().toLong();
        switch (step) {
            // one cell per pair of steps: the switches are set on one tick and read on the next, because the
            // zone is only rebuilt by AdaptiveMode's per-tick pushZones
            case 1 -> setSwitches(false, false);
            case 2 -> cell("travel off / proxy off", false, false, here);
            case 3 -> setSwitches(true, false);
            case 4 -> cell("travel on / proxy off", true, false, here);
            case 5 -> setSwitches(false, true);
            case 6 -> cell("travel off / proxy on", false, true, here);
            case 7 -> setSwitches(true, true);
            case 8 -> {
                cell("travel on / proxy on", true, true, here);
                // the zone is exactly the opted-in players', so a position far from every player is ungated
                long away = ChunkPos.asLong(player.chunkPosition().x + 1000, player.chunkPosition().z + 1000);
                if (ProxyChunkGate.isEngaged(level, away)) {
                    fails = true;
                    cells.append(", FAIL a position 1000 chunks from every player is engaged");
                }
                setSwitches(false, false);
                finish(server, !fails, cells.toString());
            }
            default -> {
            }
        }
    }

    private static void setSwitches(boolean travel, boolean proxy) {
        AdaptiveMode.setPlayerSwitch(player, "travel", travel);
        AdaptiveMode.setPlayerSwitch(player, "proxy", proxy);
    }

    /**
     * Evaluate one cell: whether a stub at the player's own chunk is engaged ({@code travel}'s axis) and
     * whether the player receives meshes ({@code proxy}'s axis). Both are read on a tick after the switches
     * were set, so the pushed zone reflects them.
     */
    private static void cell(String name, boolean travel, boolean proxy, long here) {
        boolean engaged = ProxyChunkGate.isEngaged(level, here);
        boolean renders = AdaptiveMode.renders(player);
        boolean ok = engaged == travel && renders == proxy;
        if (!ok) {
            fails = true;
        }
        cells.append(cells.isEmpty() ? "" : ", ");
        cells.append(String.format("%s%s [engaged=%s want %s, renders=%s want %s]",
            ok ? "" : "FAIL ", name, engaged, travel, renders, proxy));
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
        playerList(server).add(player);
    }

    private static void finish(MinecraftServer server, boolean pass, String detail) {
        if (pass) {
            LOGGER.info("independence probe: VERDICT PASS — {}", detail);
        } else {
            LOGGER.error("independence probe: VERDICT FAIL — {}", detail);
        }
        playerList(server).remove(player);
        AdaptiveMode.removePlayer(player.getUUID());
        level = null;
        player = null;
        server.halt(false);
    }
}
