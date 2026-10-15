package dev.aerogel.server;

import com.mojang.authlib.GameProfile;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link FakePlayer} that can stay in the player list for a whole probe run.
 *
 * <p><b>The problem this removes.</b> A fake's connection is a shared static
 * {@code FakePlayer.DUMMY_CONNECTION}, a plain {@code Connection(PacketFlow.SERVERBOUND)} that was never
 * channel-activated, so its {@code channel} field is null. That is fine until something asks whether it can
 * receive a payload: {@code ChannelAttributes.getPayloadSetup} does {@code connection.channel().attr(...)} and
 * NPEs on the null. {@code MinecraftServer.tickChildren} walks every player calling
 * {@code NetworkRegistry.hasChannel} whenever {@code tickCount % 20 == 0}, so a fake in the player list
 * crashes the server on every time update. Probes worked around that with duty cycles — in for 18 ticks, out
 * for the two around the boundary — which makes a probe's behaviour depend on where the run lands in the
 * cycle and rules out any probe needing a player present for more than 18 contiguous ticks.</p>
 *
 * <p><b>The fix.</b> Give the shared dummy {@code Connection} a real {@link EmbeddedChannel}, attached
 * through the one field that decides it ({@code Connection.channel}, private, non-final, assigned only by
 * {@code channelActive}; there is a public getter but no setter, hence reflection). {@code hasChannel} then
 * reads attributes from an empty map and returns false, exactly as it does for a client that never declared
 * the channel — no NPE, no branch on the fake. {@code isConnected()} starts answering true, so the fake stops
 * being a special case for every other code path too: a probe can now be run in a pack with other mods, and a
 * mod that behaves differently for connected players is exercised rather than dodged.</p>
 *
 * <p><b>Why the channel has a draining handler.</b> Once {@code isConnected()} is true, anything that writes
 * to a player's <i>Connection</i> writes into this channel, and an {@link EmbeddedChannel} stores what reaches
 * the tail of its pipeline, so an unconsumed write would grow for the life of the run. The handler counts the
 * message, releases it and succeeds the promise, which is what keeps that bounded.</p>
 *
 * <p><b>What does not reach it, and why the count is normally zero.</b> A fake's own outbound traffic never
 * gets here: {@code FakePlayerNetHandler} overrides both {@code Connection.send} overloads to no-ops, so
 * {@code synchronizeTime}, chunk sends and broadcasts are discarded before the connection. The drain is a
 * safety net for other writers, so {@link #selfCheck} proves it consumes by sending one packet straight down
 * the connection rather than waiting for traffic that will not come.</p>
 *
 * <p><b>Not a complete client.</b> The payload setup is left empty, so the fake is "connected, declared
 * nothing" — the same as an unmodded client. Nothing here fakes the AeroGel channel handshake; a probe that
 * needs the server to think the fake receives meshes sets that through {@code AdaptiveMode.setHasAeroGel}.</p>
 */
final class ProbeFakePlayer {
    private static final Logger LOGGER = LoggerFactory.getLogger("aerogel.probefake");
    private static final AtomicLong DRAINED = new AtomicLong();
    private static final Map<String, Long> DRAINED_TYPES = new ConcurrentHashMap<>();

    /** the shared dummy connection, guarded by itself; every fake in the JVM uses this one object. */
    private static Connection attached;
    private static EmbeddedChannel drainChannel;
    private static boolean selfChecked;
    private static boolean selfCheckPassed;

    private ProbeFakePlayer() {
    }

    /** {@link FakePlayerFactory#get} plus {@link #attach}. */
    static FakePlayer get(ServerLevel level, GameProfile profile) {
        FakePlayer fake = FakePlayerFactory.get(level, profile);
        attach(fake);
        return fake;
    }

    /**
     * Put a {@link FakePlayer} in the player list for the rest of the run, and report whether it is safe.
     *
     * <p>Returns false when the fake has no draining channel, which is the one case where parking it — or
     * leaving it parked — would be a hazard rather than an improvement. A probe that gets false must fail:
     * the workaround it replaced is gone, so the run would be asserting against a server that dies at the
     * next {@code tickCount % 20 == 0}.</p>
     */
    static boolean park(MinecraftServer server, ServerPlayer player) {
        boolean attachedOk = attach(player);
        boolean drainOk = selfCheck(player);
        if (attachedOk && drainOk) {
            List<ServerPlayer> players = playerList(server);
            if (!players.contains(player)) {
                players.add(player);
            }
        }
        return attachedOk && drainOk && player.connection.getConnection().isConnected();
    }

    /** Take the fake back out of the player list; safe to call twice and safe after {@link #park} failed. */
    static void unpark(MinecraftServer server, ServerPlayer player) {
        playerList(server).remove(player);
    }

    /** Messages the drain has consumed since the JVM started, across every fake. */
    static long drained() {
        return DRAINED.get();
    }

    /** One-line drain state for a verdict: the total, and the commonest packet type when there is one. */
    static String drainSummary() {
        long total = DRAINED.get();
        String top = DRAINED_TYPES.entrySet()
            .stream()
            .max(Map.Entry.comparingByValue())
            .map(e -> e.getKey() + " x" + e.getValue())
            .orElse("");
        return total == 0L ? "drain 0 pkts" : "drain " + total + " pkts (" + top + ")";
    }

    /**
     * Reflectively give the shared dummy {@code Connection} an {@link EmbeddedChannel}. Idempotent, and
     * deliberately never closed: {@code isConnected()} is {@code channel != null && channel.isOpen()}, so
     * closing it would silently put every fake back to the state this class exists to fix.
     */
    private static boolean attach(ServerPlayer player) {
        Connection connection = player.connection.getConnection();
        synchronized (ProbeFakePlayer.class) {
            if (attached == connection) {
                return true;
            }
            if (attached != null) {
                // every FakePlayer shares one static DUMMY_CONNECTION, so a second distinct one means the
                // framework changed under us and the "one attachment covers every fake" premise does not hold
                LOGGER.warn("probe fake player: a second distinct fake Connection appeared; only the first has a"
                    + " draining channel");
                return false;
            }
            try {
                drainChannel = new EmbeddedChannel(new Drain());
                Field field = Connection.class.getDeclaredField("channel");
                field.setAccessible(true);
                field.set(connection, drainChannel);
                attached = connection;
                LOGGER.info("probe fake player: attached an EmbeddedChannel drain to the shared fake Connection"
                    + " (isConnected={})", connection.isConnected());
                return true;
            } catch (ReflectiveOperationException e) {
                LOGGER.error("probe fake player: could not attach a channel to the fake Connection", e);
                return false;
            }
        }
    }

    /**
     * Send one packet straight down the connection and check the drain consumed it. Done once per JVM: a
     * fake's own sends are no-ops in {@code FakePlayerNetHandler}, so this is the only deterministic way to
     * show the handler is live rather than assume it.
     */
    private static boolean selfCheck(ServerPlayer player) {
        synchronized (ProbeFakePlayer.class) {
            if (selfChecked) {
                return selfCheckPassed;
            }
            selfChecked = true;
            long before = DRAINED.get();
            player.connection.getConnection().send(new ClientboundKeepAlivePacket(0L));
            selfCheckPassed = DRAINED.get() == before + 1;
            if (selfCheckPassed) {
                LOGGER.info("probe fake player: drain self-check ok, a packet written down the fake connection"
                    + " was consumed");
            } else {
                LOGGER.error("probe fake player: drain self-check FAILED, a packet written down the fake"
                    + " connection was not consumed (drained before={}, after={})", before, DRAINED.get());
            }
            return selfCheckPassed;
        }
    }

    /** the mutable backing list ({@code getPlayers()} is unmodifiable); probe-only reflection. */
    @SuppressWarnings("unchecked")
    private static List<ServerPlayer> playerList(MinecraftServer server) {
        try {
            Field f = net.minecraft.server.players.PlayerList.class.getDeclaredField("players");
            f.setAccessible(true);
            return (List<ServerPlayer>) f.get(server.getPlayerList());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Counts, releases and succeeds every write, so nothing reaches the tail of the pipeline. */
    private static final class Drain extends ChannelOutboundHandlerAdapter {
        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            try {
                DRAINED.incrementAndGet();
                if (msg instanceof Packet<?> packet) {
                    DRAINED_TYPES.merge(packet.getClass().getSimpleName(), 1L, Long::sum);
                }
                ReferenceCountUtil.release(msg);
                promise.trySuccess();
            } catch (RuntimeException e) {
                // not exceptionCaught: netty deprecates it on an outbound-only adapter, and a raw EmbeddedChannel
                // would rethrow the exception out of an API this class never calls, losing it silently
                LOGGER.warn("probe fake player: the drain handler failed to consume a message", e);
                promise.tryFailure(e);
            }
        }

        @Override
        public void flush(ChannelHandlerContext ctx) {
            // every message is consumed on write, so there is never anything to flush
        }
    }
}
