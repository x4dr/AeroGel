package dev.aerogel.server;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import java.util.List;
import java.util.Locale;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import org.joml.Vector3dc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Headless check of #9 (flag file: &lt;serverDir&gt;/aerogel.sablebody): Sable physics bodies over proxy terrain.
 * Spawns two one-block Sable bodies with {@code /sable spawn block}, no player online: body A 60 blocks above the
 * worldgen floor of an unsampled proxy chunk (unknown surface: its sections must promote the chunk at once, then
 * the body must come to rest ON the generated terrain, not sink into it), body B 80 blocks above a sampled proxy
 * chunk
 * (flying high: the chunk must stay a proxy). Sable's body position is read reflectively (its math types are
 * jar-in-jar, not on the compile classpath). Self-halting.
 */
final class SableBodyProbe {
    private static final Logger LOGGER = LoggerFactory.getLogger("aerogel.sableprobe");
    private static final int OFFSET = 5000;
    private static final int SPAWN_TICK = 20;
    private static final int CHECK_TICK = SPAWN_TICK + 15;
    private static final int MAX_TICKS = 900;

    private static ServerLevel level;
    private static ChunkPos a;
    private static ChunkPos b;
    private static int surfaceB;
    private static double aY;
    private static int ticks;
    private static int realSince = -1;
    private static double restY = Double.NaN;
    private static int restTick;
    private static String early;

    private SableBodyProbe() {
    }

    static void start(MinecraftServer server) {
        level = server.overworld();
        ChunkPos spawn = new ChunkPos(level.getSharedSpawnPos());
        // earlier runs leave their chunks real: walk +x for fresh ones (asked from disk, see PromotionProbe)
        for (int i = 0; i < 64 && b == null; i++) {
            ChunkPos candidate = new ChunkPos(spawn.x + OFFSET + i * 32, spawn.z + OFFSET);
            if (level.getChunkSource().chunkMap.read(candidate).join().isEmpty()) {
                level.setChunkForced(candidate.x, candidate.z, true); // block-ticking: Sable simulates there
                level.getChunk(candidate.x, candidate.z);
                if (a == null) {
                    a = candidate;
                } else {
                    b = candidate;
                }
            }
        }
        if (b == null) {
            fail(server, "no two fresh proxy chunks near offset " + OFFSET);
            return;
        }
        LOGGER.info("sable probe: B sample {}", ProxyStreamer.probeSampleBlocking(level, b, ProxyStreamer.topology()));
        surfaceB = ProxyStreamer.surfaceMax(level, b.toLong());
        if (surfaceB == Integer.MAX_VALUE) {
            fail(server, "sampling B stored no grid");
            return;
        }
        // short drop: a one-block body dropped from y=250 once fell through real terrain (likely tunnelling at speed)
        aY = 60 + level.getChunkSource().getGenerator().getBaseHeight(a.getMinBlockX() + 8, a.getMinBlockZ() + 8,
            Heightmap.Types.OCEAN_FLOOR_WG, level, level.getChunkSource().randomState());
        LOGGER.info("sable probe: A={} (unsampled, body at y {}) B={} (proxy surface max {})", a, aY, b, surfaceB);
    }

    static void tick(MinecraftServer server) {
        if (level == null) {
            return;
        }
        ticks++;
        double bx = b.getMinBlockX() + 8.5;
        double bz = b.getMinBlockZ() + 8.5;
        if (ticks == SPAWN_TICK) {
            spawn(server, a.getMinBlockX() + 8.5, aY, a.getMinBlockZ() + 8.5);
            spawn(server, bx, Math.min(surfaceB + 80, 310), bz);
            return;
        }
        double ay = bodyY(a.getMinBlockX() + 8.5, a.getMinBlockZ() + 8.5);
        if (ticks == CHECK_TICK) {
            double by = bodyY(bx, bz);
            boolean aStarted = ProxyChunkGate.isPromoting(level, a.toLong()) || realNow(a);
            boolean bProxy = ProxyChunkGate.isProxy(level, b.toLong()) && !ProxyChunkGate.isPromoting(level, b.toLong());
            early = String.format("A body y=%.1f promotion started=%s; B body y=%.1f (surface %d) still proxy=%s",
                ay, aStarted, by, surfaceB, bProxy);
            LOGGER.info("sable probe: {}", early);
            if (Double.isNaN(ay) || Double.isNaN(by)) {
                fail(server, "a body is missing: " + early);
            } else if (!aStarted) {
                fail(server, "body A did not start the promotion of its proxy chunk: " + early);
            } else if (!bProxy) {
                fail(server, "body B flying high promoted its proxy chunk: " + early);
            }
            return;
        }
        if (ticks < CHECK_TICK) {
            return;
        }
        if (realSince < 0 && realNow(a)) {
            realSince = ticks;
        }
        if (realSince < 0 || Double.isNaN(ay)) {
            timeout(server, ay);
            return;
        }
        if (Double.isNaN(restY) || Math.abs(ay - restY) > 0.05) {
            restY = ay; // still moving: restart the 40-tick rest window
            restTick = ticks;
        } else if (ticks - restTick >= 40 && ticks - realSince >= 40) {
            // solid floor, not the water surface: a stone body sinks through water onto the sea bed
            int surface = level.getHeight(Heightmap.Types.OCEAN_FLOOR, a.getMinBlockX() + 8, a.getMinBlockZ() + 8);
            String detail = String.format("A real after %d ticks, body at rest y=%.2f over solid floor %d; %s",
                realSince - SPAWN_TICK, ay, surface, early);
            if (ay >= surface - 0.5) {
                LOGGER.info("sable probe: VERDICT PASS — {}", detail);
                finish(server);
            } else {
                fail(server, "body A sank into the generated terrain: " + detail);
            }
            return;
        }
        timeout(server, ay);
    }

    private static void timeout(MinecraftServer server, double ay) {
        if (ticks > MAX_TICKS) {
            fail(server, String.format("timeout: A real=%s, body y=%.2f; %s", realNow(a), ay, early));
        }
    }

    private static boolean realNow(ChunkPos pos) {
        return level.getChunkSource().getChunkNow(pos.x, pos.z) != null;
    }

    private static void spawn(MinecraftServer server, double x, double y, double z) {
        String cmd = String.format(Locale.ROOT, "execute in minecraft:overworld positioned %.1f %.1f %.1f run sable spawn block", x, y, z);
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), cmd);
        LOGGER.info("sable probe: /{}", cmd);
    }

    /** y of the Sable body nearest (within 4 blocks horizontally) to x/z, NaN if none. */
    private static double bodyY(double x, double z) {
        try {
            // the static call is linked by descriptor; reflecting on SubLevelContainer would load ClientLevel
            List<?> subs = SubLevelContainer.getContainer(level).getAllSubLevels();
            for (Object sub : subs) {
                Object pose = sub.getClass().getMethod("logicalPose").invoke(sub);
                Vector3dc p = (Vector3dc) pose.getClass().getMethod("position").invoke(pose);
                if (Math.abs(p.x() - x) < 4 && Math.abs(p.z() - z) < 4) {
                    return p.y();
                }
            }
            return Double.NaN;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Sable API moved", e);
        }
    }

    private static void fail(MinecraftServer server, String why) {
        LOGGER.error("sable probe: VERDICT FAIL — {}", why);
        finish(server);
    }

    private static void finish(MinecraftServer server) {
        for (ChunkPos pos : new ChunkPos[] {a, b}) {
            if (pos != null) {
                level.setChunkForced(pos.x, pos.z, false);
            }
        }
        level = null;
        server.halt(false);
    }
}
