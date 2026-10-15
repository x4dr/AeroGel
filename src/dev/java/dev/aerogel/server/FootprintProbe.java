package dev.aerogel.server;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * How many chunks one promotion actually generates (flag file: {@code <serverDir>/aerogel.footprint}).
 *
 * <p>No player, no zone, no queue: the gate is switched on globally, exactly <b>one</b> fresh chunk is
 * force-loaded into a proxy, and that chunk alone is promoted through the mod's own manual entry point — the
 * call {@code /aerogel generate} makes. Nothing else can be promoted (no player means no linger and no
 * refill admission), so whatever appears around it afterwards is the consequence of that one promotion.</p>
 *
 * <p>The footprint is read per Chebyshev distance from ring 1 out to {@link #SAMPLE_RADIUS}: how many chunks
 * reached FULL, how many stopped at a partial status and which, and how many never generated. Statuses come
 * from the chunk holder ({@code getVisibleChunkIfPresent}), so observation cannot create proxies or request
 * generation itself.</p>
 *
 * <p>Fails if the forced chunk was not a proxy or the promotion was refused (nothing was measured), if the
 * pacer started anything (the count would no longer be one manual promotion's doing), or if the footprint
 * never settles — which is what an unbounded cascade would look like.</p>
 */
final class FootprintProbe {
    private static final Logger LOGGER = LoggerFactory.getLogger("aerogel.footprintprobe");
    private static final int OFFSET = 13000;
    private static final int SAMPLE_RADIUS = 24;
    private static final int SAMPLE_EVERY = 5;
    private static final int STABLE_SAMPLES = 12;   // 60 ticks with no change means it has settled
    private static final int MAX_SAMPLES = 400;
    private static final int PROMOTE_TIMEOUT = 600;

    private static final int PROMOTE = 0;
    private static final int SETTLE = 1;

    private static ServerLevel level;
    private static ChunkPos centre;
    private static int phase = PROMOTE;
    private static int ticks;
    private static int phaseStart;
    private static int startsAtFull;
    private static int measuredFull;
    private static boolean reachedEdge;
    private static String lastPrint = "";
    private static int stable;

    private FootprintProbe() {
    }

    static void start(MinecraftServer server) {
        level = server.overworld();
        AdaptiveMode.debugAlwaysOn();   // the gate applies with nobody to opt in
        ChunkPos spawn = new ChunkPos(level.getSharedSpawnPos());
        centre = freshSquare(spawn, OFFSET, 3);
        if (centre == null) {
            finish(server, false, "no fresh 7x7 near offset " + OFFSET);
            return;
        }
        level.setChunkForced(centre.x, centre.z, true);
        level.getChunk(centre.x, centre.z);
        if (!ProxyChunkGate.isProxy(level, centre.toLong())) {
            finish(server, false, "the forced chunk at " + centre + " is not a proxy (status "
                + statusAt(centre.x, centre.z) + "), so there is nothing to promote");
            return;
        }
        PromotionManager.debugResetRateCounters();
        if (!ProxyChunkGate.promote(level, centre)) {
            finish(server, false, "promote() refused the proxy at " + centre);
            return;
        }
        LOGGER.info("footprint probe: one chunk promoted at {}, no player and no queue (status was {}),"
            + " sampling radius {}", centre, statusAt(centre.x, centre.z), SAMPLE_RADIUS);
    }

    static void tick(MinecraftServer server) {
        if (level == null) {
            return;
        }
        ticks++;
        switch (phase) {
            case PROMOTE -> promote(server);
            case SETTLE -> settle(server);
            default -> {
            }
        }
    }

    private static void promote(MinecraftServer server) {
        if (statusAt(centre.x, centre.z) == ChunkStatus.FULL) {
            startsAtFull = PromotionManager.debugLingerStarts();
            LOGGER.info("footprint probe: the promoted chunk reached FULL in {} ticks; pacer starts {}"
                + " (must be 0), queued/in flight {}",
                ticks - phaseStart, startsAtFull, PromotionManager.debugQueued(level) + "/"
                    + PromotionManager.debugInFlight(level));
            phaseStart = ticks;
            phase = SETTLE;
            return;
        }
        if (ticks - phaseStart > PROMOTE_TIMEOUT) {
            finish(server, false, "the promoted chunk never reached FULL: status "
                + statusAt(centre.x, centre.z) + ", queued/in flight " + PromotionManager.debugQueued(level)
                + "/" + PromotionManager.debugInFlight(level));
        }
    }

    private static void settle(MinecraftServer server) {
        if (ticks % SAMPLE_EVERY == 0) {
            String fingerprint = fingerprint();
            stable = fingerprint.equals(lastPrint) ? stable + 1 : 0;
            lastPrint = fingerprint;
        }
        if ((ticks - phaseStart) / SAMPLE_EVERY > MAX_SAMPLES) {
            finish(server, false, "the footprint never settled within " + MAX_SAMPLES + " samples: " + summary());
            return;
        }
        if (stable >= STABLE_SAMPLES) {
            String why = reason();
            finish(server, why == null, why == null ? summary() : why + " | " + summary());
        }
    }

    /**
     * The bounds this probe exists to guard, as measured on an otherwise empty patch: one promotion fully
     * generates its 5x5 and no more, and the partial pyramid around it dies out well inside the sample radius.
     * A cascade, or the FULL region growing past the 5x5, is what makes this red.
     */
    private static String reason() {
        if (startsAtFull != 0) {
            return startsAtFull + " pacer start(s) happened, so this is not one promotion's doing";
        }
        if (measuredFull > 24) {
            return measuredFull + " chunks outside the promoted one reached FULL; one promotion's 5x5 is the"
                + " expected maximum (24 outside the centre)";
        }
        if (reachedEdge) {
            return "the pyramid still has content at the sample edge (ring " + SAMPLE_RADIUS + "), so it is not"
                + " bounded within the sampled radius";
        }
        return null;
    }

    /** per ring: FULL, partial (with the statuses), and never generated. */
    private static String summary() {
        StringBuilder out = new StringBuilder();
        int fullTotal = 0;
        int partialTotal = 0;
        int lastNonEmptyRing = 0;
        reachedEdge = false;
        for (int r = 1; r <= SAMPLE_RADIUS; r++) {
            int full = 0;
            int partial = 0;
            int none = 0;
            Map<String, Integer> partialNames = new TreeMap<>();
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue;
                    }
                    ChunkStatus status = statusAt(centre.x + dx, centre.z + dz);
                    if (status == null || status == ChunkStatus.EMPTY) {
                        none++;
                    } else if (status == ChunkStatus.FULL) {
                        full++;
                    } else {
                        partial++;
                        partialNames.merge(status.getName(), 1, Integer::sum);
                    }
                }
            }
            fullTotal += full;
            partialTotal += partial;
            if (full == 0 && partial == 0) {
                continue;
            }
            lastNonEmptyRing = r;
            if (r == SAMPLE_RADIUS) {
                reachedEdge = true;
            }
            out.append(String.format(Locale.ROOT, "%n  ring %2d: FULL %d, partial %d %s, none %d", r, full, partial,
                partialNames, none));
        }
        measuredFull = fullTotal;
        out.append(String.format(Locale.ROOT, "%n  total: %d FULL, %d partial (%d chunks generated by one"
            + " promotion), out to ring %d, %d pacer start(s)", fullTotal, partialTotal, fullTotal + partialTotal,
            lastNonEmptyRing, startsAtFull));
        return out.toString();
    }

    private static String fingerprint() {
        StringBuilder out = new StringBuilder();
        for (int dx = -SAMPLE_RADIUS; dx <= SAMPLE_RADIUS; dx++) {
            for (int dz = -SAMPLE_RADIUS; dz <= SAMPLE_RADIUS; dz++) {
                ChunkStatus status = statusAt(centre.x + dx, centre.z + dz);
                out.append(status == null ? "." : status.getName()).append(',');
            }
        }
        return out.toString();
    }

    private static ChunkStatus statusAt(int x, int z) {
        ChunkHolder holder = level.getChunkSource().chunkMap.getVisibleChunkIfPresent(ChunkPos.asLong(x, z));
        return holder == null ? null : holder.getPersistedStatus();
    }

    private static ChunkPos freshSquare(ChunkPos spawn, int offsetX, int radius) {
        for (int i = 0; i < 96; i++) {
            ChunkPos candidate = new ChunkPos(spawn.x + offsetX + i * 32, spawn.z + offsetX / 2);
            boolean fresh = true;
            for (int dx = -radius; dx <= radius && fresh; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
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
            LOGGER.info("footprint probe: VERDICT PASS — {}", detail);
        } else {
            LOGGER.error("footprint probe: VERDICT FAIL — {}", detail);
        }
        if (level != null && centre != null) {
            level.setChunkForced(centre.x, centre.z, false);
        }
        level = null;
        centre = null;
        server.halt(false);
    }
}
