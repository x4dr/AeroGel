package dev.aerogel.server;

import dev.aerogel.common.AeroGelConfig;
import dev.aerogel.net.ClientSwitchPayload;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerChunkCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decides when the proxy gate is enforced at all, so AeroGel is inert when it is not needed.
 *
 * <p><b>Server-authoritative.</b> The gate is enforced globally while the server is struggling — the
 * main-thread chunk-task backlog ({@link ServerChunkCache#getPendingTasksCount()}) is at or above
 * {@code backlogActivate}, or the smoothed tick time is at or above {@code activateTickMs}. It stops only
 * once the backlog and tick time have fallen back below the deactivate values <b>and no chunk is
 * mid-linger</b>, so switching to vanilla can never trigger a burst of instant generation.</p>
 *
 * <p><b>Per-player opt-in.</b> While the server is not enforcing, a player whose client {@code travel}
 * switch is on still gets the lingering gate around them; a player whose {@code travel} switch is off
 * behaves as vanilla (their nearby leftovers are forced to real terrain). Generation is shared, so an
 * opted-out player inside an opted-in zone forces vanilla locally. The client {@code proxy} switch controls
 * whether that player receives/renders proxy meshes at all.</p>
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

    private static final Map<UUID, Boolean> travel = new HashMap<>();
    private static final Map<UUID, Boolean> proxy = new HashMap<>();
    private static long[] optInCenters = new long[0];
    private static long[] optOutCenters = new long[0];

    private AdaptiveMode() {
    }

    public static void init() {
        AeroGelConfig cfg = AeroGelConfig.CFG;
        enabled = cfg.adaptiveEnabled.get();
        activateTickMs = cfg.adaptiveActivateTickMs.get();
        deactivateTickMs = cfg.adaptiveDeactivateTickMs.get();
        backlogActivate = cfg.adaptiveBacklogActivate.get();
        backlogDeactivate = cfg.adaptiveBacklogDeactivate.get();
        enforcing = false;
        travel.clear();
        proxy.clear();
        optInCenters = new long[0];
        optOutCenters = new long[0];
        LOGGER.info("adaptive: {} (activate {} ms / backlog {}, deactivate {} ms / backlog {})",
            enabled ? "on" : "off", activateTickMs, backlogActivate, deactivateTickMs, backlogDeactivate);
    }

    public static void shutdown() {
        enforcing = false;
        travel.clear();
        proxy.clear();
        optInCenters = new long[0];
        optOutCenters = new long[0];
    }

    public static void tick(MinecraftServer server) {
        if (!enabled) {
            enforcing = false;
            pushZones(server);
            return;
        }
        int backlog = server.overworld().getChunkSource().getPendingTasksCount();
        float tickMs = server.getCurrentSmoothedTickTime();
        if (enforcing) {
            if (backlog <= backlogDeactivate && tickMs <= deactivateTickMs && !PromotionManager.hasLinger()) {
                enforcing = false;
                LOGGER.info("adaptive: enforcing off (backlog {}, tick {} ms, no linger)",
                    backlog, String.format("%.1f", tickMs));
            }
        } else if (backlog >= backlogActivate || tickMs >= activateTickMs) {
            enforcing = true;
            LOGGER.info("adaptive: enforcing on (backlog {}, tick {} ms)",
                backlog, String.format("%.1f", tickMs));
        }
        pushZones(server);
    }

    private static void pushZones(MinecraftServer server) {
        List<Long> in = new ArrayList<>();
        List<Long> out = new ArrayList<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.level() != server.overworld() || player.hasDisconnected()) {
                continue;
            }
            long center = player.chunkPosition().toLong();
            if (travel.getOrDefault(player.getUUID(), false)) {
                in.add(center);
            } else {
                out.add(center);
            }
        }
        optInCenters = toArray(in);
        optOutCenters = toArray(out);
        ProxyChunkGate.setAdaptive(enforcing, optInCenters, optOutCenters,
            PromotionManager.effectiveMaxRange(server), enabled);
    }

    private static long[] toArray(List<Long> list) {
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
        ProxyChunkGate.setAdaptive(false, new long[0], new long[0], 0, false);
    }

    /** true when this player should use the lingering gate (server-enforced, or they opted in). */
    public static boolean isOptIn(ServerPlayer player) {
        return enforcing || travel.getOrDefault(player.getUUID(), false);
    }

    /** true when this player wants proxy meshes streamed/rendered. */
    public static boolean renders(ServerPlayer player) {
        return proxy.getOrDefault(player.getUUID(), true);
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
        int backlog = server.overworld().getChunkSource().getPendingTasksCount();
        return "adaptive: " + (enabled ? "on" : "off") + (enforcing ? " ENFORCING" : "")
            + ", backlog " + backlog + " (act " + backlogActivate + " / deact " + backlogDeactivate + ")"
            + ", tick " + String.format("%.1f", tickMs) + " ms (act "
            + String.format("%.1f", activateTickMs) + " / deact " + String.format("%.1f", deactivateTickMs) + ")"
            + ", opt-in " + optInCenters.length + ", opt-out " + optOutCenters.length
            + ", linger " + (PromotionManager.hasLinger() ? "yes" : "no");
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
    }
}
