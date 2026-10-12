package dev.aerogel.server;

import dev.aerogel.common.ProxyTopology;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Repeatable fast-flight benchmark (flag file: {@code <serverDir>/aerogel.flight}). It waits for the first
 * overworld player and runs a list of configurations back-to-back, each on a fresh terrain band: apply the
 * stream settings, settle until the start chunk is loaded and the server is quiet, then teleport the player
 * {@code speed} chunks/tick along +x for {@code duration} ticks while sampling the server tick time and the
 * chunk-task backlog. At the end it logs a comparison table.
 *
 * <p>Flag file: one run per line, {@code #} comments and blanks ignored:
 * <pre>
 *   radius nearTier farTier nearDistance speedChunksPerTick durationTicks zOffsetChunks
 * </pre>
 * If the file is missing/empty a default matrix (radius 20 at tiers 5/9/25/81, then radius 50/100 at tier 9)
 * is used. The probe never halts the server; after the matrix it stops moving and keeps logging.</p>
 */
final class FlightProbe {
    private static final Logger LOGGER = LoggerFactory.getLogger("aerogel.flight");
    private static final double ALTITUDE = 180.0;
    private static final int MIN_SETTLE_TICKS = 200;
    private static final int MAX_SETTLE_TICKS = 800;
    private static final double QUIET_TICK_MS = 30.0;
    private static final double STALL_MS = 50.0;

    private static final String DEFAULT_MATRIX = """
        20 9 5 32 4 600 0
        20 5 5 32 4 600 2000
        20 25 5 32 4 600 4000
        20 81 5 32 4 600 6000
        50 9 5 32 4 600 8000
        100 9 5 32 4 600 10000
        """;

    private record Run(int radius, byte nearTier, byte farTier, int nearDistance, int speed, int duration, int zOffset) {
    }

    private record Result(int index, Run run, double tickAvg, double tickMax, double backlogAvg, int backlogMax,
            int stalls, int samples, long chunks) {
    }

    private static MinecraftServer server;
    private static final List<Run> runs = new ArrayList<>();
    private static final List<Result> results = new ArrayList<>();
    private static ServerPlayer pilot;
    private static int runIndex;
    private static int phase; // 0 setup, 1 settle, 2 fly, 3 done
    private static int settleTicks;
    private static int flyTicks;
    private static double tickSum;
    private static double tickMax;
    private static double backlogSum;
    private static int backlogMax;
    private static int stalls;
    private static int samples;
    private static long chunksAtStart;

    private FlightProbe() {
    }

    static void start(MinecraftServer srv) {
        server = srv;
        runs.clear();
        results.clear();
        runIndex = 0;
        phase = 0;
        pilot = null;
        parse(srv.getServerDirectory().resolve("aerogel.flight"));
        if (runs.isEmpty()) {
            parseText(DEFAULT_MATRIX);
        }
        LOGGER.info("=== flight probe armed: {} run(s); waiting for a player ===", runs.size());
        for (int i = 0; i < runs.size(); i++) {
            LOGGER.info("flight: run {} planned {}", i + 1, describe(runs.get(i)));
        }
    }

    private static void parse(Path flag) {
        try {
            if (Files.exists(flag)) {
                parseText(Files.readString(flag));
            }
        } catch (Exception e) {
            LOGGER.warn("flight: could not parse {}; using the default matrix", flag, e);
        }
    }

    private static void parseText(String text) {
        for (String raw : text.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] p = line.split("\\s+");
            try {
                int radius = p.length > 0 ? Integer.parseInt(p[0]) : 20;
                byte near = p.length > 1 ? (byte) Integer.parseInt(p[1]) : ProxyTopology.DEFAULT;
                byte far = p.length > 2 ? (byte) Integer.parseInt(p[2]) : ProxyTopology.FIVE;
                int nearDist = p.length > 3 ? Integer.parseInt(p[3]) : 32;
                int speed = p.length > 4 ? Integer.parseInt(p[4]) : 4;
                int duration = p.length > 5 ? Integer.parseInt(p[5]) : 1200;
                int zOff = p.length > 6 ? Integer.parseInt(p[6]) : 0;
                if (!ProxyTopology.valid(near) || !ProxyTopology.valid(far)) {
                    LOGGER.warn("flight: bad tier in '{}'; skipping", line);
                    continue;
                }
                runs.add(new Run(Math.clamp(radius, 4, 1000), near, far, Math.clamp(nearDist, 1, 1000),
                    Math.clamp(speed, 1, 16), Math.clamp(duration, 20, 24000), zOff));
            } catch (NumberFormatException e) {
                LOGGER.warn("flight: malformed line '{}'; skipping", line);
            }
        }
    }

    private static String describe(Run r) {
        return "radius=" + r.radius + " near=" + ProxyTopology.name(r.nearTier) + " far="
            + ProxyTopology.name(r.farTier) + " neardist=" + r.nearDistance + " speed=" + r.speed
            + "ch/t duration=" + r.duration + "t zoff=" + r.zOffset;
    }

    static void tick(MinecraftServer srv) {
        if (server == null || runs.isEmpty()) {
            return;
        }
        if (pilot == null || pilot.hasDisconnected() || pilot.level() != srv.overworld()) {
            pilot = firstOverworldPlayer(srv);
            if (pilot == null) {
                return;
            }
            pilot.setGameMode(GameType.CREATIVE);
            pilot.getAbilities().flying = true;
            pilot.getAbilities().mayfly = true;
            pilot.getAbilities().setFlyingSpeed(0.5f);
            pilot.onUpdateAbilities();
        }
        switch (phase) {
            case 0 -> setup();
            case 1 -> settle(srv);
            case 2 -> fly(srv);
            default -> {
            }
        }
    }

    private static void setup() {
        if (runIndex >= runs.size()) {
            finish();
            return;
        }
        Run r = runs.get(runIndex);
        ProxyStreamer.setRadius(r.radius);
        ProxyStreamer.setTopology(r.nearTier);
        ProxyStreamer.setFarTier(r.farTier);
        ProxyStreamer.setNearDistance(r.nearDistance);
        pilot.teleportTo(pilot.getX(), ALTITUDE, pilot.getZ() + (double) r.zOffset * 16.0);
        settleTicks = 0;
        flyTicks = 0;
        tickSum = 0.0;
        tickMax = 0.0;
        backlogSum = 0.0;
        backlogMax = 0;
        stalls = 0;
        samples = 0;
        chunksAtStart = ProxyStreamer.totalChunksSent();
        LOGGER.info("flight: === RUN {}/{} {} ===", runIndex + 1, runs.size(), describe(r));
        phase = 1;
    }

    private static void settle(MinecraftServer srv) {
        settleTicks++;
        boolean loaded = srv.overworld().getChunkSource()
            .getChunkNow(pilot.chunkPosition().x, pilot.chunkPosition().z) != null;
        float tickMs = srv.getCurrentSmoothedTickTime();
        if (settleTicks % 20 == 0) {
            LOGGER.info("flight: settling t={} chunk=({},{}) loaded={} tick={} ms backlog={}",
                settleTicks, pilot.chunkPosition().x, pilot.chunkPosition().z, loaded,
                String.format("%.1f", tickMs), srv.overworld().getChunkSource().getPendingTasksCount());
        }
        if ((settleTicks >= MIN_SETTLE_TICKS && loaded && tickMs < QUIET_TICK_MS) || settleTicks >= MAX_SETTLE_TICKS) {
            phase = 2;
            LOGGER.info("flight: run {} settled after {} ticks (loaded={}, tick={} ms); sweeping",
                runIndex + 1, settleTicks, loaded, String.format("%.1f", tickMs));
        }
    }

    private static void fly(MinecraftServer srv) {
        Run r = runs.get(runIndex);
        flyTicks++;
        float tickMs = srv.getCurrentSmoothedTickTime();
        int backlog = srv.overworld().getChunkSource().getPendingTasksCount();
        samples++;
        tickSum += tickMs;
        if (tickMs > tickMax) {
            tickMax = tickMs;
        }
        backlogSum += backlog;
        if (backlog > backlogMax) {
            backlogMax = backlog;
        }
        if (tickMs >= STALL_MS) {
            stalls++;
        }
        if (flyTicks > r.duration) {
            long chunks = ProxyStreamer.totalChunksSent() - chunksAtStart;
            results.add(new Result(runIndex, r, tickSum / samples, tickMax, backlogSum / samples, backlogMax,
                stalls, samples, chunks));
            LOGGER.info("flight: RUN {} done {} | tick avg {} max {} ms | backlog avg {} max {} | stalls {}/{} | chunks {}",
                runIndex + 1, describe(r), String.format("%.1f", tickSum / samples),
                String.format("%.1f", tickMax), String.format("%.0f", backlogSum / samples), backlogMax,
                stalls, samples, chunks);
            runIndex++;
            phase = 0;
            return;
        }
        pilot.teleportTo(pilot.getX() + (double) r.speed * 16.0, ALTITUDE, pilot.getZ());
    }

    private static void finish() {
        phase = 3;
        LOGGER.info("flight: ===== SUMMARY =====");
        LOGGER.info(String.format("flight: %-4s %-6s %-6s %-8s %-7s %-8s %-8s %-7s %-6s",
            "run", "radius", "near", "neardist", "tickavg", "tickmax", "backlog", "stalls", "chunks"));
        for (Result res : results) {
            LOGGER.info(String.format("flight: %-4d %-6d %-6s %-8d %-7.1f %-8.1f %-8.0f %-7s %-6d",
                res.index + 1, res.run.radius, ProxyTopology.name(res.run.nearTier), res.run.nearDistance,
                res.tickAvg, res.tickMax, res.backlogAvg, res.stalls + "/" + res.samples, res.chunks));
        }
        LOGGER.info("flight: =====================");
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
