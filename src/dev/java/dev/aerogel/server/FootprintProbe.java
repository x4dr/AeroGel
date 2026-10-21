package dev.aerogel.server;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
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
 * How many chunks one promotion actually generates, and what that costs (flag file:
 * {@code <serverDir>/aerogel.footprint}).
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
 * <p><b>Void vs frontier (#17).</b> After the first promotion (in untouched land, "void") settles, the chunk
 * {@link #FRONTIER_DX} to the east — its 5x5 overlaps the void's FULL one, inside its pyramid — is
 * force-loaded and promoted the same way ("frontier"). Each round records the chunk-steps generated (status
 * advances over the sampled square), the server thread's CPU time and the whole process's CPU time above an
 * idle baseline taken just before it,
 * and the peak main-thread chunk-task backlog; and, read before the promotion, what the pacer predicts
 * ({@link ProxyChunkGate#stepsToPromote}) and would charge ({@link PromotionManager#debugStartCost}). Fails if
 * the prediction misses the measured steps by more than {@link #PREDICTION_SLACK}, or if the pacer would charge
 * the two rounds less differently than the chunk-steps they generated (by
 * {@link #CHARGE_SLACK}): a pacer that charges every start the same fails here.</p>
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
    private static final int BASELINE_TICKS = 100;
    private static final int WARMUP_TICKS = 200;     // server start work would read as a promotion's cost
    /**
     * The frontier chunk's offset from the void one. Ring 4, not 3: forcing ring 3 makes the void's ring-2
     * chunks block-ticking, which drags ring 3 to FULL as their dependency before the frontier is promoted.
     */
    private static final int FRONTIER_DX = 4;
    /** measured steps must lie within this factor of the predicted ones. */
    private static final double PREDICTION_SLACK = 0.25;
    /** the charge ratio void/frontier must be at least this share of the measured chunk-step ratio. */
    private static final double CHARGE_SLACK = 0.75;

    private static final int BASELINE = 0;
    private static final int PROMOTE = 1;
    private static final int SETTLE = 2;

    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();

    private static ServerLevel level;
    private static ChunkPos centre;
    private static Round round;
    private static Round voidRound;
    private static int phase = BASELINE;
    private static int ticks;
    private static int phaseStart;
    private static int startsAtFull;
    private static int measuredFull;
    private static boolean reachedEdge;
    private static String lastPrint = "";
    private static int stable;
    private static String voidSummary = "";

    /** one measured promotion. */
    private static final class Round {
        final String name;
        final ChunkPos pos;
        int[] before;
        int predictedSteps;
        double charge;
        long baseCpu;
        long baseProc;
        double idleCpuPerTick;
        double idleProcPerTick;
        long cpu0;
        long proc0;
        int tick0;
        int ticksToFull;
        int backlogPeak;
        long cpuAtChange;
        long procAtChange;
        int tickAtChange;
        int steps;
        int newFull;

        Round(String name, ChunkPos pos) {
            this.name = name;
            this.pos = pos;
        }

        double cpuMs() {
            return ((cpuAtChange - cpu0) - idleCpuPerTick * (tickAtChange - tick0)) / 1e6;
        }

        double procMs() {
            return ((procAtChange - proc0) - idleProcPerTick * (tickAtChange - tick0)) / 1e6;
        }

        String report() {
            return String.format(Locale.ROOT, "%s %s: %d chunk-steps generated (predicted %d), %d new FULL,"
                + " server thread %.1f ms, process %.1f ms CPU above idle, backlog peak %d, FULL after %d ticks,"
                + " settled after %d, pacer charge %.1f ms", name, pos, steps, predictedSteps, newFull, cpuMs(),
                procMs(), backlogPeak, ticksToFull, tickAtChange - tick0, charge);
        }
    }

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
        PromotionManager.debugResetRateCounters();
        begin(server, new Round("void", centre));
    }

    /** force the round's chunk into a proxy and start its idle baseline. */
    private static void begin(MinecraftServer server, Round next) {
        round = next;
        level.setChunkForced(next.pos.x, next.pos.z, true);
        level.getChunk(next.pos.x, next.pos.z);
        if (!ProxyChunkGate.isProxy(level, next.pos.toLong())) {
            finish(server, false, "the forced " + next.name + " chunk at " + next.pos + " is not a proxy (status "
                + statusAt(next.pos.x, next.pos.z) + "), so there is nothing to promote");
            return;
        }
        next.baseCpu = THREADS.getCurrentThreadCpuTime();
        next.baseProc = processCpu();
        phaseStart = ticks;
        phase = BASELINE;
    }

    static void tick(MinecraftServer server) {
        if (level == null) {
            return;
        }
        ticks++;
        switch (phase) {
            case BASELINE -> baseline(server);
            case PROMOTE -> promote(server);
            case SETTLE -> settle(server);
            default -> {
            }
        }
    }

    private static void baseline(MinecraftServer server) {
        Round r = round;
        if (ticks < WARMUP_TICKS) {
            r.baseCpu = THREADS.getCurrentThreadCpuTime();
            r.baseProc = processCpu();
            phaseStart = ticks;
            return;
        }
        if (ticks - phaseStart < BASELINE_TICKS) {
            return;
        }
        long cpu = THREADS.getCurrentThreadCpuTime();
        long proc = processCpu();
        r.idleCpuPerTick = (cpu - r.baseCpu) / (double) BASELINE_TICKS;
        r.idleProcPerTick = (proc - r.baseProc) / (double) BASELINE_TICKS;
        r.before = snapshot();
        r.predictedSteps = ProxyChunkGate.stepsToPromote(level, r.pos.toLong());
        r.charge = PromotionManager.debugStartCost(level, r.pos.toLong());
        ChunkStatus was = statusAt(r.pos.x, r.pos.z);
        r.cpu0 = cpu;
        r.proc0 = proc;
        r.tick0 = ticks;
        r.cpuAtChange = cpu;
        r.procAtChange = proc;
        r.tickAtChange = ticks;
        if (!ProxyChunkGate.promote(level, r.pos)) {
            finish(server, false, "promote() refused the " + r.name + " proxy at " + r.pos);
            return;
        }
        LOGGER.info("footprint probe: {} chunk promoted at {}, no player and no queue (status was {}), idle"
            + " server thread {} ms/tick, predicted {} chunk-steps, pacer would charge {} ms", r.name, r.pos,
            was, fmt(r.idleCpuPerTick / 1e6), r.predictedSteps, fmt(r.charge));
        lastPrint = "";
        stable = 0;
        phaseStart = ticks;
        phase = PROMOTE;
    }

    private static void promote(MinecraftServer server) {
        sampleBacklog();
        if (statusAt(round.pos.x, round.pos.z) == ChunkStatus.FULL) {
            startsAtFull = PromotionManager.debugLingerStarts();
            round.ticksToFull = ticks - phaseStart;
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
                + statusAt(round.pos.x, round.pos.z) + ", queued/in flight " + PromotionManager.debugQueued(level)
                + "/" + PromotionManager.debugInFlight(level));
        }
    }

    private static void settle(MinecraftServer server) {
        sampleBacklog();
        if (ticks % SAMPLE_EVERY == 0) {
            String fingerprint = fingerprint();
            if (fingerprint.equals(lastPrint)) {
                stable++;
            } else {
                stable = 0;
                round.cpuAtChange = THREADS.getCurrentThreadCpuTime();
                round.procAtChange = processCpu();
                round.tickAtChange = ticks;
            }
            lastPrint = fingerprint;
        }
        if ((ticks - phaseStart) / SAMPLE_EVERY > MAX_SAMPLES) {
            finish(server, false, "the footprint never settled within " + MAX_SAMPLES + " samples: " + summary());
            return;
        }
        if (stable < STABLE_SAMPLES) {
            return;
        }
        measure(round);
        LOGGER.info("footprint probe: {}", round.report());
        if (round.name.equals("void")) {
            voidSummary = summary();
            String why = reason();
            if (why != null) {
                finish(server, false, why + " | " + voidSummary);
                return;
            }
            voidRound = round;
            begin(server, new Round("frontier", new ChunkPos(centre.x + FRONTIER_DX, centre.z)));
            return;
        }
        String why = costReason(voidRound, round);
        // the round reports first: the suite runner keeps only the VERDICT line itself
        String detail = voidRound.report() + " | " + round.report() + voidSummary;
        finish(server, why == null, why == null ? detail : why + " | " + detail);
    }

    private static void sampleBacklog() {
        round.backlogPeak = Math.max(round.backlogPeak, level.getChunkSource().getPendingTasksCount());
    }

    /** steps and new FULL chunks between the round's snapshot and now. */
    private static void measure(Round r) {
        int[] after = snapshot();
        for (int i = 0; i < after.length; i++) {
            r.steps += Math.max(0, after[i] - r.before[i]);
            if (after[i] == ChunkStatus.FULL.getIndex() && r.before[i] != after[i]) {
                r.newFull++;
            }
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

    /**
     * #17: the pacer must know a start's generation. Its step prediction has to match what vanilla generated,
     * and it has to charge the void round more than the frontier round by roughly what the server thread paid.
     */
    private static String costReason(Round v, Round f) {
        if (startsAtFull != 0) {
            return startsAtFull + " pacer start(s) happened, so the frontier round is not one promotion's doing";
        }
        for (Round r : new Round[] {v, f}) {
            if (Math.abs(r.steps - r.predictedSteps) > PREDICTION_SLACK * r.predictedSteps) {
                return String.format(Locale.ROOT, "%s: predicted %d chunk-steps, vanilla generated %d", r.name,
                    r.predictedSteps, r.steps);
            }
        }
        if (v.steps <= f.steps || v.procMs() <= f.procMs() || f.procMs() <= 0) {
            return String.format(Locale.ROOT, "the void round was not the costlier one (%d vs %d steps, %.1f vs"
                + " %.1f ms CPU), so this run cannot tell the two apart", v.steps, f.steps, v.procMs(), f.procMs());
        }
        // Against the measured steps, not the CPU: process CPU tracks the steps (ratio ~3 here) but its idle
        // baseline swings the frontier figure 2-5 s between runs, which is noise a bound must not sit on.
        double measured = (double) v.steps / f.steps;
        double charged = v.charge / f.charge;
        if (charged < CHARGE_SLACK * measured) {
            return String.format(Locale.ROOT, "the pacer charges void/frontier %.2fx (%.1f / %.1f ms) while the"
                + " void generated %.2fx the chunk-steps, so it does not know how much a start generates", charged,
                v.charge, f.charge, measured);
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

    /** status index per sampled position (missing = EMPTY, as {@link ProxyChunkGate#stepsToPromote} counts). */
    private static int[] snapshot() {
        int side = 2 * SAMPLE_RADIUS + 1;
        int[] out = new int[side * side];
        int i = 0;
        for (int dx = -SAMPLE_RADIUS; dx <= SAMPLE_RADIUS; dx++) {
            for (int dz = -SAMPLE_RADIUS; dz <= SAMPLE_RADIUS; dz++) {
                ChunkStatus status = statusAt(centre.x + dx, centre.z + dz);
                out[i++] = status == null ? 0 : status.getIndex();
            }
        }
        return out;
    }

    private static ChunkStatus statusAt(int x, int z) {
        ChunkHolder holder = level.getChunkSource().chunkMap.getVisibleChunkIfPresent(ChunkPos.asLong(x, z));
        return holder == null ? null : holder.getPersistedStatus();
    }

    private static long processCpu() {
        return ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean())
            .getProcessCpuTime();
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
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
            level.setChunkForced(centre.x + FRONTIER_DX, centre.z, false);
        }
        level = null;
        centre = null;
        server.halt(false);
    }
}
