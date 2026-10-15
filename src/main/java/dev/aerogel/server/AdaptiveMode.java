package dev.aerogel.server;

import dev.aerogel.common.AeroGelConfig;
import dev.aerogel.net.ClientSwitchPayload;
import dev.aerogel.net.MeshBatchPayload;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.resources.ResourceKey;
import net.minecraft.Util;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decides when the proxy gate is enforced at all, so AeroGel is inert when it is not needed.
 *
 * <p><b>Server-authoritative.</b> The gate is enforced globally while the server is struggling — the
 * main-thread chunk-task backlog ({@link ServerChunkCache#getPendingTasksCount()}, max over every gated level)
 * is at or above {@code backlogActivate}, or the smoothed tick time is at or above {@code activateTickMs}.
 * Both inputs are the max over the last {@code windowSeconds}, because a single sample swings across the
 * whole band within a tick (#2). It stops only once both windowed values are back below the deactivate
 * values, {@code holdSeconds} have passed since the last activating sample, <b>and the promotion queue and
 * in-flight set are both empty</b>, so switching to vanilla can never trigger a burst of instant generation.
 * That last condition is read <em>after</em> {@code PromotionManager}'s refill has run for the tick, which is
 * what makes it meaningful: while the server is calm the refill bypasses the linger threshold, so every stub
 * that would generate on disengagement is already queued by the time the veto is asked.</p>
 *
 * <p><b>Per-player zones.</b> While the server is not enforcing, the gate applies only inside the zone
 * (Chebyshev {@link PromotionManager#effectiveMaxRange}) of a player whose client {@code travel} switch is
 * on; everyone else is vanilla. Generation is shared, so a player who declined {@code travel} and stands
 * inside someone else's opted-in zone is gated anyway -- a preference being overridden, not a void, and a
 * rendering preference the client {@code proxy} switch ({@link #renders}) already covers. There is
 * deliberately no opt-out list subtracted from that union: it held most connected players rather than the few
 * who asked, so it cost more on the idle path than the behaviour it protected was worth.</p>
 *
 * <p><b>Clients without AeroGel</b> (#7) are recorded once at login ({@link #onLogin}) and receive no meshes
 * (the streamer does no work for them), because a vanilla client has no proxy layer to draw them with. Their
 * chunks are gated and promoted like everyone else's: generation is a server-side cost, and excluding them
 * would only move the stall to somewhere they cannot see it coming.</p>
 *
 * <p>All state is touched on the server thread.</p>
 */
public final class AdaptiveMode {
    private static final Logger LOGGER = LoggerFactory.getLogger("aerogel.adaptive");

    private static boolean enabled = true;
    private static boolean enforcing;
    private static double activateTickMs = 35.0;
    private static double deactivateTickMs = 22.0;
    private static int backlogActivate = 64;
    private static int backlogDeactivate = 16;
    private static int holdSeconds = 10;
    private static int windowSeconds = 5;
    private static boolean engageOnTeleport;
    /** set only for the duration of one {@link #pushZones} call from {@link #onTeleport}: zone centre override. */
    private static UUID teleportPlayer;
    private static long teleportTarget;
    private static final Window WINDOW = new Window();

    private static final Map<UUID, Boolean> travel = new HashMap<>();
    private static final Map<UUID, Boolean> proxy = new HashMap<>();
    /** players whose connection lacks the AeroGel channels, cached at login so no send path looks it up. */
    /**
     * Connections we have confirmed carry the AeroGel channels (#15). Membership means yes; absence means
     * "not confirmed", which is treated as a client that cannot receive meshes -- so a player whose login
     * event never reached us is never streamed to, instead of being assumed capable.
     */
    private static final Set<UUID> aeroGelClients = new HashSet<>();
    private static final long[] NO_CENTERS = new long[0];
    /** one reusable scratch per dimension, so the per-tick zone push allocates nothing in the steady state. */
    private static final Map<ResourceKey<Level>, Zones> ZONES = new HashMap<>();
    private static int optInCount;

    private AdaptiveMode() {
    }

    public static void init() {
        AeroGelConfig cfg = AeroGelConfig.CFG;
        enabled = cfg.adaptiveEnabled.get();
        activateTickMs = cfg.adaptiveActivateTickMs.get();
        deactivateTickMs = cfg.adaptiveDeactivateTickMs.get();
        backlogActivate = cfg.adaptiveBacklogActivate.get();
        backlogDeactivate = cfg.adaptiveBacklogDeactivate.get();
        holdSeconds = cfg.adaptiveHoldSeconds.get();
        windowSeconds = cfg.adaptiveWindowSeconds.get();
        engageOnTeleport = cfg.adaptiveEngageOnTeleport.get();
        enforcing = false;
        WINDOW.clear();
        travel.clear();
        proxy.clear();
        aeroGelClients.clear();
        ZONES.clear();
        optInCount = 0;
        LOGGER.info("adaptive: {} (activate {} ms / backlog {}, deactivate {} ms / backlog {}, hold {} s, window {} s,"
                + " engage on teleport {})",
            enabled ? "on" : "off", activateTickMs, backlogActivate, deactivateTickMs, backlogDeactivate,
            holdSeconds, windowSeconds, engageOnTeleport);
    }

    public static void shutdown() {
        enforcing = false;
        WINDOW.clear();
        travel.clear();
        proxy.clear();
        aeroGelClients.clear();
        ZONES.clear();
        optInCount = 0;
    }

    public static void tick(MinecraftServer server) {
        if (!enabled) {
            enforcing = false;
            WINDOW.clear();
            pushZones(server);
            return;
        }
        boolean was = enforcing;
        enforcing = WINDOW.decide(enforcing, Util.getMillis(), backlog(server), server.getCurrentSmoothedTickTime(),
            PromotionManager.drained());
        if (enforcing != was) {
            LOGGER.info(enforcing ? "adaptive: enforcing on (backlog {}, tick {} ms over {} s)"
                    : "adaptive: enforcing off (backlog {}, tick {} ms over {} s, promotion drained)",
                WINDOW.maxBacklog, String.format("%.1f", WINDOW.maxTickMs), windowSeconds);
        }
        pushZones(server);
    }

    /**
     * #14: a player teleports to x/z in their current level (fired before the move). An opted-in player (client
     * {@code travel}) gets their zone moved to the target first, so exploration mode covers the arrival. With
     * {@code engageOnTeleport}
     * and a destination that may not be generated, enforce now, before the arrival area is requested, counted as
     * an activating sample: {@code holdSeconds} applies and {@link #tick} switches it off by itself.
     */
    public static void onTeleport(ServerPlayer player, double x, double z) {
        if (!enabled || !hasAeroGel(player) || !(player.level() instanceof ServerLevel level)) {
            return;
        }
        long target = ChunkPos.asLong(Mth.floor(x) >> 4, Mth.floor(z) >> 4);
        if (!enforcing && optsIn(player)) {
            // an opted-in player's zone follows player.chunkPosition(), refreshed per tick: at the teleport it is
            // still around the old position, so move it to the target before the arrival is requested
            teleportTarget = target;
            teleportPlayer = player.getUUID();
            pushZones(level.getServer());
            teleportPlayer = null;
        }
        if (!engageOnTeleport || !ProxyChunkGate.mayBeUngenerated(level, target)) {
            return;
        }
        WINDOW.activate(Util.getMillis());
        if (!enforcing) {
            enforcing = true;
            LOGGER.info("adaptive: enforcing on ({} teleports into land that may not be generated)",
                player.getGameProfile().getName());
            pushZones(level.getServer());
        }
    }

    /** #8: lag from any gated level counts, not just the overworld. */
    private static int backlog(MinecraftServer server) {
        int max = 0;
        for (ServerLevel level : server.getAllLevels()) {
            if (ProxyChunkGate.isActive(level)) {
                max = Math.max(max, level.getChunkSource().getPendingTasksCount());
            }
        }
        return max;
    }

    /**
     * The enforcing decision over a sliding window of samples, kept free of the server so a probe can feed it a
     * synthetic sequence. On: the window's max backlog or max tick time reaches its activate value. Off: both
     * window maxima are at or below the deactivate values, {@code holdSeconds} have passed since the last
     * activating sample, and the promotion queue is drained.
     */
    static final class Window {
        private record Sample(long ms, int backlog, double tickMs) {
        }

        private final ArrayDeque<Sample> samples = new ArrayDeque<>();
        private long lastActivatingMs = Long.MIN_VALUE;
        int maxBacklog;
        double maxTickMs;

        void activate(long nowMs) {
            lastActivatingMs = nowMs;
        }

        void clear() {
            samples.clear();
            lastActivatingMs = Long.MIN_VALUE;
            maxBacklog = 0;
            maxTickMs = 0;
        }

        boolean decide(boolean enforcingNow, long nowMs, int backlog, double tickMs, boolean drained) {
            samples.addLast(new Sample(nowMs, backlog, tickMs));
            while (samples.peekFirst().ms() < nowMs - windowSeconds * 1000L) {
                samples.removeFirst();
            }
            maxBacklog = 0;
            maxTickMs = 0;
            for (Sample s : samples) {
                maxBacklog = Math.max(maxBacklog, s.backlog());
                maxTickMs = Math.max(maxTickMs, s.tickMs());
            }
            if (backlog >= backlogActivate || tickMs >= activateTickMs) {
                lastActivatingMs = nowMs;
            }
            if (!enforcingNow) {
                return maxBacklog >= backlogActivate || maxTickMs >= activateTickMs;
            }
            return maxBacklog > backlogDeactivate || maxTickMs > deactivateTickMs || !drained
                || holdRemainingMs(nowMs) > 0;
        }

        long holdRemainingMs(long nowMs) {
            return lastActivatingMs == Long.MIN_VALUE ? 0
                : Math.max(0, lastActivatingMs + holdSeconds * 1000L - nowMs);
        }
    }

    /**
     * Refills the per-dimension zone scratch from the players who opted in and hands each level its centres.
     * Runs every tick, so it must not allocate in the steady state: the scratch is refilled in place and the
     * gate keeps the exact-length array it already has unless the centres actually moved.
     */
    private static void pushZones(MinecraftServer server) {
        // zones are per-dimension: a player only gates the level they are actually in.
        for (Zones zones : ZONES.values()) {
            zones.count = 0;
        }
        int optedIn = 0;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.hasDisconnected() || !optsIn(player)) {
                continue;
            }
            long center = player.getUUID().equals(teleportPlayer) ? teleportTarget : player.chunkPosition().toLong();
            ZONES.computeIfAbsent(player.level().dimension(), key -> new Zones()).add(center);
            optedIn++;
        }
        optInCount = optedIn;
        int radius = PromotionManager.effectiveMaxRange(server);
        for (ServerLevel level : server.getAllLevels()) {
            Zones zones = ZONES.get(level.dimension());
            long[] centers = zones == null ? NO_CENTERS : zones.handOver();
            ProxyChunkGate.setAdaptive(level, enforcing, centers, radius, enabled);
        }
    }

    /**
     * One dimension's zone centres: a growable scratch the push fills, plus the exact-length array
     * {@link ProxyChunkGate} is currently holding. Centres move every tick during flight, so the array is
     * recopied whenever the contents differ -- but a server that is standing still hands back the very same
     * array and allocates nothing at all. An array that has been handed over is never written to again; the
     * gate holds it until the next push.
     */
    private static final class Zones {
        private long[] buf = new long[8];
        private int count;
        private long[] handed = NO_CENTERS;

        void add(long center) {
            if (count == buf.length) {
                buf = Arrays.copyOf(buf, buf.length * 2);
            }
            buf[count++] = center;
        }

        /** the exact-length array for the gate: the one it already holds when nothing moved. */
        long[] handOver() {
            if (!matches(handed)) {
                handed = Arrays.copyOf(buf, count);
            }
            return handed;
        }

        private boolean matches(long[] array) {
            if (array.length != count) {
                return false;
            }
            for (int i = 0; i < count; i++) {
                if (array[i] != buf[i]) {
                    return false;
                }
            }
            return true;
        }
    }

    // ---- queries ----

    public static boolean enforcing() {
        return enforcing;
    }

    public static boolean enabled() {
        return enabled;
    }

    /**
     * True when the adaptive window has no reason to enforce. Read by {@code PromotionManager}'s refill: while
     * calm the linger threshold is bypassed so the gate can reach a state where it may disengage. A one-tick
     * lag is harmless — the drain simply waits a tick longer.
     */
    public static boolean serverCalm() {
        return !enforcing;
    }

    /** test hook: force the legacy always-on gate for a headless probe, without touching the config. */
    public static void debugAlwaysOn() {
        enabled = false;
        enforcing = false;
        ProxyChunkGate.setAdaptiveAll(false, false);
    }

    private static boolean optsIn(ServerPlayer player) {
        return hasAeroGel(player) && travel.getOrDefault(player.getUUID(), false);
    }

    /** true when this player wants proxy meshes streamed/rendered (and its client can receive them). */
    public static boolean renders(ServerPlayer player) {
        return hasAeroGel(player) && proxy.getOrDefault(player.getUUID(), true);
    }

    /**
     * True only when this player's login confirmed the AeroGel channels. Anything not confirmed here is
     * treated as unable to receive meshes, so a login event that never reached us costs a missed stream
     * rather than a stream into a client that cannot decode it (#15).
     */
    public static boolean hasAeroGel(ServerPlayer player) {
        return aeroGelClients.contains(player.getUUID());
    }

    /** login: cache whether the connection negotiated the AeroGel channels (#7). */
    public static void onLogin(ServerPlayer player) {
        setHasAeroGel(player, player.connection.hasChannel(MeshBatchPayload.TYPE));
    }

    /** split from {@link #onLogin} so the dev probe can drive it without a real connection. */
    public static void setHasAeroGel(ServerPlayer player, boolean has) {
        if (has) {
            aeroGelClients.add(player.getUUID());
        } else {
            aeroGelClients.remove(player.getUUID());
            // once per login; absence from the set is now the default, so this cannot be a change-detect
            LOGGER.info("adaptive: {} joined without AeroGel: no proxy meshes", player.getGameProfile().getName());
        }
    }

    /** current per-player switch value (falls back to the client default). */
    public static boolean playerSwitch(ServerPlayer player, String feature) {
        return switch (feature) {
            case "travel" -> travel.getOrDefault(player.getUUID(), false);
            case "proxy" -> proxy.getOrDefault(player.getUUID(), true);
            default -> ClientSwitchPayload.defaultOn(feature);
        };
    }

    // ---- tuning ----

    public static void setEnabled(boolean on) {
        enabled = on;
        AeroGelConfig.CFG.adaptiveEnabled.set(on);
        AeroGelConfig.SPEC.save();
    }

    public static void setActivateTickMs(double ms) {
        activateTickMs = Math.clamp(ms, 1.0, 200.0);
        AeroGelConfig.CFG.adaptiveActivateTickMs.set(activateTickMs);
        AeroGelConfig.SPEC.save();
    }

    public static void setDeactivateTickMs(double ms) {
        deactivateTickMs = Math.clamp(ms, 1.0, 200.0);
        AeroGelConfig.CFG.adaptiveDeactivateTickMs.set(deactivateTickMs);
        AeroGelConfig.SPEC.save();
    }

    public static void setBacklogActivate(int tasks) {
        backlogActivate = Math.clamp(tasks, 1, 100000);
        AeroGelConfig.CFG.adaptiveBacklogActivate.set(backlogActivate);
        AeroGelConfig.SPEC.save();
    }

    public static void setBacklogDeactivate(int tasks) {
        backlogDeactivate = Math.clamp(tasks, 0, 100000);
        AeroGelConfig.CFG.adaptiveBacklogDeactivate.set(backlogDeactivate);
        AeroGelConfig.SPEC.save();
    }

    public static void setHoldSeconds(int seconds) {
        holdSeconds = Math.clamp(seconds, 0, 600);
        AeroGelConfig.CFG.adaptiveHoldSeconds.set(holdSeconds);
        AeroGelConfig.SPEC.save();
    }

    public static void setWindowSeconds(int seconds) {
        windowSeconds = Math.clamp(seconds, 0, 60);
        AeroGelConfig.CFG.adaptiveWindowSeconds.set(windowSeconds);
        AeroGelConfig.SPEC.save();
    }

    public static void setEngageOnTeleport(boolean on) {
        engageOnTeleport = on;
        AeroGelConfig.CFG.adaptiveEngageOnTeleport.set(on);
        AeroGelConfig.SPEC.save();
    }

    public static boolean engageOnTeleport() {
        return engageOnTeleport;
    }

    public static int holdSeconds() {
        return holdSeconds;
    }

    public static int windowSeconds() {
        return windowSeconds;
    }

    public static double activateTickMs() {
        return activateTickMs;
    }

    public static double deactivateTickMs() {
        return deactivateTickMs;
    }

    public static int backlogActivate() {
        return backlogActivate;
    }

    public static int backlogDeactivate() {
        return backlogDeactivate;
    }

    public static String status(MinecraftServer server, float tickMs) {
        return "adaptive: " + (enabled ? "on" : "off") + (enforcing ? " ENFORCING" : "")
            + ", backlog " + backlog(server) + " now / " + WINDOW.maxBacklog + " max " + windowSeconds + " s (act "
            + backlogActivate + " / deact " + backlogDeactivate + ")"
            + ", tick " + String.format("%.1f", tickMs) + " ms now / " + String.format("%.1f", WINDOW.maxTickMs)
            + " ms max (act "
            + String.format("%.1f", activateTickMs) + " / deact " + String.format("%.1f", deactivateTickMs) + ")"
            + ", hold " + String.format("%.1f", WINDOW.holdRemainingMs(Util.getMillis()) / 1000.0) + " s left of "
            + holdSeconds + " s"
            + ", opt-in " + optInCount
            + ", " + PromotionManager.drainState() + " queued/in flight"
            + ", engage on teleport " + (engageOnTeleport ? "on" : "off");
    }

    // ---- per-player client state ----

    public static void onClientSwitch(ServerPlayer player, ClientSwitchPayload payload) {
        setPlayerSwitch(player, payload.feature(), payload.on());
    }

    public static void setPlayerSwitch(ServerPlayer player, String feature, boolean on) {
        switch (feature) {
            case "travel" -> travel.put(player.getUUID(), on);
            case "proxy" -> proxy.put(player.getUUID(), on);
            default -> {
            }
        }
    }

    public static void removePlayer(UUID id) {
        travel.remove(id);
        proxy.remove(id);
        aeroGelClients.remove(id);
    }
}
