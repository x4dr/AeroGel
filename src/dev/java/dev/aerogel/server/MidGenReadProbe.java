package dev.aerogel.server;

import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * #18: does a main-thread read on a neighbour that is mid-generation stall (flag file:
 * {@code <serverDir>/aerogel.midgen})?
 *
 * <p>Same setup as {@code aerogel.footprint}: the gate on globally, one fresh chunk forced into a proxy and
 * promoted through the manual entry point. Every tick from the promotion until the area settles, every chunk
 * within {@link #RADIUS} of it except the promoted one — the 5x5 that generates to FULL and the inner pyramid
 * rings — is read through each main-thread path a caller has: {@code ServerChunkCache.getChunk} at FULL with
 * {@code requireChunk} false and true, at {@code STRUCTURE_REFERENCES} (structure lookups), {@code getChunkNow},
 * and {@code Level.getBlockState}. Each read is timed.</p>
 *
 * <p>The one window in which NeoForge's {@code getChunkNow} answers for a chunk that is not yet available at
 * FULL is {@code GenerationChunkHolder.currentlyLoading}, set only inside the FULL step's main-thread work
 * ({@code ChunkStatusTasks.full}: {@code runPostLoad}, block-entity registration, {@code ChunkEvent.Load}). A
 * tick-time read can never land in it, so a {@code ChunkEvent.Load} listener reads the loading chunk and its 3x3
 * from inside that window as well, which is where a mod's load handler would.</p>
 *
 * <p>The stall witness does not depend on timing: a read that hands back a real chunk for a position that was
 * not available at that status just before it had to generate it, i.e. {@code managedBlock} on its future.
 * Nor does the second: a {@code ChunkEvent.Load} firing while one of the probe's own reads is on the stack means
 * that read ran chunk tasks. Fails if any read did either, if any read took longer than {@link #STALL_MS}, or if no read ever landed on a
 * partially generated chunk inside the 5x5 or inside a load window (nothing was measured).</p>
 */
final class MidGenReadProbe {
    private static final Logger LOGGER = LoggerFactory.getLogger("aerogel.midgenprobe");
    private static final int OFFSET = 17000;
    private static final int RADIUS = 6;   // the 5x5 plus pyramid rings 3..6 (light, carvers, biomes, starts)
    private static final double STALL_MS = 50.0;
    private static final int SAMPLE_EVERY = 5;
    private static final int STABLE_SAMPLES = 12;
    private static final int MAX_TICKS = 2400;

    private static final String[] PATHS = {"getChunk(FULL,false)", "getChunk(FULL,true)",
        "getChunk(STRUCTURE_REFERENCES,true)", "getChunkNow", "Level.getBlockState",
        "in ChunkEvent.Load: getChunk(FULL,true)", "in ChunkEvent.Load: Level.getBlockState"};

    private static ServerLevel level;
    private static ChunkPos centre;
    private static int ticks;
    private static final long[] maxNanos = new long[PATHS.length];
    private static final String[] maxAt = new String[PATHS.length];
    private static final long[] over1ms = new long[PATHS.length];
    private static long reads;
    private static long partialReads;
    private static long partialInner;
    private static long forced;
    private static String firstForced;
    private static long maxTickNanos;
    private static String lastPrint = "";
    private static int stable;
    private static boolean listening;
    private static long loadWindows;
    private static long trapSeen;
    private static boolean reading;
    private static long nestedLoads;

    private MidGenReadProbe() {
    }

    static void start(MinecraftServer server) {
        level = server.overworld();
        AdaptiveMode.debugAlwaysOn();   // the gate applies everywhere: every unloaded position is engaged
        ChunkPos spawn = new ChunkPos(level.getSharedSpawnPos());
        centre = freshSquare(spawn);
        if (centre == null) {
            finish(server, false, "no fresh 7x7 near offset " + OFFSET);
            return;
        }
        level.setChunkForced(centre.x, centre.z, true);
        level.getChunk(centre.x, centre.z);
        if (!ProxyChunkGate.isProxy(level, centre.toLong()) || !ProxyChunkGate.promote(level, centre)) {
            finish(server, false, "the forced chunk at " + centre + " is not a promotable proxy");
            return;
        }
        if (!listening) {
            listening = true;
            NeoForge.EVENT_BUS.addListener(MidGenReadProbe::onLoad);
        }
        LOGGER.info("midgen probe: promoted {}, reading radius {} every tick through {} paths", centre, RADIUS,
            PATHS.length);
    }

    static void tick(MinecraftServer server) {
        if (level == null) {
            return;
        }
        ticks++;
        long t0 = System.nanoTime();
        reading = true;
        try {
            readAll();
        } finally {
            reading = false;
        }
        maxTickNanos = Math.max(maxTickNanos, System.nanoTime() - t0);
        if (ticks % SAMPLE_EVERY == 0) {
            String print = fingerprint();
            stable = print.equals(lastPrint) && !ProxyChunkGate.isPromoting(level, centre.toLong()) ? stable + 1 : 0;
            lastPrint = print;
        }
        if (stable >= STABLE_SAMPLES || ticks >= MAX_TICKS) {
            String why = reason();
            finish(server, why == null, (why == null ? "" : why + " | ") + summary());
        }
    }

    private static void readAll() {
        ServerChunkCache cache = level.getChunkSource();
        int y = level.getSeaLevel();
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                if (dx == 0 && dz == 0) {
                    continue; // the promoted chunk itself is isPromoting's (#13), not this issue's
                }
                int x = centre.x + dx;
                int z = centre.z + dz;
                long pos = ChunkPos.asLong(x, z);
                int ring = Math.max(Math.abs(dx), Math.abs(dz));
                ChunkStatus status = statusAt(pos);
                if (status != null && status != ChunkStatus.FULL) {
                    partialReads++;
                    if (ring <= 2) {
                        partialInner++;
                    }
                }
                String where = "ring " + ring + " at " + (status == null ? "none" : status.getName());
                boolean fullBefore = ProxyChunkGate.isAvailable(level, pos, ChunkStatus.FULL);
                boolean refsBefore = ProxyChunkGate.isAvailable(level, pos, ChunkStatus.STRUCTURE_REFERENCES);

                long t = System.nanoTime();
                ChunkAccess peek = cache.getChunk(x, z, ChunkStatus.FULL, false);
                record(0, t, where);
                witness(fullBefore, peek, "getChunk(FULL,false)", pos);

                t = System.nanoTime();
                ChunkAccess full = cache.getChunk(x, z, ChunkStatus.FULL, true);
                record(1, t, where);
                witness(fullBefore, full, "getChunk(FULL,true)", pos);

                t = System.nanoTime();
                ChunkAccess refs = cache.getChunk(x, z, ChunkStatus.STRUCTURE_REFERENCES, true);
                record(2, t, where);
                witness(refsBefore, refs, "getChunk(STRUCTURE_REFERENCES,true)", pos);

                t = System.nanoTime();
                cache.getChunkNow(x, z);
                record(3, t, where);

                t = System.nanoTime();
                level.getBlockState(new BlockPos(x * 16 + 8, y, z * 16 + 8));
                record(4, t, where);
                reads += 5;
            }
        }
    }

    /** inside the FULL step: the only window where getChunkNow answers for a chunk not available at FULL. */
    private static void onLoad(ChunkEvent.Load event) {
        ChunkPos at = event.getChunk().getPos();
        if (level == null || event.getLevel() != level) {
            return;
        }
        if (reading) {
            nestedLoads++; // a chunk finished FULL inside one of our reads: that read ran chunk tasks (managedBlock)
            return;
        }
        if (Math.max(Math.abs(at.x - centre.x), Math.abs(at.z - centre.z)) > RADIUS) {
            return;
        }
        loadWindows++;
        if (level.getChunkSource().getChunkNow(at.x, at.z) != null
            && !ProxyChunkGate.isAvailable(level, at.toLong(), ChunkStatus.FULL)) {
            trapSeen++; // NeoForge's currentlyLoading: "loaded" before the FULL future completes
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                String where = "load window of " + at + ", read " + (at.x + dx) + "," + (at.z + dz);
                long t = System.nanoTime();
                level.getChunkSource().getChunk(at.x + dx, at.z + dz, ChunkStatus.FULL, true);
                record(5, t, where);
                t = System.nanoTime();
                level.getBlockState(new BlockPos((at.x + dx) * 16 + 8, level.getSeaLevel(), (at.z + dz) * 16 + 8));
                record(6, t, where);
                reads += 2;
            }
        }
    }

    private static void record(int path, long start, String where) {
        long ns = System.nanoTime() - start;
        if (ns > 1_000_000L) {
            over1ms[path]++;
        }
        if (ns > maxNanos[path]) {
            maxNanos[path] = ns;
            maxAt[path] = where + ", tick " + ticks;
        }
    }

    /**
     * A real chunk for a position that was not available at that status before the read: the read went through
     * vanilla's future chain (ticket, then {@code managedBlock}) to load or generate it.
     */
    private static void witness(boolean availableBefore, ChunkAccess result, String path, long pos) {
        if (!availableBefore && result != null && !(result instanceof EmptyLevelChunk)) {
            forced++;
            if (firstForced == null) {
                firstForced = path + " at " + new ChunkPos(pos) + " tick " + ticks;
            }
        }
    }

    private static String reason() {
        if (nestedLoads > 0) {
            return nestedLoads + " chunk(s) finished FULL inside a probe read, i.e. a read ran chunk tasks"
                + " (managedBlock)";
        }
        if (forced > 0) {
            return forced + " read(s) went through vanilla's future chain for a chunk not available at that status"
                + " (first: " + firstForced + ")";
        }
        for (int i = 0; i < PATHS.length; i++) {
            if (maxNanos[i] / 1e6 > STALL_MS) {
                return PATHS[i] + " took " + ms(maxNanos[i]) + " ms (> " + STALL_MS + ")";
            }
        }
        if (partialInner == 0 || loadWindows == 0) {
            return "no read landed on a partially generated chunk inside the 5x5 or in a load window, so nothing"
                + " was measured";
        }
        if (ticks >= MAX_TICKS) {
            return "the area never settled within " + MAX_TICKS + " ticks";
        }
        return null;
    }

    private static String summary() {
        StringBuilder out = new StringBuilder(String.format(Locale.ROOT,
            "%d ticks, %d reads, %d on a partial chunk (%d inside the 5x5), %d load window(s) (%d with getChunkNow"
                + " ahead of FULL), %d read(s) through vanilla's future chain, %d load(s) inside a read; probe read cost"
                + " max %s ms/tick", ticks, reads, partialReads, partialInner, loadWindows, trapSeen, forced,
            nestedLoads, ms(maxTickNanos)));
        for (int i = 0; i < PATHS.length; i++) {
            out.append(String.format(Locale.ROOT, " | %s max %s ms (%s), >1ms %d", PATHS[i], ms(maxNanos[i]),
                maxAt[i], over1ms[i]));
        }
        return out.toString();
    }

    private static String ms(long nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1e6);
    }

    private static String fingerprint() {
        StringBuilder out = new StringBuilder();
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                ChunkStatus status = statusAt(ChunkPos.asLong(centre.x + dx, centre.z + dz));
                out.append(status == null ? "." : status.getName()).append(',');
            }
        }
        return out.toString();
    }

    private static ChunkStatus statusAt(long pos) {
        ChunkHolder holder = level.getChunkSource().chunkMap.getVisibleChunkIfPresent(pos);
        return holder == null ? null : holder.getPersistedStatus();
    }

    private static ChunkPos freshSquare(ChunkPos spawn) {
        for (int i = 0; i < 96; i++) {
            ChunkPos candidate = new ChunkPos(spawn.x + OFFSET + i * 32, spawn.z + OFFSET / 2);
            boolean fresh = true;
            for (int dx = -3; dx <= 3 && fresh; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    if (!level.getChunkSource().chunkMap.read(new ChunkPos(candidate.x + dx, candidate.z + dz))
                        .join().isEmpty()) {
                        fresh = false;
                        break;
                    }
                }
            }
            if (fresh) {
                return candidate;
            }
        }
        return null;
    }

    private static void finish(MinecraftServer server, boolean pass, String detail) {
        if (pass) {
            LOGGER.info("midgen probe: VERDICT PASS — {}", detail);
        } else {
            LOGGER.error("midgen probe: VERDICT FAIL — {}", detail);
        }
        if (level != null && centre != null) {
            level.setChunkForced(centre.x, centre.z, false);
        }
        level = null;
        centre = null;
        server.halt(false);
    }
}
