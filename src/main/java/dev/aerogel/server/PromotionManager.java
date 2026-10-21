package dev.aerogel.server;

import dev.aerogel.common.AeroGelConfig;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decides when a proxy chunk becomes real terrain, and paces that work so it can never cause lag.
 *
 * <p><b>Linger.</b> Linger accrues for <em>every</em> player near a stub, whether or not their client
 * confirmed the AeroGel channels, whether or not they asked for {@code travel} and whether or not it draws
 * proxies: generating terrain is a server-side decision and needs nobody's consent. Promotion takes no
 * interest in what any individual client renders — {@code AdaptiveMode.renders} gates sending meshes and
 * nothing else.</p>
 *
 * <p>One {@code lingerStart} timestamp per stub is written on first approach and refreshed
 * ({@code lingerSeen}) every tick the stub stays near a player, so a chunk's duration is the subtraction.
 * There is no per-tick counter to maintain and no prune sweep of its own — {@link #refill} retires an
 * entry as it stops being seen. Crossing the distance-dependent threshold
 * {@code t(d) = min + (d/maxRange)*(max - min)}, where {@code d} is the Chebyshev distance to the nearest
 * player and both endpoints are interpolated between idle and busy server load, makes a chunk a
 * <em>candidate</em>.</p>
 *
 * <p><b>Immediate.</b> Touching the surface (descending/landing within {@code landingMargin} above the
 * sampled height), any runtime block write or right-click into a stub, and a Sable physics body asking for the
 * terrain section it is about to hit are not queued at all: the chunk is handed straight to vanilla
 * generation the moment it is triggered, with no budget, hard gate or concurrency cap. That is the "let
 * vanilla do it" path — the only limit is that the same chunk is not started twice while it is still
 * generating, so a mass block write starts one promotion per chunk rather than one per write. These are the
 * <em>only</em> budget bypasses; linger promotion never takes this path, including the drain.</p>
 *
 * <p><b>Rate limiting.</b> Only <em>linger</em> promotion is paced. A token bucket holds a wall-clock
 * budget that refills at {@code maxGenFraction} ms per ms (default 0.5, i.e. at most half the server's
 * time) and each linger promotion start costs {@code chunkCostMs}; linger starts are also blocked while the
 * smoothed tick time is at or above a brake, which is the tighter of {@code hardGateMs} and the adaptive
 * activate threshold so the drain cannot push load back to the level that would re-engage enforcement.
 * {@code maxInFlight} bounds how many linger regenerations run concurrently, and a long dry spell forces one
 * start anyway, which is the floor that guarantees progress on a server that is already lagging. Immediate
 * promotions are deliberately outside all of this: a player action is a player action.</p>
 *
 * <p><b>The queue is a work buffer, not a backlog.</b> Each tick {@link #refill} rebuilds it from the union
 * of what is queued and what just became a candidate, then keeps the nearest {@link State#queueCap}. That
 * makes <b>nearest discards farthest</b> a property of the refill rather than a policy with its own eviction
 * scan, and it is why a dropped chunk needs nothing remembered about it: it is still a stub with its
 * {@code lingerStart} intact, so it re-qualifies on a later pass. Two things are never dropped for capacity:
 * a chunk holding a pending deferred write (a player's edit), and a chunk queued by a direct trigger (a
 * player's action), which has no linger entry and is not the refill's to reorder. Admission also requires a
 * loaded FULL holder, because promotion can only force a future on a holder that exists — AeroGel can never
 * invent a ticket, so a chunk vanilla would not have generated is not a candidate at all.</p>
 *
 * <p><b>Disengagement.</b> While the server is calm the refill bypasses the linger threshold, so every fresh
 * stub is eligible at once and the same pacer decides when each actually starts. The gate's veto is
 * {@link #drained} — queue and in-flight both empty — and it is sound only because the refill runs first:
 * a chunk that would generate on disengagement is already queued by the time the veto is asked.</p>
 *
 * <p><b>Per-dimension.</b> The queue/linger/budget state lives in a {@link State} per {@code ServerLevel};
 * scalar config stays global. Every method resolves the state from the {@link ServerLevel} in scope, so an
 * overworld position is never treated as a proxy in the nether/end.</p>
 *
 * <p><b>In-flight retry.</b> A promotion handed to {@link ProxyChunkGate#promote} stays in the in-flight set
 * until the chunk is genuinely real. If its completion callback never fires, {@link #retryInFlight} drives the
 * same chunk again every {@link #IN_FLIGHT_RETRY_TICKS} without releasing the slot — <b>it never concludes
 * that a generation finished because time passed</b>. Releasing the slot on a timer would let the next tick
 * start another generation on top of one still running, which is the wrong direction for a server that is
 * already behind; a stuck chunk holding its slot is loud in the log, whereas an in-flight count drifting below
 * the number of generations actually running is not.</p>
 *
 * <p>All state is touched on the server thread (tick / player-tick / block-write events).</p>
 */
public final class PromotionManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("aerogel.promote");

    /** the FULL ticket level minus the player ticket level: how far past view-distance vanilla generates FULL. */
    private static final int FULL_SLACK = ChunkLevel.byStatus(FullChunkStatus.FULL)
        - ChunkLevel.byStatus(FullChunkStatus.ENTITY_TICKING);
    private static final int MIN_LINGER_TICKS = 20;
    private static final int MAX_LINGER_TICKS_LIMIT = 20 * 600;
    private static final int LINGER_GRACE_TICKS = 40;
    private static final int MAX_WRITE_LOG = 256;

    /**
     * Depth of vanilla-initiated writes on this thread (#16). Set by {@code VanillaChunkWriteMixin} around
     * post-generation cleanup and by {@code VanillaBlockTickMixin} around the block-tick queue -- the two
     * vanilla paths that reach {@code Level.setBlock} after a chunk stopped being a stub. A depth counter
     * rather than a flag because those run on chunk-task workers and the server thread at the same time.
     *
     * <p><b>WHITE-BOX.</b> This works by hooking the vanilla paths that write into a chunk after generation
     * reports done; the flag is only correct while that list stays complete. A vanilla update adding a third
     * such path puts those writes in the caller queue, where they replay on top of the caller's again and
     * silently erase blocks -- and the promote probe will not catch it unless the test chunk happens to carry
     * vegetation at the probe's write height. If that shows up, turn on
     * {@link #refuseStubWrites} ({@code /aerogel promote refuse true}) instead of extending the hook: refusing
     * writes has no detection to keep in sync.</p>
     */
    private static final ThreadLocal<int[]> VANILLA_WRITES = ThreadLocal.withInitial(() -> new int[1]);

    /** called by the mixins; pair every call with {@link #popVanillaWrite()}. */
    public static void pushVanillaWrite() {
        VANILLA_WRITES.get()[0]++;
    }

    public static void popVanillaWrite() {
        int[] depth = VANILLA_WRITES.get();
        if (depth[0] > 0) {
            depth[0]--;
        }
    }

    /** true when the write in flight is vanilla fixing up a chunk rather than a caller changing it. */
    static boolean isVanillaWrite() {
        return VANILLA_WRITES.get()[0] > 0;
    }
    private static final int MAX_RANGE = 64;
    private static final byte NO_DISTANCE = (byte) 127;
    /** how long (ticks) a queued chunk may go with no player within range before it is dropped. */
    private static final int QUEUE_GRACE_TICKS = 200;
    /** how often the queue proximity/eviction sweep runs (ticks). */
    private static final int QUEUE_SWEEP_INTERVAL = 20;
    /**
     * How often a promotion whose completion callback has not fired is driven again. Not a timeout: nothing
     * is concluded when it elapses, the slot is never released on this signal alone, and the chunk is retried
     * until it is genuinely real. See {@link #retryInFlight}.
     */
    private static int inFlightRetryTicks = 300;
    /** total retries issued, so a probe can tell the retry path actually ran. */
    private static int inFlightRetries;
    /**
     * slots {@link #retryInFlight} freed for a real chunk no longer promoting that no completion had cleared:
     * with the completions drained before this tick, each one is an anomaly (#20).
     */
    private static int inFlightReclaims;
    /**
     * The pacer's decisions since {@link #debugResetRateCounters}, so a probe can tell a drain that was paced
     * by the budget from one that was paced by the concurrency cap, and see the brake and the starvation floor
     * bite instead of inferring them from timing.
     */
    private static int lingerStarts;
    private static int brakeBlocks;
    private static int budgetBlocks;
    private static int forcedStarts;
    /** if no promotion has started for this many ticks, start one even under budget/hard-gate pressure. */
    private static final int FORCE_START_TICKS = 200;
    /**
     * How many queued chunks beyond the in-flight set the refill will hold. The queue is a <b>work buffer,
     * not a backlog</b>: it only has to cover what the drain can consume between two refills, and the refill
     * runs every tick against a set rebuilt from the nearest candidates. 64 is a realistic linger burst
     * (an 8x8 of chunks crossing their threshold together) absorbed without churn; the scans over it are
     * O(n) map walks whose cost is nothing at this size. Deliberately not derived from a drain rate — that
     * would couple queue depth to a load-derived number, which is a feedback loop inside the component whose
     * whole job is to break feedback loops.
     */
    private static final int QUEUE_HEADROOM = 64;
    /** how long a server stop may spend finishing chunks with deferred writes (#6). */
    // ponytail: fixed 10 s; a config knob if a big world ever stops with hundreds of pending chunks
    private static final long STOP_FINISH_NANOS = 10_000_000_000L;

    private static final Map<ResourceKey<Level>, State> STATES = new HashMap<>();

    private static int maxRange = -1;
    private static int lingerMinIdleTicks = 200;
    private static int lingerMinBusyTicks = 400;
    private static int lingerMaxIdleTicks = 600;
    private static int lingerMaxBusyTicks = 1200;
    private static double landingMargin = 8.0;
    private static boolean refuseStubWrites;
    private static int maxInFlight = 2;
    private static double maxGenFraction = 0.5;
    private static double chunkCostMs = 100.0;
    private static double burstMs = 200.0;
    private static double hardGateMs = 60.0;
    private static double replayBudgetMs = 1.5;
    private static double idleTickMs = 10.0;
    private static double busyTickMs = 45.0;
    private static boolean enabled = true;
    private static long lastStatusLog;
    /** the chunk whose deferred writes are being replayed right now: its own writes must not re-defer. */
    private static long replayingPos;
    private static boolean replaying;

    /** per-{@code ServerLevel} promotion state. */
    private static final class State {
        final ServerLevel level;
        /**
         * chunk -> the tick its linger began, written on first approach and never rewritten while the player
         * stays. Duration is {@code lingerSeen - lingerStart}: there is no per-tick counter increment and no
         * periodic prune sweep, because an entry that stops being seen is dropped by the scan that reads it.
         */
        final Long2IntOpenHashMap lingerStart = new Long2IntOpenHashMap();
        /** chunk -> last tick a player was within range; also the expiry clock for {@link #lingerStart}. */
        final Long2IntOpenHashMap lingerSeen = new Long2IntOpenHashMap();
        /** per-tick scratch: chunk -> minimum Chebyshev distance to a player (this tick only). */
        final Long2ByteOpenHashMap occupied = new Long2ByteOpenHashMap();
        /** queued chunks awaiting a free slot: chunk pos -> tick it was last within range of a player. */
        final Long2IntOpenHashMap queue = new Long2IntOpenHashMap();

        // refill scratch. The refill runs every tick and its inputs are bounded (the queue by
        // queueCap, the candidates by the linger maps), so all three are reused rather than reallocated.
        /** union of the current queue and this tick's candidates: chunk pos -> Chebyshev distance. */
        final Long2IntOpenHashMap unionScratch = new Long2IntOpenHashMap();
        /** the union's keys and distances, held in parallel so they can be partially sorted in place. */
        long[] unionPos = new long[64];
        int[] unionDist = new int[64];

        /** chunks handed to {@link ProxyChunkGate#promote} whose completion callback has not fired yet. */
        final LongOpenHashSet inFlight = new LongOpenHashSet();
        /** in-flight chunk -> tick it was started, so a stuck future can be timed out. */
        final Long2LongOpenHashMap inFlightSince = new Long2LongOpenHashMap();
        /** chunk -> writes cancelled while it was a proxy or generating, replayed in order once it is real. */
        final Long2ObjectOpenHashMap<WriteLog> deferredWrites = new Long2ObjectOpenHashMap<>();
        /** per-tick scratch: chunks whose write log can be replayed now. */
        final LongArrayList replayScratch = new LongArrayList();
        /** reused per tick so the tick path does not allocate a fresh player list. */
        final List<ServerPlayer> playersScratch = new ArrayList<>();
        /** how many chunks the refill will hold: {@code maxInFlight} plus {@link #QUEUE_HEADROOM}. */
        int queueCap = QUEUE_HEADROOM;
        /** tick the last promotion started; drives the progress guarantee (see FORCE_START_TICKS). */
        int lastStartTick;

        /** generation-time budget in ms (may go negative when immediate work overspends it). */
        double budgetMs;
        long lastRefillNanos;
        int tickCounter;

        State(ServerLevel level) {
            this.level = level;
            occupied.defaultReturnValue(NO_DISTANCE);
            this.budgetMs = burstMs;
        }
    }

    private PromotionManager() {
    }

    public static void init(ServerLevel lvl) {
        AeroGelConfig cfg = AeroGelConfig.CFG;
        enabled = cfg.promotionEnabled.get();
        maxRange = cfg.maxRange.get();
        lingerMinIdleTicks = cfg.lingerMinIdleSeconds.get() * 20;
        lingerMinBusyTicks = cfg.lingerMinBusySeconds.get() * 20;
        lingerMaxIdleTicks = cfg.lingerMaxIdleSeconds.get() * 20;
        lingerMaxBusyTicks = cfg.lingerMaxBusySeconds.get() * 20;
        landingMargin = cfg.landingMargin.get();
        refuseStubWrites = cfg.refuseStubWrites.get();
        maxInFlight = cfg.maxInFlight.get();
        maxGenFraction = cfg.maxGenFraction.get();
        chunkCostMs = cfg.chunkCostMs.get();
        burstMs = cfg.burstMs.get();
        hardGateMs = cfg.hardGateMs.get();
        replayBudgetMs = cfg.replayBudgetMs.get();
        idleTickMs = cfg.idleTickMs.get();
        busyTickMs = cfg.busyTickMs.get();
        State state = new State(lvl);
        state.queueCap = maxInFlight + QUEUE_HEADROOM;
        STATES.put(lvl.dimension(), state);
        LOGGER.info("promotion active for {}: linger {}..{}s (idle) / {}..{}s (busy) out to {} chunks, "
                + "landing {} blocks, concurrency {}, budget {} ms/s, cost {} ms",
            lvl.dimension().location(),
            lingerMinIdleTicks / 20, lingerMaxIdleTicks / 20, lingerMinBusyTicks / 20, lingerMaxBusyTicks / 20,
            effectiveMaxRange(lvl.getServer()), landingMargin, maxInFlight,
            (int) (maxGenFraction * 1000), chunkCostMs);
    }

    public static void shutdown() {
        STATES.clear();
    }

    private static State state(Level lvl) {
        return lvl == null ? null : STATES.get(lvl.dimension());
    }

    private static State state(ServerLevel lvl) {
        return lvl == null ? null : STATES.get(lvl.dimension());
    }

    // ---- tuning (each setter persists to the COMMON config) ----

    public static boolean enabled() {
        return enabled;
    }

    public static void setEnabled(boolean on) {
        enabled = on;
        AeroGelConfig.CFG.promotionEnabled.set(on);
        AeroGelConfig.SPEC.save();
    }

    public static int maxRangeSetting() {
        return maxRange;
    }

    public static void setMaxRange(int chunks) {
        maxRange = clamp(chunks, -1, 64);
        AeroGelConfig.CFG.maxRange.set(maxRange);
        AeroGelConfig.SPEC.save();
    }

    public static void setLingerSeconds(int minIdle, int minBusy, int maxIdle, int maxBusy) {
        lingerMinIdleTicks = clamp(minIdle * 20, MIN_LINGER_TICKS, MAX_LINGER_TICKS_LIMIT);
        lingerMinBusyTicks = clamp(Math.max(minIdle, minBusy) * 20, lingerMinIdleTicks, MAX_LINGER_TICKS_LIMIT);
        lingerMaxIdleTicks = clamp(Math.max(minBusy, maxIdle) * 20, lingerMinBusyTicks, MAX_LINGER_TICKS_LIMIT);
        lingerMaxBusyTicks = clamp(Math.max(maxIdle, maxBusy) * 20, lingerMaxIdleTicks, MAX_LINGER_TICKS_LIMIT);
        AeroGelConfig.CFG.lingerMinIdleSeconds.set(lingerMinIdleTicks / 20);
        AeroGelConfig.CFG.lingerMinBusySeconds.set(lingerMinBusyTicks / 20);
        AeroGelConfig.CFG.lingerMaxIdleSeconds.set(lingerMaxIdleTicks / 20);
        AeroGelConfig.CFG.lingerMaxBusySeconds.set(lingerMaxBusyTicks / 20);
        AeroGelConfig.SPEC.save();
    }

    public static int lingerMinIdleSeconds() {
        return lingerMinIdleTicks / 20;
    }

    public static int lingerMinBusySeconds() {
        return lingerMinBusyTicks / 20;
    }

    public static int lingerMaxIdleSeconds() {
        return lingerMaxIdleTicks / 20;
    }

    public static int lingerMaxBusySeconds() {
        return lingerMaxBusyTicks / 20;
    }

    public static double landingMargin() {
        return landingMargin;
    }

    public static void setLandingMargin(double blocks) {
        landingMargin = Math.clamp(blocks, 0.0, 64.0);
        AeroGelConfig.CFG.landingMargin.set(landingMargin);
        AeroGelConfig.SPEC.save();
    }

    /** true when the server owner opted into refusing (not deferring) writes into a non-real chunk (#16). */
    public static boolean refusesStubWrites() {
        return refuseStubWrites;
    }

    /** Dev hook: {@link #setRefuseStubWrites} without persisting it, so a probe cannot leave it set. */
    public static void debugSetRefuseStubWrites(boolean on) {
        refuseStubWrites = on;
    }

    public static void setRefuseStubWrites(boolean on) {
        refuseStubWrites = on;
        AeroGelConfig.CFG.refuseStubWrites.set(on);
        AeroGelConfig.SPEC.save();
    }

    public static int maxInFlight() {
        return maxInFlight;
    }

    public static void setMaxInFlight(int count) {
        maxInFlight = clamp(count, 1, 16);
        AeroGelConfig.CFG.maxInFlight.set(maxInFlight);
        AeroGelConfig.SPEC.save();
    }

    public static double maxGenFraction() {
        return maxGenFraction;
    }

    public static void setMaxGenFraction(double fraction) {
        maxGenFraction = Math.clamp(fraction, 0.05, 1.0);
        AeroGelConfig.CFG.maxGenFraction.set(maxGenFraction);
        AeroGelConfig.SPEC.save();
    }

    public static double chunkCostMs() {
        return chunkCostMs;
    }

    public static void setChunkCostMs(double ms) {
        chunkCostMs = Math.clamp(ms, 1.0, 5000.0);
        AeroGelConfig.CFG.chunkCostMs.set(chunkCostMs);
        AeroGelConfig.SPEC.save();
    }

    public static double hardGateMs() {
        return hardGateMs;
    }

    public static void setHardGateMs(double ms) {
        hardGateMs = Math.clamp(ms, 0.0, 1000.0);
        AeroGelConfig.CFG.hardGateMs.set(hardGateMs);
        AeroGelConfig.SPEC.save();
    }

    public static double replayBudgetMs() {
        return replayBudgetMs;
    }

    public static void setReplayBudgetMs(double ms) {
        replayBudgetMs = Math.clamp(ms, 0.1, 50.0);
        AeroGelConfig.CFG.replayBudgetMs.set(replayBudgetMs);
        AeroGelConfig.SPEC.save();
    }

    /** the linger range actually in use (resolves the auto value). */
    public static int effectiveMaxRange(MinecraftServer server) {
        if (maxRange > 0) {
            return Math.min(maxRange, MAX_RANGE);
        }
        int view = server == null ? 10 : server.getPlayerList().getViewDistance();
        return Math.min(view + FULL_SLACK, MAX_RANGE);
    }

    /**
     * True when nothing is left for the promotion path to do: no chunk is generating and the queue is empty.
     *
     * <p>That is a complete answer <b>only because of the order of a tick</b>, and the order is load-bearing:
     * the window is sampled first, then {@link #refill} runs, and only then is this asked. The refill below
     * admits every fresh in-range stub the gate could promote, so an empty queue after it can only mean there
     * was nothing left to fill from — which is why this needs no separate count of "chunks still gated in
     * range". If this were asked before the refill, or the refill and the enforcement decision read different
     * window maxima, it would report "drained" while gated stubs sat there and the gate would drop on them.
     * See {@code AdaptiveMode.Window.sample} for the tick where that used to happen.</p>
     *
     * <p>It does not degenerate the way the old {@code hasLinger} veto did: that was a predicate on the linger
     * maps, which is "there are players" once linger is universal. This is about real outstanding work, and it
     * reaches zero because the drain finishes it.</p>
     */
    public static boolean drained() {
        for (State state : STATES.values()) {
            if (!state.queue.isEmpty() || !state.inFlight.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** {@code queued/in-flight}, summed over levels, for the status lines the veto is read on. */
    public static String drainState() {
        int queued = 0;
        int inFlight = 0;
        for (State state : STATES.values()) {
            queued += state.queue.size();
            inFlight += state.inFlight.size();
        }
        return queued + "/" + inFlight;
    }

    /** effective linger threshold in ticks at distance {@code distance} and the current load. */
    public static int thresholdTicks(int distance, int range, float tickMs) {
        double load = loadFactor(tickMs);
        double min = lingerMinIdleTicks + (lingerMinBusyTicks - lingerMinIdleTicks) * load;
        double max = lingerMaxIdleTicks + (lingerMaxBusyTicks - lingerMaxIdleTicks) * load;
        return thresholdTicks(distance, range, min, max);
    }

    /** the same interpolation with the two load-adaptive endpoints already resolved (computed once a tick). */
    private static int thresholdTicks(int distance, int range, double min, double max) {
        double t = min + (range <= 0 ? 0.0 : (double) distance / range) * (max - min);
        return (int) t;
    }

    public static String status(MinecraftServer server, float tickMs) {
        int range = effectiveMaxRange(server);
        int queued = 0;
        int inFlight = 0;
        for (State state : STATES.values()) {
            queued += state.queue.size();
            inFlight += state.inFlight.size();
        }
        return "promote: " + (enabled ? "on" : "off")
            + ", range " + range + "ch, linger " + lingerMinIdleSeconds() + ".." + lingerMaxIdleSeconds() + "s idle / "
            + lingerMinBusySeconds() + ".." + lingerMaxBusySeconds() + "s busy"
            + " (now d0 " + fmt(thresholdTicks(0, range, tickMs) / 20.0) + "s, d" + range + " "
            + fmt(thresholdTicks(range, range, tickMs) / 20.0) + "s @ " + fmt(tickMs) + " ms)"
            + ", landing " + fmt(landingMargin) + " blocks"
            + ", concurrency " + maxInFlight + ", budget " + fmt(budgetOfFirst()) + "/" + (int) (maxGenFraction * 1000) + " ms/s"
            + ", cost " + fmt(chunkCostMs) + " ms, hard gate " + fmt(hardGateMs) + " ms"
            + ", replay " + fmt(replayBudgetMs) + " ms/tick"
            + ", queued " + queued + ", in flight " + inFlight;
    }

    private static double budgetOfFirst() {
        for (State state : STATES.values()) {
            return state.budgetMs;
        }
        return 0.0;
    }

    private static String fmt(double value) {
        return String.format("%.1f", value);
    }

    // ---- tick ----

    public static void tick(MinecraftServer server) {
        // one wall-time budget per tick for all levels' deferred-write replay (#5)
        long deadline = System.nanoTime() + (long) (replayBudgetMs * 1_000_000.0);
        for (State state : STATES.values()) {
            replayWrites(state, deadline);
            tickState(server, state);
        }
    }

    private static void tickState(MinecraftServer server, State state) {
        state.tickCounter++;
        if (!enabled) {
            return;
        }
        float tickMs = server.getCurrentSmoothedTickTime();
        int range = effectiveMaxRange(server);
        state.queueCap = maxInFlight + QUEUE_HEADROOM;
        double load = loadFactor(tickMs);
        double minTicks = lingerMinIdleTicks + (lingerMinBusyTicks - lingerMinIdleTicks) * load;
        double maxTicks = lingerMaxIdleTicks + (lingerMaxBusyTicks - lingerMaxIdleTicks) * load;
        List<ServerPlayer> players = state.playersScratch;
        levelPlayers(server, state.level, players);
        refillBudget(state);
        retryInFlight(state);
        // nothing to linger over when the level has no proxies: the per-player scan is skipped, but the refill
        // below still runs, because retiring a departed player's linger entries is half of what it does.
        if (ProxyChunkGate.mayHaveProxy(state.level)) {
            if (!players.isEmpty()) {
                accumulateLinger(state, players, range);
            }
            // The refill runs BEFORE anything reads the queue as the disengage veto, and while the server is
            // calm it bypasses the linger threshold so every fresh stub is eligible at once. That ordering is
            // the whole reason "queue empty" can only mean "nothing left to generate": a chunk that would
            // generate on disengagement is in the queue by the time the veto is asked. It also runs with no
            // players present, because retiring a departed player's linger entries is half of the same pass.
            refill(state, players, range, AdaptiveMode.windowCalm(), minTicks, maxTicks);
        }
        if (state.tickCounter % 100 == 0 && System.currentTimeMillis() - lastStatusLog > 30_000) {
            lastStatusLog = System.currentTimeMillis();
            LOGGER.info(status(server, tickMs));
        }
        if (state.tickCounter % QUEUE_SWEEP_INTERVAL == 0) {
            sweepQueue(state, players, range);
        }
        processQueue(state, players, tickMs);
    }

    private static void refillBudget(State state) {
        long now = System.nanoTime();
        if (state.lastRefillNanos == 0L) {
            state.lastRefillNanos = now;
            return;
        }
        double elapsedMs = (now - state.lastRefillNanos) / 1_000_000.0;
        state.lastRefillNanos = now;
        state.budgetMs = Math.min(burstMs, state.budgetMs + elapsedMs * maxGenFraction);
    }

    /** refill a per-state scratch list (avoids a fresh allocation every tick). */
    private static void levelPlayers(MinecraftServer server, ServerLevel level, List<ServerPlayer> out) {
        out.clear();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.level() == level && !player.hasDisconnected()) {
                out.add(player);
            }
        }
    }

    /**
     * One pass over the range of every player. Linger accrues for all of them: whether or not a client
     * confirmed the AeroGel channels, whether or not it asked for {@code travel} and whether or not it draws
     * proxies, generation is a server-side decision and promotion takes no interest in what any client draws.
     *
     * <p>Duration is {@code lingerSeen - lingerStart}, so this loop writes a timestamp and nothing else: the
     * clock starts on first approach and is never rewritten while the player stays. That is what makes the
     * duration monotone without a per-tick counter to maintain.</p>
     */
    private static void accumulateLinger(State state, List<ServerPlayer> players, int range) {
        state.occupied.clear();
        for (ServerPlayer player : players) {
            ChunkPos center = player.chunkPosition();
            for (int dx = -range; dx <= range; dx++) {
                for (int dz = -range; dz <= range; dz++) {
                    long pos = ChunkPos.asLong(center.x + dx, center.z + dz);
                    if (!ProxyChunkGate.isProxy(state.level, pos)) {
                        continue;
                    }
                    int dist = Math.max(Math.abs(dx), Math.abs(dz));
                    byte existing = state.occupied.get(pos);
                    if (dist < existing) {
                        state.occupied.put(pos, (byte) dist);
                        if (existing == NO_DISTANCE) {
                            // First approach: start the clock. putIfAbsent, not put -- a chunk at the edge of
                            // range that oscillates in and out must not have its banked duration reset every
                            // time it drops out for a tick, or it could never reach its threshold.
                            state.lingerStart.putIfAbsent(pos, state.tickCounter);
                        }
                    }
                    state.lingerSeen.put(pos, state.tickCounter);
                }
            }
        }
    }

    /**
     * The single pass over the linger map, per tick: retire what has stopped being seen and admit what has
     * qualified. <b>One scan does both</b>, because the two are the same decision — an entry is either still
     * near a player, and therefore a candidate, or it is not, and therefore finished. Splitting them means
     * walking the same map twice and having to keep the two halves in agreement; a chunk's linger is retired
     * by the same pass that would have enqueued it, and by nothing else. Distance has no hand in it: it
     * decides the <em>threshold</em>, never whether an entry lives or its clock restarts.
     *
     * <p>With no players in the level nothing refreshes {@code lingerSeen}, so every entry ages out here and
     * the pass admits nothing — which is why it runs whether or not anyone is online, and why the stale case
     * is not a separate sweep.</p>
     *
     * <p>While the server is <b>calm</b> the linger threshold is bypassed: every stub that is still fresh is
     * eligible immediately, because the gate is trying to reach a state where it can disengage and a stub it
     * cannot disengage past is the thing in the way. The budget is <em>not</em> bypassed — see
     * {@link #processQueue} — so the threshold is the only thing this skips.</p>
     *
     * <p>The queue is rebuilt from the union of what is already queued and this tick's candidates, then cut
     * to {@link State#queueCap}. That makes <b>nearest discards farthest</b> a property of the refill rather
     * than a policy with its own eviction scan, and it is why nothing has to be remembered about a chunk that
     * was cut: it is still a stub with its {@code lingerStart} intact, so it re-qualifies on a later pass if
     * the player is still near it.</p>
     */
    private static void refill(State state, List<ServerPlayer> players, int range, boolean calm,
        double minTicks, double maxTicks) {
        Long2IntOpenHashMap union = state.unionScratch;
        union.clear();
        // Seed with the queued chunks the refill owns, so a nearer candidate can displace a farther queued
        // one. A chunk queued by a direct trigger -- a write or right-click whose holder was not loaded, so
        // promoteImmediate fell back to the queue -- has no linger entry and is deliberately not seeded: it is
        // a player's action, not the refill's to reorder or drop.
        if (!players.isEmpty()) {
            for (var entry : state.queue.long2IntEntrySet()) {
                long pos = entry.getLongKey();
                if (state.lingerStart.containsKey(pos)) {
                    union.put(pos, nearestDistance(pos, players));
                }
            }
        }
        var it = state.lingerStart.long2IntEntrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            long pos = entry.getLongKey();
            if (state.tickCounter - state.lingerSeen.get(pos) > LINGER_GRACE_TICKS) {
                // Stale: retire it here rather than in a sweep of its own. A chunk holding a deferred write
                // keeps its clock, because that banked duration is the only thing between a player's block
                // placement and being re-lingered from zero.
                if (!state.deferredWrites.containsKey(pos)) {
                    it.remove();
                    state.lingerSeen.remove(pos);
                }
                continue;
            }
            if (players.isEmpty() || state.queue.containsKey(pos) || state.inFlight.contains(pos)) {
                continue;
            }
            if (!ProxyChunkGate.isProxy(state.level, pos)) {
                continue; // became real by another route (dependency generation, vanilla's own pipeline)
            }
            if (!ProxyChunkGate.hasHolder(state.level, pos)) {
                // No holder whose ticket reaches FULL. Promotion allow-lists a position and forces that
                // holder's FULL future, so AeroGel can never invent a ticket: this chunk is one vanilla would
                // not have generated either, and admitting it would only occupy a slot it can never use.
                continue;
            }
            // the walk above already recorded this tick's distance for every in-range stub; only an entry
            // that is fresh but no longer in range needs the scan
            int distance = state.occupied.get(pos);
            if (distance == NO_DISTANCE) {
                distance = nearestDistance(pos, players);
            }
            if (!calm && state.lingerSeen.get(pos) - entry.getIntValue()
                < thresholdTicks(distance, range, minTicks, maxTicks)) {
                continue;
            }
            union.put(pos, distance);
        }
        // With nobody in the level there is nothing to admit and nothing to cut back to, and applying an
        // empty union would clear the queue of chunks that are already draining. The walk above has done the
        // expiry either way, which is the half that has to happen with no players.
        if (players.isEmpty()) {
            return;
        }

        int cap = state.queueCap;
        int n = spread(union, state);
        // partial selection sort: the first min(cap, n) entries are the nearest, for O(n * cap) comparisons
        int take = Math.min(cap, n);
        for (int i = 0; i < take; i++) {
            int best = i;
            for (int j = i + 1; j < n; j++) {
                if (state.unionDist[j] < state.unionDist[best]) {
                    best = j;
                }
            }
            if (best != i) {
                long p = state.unionPos[i];
                state.unionPos[i] = state.unionPos[best];
                state.unionPos[best] = p;
                int d = state.unionDist[i];
                state.unionDist[i] = state.unionDist[best];
                state.unionDist[best] = d;
            }
        }
        // Apply the cut. Only a linger-driven entry is the refill's to drop: a direct trigger or a chunk
        // holding a player's deferred write is never removed for capacity.
        for (var e = state.queue.long2IntEntrySet().iterator(); e.hasNext();) {
            long pos = e.next().getLongKey();
            if (!state.lingerStart.containsKey(pos) || state.deferredWrites.containsKey(pos)) {
                continue;
            }
            boolean kept = false;
            for (int i = 0; i < take; i++) {
                if (state.unionPos[i] == pos) {
                    kept = true;
                    break;
                }
            }
            if (!kept) {
                e.remove();
            }
        }
        for (int i = 0; i < take; i++) {
            long pos = state.unionPos[i];
            if (!state.queue.containsKey(pos)) {
                state.queue.put(pos, state.tickCounter);
            }
        }
    }

    /** copy the union's keys and distances into the parallel scratch arrays, growing them if needed. */
    private static int spread(Long2IntOpenHashMap union, State state) {
        if (state.unionPos.length < union.size()) {
            int size = Math.max(union.size(), state.unionPos.length * 2);
            state.unionPos = new long[size];
            state.unionDist = new int[size];
        }
        int i = 0;
        for (var entry : union.long2IntEntrySet()) {
            state.unionPos[i] = entry.getLongKey();
            state.unionDist[i] = entry.getIntValue();
            i++;
        }
        return i;
    }

    /**
     * Retry promotions whose completion callback has not fired, and clear the ones that finished anyway.
     *
     * <p><b>This must not assume a promotion is done because time has passed.</b> Releasing the slot is what
     * makes that dangerous: {@code inFlight} is what {@link #processQueue} bounds concurrency on, so freeing a
     * slot while a generation is still running lets the next tick start another one on top of it — concurrency
     * climbs past {@code maxInFlight} precisely when the server is already hanging. So the entry stays and so
     * does the slot, and the gate is asked to drive the same chunk again; it is cleared only once the chunk is
     * genuinely real. {@code ProxyChunkGate.promote} re-runs {@code forcePrepare}, so asking again is a real
     * re-drive rather than the no-op {@code promoteImmediate} would be for an already-allowed position.</p>
     *
     * <p>The completions are drained before this runs ({@code ProxyChunkGate.drainCompletions}), so a slot
     * whose promotion finished normally is already free here; a reclaim, counted and logged, is the anomaly.
     * A lost callback leaves the position promoting and is recovered by the re-drive, not the reclaim.</p>
     *
     * <p>The trade: a chunk whose generation is genuinely stuck now holds its concurrency slot and keeps being
     * retried, instead of quietly letting the in-flight count drift below the number of generations actually
     * running. A stall is loud in the log; amplification is not.</p>
     */
    private static void retryInFlight(State state) {
        if (state.inFlightSince.isEmpty()) {
            return;
        }
        var it = state.inFlightSince.long2LongEntrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            long pos = entry.getLongKey();
            // FULL status (what isLoadedReal reads) lands before the FULL future the callback waits on, which
            // also waits for the 3x3 around it, so a real chunk still promoting is not evidence of a lost
            // callback: it is left to the retry timer below, whose re-drive registers a fresh one (#20)
            if (ProxyChunkGate.isLoadedReal(state.level, pos) && !ProxyChunkGate.isPromoting(state.level, pos)) {
                // real and nothing pending, yet no completion cleared the slot: it is genuinely free now
                it.remove();
                state.inFlight.remove(pos);
                inFlightReclaims++;
                LOGGER.warn("promotion of proxy chunk {} finished without clearing its slot; reclaimed ({} so far)",
                    new ChunkPos(pos), inFlightReclaims);
                continue;
            }
            if (state.tickCounter - entry.getLongValue() < inFlightRetryTicks) {
                continue;
            }
            entry.setValue(state.tickCounter);
            inFlightRetries++;
            WriteLog log = state.deferredWrites.get(pos);
            LOGGER.warn("promotion of proxy chunk {} has not completed after {} s ({} tick(s)), {} deferred"
                    + " write(s); asking again, slot stays occupied",
                new ChunkPos(pos), inFlightRetryTicks / 20, inFlightRetryTicks, log == null ? 0 : log.size());
            try {
                if (!ProxyChunkGate.promote(state.level, new ChunkPos(pos), () -> onComplete(state, pos))) {
                    // most likely no holder reaching FULL any more. Leave the entry so the next pass retries
                    // rather than forgetting the chunk while its generation may still be running.
                    LOGGER.warn("re-drive of proxy chunk {} was refused; it stays in flight and is retried",
                        new ChunkPos(pos));
                }
            } catch (Throwable t) {
                LOGGER.error("failed to re-drive regeneration of proxy chunk {}", new ChunkPos(pos), t);
            }
        }
    }

    /**
     * Backstop for queued chunks no player has been within {@code range} of for {@link #QUEUE_GRACE_TICKS}.
     * The refill already drops a queued chunk whose linger has expired, so this only catches entries that
     * have no linger entry at all — a chunk re-queued by {@code processQueue}'s failure path, which never
     * went through the refill. A chunk with a pending deferred write is never dropped; its clock is
     * refreshed instead. Runs periodically, not every tick.
     */
    private static void sweepQueue(State state, List<ServerPlayer> players, int range) {
        if (state.queue.isEmpty()) {
            return;
        }
        int now = state.tickCounter;
        var it = state.queue.long2IntEntrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            long pos = entry.getLongKey();
            if (nearestDistance(pos, players) <= range) {
                entry.setValue(now);
                continue;
            }
            if (now - entry.getIntValue() <= QUEUE_GRACE_TICKS) {
                continue;
            }
            if (state.deferredWrites.containsKey(pos)) {
                entry.setValue(now);
                continue;
            }
            it.remove();
        }
    }

    /** touch trigger: called from the streamer's player tick once the proxy surface is known. */
    public static void onPlayerNearSurface(ServerPlayer player, long chunkPos, double surface) {
        State state = state(player.level());
        if (!enabled || state == null || player.isSpectator()) {
            return;
        }
        if (player.getY() - surface <= landingMargin && player.getDeltaMovement().y <= 0.08) {
            promoteImmediate(state, chunkPos);
        }
    }

    /**
     * A Sable physics body (ship, coaster, any sub-level or physics object) asks for the terrain section
     * {@code sectionY} of this chunk (#9). Sable would bake the placeholder's air into its collision world and
     * keep it, so the body sinks into proxy terrain. Returns true while the chunk is a stub target: the caller
     * must not register the section yet (Sable asks again next tick and gets the real terrain once it exists).
     * A section within {@code landingMargin} of the sampled proxy surface, or of an unsampled chunk, is
     * promoted at once (Maric 2026-10-14: "ships colliding should force generate the collided chunk"); a ship
     * flying higher over proxy terrain generates nothing. Only Sable's first request for a section lands here.
     */
    public static boolean onBodySection(ServerLevel level, long chunkPos, int sectionY) {
        State state = state(level);
        if (state == null || !ProxyChunkGate.mayHaveProxy(level) || !isStubTarget(state, chunkPos)) {
            return false;
        }
        // ponytail: the coarse grid max ignores trees/overhangs above it; sample per section if a hull clips them
        if ((sectionY << 4) <= ProxyStreamer.surfaceMax(level, chunkPos) + landingMargin) {
            promoteImmediate(state, chunkPos); // no-op while generating or with the promote switch off
        }
        return true;
    }

    /**
     * Runtime block-write guard. Called from {@code Level.setBlock} for every server-side write; returns
     * true when the write must be cancelled because it targets a proxy, a chunk still generating, or a real
     * chunk whose earlier writes are not all replayed yet (so the order is kept). The write is remembered and
     * replayed under the per-tick budget once the chunk is real. Only a proxy whose promotion has not started
     * (no holder yet) is capped at {@link #MAX_WRITE_LOG}; once it is promoting nothing is dropped (#5). The
     * fast bail keeps the dormant case to one state lookup + one volatile flag read.
     */
    /**
     * Server-owner mode: refuse a write into a chunk that is not real terrain (#16), rather than deferring it.
     * Returns true when the caller must be told the write failed. Promotes the chunk so the next attempt works.
     */
    public static boolean refuseStubWrite(ServerLevel serverLevel, BlockPos pos) {
        if (!enabled || !refuseStubWrites) {
            return false;
        }
        State promotion = state(serverLevel);
        if (promotion == null || !ProxyChunkGate.mayHaveProxy(serverLevel)) {
            return false;
        }
        return refuseInto(promotion, ChunkPos.asLong(pos));
    }

    public static boolean tryDeferWrite(ServerLevel serverLevel, BlockPos pos, BlockState state, int flags) {
        if (!enabled || refuseStubWrites) {
            return false;
        }
        State promotion = state(serverLevel);
        if (promotion == null || !ProxyChunkGate.mayHaveProxy(serverLevel)) {
            return false;
        }
        long chunkPos = ChunkPos.asLong(pos);
        if ((replaying && chunkPos == replayingPos) || !isStubTarget(promotion, chunkPos)) {
            return false;
        }
        promoteImmediate(promotion, chunkPos); // no-op once it is generating or real
        WriteLog log = promotion.deferredWrites.computeIfAbsent(chunkPos, k -> new WriteLog());
        // the cap is about the caller's writes: vanilla's own terrain writes are not the caller's to lose
        if (isVanillaWrite()) {
            log.vanilla.addLast(new DeferredWrite(pos.immutable(), state, flags));
            log.vanillaCount++;
        } else if (log.caller.size() < MAX_WRITE_LOG || !ProxyChunkGate.isProxy(serverLevel, chunkPos)) {
            log.caller.addLast(new DeferredWrite(pos.immutable(), state, flags));
            log.callerCount++;
        } else {
            LOGGER.warn("proxy chunk {} has more than {} deferred writes; dropping the newest", new ChunkPos(chunkPos), MAX_WRITE_LOG);
        }
        return true;
    }

    /**
     * A player's right-click into a proxy, a chunk still promoting, or one with writes awaiting replay is
     * refused rather than deferred (#6): the deferral loses what the caller fills in after {@code setBlock}
     * (block-entity contents, sign text) and the item is already consumed. The caller checks the clicked block
     * (refuse the whole click) and the block on the clicked face (refuse only the item use, i.e. placement, so a
     * chest at a proxy border still opens). A stub target is promoted at once, so the next click works. Returns
     * true when the position is a stub target.
     */
    public static boolean refusePlayerUse(ServerLevel level, BlockPos pos) {
        State state = state(level);
        if (!enabled || state == null || !ProxyChunkGate.mayHaveProxy(level)) {
            return false;
        }
        return refuseInto(state, ChunkPos.asLong(pos));
    }

    private static boolean refuseInto(State state, long chunkPos) {
        if (!isStubTarget(state, chunkPos)) {
            return false;
        }
        promoteImmediate(state, chunkPos);
        return true;
    }

    /**
     * Server stop (#6): finish every chunk that still holds deferred writes — generate it, replay its writes —
     * so the save that follows persists them. The tick loop has ended, so this drives the chunk tasks itself;
     * a FULL ticket is added for a chunk with no loaded holder (vanilla removes it on closing). Bounded by
     * {@link #STOP_FINISH_NANOS}; whatever is left is logged and lost.
     */
    public static void finishPendingWrites(MinecraftServer server) {
        if (STATES.values().stream().allMatch(s -> s.deferredWrites.isEmpty())) {
            return;
        }
        long start = System.nanoTime();
        server.managedBlock(() -> {
            boolean pending = false;
            for (State state : STATES.values()) {
                for (long pos : state.deferredWrites.keySet()) {
                    if (!ProxyChunkGate.hasHolder(state.level, pos)) {
                        ChunkPos chunk = new ChunkPos(pos);
                        state.level.getChunkSource().addRegionTicket(TicketType.UNKNOWN, chunk, 0, chunk);
                    }
                    promoteImmediate(state, pos); // no-op while generating or once real
                }
                state.level.getChunkSource().pollTask(); // haveTime() is false after the loop: drive chunk work
                replayWrites(state, Long.MAX_VALUE);
                pending |= !state.deferredWrites.isEmpty();
            }
            return !pending || System.nanoTime() - start >= STOP_FINISH_NANOS;
        });
        for (State state : STATES.values()) {
            for (var entry : state.deferredWrites.long2ObjectEntrySet()) {
                LOGGER.error("server stop: proxy chunk {} not finished within {} ms; {} deferred write(s) lost",
                    new ChunkPos(entry.getLongKey()), STOP_FINISH_NANOS / 1_000_000, entry.getValue().size());
            }
        }
        LOGGER.info("server stop: finished pending deferred writes in {} ms", (System.nanoTime() - start) / 1_000_000);
    }

    /**
     * true while the position is a proxy, still generating, or has deferred writes waiting for replay (a later
     * write must queue behind them). A promoted chunk that is already real in memory takes writes directly,
     * even before its completion callback ran (its own post-processing writes land here).
     */
    private static boolean isStubTarget(State state, long chunkPos) {
        if (state.deferredWrites.containsKey(chunkPos) || ProxyChunkGate.isProxy(state.level, chunkPos)) {
            return true;
        }
        return (state.inFlight.contains(chunkPos) || ProxyChunkGate.isPromoting(state.level, chunkPos))
            && !ProxyChunkGate.isLoadedReal(state.level, chunkPos);
    }

    /** Dev hook: how many retries have been issued, so a probe can tell the retry path ran. */
    public static int debugInFlightRetries() {
        return inFlightRetries;
    }

    /** Dev hook: how many in-flight slots were reclaimed without a completion (see {@link #retryInFlight}). */
    public static int debugInFlightReclaims() {
        return inFlightReclaims;
    }

    /**
     * Dev hooks: the pacer's own decisions since the last {@link #debugResetRateCounters}. {@code starts} counts
     * linger promotions admitted (not completions), {@code brakeBlocks} and {@code budgetBlocks} count the ticks
     * that returned early because the smoothed tick time was at the brake or the budget was spent, and
     * {@code forcedStarts} counts the starts the {@code FORCE_START_TICKS} floor let through over both. A probe
     * measuring the drain cannot tell "paced by the budget" from "paced by the concurrency cap" without these,
     * and the floor is the one start the brake does not stop.
     */
    public static int debugLingerStarts() {
        return lingerStarts;
    }

    public static int debugBrakeBlocks() {
        return brakeBlocks;
    }

    public static int debugBudgetBlocks() {
        return budgetBlocks;
    }

    public static int debugForcedStarts() {
        return forcedStarts;
    }

    /** Dev hook: zero the counters above, so a probe reads one phase rather than the JVM's whole life. */
    public static void debugResetRateCounters() {
        lingerStarts = 0;
        brakeBlocks = 0;
        budgetBlocks = 0;
        forcedStarts = 0;
    }

    /** Dev hook: the queue depth for one level, which the refill's admission shows up in. */
    public static int debugQueued(ServerLevel lvl) {
        State state = state(lvl);
        return state == null ? 0 : state.queue.size();
    }

    /** Dev hook: the in-flight count for one level. */
    public static int debugInFlight(ServerLevel lvl) {
        State state = state(lvl);
        return state == null ? 0 : state.inFlight.size();
    }

    /**
     * Dev hook: shorten the in-flight retry interval so a probe can exercise the retry path during an
     * ordinary promotion. One tick means every in-flight chunk is re-driven every tick while it generates,
     * which is the harshest version of what {@link #retryInFlight} does — exactly what a probe needs to find
     * out whether re-driving a live generation disturbs it.
     */
    public static void debugSetInFlightRetryTicks(int ticks) {
        inFlightRetryTicks = Math.max(1, ticks);
    }

    /** Dev hook: how many deferred writes this chunk still has to replay, or 0 when it has none. A probe that
     * counts blocks in the world cannot tell "deferred, not yet replayed" from "lost", and that distinction is
     * the whole question in #16.
     */
    public static int debugDeferredWrites(ServerLevel lvl, long chunkPos) {
        State state = state(lvl);
        if (state == null) {
            return 0;
        }
        WriteLog log = state.deferredWrites.get(chunkPos);
        return log == null ? 0 : log.size();
    }

    /** test hook: queue a chunk as if it had lingered (used by the dev-only headless stress probe). */
    public static void debugEnqueue(ServerLevel lvl, long chunkPos) {
        State state = state(lvl);
        if (state != null && enabled) {
            // deliberately past the cap: the probe is simulating "these chunks all qualified", and the refill
            // is what would normally decide the set
            state.queue.put(chunkPos, state.tickCounter);
        }
    }

    /**
     * Queue a chunk, bounded by the soft cap. {@code force} bypasses the "is it still a proxy" check, for a
     * position the gate has already allowed (a retry after a failed start).
     */
    private static void enqueue(State state, long chunkPos, boolean force) {
        if (!enabled || state.inFlight.contains(chunkPos) || state.queue.containsKey(chunkPos)) {
            return;
        }
        if (!force && !ProxyChunkGate.isProxy(state.level, chunkPos)) {
            return; // already real (or the proxy is inactive)
        }
        if (state.queue.size() >= state.queueCap) {
            return; // the refill decides what belongs in the queue; do not grow it past the buffer here
        }
        state.queue.put(chunkPos, state.tickCounter);
    }

    /** queue a chunk for paced linger promotion (the only path that is rate-limited). */
    private static void enqueue(State state, long chunkPos) {
        enqueue(state, chunkPos, false);
    }

    /**
     * Start a promotion now, outside the queue and its rate limit. The trigger was a player action (touching
     * the ground, a block write, a refused right-click, a Sable body hitting the surface), so the chunk goes
     * to the vanilla generation pipeline immediately: not capped by the budget, the hard gate or
     * {@code maxInFlight}. The only guard is that the same chunk is not started twice while it is still
     * generating. If the gate cannot start it yet (no loaded holder), it falls back to the linger queue so the
     * trigger is not lost. These are the only budget bypasses; linger promotion, including the drain, never
     * takes this path.
     */
    private static void promoteImmediate(State state, long chunkPos) {
        if (!enabled || state.inFlight.contains(chunkPos)) {
            return;
        }
        if (!ProxyChunkGate.isProxy(state.level, chunkPos)) {
            return; // already real (or the proxy is inactive)
        }
        if (!ProxyChunkGate.hasHolder(state.level, chunkPos)) {
            enqueue(state, chunkPos); // holder not loaded yet: queue it rather than dropping the trigger
            return;
        }
        state.inFlight.add(chunkPos);
        state.inFlightSince.put(chunkPos, state.tickCounter);
        boolean started;
        try {
            started = ProxyChunkGate.promote(state.level, new ChunkPos(chunkPos), () -> onComplete(state, chunkPos));
        } catch (Throwable t) {
            LOGGER.error("failed to start regeneration of proxy chunk {}", new ChunkPos(chunkPos), t);
            started = false;
        }
        if (started) {
            return;
        }
        state.inFlight.remove(chunkPos);
        state.inFlightSince.remove(chunkPos);
        enqueue(state, chunkPos);
    }

    /**
     * Drain the queue, nearest-player-first, under the generation budget. A single start per level and tick,
     * so the budget and {@code maxInFlight} are what set the rate rather than the loop. The budget is charged
     * the *measured* main-thread cost of finishing a promotion (push + replay + resend), so an idle server
     * starts as fast as the concurrency cap allows while a lagging one backs off. A long dry spell forces one
     * start anyway, which is the floor that guarantees progress on a server that is already lagging.
     */
    private static void processQueue(State state, List<ServerPlayer> players, float tickMs) {
        if (state.queue.isEmpty() || state.inFlight.size() >= maxInFlight) {
            return;
        }
        long pos = pickNext(state, players);
        if (pos == Long.MIN_VALUE) {
            return; // nothing with a loaded holder; try again next tick
        }
        double cost = Math.clamp(ProxyChunkGate.postCostMs(state.level), 2.0, 2000.0);
        boolean starved = state.tickCounter - state.lastStartTick >= FORCE_START_TICKS;
        if (!starved) {
            // The drain must not push tick time to the level that would re-engage enforcement, so the brake
            // is the adaptive activate threshold when that is tighter than the configured hard gate. Derived
            // from the adaptive thresholds on purpose: no new knob, and the two cannot drift apart.
            double brake = hardGateMs <= 0.0 ? AdaptiveMode.activateTickMs()
                : Math.min(hardGateMs, AdaptiveMode.activateTickMs());
            if (brake > 0.0 && tickMs >= brake) {
                brakeBlocks++;
                return; // server already at the level that would re-engage enforcement: queue, do not start
            }
            if (state.budgetMs < cost) {
                budgetBlocks++;
                return; // no generation time left this window
            }
        }
        state.budgetMs -= cost;
        state.queue.remove(pos);
        state.inFlight.add(pos);
        state.inFlightSince.put(pos, state.tickCounter);
        boolean started;
        try {
            started = ProxyChunkGate.promote(state.level, new ChunkPos(pos), () -> onComplete(state, pos));
        } catch (Throwable t) {
            LOGGER.error("failed to start regeneration of proxy chunk {}", new ChunkPos(pos), t);
            started = false;
        }
        if (!started) {
            state.inFlight.remove(pos);
            state.inFlightSince.remove(pos);
            state.budgetMs += cost; // the promotion never ran: refund the budget
            enqueue(state, pos, true);
        } else {
            lingerStarts++;
            if (starved) {
                forcedStarts++;
            }
            state.lastStartTick = state.tickCounter;
        }
    }

    /**
     * Nearest to a player, or {@link Long#MIN_VALUE} when nothing is startable. The queue is bounded by
     * {@link State#queueCap}, so the full scan is cheap. A chunk whose holder is not loaded is skipped (left
     * queued) so we do not stall on it.
     */
    private static long pickNext(State state, List<ServerPlayer> players) {
        long best = Long.MIN_VALUE;
        int bestDist = Integer.MAX_VALUE;
        for (var entry : state.queue.long2IntEntrySet()) {
            long pos = entry.getLongKey();
            if (!ProxyChunkGate.hasHolder(state.level, pos)) {
                continue; // holder not loaded yet; leave it queued
            }
            int distance = nearestDistance(pos, players);
            if (best == Long.MIN_VALUE || distance < bestDist) { // no player online: all MAX_VALUE (#12)
                best = pos;
                bestDist = distance;
            }
        }
        return best;
    }

    private static int nearestDistance(long pos, List<ServerPlayer> players) {
        if (players.isEmpty()) {
            return Integer.MAX_VALUE;
        }
        int best = Integer.MAX_VALUE;
        for (ServerPlayer player : players) {
            ChunkPos center = player.chunkPosition();
            int distance = Math.max(Math.abs(ChunkPos.getX(pos) - center.x), Math.abs(ChunkPos.getZ(pos) - center.z));
            if (distance < best) {
                best = distance;
            }
        }
        return best;
    }

    private static void onComplete(State state, long pos) {
        state.inFlight.remove(pos);
        state.inFlightSince.remove(pos);
        state.lingerStart.remove(pos);
        state.lingerSeen.remove(pos);
        WriteLog log = state.deferredWrites.get(pos);
        if (log != null && state.level.getChunkSource().getChunkNow(ChunkPos.getX(pos), ChunkPos.getZ(pos)) == null) {
            // regeneration did not produce real terrain (or the chunk unloaded): the writes stay logged and
            // replay whenever the chunk is real, rather than silently dropping the player's edit.
            LOGGER.warn("proxy chunk {} not real after promotion; keeping {} deferred write(s)", new ChunkPos(pos), log.size());
        }
    }

    /**
     * Replay deferred writes, oldest first, into every chunk that is real now, until the per-tick wall-time
     * budget is spent; the rest carries to the next tick (#5). At least one write runs per level and tick, so
     * a tiny budget cannot starve the replay.
     * ponytail: scans every logged chunk each tick; a ready-queue if the log ever holds thousands of chunks.
     */
    private static void replayWrites(State state, long deadline) {
        if (state.deferredWrites.isEmpty()) {
            return;
        }
        LongArrayList ready = state.replayScratch;
        ready.clear();
        for (long pos : state.deferredWrites.keySet()) {
            if (!ProxyChunkGate.isPromoting(state.level, pos)
                && state.level.getChunkSource().getChunkNow(ChunkPos.getX(pos), ChunkPos.getZ(pos)) != null) {
                ready.add(pos);
            }
        }
        // setBlock below may defer writes into other chunks, so the map is not iterated while replaying
        for (int i = 0; i < ready.size(); i++) {
            long pos = ready.getLong(i);
            WriteLog log = state.deferredWrites.get(pos);
            replaying = true;
            replayingPos = pos;
            try {
                DeferredWrite write;
                while ((write = log.next()) != null) {
                    state.level.setBlock(write.pos(), write.state(), write.flags());
                    log.replayed++;
                    if (System.nanoTime() >= deadline) {
                        break;
                    }
                }
            } finally {
                replaying = false;
            }
            log.ticks++;
            if (log.isEmpty()) {
                state.deferredWrites.remove(pos);
                LOGGER.info("replayed {} deferred write(s) into promoted chunk {} over {} tick(s) ({} caller, {} vanilla)",
                    log.replayed, new ChunkPos(pos), log.ticks, log.callerCount, log.vanillaCount);
            }
            if (System.nanoTime() >= deadline) {
                return;
            }
        }
    }

    private static double loadFactor(float tickMs) {
        double span = busyTickMs - idleTickMs;
        if (span <= 0.0) {
            return 1.0; // degenerate hand-edited config; assume fully loaded (see AeroGelConfig.validate)
        }
        return Math.clamp((tickMs - idleTickMs) / span, 0.0, 1.0);
    }

    private static int clamp(int value, int min, int max) {
        return Math.clamp(value, min, max);
    }

    private record DeferredWrite(BlockPos pos, BlockState state, int flags) {
    }

    /**
     * One chunk's deferred writes, split by origin, plus replay progress for the log line.
     *
     * <p>Two queues, not one: vanilla's own writes into a chunk that is no longer a stub (post-generation
     * cleanup, a scheduled leaf tick) are this chunk's real terrain and must land <b>before</b> the caller's
     * writes, whatever order they arrived in. A single FIFO put them in arrival order, so a leaf tick that
     * fired after promotion was replayed on top of the player's block and erased it (#16).</p>
     */
    private static final class WriteLog {
        /** vanilla's own terrain writes; drained first so the caller's always land last. */
        final ArrayDeque<DeferredWrite> vanilla = new ArrayDeque<>();
        /** the caller's writes — player, piston, mod code. Drained last; this is what the cap applies to. */
        final ArrayDeque<DeferredWrite> caller = new ArrayDeque<>();
        int replayed;
        int ticks;

        boolean isEmpty() {
            return vanilla.isEmpty() && caller.isEmpty();
        }

        int size() {
            return vanilla.size() + caller.size();
        }

        int callerCount;
        int vanillaCount;

        /** vanilla first, so a caller write is never overwritten by terrain it was meant to replace. */
        DeferredWrite next() {
            DeferredWrite write = vanilla.pollFirst();
            return write != null ? write : caller.pollFirst();
        }
    }

}
