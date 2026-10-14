package dev.aerogel.server;

import dev.aerogel.common.AeroGelConfig;
import dev.aerogel.net.ClientSwitchPayload;
import dev.aerogel.net.MeshBatchPayload;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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
 * values, {@code holdSeconds} have passed since the last activating sample, <b>and no chunk is
 * mid-linger</b>, so switching to vanilla can never trigger a burst of instant generation.</p>
 *
 * <p><b>Per-player opt-in.</b> While the server is not enforcing, a player whose client {@code travel}
 * switch is on still gets the lingering gate around them; a player whose {@code travel} switch is off
 * behaves as vanilla (their nearby leftovers are forced to real terrain). Generation is shared, so an
 * opted-out player inside an opted-in zone forces vanilla locally. The client {@code proxy} switch controls
 * whether that player receives/renders proxy meshes at all.</p>
 *
 * <p><b>Clients without AeroGel</b> (#7) are recorded once at login ({@link #onLogin}) and treated as opted
 * out, even while the server enforces: they get no meshes (the streamer does no work for them) and their zone
 * is vanilla, because a vanilla client has no proxy layer and would otherwise stand at the edge of terrain it
 * can never receive.</p>
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
    private static final Window WINDOW = new Window();

    private static final Map<UUID, Boolean> travel = new HashMap<>();
    private static final Map<UUID, Boolean> proxy = new HashMap<>();
    /** players whose connection lacks the AeroGel channels, cached at login so no send path looks it up. */
    private static final Set<UUID> vanilla = new HashSet<>();
    private static int optInCount;
    private static int optOutCount;

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
        vanilla.clear();
        optInCount = 0;
        optOutCount = 0;
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
        vanilla.clear();
        optInCount = 0;
        optOutCount = 0;
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
            PromotionManager.hasLinger());
        if (enforcing != was) {
            LOGGER.info(enforcing ? "adaptive: enforcing on (backlog {}, tick {} ms over {} s)"
                    : "adaptive: enforcing off (backlog {}, tick {} ms over {} s, no linger)",
                WINDOW.maxBacklog, String.format("%.1f", WINDOW.maxTickMs), windowSeconds);
        }
        pushZones(server);
    }

    /**
     * #14: a player teleports to x/z in their current level (fired before the move). With {@code engageOnTeleport}
     * and a destination that may not be generated, enforce now, before the arrival area is requested, counted as
     * an activating sample: {@code holdSeconds} applies and {@link #tick} switches it off by itself.
     */
    public static void onTeleport(ServerPlayer player, double x, double z) {
        if (!engageOnTeleport || !enabled || !hasAeroGel(player) || !(player.level() instanceof ServerLevel level)
            || !ProxyChunkGate.mayBeUngenerated(level, ChunkPos.asLong(Mth.floor(x) >> 4, Mth.floor(z) >> 4))) {
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
     * activating sample, and no linger counter remains.
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

        boolean decide(boolean enforcingNow, long nowMs, int backlog, double tickMs, boolean linger) {
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
            return maxBacklog > backlogDeactivate || maxTickMs > deactivateTickMs || linger
                || holdRemainingMs(nowMs) > 0;
        }

        long holdRemainingMs(long nowMs) {
            return lastActivatingMs == Long.MIN_VALUE ? 0
                : Math.max(0, lastActivatingMs + holdSeconds * 1000L - nowMs);
        }
    }

    private static void pushZones(MinecraftServer server) {
        // zones are per-dimension: a player only gates the level they are actually in.
        Map<ResourceKey<Level>, List<Long>> in = new HashMap<>();
        Map<ResourceKey<Level>, List<Long>> out = new HashMap<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.hasDisconnected()) {
                continue;
            }
            ResourceKey<Level> key = player.level().dimension();
            long center = player.chunkPosition().toLong();
            if (optsIn(player)) {
                in.computeIfAbsent(key, k -> new ArrayList<>()).add(center);
            } else {
                out.computeIfAbsent(key, k -> new ArrayList<>()).add(center);
            }
        }
        optInCount = in.values().stream().mapToInt(List::size).sum();
        optOutCount = out.values().stream().mapToInt(List::size).sum();
        int radius = PromotionManager.effectiveMaxRange(server);
        for (ServerLevel level : server.getAllLevels()) {
            ResourceKey<Level> key = level.dimension();
            ProxyChunkGate.setAdaptive(level, enforcing, toArray(in.get(key)), toArray(out.get(key)), radius, enabled);
        }
    }

    private static long[] toArray(List<Long> list) {
        if (list == null) {
            return new long[0];
        }
        long[] array = new long[list.size()];
        for (int i = 0; i < array.length; i++) {
            array[i] = list.get(i);
        }
        return array;
    }

    // ---- queries ----

    public static boolean enforcing() {
        return enforcing;
    }

    public static boolean enabled() {
        return enabled;
    }

    /** test hook: force the legacy always-on gate for a headless probe, without touching the config. */
    public static void debugAlwaysOn() {
        enabled = false;
        enforcing = false;
        ProxyChunkGate.setAdaptiveAll(false, false);
    }

    /** true when this player should use the lingering gate (server-enforced, or they opted in). */
    public static boolean isOptIn(ServerPlayer player) {
        return hasAeroGel(player) && (enforcing || travel.getOrDefault(player.getUUID(), false));
    }

    private static boolean optsIn(ServerPlayer player) {
        return hasAeroGel(player) && travel.getOrDefault(player.getUUID(), false);
    }

    /** true when this player wants proxy meshes streamed/rendered (and its client can receive them). */
    public static boolean renders(ServerPlayer player) {
        return hasAeroGel(player) && proxy.getOrDefault(player.getUUID(), true);
    }

    /** true when this player's client has the AeroGel channels; guard every server-to-client payload with it. */
    public static boolean hasAeroGel(ServerPlayer player) {
        return !vanilla.contains(player.getUUID());
    }

    /** login: cache whether the connection negotiated the AeroGel channels (#7). */
    public static void onLogin(ServerPlayer player) {
        setHasAeroGel(player, player.connection.hasChannel(MeshBatchPayload.TYPE));
    }

    /** split from {@link #onLogin} so the dev probe can drive it without a real connection. */
    public static void setHasAeroGel(ServerPlayer player, boolean has) {
        if (has) {
            vanilla.remove(player.getUUID());
        } else if (vanilla.add(player.getUUID())) {
            LOGGER.info("adaptive: {} joined without AeroGel: no proxy meshes, their zone stays vanilla",
                player.getGameProfile().getName());
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
            + ", opt-in " + optInCount + ", opt-out " + optOutCount
            + ", linger " + (PromotionManager.hasLinger() ? "yes" : "no")
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
        vanilla.remove(id);
    }
}
