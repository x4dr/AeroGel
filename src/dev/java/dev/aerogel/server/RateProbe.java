package dev.aerogel.server;

import com.mojang.authlib.GameProfile;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.neoforged.neoforge.common.util.FakePlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Headless measurement of the linger drain's rate, of what the neighbours of a promoted chunk actually reach,
 * and of the two escape hatches in the pacer (flag file: {@code <serverDir>/aerogel.rate}).
 *
 * <p>This is the last open question from the rewrite plan, and the reason it could not be answered before is
 * that a {@code FakePlayer} could not stay in the player list: the question needs a player lingering over a
 * square of stubs for seconds. {@link ProbeFakePlayer} removes that limit, so the drain can be driven through
 * the real linger path — {@code debugEnqueue} is <b>not</b> used here — and three things no probe could reach
 * become reachable: the refill's <b>calm-threshold bypass</b> filling the queue, the pacer under a server that
 * is actually over the threshold, and the <b>status</b> each neighbour of a promoted chunk reaches.</p>
 *
 * <p><b>{@code real} means FULL.</b> The previous revision called a chunk real when it was loaded and not a
 * proxy, which a <i>partially</i> generated neighbour satisfies, so it scored a drain of 8 pacer starts as
 * "49 real". The status here comes from {@code ChunkHolder.getPersistedStatus()} — the access's
 * {@code ChunkAccess.getPersistedStatus()}, the same reader {@link ProxyChunkGate} classifies with and the one
 * vanilla's own {@code ChunkMap} debug dump prints. It is monotonic: {@code ChunkStep.completeChunkGeneration}
 * only ever advances it, and it is FULL exactly when the chunk became a {@code LevelChunk}/imposter. The
 * holder's <i>ticket</i> level is recorded alongside it, because a chunk whose ticket cannot reach FULL is not
 * allowed to exist at FULL ({@code GenerationChunkHolder.isStatusDisallowed}), so the ticket says which ring
 * statuses vanilla itself asks for.</p>
 *
 * <p><b>One start is not one chunk.</b> Promoting one proxy fully generates its 5x5 neighbourhood — the chunk
 * plus 24 — because a chunk near a player has to be a ticking chunk and that requires its vicinity at FULL, and
 * vanilla raises a partial pyramid out to Chebyshev ring 13 around that from the same dependencies. The
 * footprint is measured by {@code aerogel.footprint}. What it means here is that
 * {@link PromotionManager#maxInFlight()} bounds <b>starts</b>, not chunks generated: one start can take a whole
 * neighbourhood to FULL, so a 49-stub square needs a handful of starts rather than 49. The probe therefore
 * reports chunks-materialised-per-start as a number, and does not assert that each stub was admitted by a start
 * of its own — a per-chunk admission assertion is contradicted by the footprint and would fail on a correct
 * server.</p>
 *
 * <p><b>Phase A — the calm bypass, the rate, and the ring.</b> With the server calm and a zone over a fresh
 * 7x7 (an opted-in fake whose {@code travel} switch is on, enforcement off), the square is force-loaded into
 * stubs and the refill admits all of them. Admission within a few ticks of the load, against a minimum linger
 * threshold of {@code lingerMinIdleSeconds} (200 ticks), is only explicable by the bypass; the probe asserts
 * both, so a run where the threshold did the admitting fails instead of passing quietly. The drain is then
 * measured against the pacer's own counters: starts, chunks per start, budget blocks, brake blocks, floor
 * starts, the peak in-flight count against {@code maxInFlight}, and the peak smoothed tick time. Every tick of
 * the drain and of
 * a bounded <b>settle window</b> after it samples a 15x15 around the centre and records, per Chebyshev distance
 * ring, the status each chunk reached, whether it was ever seen at FULL, and whether it was ever in flight. The
 * settle window is not decoration: at drain end the ring is still mid-fan-out, and a status read there scores a
 * partial as final — the first revision of this probe did exactly that and called the result a pass. The probe
 * asserts that the 7x7 square reaches FULL and that the drain made progress at all, because that is the drain
 * happening. Chunks OUTSIDE the square that reach FULL are <b>reported, never failed on</b>: the square's own
 * starts drag their 5x5 neighbourhoods to FULL, so ring 4 and 5 outside it reaching FULL is the footprint
 * rather than a defect, and failing on it would be asserting something the measured server contradicts. They
 * are still recorded with the two mechanisms separated, because "the pacer promoted it" (a candidate in the
 * 12-chunk linger range, which is what the pacer is for) and "somebody else's range future dragged it in"
 * (vanilla's dependency generation) remain different findings.</p>
 *
 * <p><b>Phase B — the brake, and the floor that overrides it.</b> On an idle dev server the smoothed tick time
 * sits around 2-3 ms, so a 35 ms brake is never reachable and "the drain stayed under the threshold" would pass
 * on headroom it never tested. This phase therefore injects a known wall-clock load ({@link #injectNanos},
 * spun from {@link #tickPre} inside the measured tick window) and ramps it until the brake is reached, then
 * holds past {@code FORCE_START_TICKS}. What it establishes is the direction of both bounds: while the brake is
 * saturated the pacer admits nothing, and exactly the floor keeps progress. The brake is the smoothed tick time
 * <i>as the probe reads it</i>, which lags the pacer's own reading by at most a tick, so a start that got past a
 * closed brake is only counted when it happened on a tick the probe also saw open. The load is the probe's own;
 * enforcement engaging under it is reported, not asserted.</p>
 *
 * <p><b>Enforcement is reported, never asserted.</b> Phase A's drain is real generation work — the pyramid is
 * main-thread chunk-task backlog — so it can push the backlog signal over its activate threshold on its own;
 * which signal crossed, by how much, and on which tick is recorded and printed, and the verdict states it. The
 * window maxima are read from the adaptive status line, which prints the state the decision itself used,
 * because the probe's own per-tick samples can miss the spike: the window is sampled inside the tick, with the
 * pyramid's chunk tasks still queued. A probe that failed on enforcement would be asserting something the
 * design does not claim.</p>
 *
 * <p>Both squares are fresh land, found by reading the chunk source the way {@code vanillaclient} does, so a
 * re-run cannot score itself on chunks a previous run generated.</p>
 */
final class RateProbe {
    private static final Logger LOGGER = LoggerFactory.getLogger("aerogel.rateprobe");
    private static final int OFFSET_A = 11000;
    private static final int OFFSET_B = 11400;
    private static final int RADIUS = 3;             // 7x7 = 49 stubs per square: inside the queue cap
    private static final int RING_MAX = 7;           // sampled out to Chebyshev distance 7
    private static final int IDLE_TIMEOUT = 1200;
    private static final int B_ENFORCE_WAIT = 400;   // prefer a not-enforcing baseline for phase B, not forever
    private static final int DRAIN_TIMEOUT = 900;
    private static final int DRAIN_STALL = 150;      // ticks without one new FULL chunk before A gives up
    private static final int SETTLE_MAX = 200;       // ticks spent watching the ring after the square is FULL
    private static final int SETTLE_QUIET = 40;      // ... or until it has not changed for this long
    private static final int SATURATE_TIMEOUT = 400;
    private static final int STUB_TIMEOUT = 200;
    private static final int B_ADMIT_TIMEOUT = 700;  // the busy linger threshold is 400 ticks
    private static final int RAMP_TICKS = 6;         // ticks per injection step while looking for the brake
    private static final int SATURATE_HOLD = 260;    // past FORCE_START_TICKS = 200, so the floor must fire
    // The ramp is deliberately short: phase B loads its square under a calm window (the calm bypass is what puts
    // it in the queue) and then has to reach the brake *while the pacer still has that square to do*. A slow
    // ramp drained the square before the brake engaged, and the floor then started a ring chunk with the square
    // already FULL, which is a floor that "resumed progress" on nothing. 24 ms is under the 35 ms brake here,
    // and the smoothing lag costs a few ticks, so the brake lands inside the drain.
    private static final double[] INJECTION_STEPS_MS = {24, 32, 40, 48, 60};

    /** the adaptive status line prints the window maxima the enforcement decision used; see {@link #enforceReport}. */
    private static final Pattern WINDOW_BACKLOG = Pattern.compile("backlog \\d+ now / (\\d+) max");
    private static final Pattern WINDOW_TICK = Pattern.compile("tick [\\d.]+ ms now / ([\\d.]+) ms max");

    private static final int PHASE_ZONE_A = 0;
    private static final int PHASE_LOAD_A = 1;
    private static final int PHASE_DRAIN_A = 2;
    private static final int PHASE_SETTLE_A = 3;
    private static final int PHASE_ZONE_B = 4;
    private static final int PHASE_LOAD_B = 5;
    private static final int PHASE_SATURATE = 6;
    private static final int PHASE_RECOVER = 7;

    private static ServerLevel level;
    private static FakePlayer viewer;
    private static ChunkPos centre;
    private static ChunkPos centreB;
    private static int expected;
    private static int phase = PHASE_ZONE_A;
    private static int ticks;
    private static int phaseStart;
    private static long phaseStartNanos;
    private static int drainStartTick;

    /** injection driven from the pre-tick hook; 0 when not injecting. */
    private static long injectNanos;

    // phase A
    private static int stubsAtLoadA;
    private static int admissionTicks;
    private static int admissionQueued;
    private static boolean admissionCalm;
    private static int startsA;
    private static int brakeBlocksA;
    private static int budgetBlocksA;
    private static int forcedStartsA;
    private static int drainTicksA;
    private static long drainNanosA;
    private static double maxTickMsA;
    private static int peakInFlightA;
    private static int maxBacklogA;
    private static int enforceTickA = -1;            // ticks into the drain when enforcement first engaged
    private static String enforceSignalA = "";
    private static int fullA;
    private static int lastFullA;
    private static int lastFullTickA;
    private static boolean drainGaveUpA;
    private static double postCostBeforeA;
    private static double postCostAfterA;

    /** distance rings around {@link #centre}: which chunks the promotion dragged to FULL outside the square. */
    private static final Map<Long, String> outsideFull = new LinkedHashMap<>();
    private static final Map<Long, Integer> outsideFullTick = new LinkedHashMap<>();
    private static final Set<Long> promotingSeen = new HashSet<>();
    private static int outsideFullFirstTick = -1;

    // phase A, settle window
    private static int settleTicks;
    private static int settleLastChangeTick;
    private static int settleLastOutside = -1;
    private static int settleSamples;

    // phase B
    private static int stubsAtLoadB;
    private static int bAdmissionTicks;
    private static boolean bAdmissionCalm;
    private static double injectionStepMs;
    private static int injectionStep;
    private static int ticksToBrake;
    private static int saturateTicks;
    private static int startsAtBrakeB;
    private static int forcedAtBrakeB;
    private static int startsB;
    private static int brakeBlocksB;
    private static int forcedStartsB;
    private static int brakeOpenTicksB;
    private static int peakInFlightB;
    private static double maxTickMsB;
    private static boolean enforcementB;
    private static int fullAtSaturateStart;
    private static int fullAtSaturateEnd;
    private static int fullB;
    private static int recoverTicks;

    private RateProbe() {
    }

    static void start(MinecraftServer server) {
        level = server.overworld();
        ChunkPos spawn = new ChunkPos(level.getSharedSpawnPos());
        centre = freshSquare(spawn, OFFSET_A);
        centreB = freshSquare(spawn, OFFSET_B);
        expected = (2 * RADIUS + 1) * (2 * RADIUS + 1);
        if (centre == null || centreB == null) {
            finish(server, false, "no fresh 7x7 pair near offsets " + OFFSET_A + "/" + OFFSET_B);
            return;
        }
        viewer = ProbeFakePlayer.get(level,
            new GameProfile(UUID.fromString("ae0ae0ae-0000-4000-8000-0000000007e1"), "aerogel-rate"));
        AdaptiveMode.setHasAeroGel(viewer, true);   // the switches are meaningful
        AdaptiveMode.setPlayerSwitch(viewer, "travel", true);  // opted in: a zone, with enforcement off
        AdaptiveMode.setPlayerSwitch(viewer, "proxy", false);  // no meshes: this measures generation only
        viewer.setPos(centre.getMiddleBlockX() + 0.5, 200, centre.getMiddleBlockZ() + 0.5);
        if (!ProbeFakePlayer.park(server, viewer)) {
            finish(server, false, "the fake player has no draining channel, so it cannot stay in the player"
                + " list and this run would assert nothing");
        }
    }

    static void tick(MinecraftServer server) {
        if (level == null) {
            return;
        }
        ticks++;
        switch (phase) {
            case PHASE_ZONE_A -> zoneA(server);
            case PHASE_LOAD_A -> loadA(server);
            case PHASE_DRAIN_A -> drainA(server);
            case PHASE_SETTLE_A -> settleA(server);
            case PHASE_ZONE_B -> zoneB(server);
            case PHASE_LOAD_B -> loadB(server);
            case PHASE_SATURATE -> saturate(server);
            case PHASE_RECOVER -> recover(server);
            default -> {
            }
        }
    }

    /** the injected load; see the class doc for why it is here and not in {@link #tick}. */
    static void tickPre() {
        long nanos = injectNanos;
        if (nanos <= 0L) {
            return;
        }
        long end = System.nanoTime() + nanos;
        while (System.nanoTime() < end) {
            Thread.onSpinWait();
        }
    }

    private static void zoneA(MinecraftServer server) {
        if (ticks > IDLE_TIMEOUT) {
            finish(server, false, "the server never went calm with a zone over square A");
            return;
        }
        // the real path: no debugAlwaysOn, no pinned thresholds. Calm is what the refill reads to bypass the
        // linger threshold, and the zone is what makes the square gated at all.
        if (AdaptiveMode.enforcing() || !AdaptiveMode.windowCalm() || !zoneCovers(centre)) {
            return;
        }
        LOGGER.info("rate probe: calm window and an opted-in zone over {} (brake {} ms, activate {} ms, minimum"
            + " linger threshold {} ticks)", centre, fmt(brakeMs()), fmt(AdaptiveMode.activateTickMs()),
            minLingerTicks());
        loadSquare(server, centre);
        // read synchronously, in the same tick as the reads: no pacer tick can have run in between, so this is
        // the square as the gate made it, not a square a start has already eaten into
        stubsAtLoadA = stubCount(centre);
        if (stubsAtLoadA != expected) {
            finish(server, false, String.format("square A did not become %d stubs on the load tick: %d stubs"
                + " (engaged=%s, active=%s)", expected, stubsAtLoadA,
                ProxyChunkGate.isEngaged(level, centre.toLong()), ProxyChunkGate.isActive(level)));
            return;
        }
        phaseStart = ticks;
        lastFullTickA = ticks;
        phase = PHASE_LOAD_A;
    }

    private static void loadA(MinecraftServer server) {
        // "admitted" is scoped to the square: the refill took these positions in. Reading the level-wide queue
        // depth instead made phase B score itself the moment it loaded, on the ~58 positions left over from
        // phase A -- the refill's own queue cap, not this square.
        if (stubCount(centre) < stubsAtLoadA) {
            admissionTicks = ticks - phaseStart;
            admissionQueued = PromotionManager.debugQueued(level) + PromotionManager.debugInFlight(level);
            admissionCalm = AdaptiveMode.windowCalm();
            PromotionManager.debugResetRateCounters();
            postCostBeforeA = ProxyChunkGate.postCostMs(level);
            phaseStart = ticks;
            lastFullTickA = ticks;
            drainStartTick = ticks;
            phaseStartNanos = System.nanoTime();
            phase = PHASE_DRAIN_A;
            LOGGER.info("rate probe: square A admitted ({} position(s) left the stub state) {} ticks after load"
                + " (calm={}), threshold is {} ticks; {} queued/in flight across the level", stubsAtLoadA
                - stubCount(centre), admissionTicks, admissionCalm, minLingerTicks(), admissionQueued);
            return;
        }
        if (ticks - phaseStart > STUB_TIMEOUT) {
            finish(server, false, String.format("square A was never admitted: %d/%d stubs, %s queued/in flight,"
                + " engaged=%s", stubCount(centre), expected, drainState(),
                ProxyChunkGate.isEngaged(level, centre.toLong())));
        }
    }

    private static void drainA(MinecraftServer server) {
        sampleA(server);
        fullA = fullCount(centre);
        if (fullA >= expected) {
            startsA = PromotionManager.debugLingerStarts();
            brakeBlocksA = PromotionManager.debugBrakeBlocks();
            budgetBlocksA = PromotionManager.debugBudgetBlocks();
            forcedStartsA = PromotionManager.debugForcedStarts();
            drainTicksA = ticks - phaseStart;
            drainNanosA = System.nanoTime() - phaseStartNanos;
            postCostAfterA = ProxyChunkGate.postCostMs(level);
            reportA("drain end");
            // the drain ends the moment the square is FULL, which for the region nearest the player is the inner
            // 3x3's five-by-fives: the *ring* may still be mid-fan-out, and a status read at that tick would
            // score a partial as final. Keep the square forced and the player in place and watch it settle.
            phaseStart = ticks;
            settleLastChangeTick = ticks;
            phase = PHASE_SETTLE_A;
            return;
        }
        if (fullA > lastFullA) {
            lastFullA = fullA;
            lastFullTickA = ticks;
        }
        if (ticks - lastFullTickA > DRAIN_STALL) {
            // the drain is not progressing: record what it did reach and carry on to phase B, so the brake and
            // the floor are still measured. sound() fails the run on the missing FULL square either way.
            drainGaveUpA = true;
            startsA = PromotionManager.debugLingerStarts();
            brakeBlocksA = PromotionManager.debugBrakeBlocks();
            budgetBlocksA = PromotionManager.debugBudgetBlocks();
            forcedStartsA = PromotionManager.debugForcedStarts();
            drainTicksA = ticks - phaseStart;
            drainNanosA = System.nanoTime() - phaseStartNanos;
            postCostAfterA = ProxyChunkGate.postCostMs(level);
            LOGGER.error("rate probe: square A stopped making progress at {}/{} FULL after {} ticks ({});"
                + " leaving for the settle window", fullA, expected, drainTicksA, drainState());
            reportA("drain gave up");
            phaseStart = ticks;
            settleLastChangeTick = ticks;
            phase = PHASE_SETTLE_A;
            return;
        }
        if (ticks - phaseStart > DRAIN_TIMEOUT) {
            finish(server, false, String.format("square A only reached %d/%d FULL after %d ticks (%s)",
                fullA, expected, ticks - phaseStart, drainState()));
        }
    }

    /**
     * Watch the ring after the square is FULL. A status read at drain end is not the answer to "what do the
     * neighbours end at": the dependency fan-out toward the square is still running, and the pacer keeps
     * draining its own queue, which by then holds the nearest ring positions. So sample every tick, and let the
     * two mechanisms show up separately: a chunk that was ever in {@code isPromoting} reached FULL through its
     * own pacer promotion, one that never was reached FULL as somebody else's dependency.
     */
    private static void settleA(MinecraftServer server) {
        settleTicks++;
        sampleA(server);
        settleSamples++;
        fullA = fullCount(centre);
        int outside = outsideFull.size();
        if (outside != settleLastOutside) {
            settleLastOutside = outside;
            settleLastChangeTick = ticks;
        }
        if ((ticks - phaseStart) % 20 == 0) {
            LOGGER.info("rate probe: settle A t+{}: square {}/{}, outside FULL {} ({} of them without a promotion"
                + " of their own, i.e. dragged in), {} pacer start(s), {} queued, {} in flight, backlog {}, tick"
                + " {} ms, enforcing {}", ticks - phaseStart, fullA, expected, outside, outsideFullDependencyCount(),
                PromotionManager.debugLingerStarts(), PromotionManager.debugQueued(level),
                PromotionManager.debugInFlight(level), maxBacklogA, fmt(maxTickMsA), AdaptiveMode.enforcing());
        }
        if (settleTicks >= SETTLE_MAX || ticks - settleLastChangeTick >= SETTLE_QUIET) {
            LOGGER.info("rate probe: settle window closed after {} tick(s) ({} sample(s)); the ring table below is"
                + " the settled one", settleTicks, settleSamples);
            reportA("settled");
            unforce(centre);
            viewer.setPos(centreB.getMiddleBlockX() + 0.5, 200, centreB.getMiddleBlockZ() + 0.5);
            phaseStart = ticks;
            phase = PHASE_ZONE_B;
        }
    }

    private static void zoneB(MinecraftServer server) {
        if (ticks - phaseStart > IDLE_TIMEOUT) {
            finish(server, false, "the zone never followed the player to square B");
            return;
        }
        if (!zoneCovers(centreB) || server.getCurrentSmoothedTickTime() >= brakeMs()) {
            return;
        }
        // a not-enforcing, calm baseline makes "the injected load engaged enforcement" a clean statement, but
        // phase A's own generation can hold enforcement on for a while; wait, then proceed anyway and report it
        if (AdaptiveMode.enforcing() && ticks - phaseStart < B_ENFORCE_WAIT) {
            return;
        }
        LOGGER.info("rate probe: square B: zone ready after {} ticks, calm={}, enforcement={}", ticks - phaseStart,
            AdaptiveMode.windowCalm(), AdaptiveMode.enforcing());
        loadSquare(server, centreB);
        stubsAtLoadB = stubCount(centreB);
        if (stubsAtLoadB == 0) {
            finish(server, false, "square B did not become stubs on the load tick (0 stubs, engaged="
                + ProxyChunkGate.isEngaged(level, centreB.toLong()) + ")");
            return;
        }
        phaseStart = ticks;
        phase = PHASE_LOAD_B;
    }

    private static void loadB(MinecraftServer server) {
        if (stubCount(centreB) < stubsAtLoadB) {
            bAdmissionTicks = ticks - phaseStart;
            bAdmissionCalm = AdaptiveMode.windowCalm();
            PromotionManager.debugResetRateCounters();
            injectionStep = 0;
            injectionStepMs = INJECTION_STEPS_MS[0];
            injectNanos = (long) (injectionStepMs * 1_000_000.0);
            phaseStart = ticks;
            phase = PHASE_SATURATE;
            LOGGER.info("rate probe: square B admitted {} tick(s) after load (calm={}); injecting {} ms/tick and"
                + " ramping until the {} ms brake is reached", bAdmissionTicks, bAdmissionCalm,
                fmt(injectionStepMs), fmt(brakeMs()));
            return;
        }
        if (ticks - phaseStart > B_ADMIT_TIMEOUT) {
            finish(server, false, String.format("square B was never admitted: %d/%d stubs, %s", stubCount(centreB),
                expected, drainState()));
        }
    }

    private static void saturate(MinecraftServer server) {
        double tickMs = server.getCurrentSmoothedTickTime();
        maxTickMsB = Math.max(maxTickMsB, tickMs);
        peakInFlightB = Math.max(peakInFlightB, PromotionManager.debugInFlight(level));
        if (AdaptiveMode.enforcing()) {
            enforcementB = true;
        }
        if (saturateTicks == 0) {
            if (tickMs >= brakeMs()) {
                ticksToBrake = ticks - phaseStart;
                saturateTicks = 1;
                fullAtSaturateStart = fullCount(centreB);
                // baseline for the brake claim: the ramp itself lets starts through while the tick time is still
                // rising, and those are not starts "past the brake". Counting from load instead would blame the
                // pacer for the ramp, which is what the previous revision's `startsB - forcedStartsB > 1` was
                // papering over.
                startsAtBrakeB = PromotionManager.debugLingerStarts();
                forcedAtBrakeB = PromotionManager.debugForcedStarts();
                LOGGER.info("rate probe: smoothed tick time {} ms reached the {} ms brake with {} ms/tick injected"
                    + " after {} ticks ({} start(s) during the ramp)", fmt(tickMs), fmt(brakeMs()),
                    fmt(injectionStepMs), ticksToBrake, startsAtBrakeB);
                return;
            }
            if (ticks - phaseStart >= (injectionStep + 1) * RAMP_TICKS) {
                injectionStep++;
                if (injectionStep >= INJECTION_STEPS_MS.length) {
                    injectNanos = 0L;
                    finish(server, false, String.format("the %s ms brake was never reached: injecting up to %s"
                        + " ms/tick only got smoothed tick time to %s ms", fmt(brakeMs()),
                        fmt(INJECTION_STEPS_MS[INJECTION_STEPS_MS.length - 1]), fmt(tickMs)));
                    return;
                }
                injectionStepMs = INJECTION_STEPS_MS[injectionStep];
                injectNanos = (long) (injectionStepMs * 1_000_000.0);
            }
            return;
        }
        saturateTicks++;
        if (tickMs < brakeMs()) {
            // the pacer reads the same smoothed value (a tick earlier at most): a start is only allowed to slip
            // past a closed brake on a tick the probe saw open, so oscillation around the threshold is fine but
            // a start through a saturating brake is not
            brakeOpenTicksB++;
        }
        if (saturateTicks > SATURATE_HOLD) {
            startsB = PromotionManager.debugLingerStarts();
            brakeBlocksB = PromotionManager.debugBrakeBlocks();
            forcedStartsB = PromotionManager.debugForcedStarts();
            fullAtSaturateEnd = fullCount(centreB);
            injectNanos = 0L;
            LOGGER.info("rate probe: held {} ticks over the brake at {} ms/tick injected: {} brake block(s), {}"
                + " start(s) of which {} from the floor; the brake was open on {} tick(s); square B {}/{} FULL,"
                + " {} -> {} during the hold", SATURATE_HOLD, fmt(injectionStepMs), brakeBlocksB, startsB,
                forcedStartsB, brakeOpenTicksB, fullAtSaturateEnd, expected, fullAtSaturateStart,
                fullAtSaturateEnd);
            phaseStart = ticks;
            phase = PHASE_RECOVER;
            return;
        }
        if (saturateTicks > SATURATE_TIMEOUT) {
            injectNanos = 0L;
            finish(server, false, "the saturated phase never ended");
        }
    }

    private static void recover(MinecraftServer server) {
        fullB = fullCount(centreB);
        if (fullB >= expected) {
            recoverTicks = ticks - phaseStart;
            String failure = sound();
            finish(server, failure == null, failure == null ? verdict() : failure + " | " + verdict());
            return;
        }
        if (ticks - phaseStart > DRAIN_TIMEOUT) {
            injectNanos = 0L;
            finish(server, false, String.format("square B only reached %d/%d FULL after the injected load"
                + " stopped (%d ticks, %s)", fullB, expected, ticks - phaseStart, drainState()));
        }
    }

    /** per-tick samples for phase A, which is where the drain-safety claim and the ring live. */
    private static void sampleA(MinecraftServer server) {
        double tickMs = server.getCurrentSmoothedTickTime();
        maxTickMsA = Math.max(maxTickMsA, tickMs);
        peakInFlightA = Math.max(peakInFlightA, PromotionManager.debugInFlight(level));
        int backlog = backlogNow();
        maxBacklogA = Math.max(maxBacklogA, backlog);
        if (AdaptiveMode.enforcing() && enforceTickA < 0) {
            enforceTickA = ticks - drainStartTick;
            enforceSignalA = enforceReport(server, tickMs, backlog);
        }
        sampleRings();
        if (phase == PHASE_DRAIN_A && (ticks - drainStartTick) % 10 == 0) {
            LOGGER.info("rate probe: drain A t+{}: FULL {}/{}, outside FULL {} ({} dragged in), centre promoting {},"
                + " {} queued, {} in flight, starts {}, brake blocks {}, budget blocks {}, floor {}, backlog {},"
                + " tick {} ms, enforcing {}", ticks - drainStartTick, fullCount(centre), expected,
                outsideFull.size(), outsideFullDependencyCount(),
                ProxyChunkGate.isPromoting(level, centre.toLong()), PromotionManager.debugQueued(level),
                PromotionManager.debugInFlight(level), PromotionManager.debugLingerStarts(),
                PromotionManager.debugBrakeBlocks(), PromotionManager.debugBudgetBlocks(),
                PromotionManager.debugForcedStarts(), backlog, fmt(tickMs), AdaptiveMode.enforcing());
        }
    }

    /**
     * Whether enforcement engaged during the drain is reported, not asserted: the pyramid is real chunk-task
     * backlog, so the drain may legitimately raise the backlog signal and engage. Which signal crossed and its
     * value against the threshold come from the adaptive status line, whose {@code max} figures are the window
     * maxima the decision used; the probe's own per-tick sample can miss the spike, because the window is
     * sampled inside the tick while the pyramid's chunk tasks are still queued. An unparseable status wording
     * falls back to the probe's own maxima rather than failing: this is a report.
     */
    private static String enforceReport(MinecraftServer server, double tickMs, int backlog) {
        String status = AdaptiveMode.status(server, (float) tickMs);
        Matcher backlogMatcher = WINDOW_BACKLOG.matcher(status);
        Matcher tickMatcher = WINDOW_TICK.matcher(status);
        String signal;
        if (backlogMatcher.find() && tickMatcher.find()) {
            int windowBacklog = Integer.parseInt(backlogMatcher.group(1));
            double windowTick = Double.parseDouble(tickMatcher.group(1));
            boolean backlogCrossed = windowBacklog >= AdaptiveMode.backlogActivate();
            boolean tickCrossed = windowTick >= AdaptiveMode.activateTickMs();
            signal = String.format("signal %s crossed (window max backlog %d of %d, window max tick %s of %s ms)",
                backlogCrossed && tickCrossed ? "backlog AND tick time both"
                    : backlogCrossed ? "backlog" : tickCrossed ? "tick time"
                    : "neither at this sample, so a hold or a teleport is keeping it on",
                windowBacklog, AdaptiveMode.backlogActivate(), fmt(windowTick),
                fmt(AdaptiveMode.activateTickMs()));
        } else {
            signal = String.format("status line unparsed, probe maxima so far: backlog %d of %d, tick %s of %s ms",
                backlog, AdaptiveMode.backlogActivate(), fmt(tickMs), fmt(AdaptiveMode.activateTickMs()));
        }
        return signal + "; " + status;
    }

    /**
     * The answer to "what does one promotion drag in": every chunk in the 15x15 around the centre that has
     * reached FULL while sitting outside the stub square, with its ticket level (which says whether vanilla
     * even allows FULL there) and whether the pacer ever had it in flight (its own promotion) or not (vanilla's
     * dependency generation, i.e. a neighbour's range future or ticking requirement).
     */
    private static void sampleRings() {
        for (int dx = -RING_MAX; dx <= RING_MAX; dx++) {
            for (int dz = -RING_MAX; dz <= RING_MAX; dz++) {
                int ring = Math.max(Math.abs(dx), Math.abs(dz));
                if (ring < 1) {
                    continue; // the centre itself is the square
                }
                int x = centre.x + dx;
                int z = centre.z + dz;
                long pos = ChunkPos.asLong(x, z);
                if (ProxyChunkGate.isPromoting(level, pos)) {
                    promotingSeen.add(pos); // its own pacer promotion, whenever it lands
                    continue;
                }
                if (ring <= RADIUS || outsideFull.containsKey(pos)) {
                    continue;
                }
                ChunkHolder holder = holderAt(x, z);
                if (holder == null || holder.getPersistedStatus() != ChunkStatus.FULL) {
                    continue;
                }
                boolean ownPromotion = promotingSeen.contains(pos);
                outsideFull.put(pos, String.format("(%+d,%+d) r=%d ticket=%d %s", dx, dz, ring,
                    holder.getTicketLevel(), ownPromotion ? "own pacer promotion"
                        : "DRAGGED IN (never in flight)"));
                outsideFullTick.put(pos, ticks - drainStartTick);
                if (outsideFullFirstTick < 0) {
                    outsideFullFirstTick = ticks - drainStartTick;
                }
            }
        }
    }

    /** outside the square, FULL without ever having been promoted itself: the footprint, reported not failed on. */
    private static int outsideFullDependencyCount() {
        int dragged = 0;
        for (String note : outsideFull.values()) {
            if (note.contains("DRAGGED IN")) {
                dragged++;
            }
        }
        return dragged;
    }

    /** the per-distance-ring status distribution, sampled fresh so it reads the settled state. */
    private static String ringTable(ChunkPos square) {
        StringBuilder sb = new StringBuilder();
        for (int ring = 0; ring <= RING_MAX; ring++) {
            Map<String, Integer> hist = new TreeMap<>();
            int n = 0;
            int ticketMin = Integer.MAX_VALUE;
            int ticketMax = Integer.MIN_VALUE;
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue;
                    }
                    n++;
                    ChunkHolder holder = holderAt(square.x + dx, square.z + dz);
                    hist.merge(statusName(holder == null ? null : holder.getPersistedStatus()), 1, Integer::sum);
                    if (holder != null) {
                        ticketMin = Math.min(ticketMin, holder.getTicketLevel());
                        ticketMax = Math.max(ticketMax, holder.getTicketLevel());
                    }
                }
            }
            sb.append(String.format("%n  ring %d: n=%d, %s, ticket%s, vanilla status at that ticket is %s,"
                + " reached %s", ring, n, ring <= RADIUS ? "inside the stub square" : "OUTSIDE the stub square",
                ticketMax == Integer.MIN_VALUE ? "s none (no holder)"
                    : (ticketMin == ticketMax ? " " + ticketMax : "s " + ticketMin + ".." + ticketMax),
                statusName(ticketMax == Integer.MIN_VALUE ? null : ChunkLevel.generationStatus(ticketMax)), hist));
        }
        return sb.toString();
    }

    private static void reportA(String when) {
        LOGGER.info("rate probe: square A {} after {} ticks with {} pacer start(s): {} of {} chunks materialised"
            + " to FULL, {} chunk(s) materialised per start (the cap bounds starts, not chunks generated), {}"
            + " chunk(s) outside the square reached FULL at '" + when + "' ({} of them without any promotion of"
            + " their own, first at t+{}; reported, not failed on), peak in flight {} (cap {}), brake blocks {},"
            + " budget blocks {}, floor starts {}", drainGaveUpA ? "gave up" : "reached FULL", drainTicksA,
            startsA, fullA, expected, fmt(startsA > 0 ? (double) fullA / startsA : 0), outsideFull.size(),
            outsideFullDependencyCount(), outsideFullFirstTick < 0 ? -1 : outsideFullFirstTick, peakInFlightA,
            PromotionManager.maxInFlight(), brakeBlocksA, budgetBlocksA, forcedStartsA);
        LOGGER.info("rate probe: square A ring status table at '{}' (ring r = Chebyshev distance r from the"
            + " centre; rings 0..{} are the stub square, {}..{} are OUTSIDE it):{}", when, RADIUS, RADIUS + 1,
            RING_MAX, ringTable(centre));
        for (Map.Entry<Long, String> entry : outsideFull.entrySet()) {
            LOGGER.info("rate probe:   outside FULL {} {} first seen at t+{}", new ChunkPos(entry.getKey()),
                entry.getValue(), outsideFullTick.getOrDefault(entry.getKey(), -1));
        }
        if (enforceTickA >= 0) {
            LOGGER.info("rate probe: enforcement engaged {} ticks into the drain: {}", enforceTickA,
                enforceSignalA);
        } else {
            LOGGER.info("rate probe: enforcement never engaged during the drain (window max backlog {}, max tick"
                + " {} ms)", maxBacklogA, fmt(maxTickMsA));
        }
    }

    /**
     * Every failure, not the first: one failure must not hide the others, and the verdict is where they are
     * read. The enforcement effect is deliberately absent — it is reported, not asserted — and so is the raise
     * outside the stub square, which the footprint makes expected. What is left is the four mechanisms this
     * probe exists for: the drain ran, the calm bypass admitted, the brake blocked starts, and the starvation
     * floor was the only start that got through past the hold.
     */
    private static String sound() {
        List<String> failures = new ArrayList<>();
        if (!admissionCalm) {
            failures.add("square A was admitted without a calm window, so the linger threshold was in play and"
                + " the calm bypass was never exercised");
        }
        if (admissionTicks >= minLingerTicks()) {
            failures.add("square A was admitted " + admissionTicks + " ticks after load, at or past the "
                + minLingerTicks() + "-tick minimum linger threshold, so something other than the calm bypass"
                + " admitted it");
        }
        if (fullA < expected) {
            failures.add(String.format("only %d of %d chunks of square A reached FULL after %d ticks and %d pacer"
                + " start(s) (%s)", fullA, expected, drainTicksA, startsA, drainState()));
        }
        if (startsA == 0) {
            failures.add("square A reached FULL without a single pacer start, so the drain never fired and the"
                + " rate it reported means nothing");
        }
        if (peakInFlightA > PromotionManager.maxInFlight()) {
            failures.add(String.format("the drain ran %d promotion(s) concurrently, over the %d the cap allows",
                peakInFlightA, PromotionManager.maxInFlight()));
        }
        if (fullB < expected) {
            failures.add(String.format("square B only reached %d of %d FULL after the injected load stopped",
                fullB, expected));
        }
        if (peakInFlightB > PromotionManager.maxInFlight()) {
            failures.add(String.format("the injected phase ran %d promotion(s) concurrently, over the %d the cap"
                + " allows", peakInFlightB, PromotionManager.maxInFlight()));
        }
        if (brakeBlocksB == 0) {
            failures.add(String.format("the brake never blocked a start even with %s ms/tick injected and smoothed"
                + " tick time at %s ms, so the brake was not exercised", fmt(injectionStepMs), fmt(maxTickMsB)));
        }
        if (forcedStartsB == 0) {
            failures.add(String.format("over %d ticks saturated at %s ms/tick nothing came from the floor, so the"
                + " starvation floor did not resume progress", saturateTicks - 1, fmt(injectionStepMs)));
        }
        if ((startsB - startsAtBrakeB) - (forcedStartsB - forcedAtBrakeB) > brakeOpenTicksB) {
            failures.add(String.format("%d start(s) got past the brake after it was reached, of which only %d were"
                + " the floor, but the brake was open on only %d tick(s)", startsB - startsAtBrakeB,
                forcedStartsB - forcedAtBrakeB, brakeOpenTicksB));
        }
        if (fullAtSaturateEnd <= fullAtSaturateStart) {
            failures.add(String.format("the floor let through %d start(s) but square B sat at %d/%d FULL for the"
                + " whole %d-tick hold, so the floor did not resume progress", forcedStartsB, fullAtSaturateStart,
                expected, SATURATE_HOLD));
        }
        return failures.isEmpty() ? null : String.join(" | ", failures);
    }

    private static String verdict() {
        double seconds = drainNanosA / 1e9;
        return String.format(
            "A: %d-stub square admitted %d tick(s) after load (calm=%s, min linger threshold %d ticks), %d of %d"
                + " FULL in %d ticks: %d start(s) in %s s = %s starts/s, %s chunk(s) materialised per start (the"
                + " cap bounds starts, not chunks generated), peak in flight %d (cap %d), brake blocks %d, budget"
                + " blocks %d, floor starts %d, peak smoothed tick %s ms against the %s ms brake, post cost"
                + " %s -> %s ms; enforcement during the drain %s; %d chunk(s) OUTSIDE the square reached FULL in"
                + " the drained+%d-tick settle window, %d of them dragged in without any promotion of their own"
                + " (first at t+%d; reported, not failed on); ring table above | B: square admitted %d tick(s)"
                + " after load (calm=%s), %s ms/tick injected, brake reached after %d ticks, over %d saturated"
                + " ticks the brake blocked %d and was open %d, the floor started %d of %d start(s) after the"
                + " brake engaged (square B %d -> %d FULL, max smoothed tick %s ms), enforcement engaged from the"
                + " injected load %s, then %d ticks to finish once the load stopped",
            expected, admissionTicks, admissionCalm, minLingerTicks(), fullA, expected, drainTicksA, startsA,
            fmt(seconds), fmt(seconds > 0 ? startsA / seconds : 0), fmt(startsA > 0 ? (double) fullA / startsA : 0),
            peakInFlightA, PromotionManager.maxInFlight(), brakeBlocksA, budgetBlocksA, forcedStartsA,
            fmt(maxTickMsA), fmt(brakeMs()), fmt(postCostBeforeA), fmt(postCostAfterA), enforcementReportA(),
            outsideFull.size(), settleTicks, outsideFullDependencyCount(),
            outsideFullFirstTick < 0 ? -1 : outsideFullFirstTick, bAdmissionTicks, bAdmissionCalm,
            fmt(injectionStepMs), ticksToBrake,
            saturateTicks - 1, brakeBlocksB, brakeOpenTicksB, forcedStartsB - forcedAtBrakeB,
            startsB - startsAtBrakeB, fullAtSaturateStart, fullAtSaturateEnd, fmt(maxTickMsB), enforcementB,
            recoverTicks);
    }

    /** whether enforcement engaged during the drain, and which window maximum crossed. Never a pass/fail. */
    private static String enforcementReportA() {
        if (enforceTickA < 0) {
            return String.format("never engaged (probe max backlog %d of %d, probe max tick %s of %s ms)",
                maxBacklogA, AdaptiveMode.backlogActivate(), fmt(maxTickMsA), fmt(AdaptiveMode.activateTickMs()));
        }
        return "engaged at t+" + enforceTickA + " into the drain, " + enforceSignalA;
    }

    /** the brake the pacer uses: the tighter of the hard gate and the adaptive activate threshold. */
    private static double brakeMs() {
        double hardGate = PromotionManager.hardGateMs();
        return hardGate <= 0.0 ? AdaptiveMode.activateTickMs()
            : Math.min(hardGate, AdaptiveMode.activateTickMs());
    }

    private static int minLingerTicks() {
        return PromotionManager.lingerMinIdleSeconds() * 20;
    }

    private static int backlogNow() {
        return level.getChunkSource().getPendingTasksCount();
    }

    private static ChunkHolder holderAt(int x, int z) {
        ServerChunkCache cache = (ServerChunkCache) level.getChunkSource();
        return cache.chunkMap.getVisibleChunkIfPresent(ChunkPos.asLong(x, z));
    }

    /** the status a loaded position reached; see the class doc for why this reader and not {@code getChunkNow}. */
    private static ChunkStatus statusAt(int x, int z) {
        ChunkHolder holder = holderAt(x, z);
        return holder == null ? null : holder.getPersistedStatus();
    }

    private static String statusName(ChunkStatus status) {
        return status == null ? "none" : status.getName();
    }

    private static boolean zoneCovers(ChunkPos square) {
        // the corners are the binding case: the zone is a square of view-distance+2 around the player
        return ProxyChunkGate.isEngaged(level, ChunkPos.asLong(square.x - RADIUS, square.z - RADIUS))
            && ProxyChunkGate.isEngaged(level, ChunkPos.asLong(square.x + RADIUS, square.z + RADIUS));
    }

    private static void loadSquare(MinecraftServer server, ChunkPos square) {
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                level.setChunkForced(square.x + dx, square.z + dz, true);
                level.getChunk(square.x + dx, square.z + dz);
            }
        }
    }

    private static void unforce(ChunkPos square) {
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                level.setChunkForced(square.x + dx, square.z + dz, false);
            }
        }
    }

    private static int stubCount(ChunkPos square) {
        int stubs = 0;
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                if (ProxyChunkGate.isProxy(level, ChunkPos.asLong(square.x + dx, square.z + dz))) {
                    stubs++;
                }
            }
        }
        return stubs;
    }

    /** chunks of the square whose generation status actually reached FULL. */
    private static int fullCount(ChunkPos square) {
        int full = 0;
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                if (statusAt(square.x + dx, square.z + dz) == ChunkStatus.FULL) {
                    full++;
                }
            }
        }
        return full;
    }

    /** a square of chunks that are not on disk, so a previous run cannot have generated them. */
    private static ChunkPos freshSquare(ChunkPos spawn, int offsetX) {
        for (int i = 0; i < 96; i++) {
            ChunkPos candidate = new ChunkPos(spawn.x + offsetX + i * 32, spawn.z - offsetX / 2);
            boolean fresh = true;
            for (int dx = -RADIUS; dx <= RADIUS && fresh; dx++) {
                for (int dz = -RADIUS; dz <= RADIUS; dz++) {
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

    private static String drainState() {
        return PromotionManager.debugQueued(level) + " queued/" + PromotionManager.debugInFlight(level);
    }

    private static String fmt(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    private static void finish(MinecraftServer server, boolean pass, String detail) {
        if (pass) {
            LOGGER.info("rate probe: VERDICT PASS — {}", detail);
        } else {
            LOGGER.error("rate probe: VERDICT FAIL — {}", detail);
        }
        injectNanos = 0L;
        PromotionManager.debugResetRateCounters();
        if (level != null) {
            if (centre != null) {
                unforce(centre);
            }
            if (centreB != null) {
                unforce(centreB);
            }
        }
        if (viewer != null) {
            ProbeFakePlayer.unpark(server, viewer);
            AdaptiveMode.removePlayer(viewer.getUUID());
        }
        level = null;
        viewer = null;
        centre = null;
        centreB = null;
        server.halt(false);
    }
}
