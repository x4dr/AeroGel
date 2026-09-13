package dev.aerogel.server;

import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Scripted fast-flight harness (flag file: {@code <serverDir>/aerogel.flight}). It waits for the first
 * overworld player, puts them in creative flight at a fixed altitude, and teleports them {@code speed}
 * chunks per tick along +x for {@code duration} ticks, logging a per-second server summary
 * ({@code adaptive status}, {@code promote status}, smoothed tick time, chunk-task backlog).
 *
 * <p>The flag file may contain {@code <speedChunksPerTick> <durationTicks>} (defaults 4 and 1200). The
 * probe does not force any switch: run {@code /aerogel switch adaptive false} first for a deterministic
 * always-on gate, or leave the default adaptive path to see whether the sweep trips it. It never halts the
 * server; after the run it stops moving and keeps logging until the server is stopped.</p>
 */
final class FlightProbe {
    private static final Logger LOGGER = LoggerFactory.getLogger("aerogel.flight");
    private static final int DEFAULT_SPEED_CHUNKS = 4;
    private static final int DEFAULT_DURATION_TICKS = 1200;
    /** above normal terrain so the sweep is not stopped by collisions. */
    private static final double ALTITUDE = 180.0;

    private static MinecraftServer server;
    private static ServerPlayer pilot;
    private static int speedChunks = DEFAULT_SPEED_CHUNKS;
    private static int durationTicks = DEFAULT_DURATION_TICKS;
    private static int ticks;
    private static boolean reportedDone;

    private FlightProbe() {
    }

    static void start(MinecraftServer srv) {
        server = srv;
        Path flag = srv.getServerDirectory().resolve("aerogel.flight");
        try {
            if (Files.exists(flag)) {
                String text = Files.readString(flag).trim();
                if (!text.isEmpty()) {
                    String[] parts = text.split("\\s+");
                    if (parts.length >= 1) {
                        speedChunks = Math.clamp(Integer.parseInt(parts[0]), 1, 16);
                    }
                    if (parts.length >= 2) {
                        durationTicks = Math.clamp(Integer.parseInt(parts[1]), 20, 24000);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warn("flight: could not parse {}, using defaults ({} ch/tick, {} ticks)",
                flag, DEFAULT_SPEED_CHUNKS, DEFAULT_DURATION_TICKS, e);
        }
        LOGGER.info("=== flight probe armed: {} chunks/tick, {} ticks, altitude {}; waiting for a player ===",
            speedChunks, durationTicks, ALTITUDE);
    }

    static void tick(MinecraftServer srv) {
        if (server == null) {
            return;
        }
        if (pilot == null || pilot.hasDisconnected() || pilot.level() != srv.overworld()) {
            pilot = firstOverworldPlayer(srv);
            if (pilot == null) {
                return;
            }
            arm(pilot);
        }
        ticks++;
        if (ticks > durationTicks) {
            if (!reportedDone) {
                reportedDone = true;
                LOGGER.info("=== flight probe: run complete — {} ticks, ~{} chunks swept; stopping movement ===",
                    ticks, ticks * speedChunks);
            }
            return;
        }
        pilot.teleportTo(pilot.getX() + (double) speedChunks * 16.0, ALTITUDE, pilot.getZ());
        if (ticks % 20 == 0) {
            float tickMs = srv.getCurrentSmoothedTickTime();
            int backlog = srv.overworld().getChunkSource().getPendingTasksCount();
            LOGGER.info("flight: t={} chunk=({},{}) swept~{}ch | tick {} ms, backlog {} | {} | {}",
                ticks, pilot.chunkPosition().x, pilot.chunkPosition().z, ticks * speedChunks,
                String.format("%.1f", tickMs), backlog,
                AdaptiveMode.status(srv, tickMs), PromotionManager.status(srv, tickMs));
        }
    }

    private static void arm(ServerPlayer player) {
        player.setGameMode(GameType.CREATIVE);
        player.getAbilities().flying = true;
        player.getAbilities().mayfly = true;
        player.getAbilities().setFlyingSpeed(0.5f);
        player.onUpdateAbilities();
        player.teleportTo(player.getX(), ALTITUDE, player.getZ());
        ticks = 0;
        reportedDone = false;
        LOGGER.info("flight: pilot {} at ({},{}) y={} -> +x at {} chunks/tick",
            player.getGameProfile().getName(),
            String.format("%.0f", player.getX()), String.format("%.0f", player.getZ()),
            String.format("%.0f", player.getY()), speedChunks);
    }

    private static ServerPlayer firstOverworldPlayer(MinecraftServer srv) {
        for (ServerPlayer player : srv.getPlayerList().getPlayers()) {
            if (!player.hasDisconnected() && player.level() == srv.overworld()) {
                return player;
            }
        }
        return null;
    }
}
