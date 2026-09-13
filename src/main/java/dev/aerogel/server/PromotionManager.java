package dev.aerogel.server;

import dev.aerogel.common.AeroGelConfig;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
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
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decides when a proxy chunk becomes real terrain, and paces that work so it can never cause lag.
 *
 * <p><b>Linger.</b> One cumulative counter per proxy chunk. It accrues a tick for every tick a player is
 * within {@code maxRange}, and is promoted once the counter reaches a distance-dependent threshold
 * {@code t(d) = min + (d/maxRange)*(max - min)}, where {@code d} is the Chebyshev distance to the nearest
 * player. Both endpoints are interpolated between idle and busy server load. Because the counter is
 * cumulative and the threshold shrinks as a chunk approaches, a chunk banked at the fringe promotes the
 * moment it is walked into range.</p>
 *
 * <p><b>Immediate.</b> Touching the surface (descending/landing within {@code landingMargin} above the
 * sampled height) and any runtime block write queue the chunk at once. Players who have opted out of proxy
 * travel also have their nearby leftovers forced to real terrain.</p>
 *
 * <p><b>Rate limiting.</b> A token bucket holds a wall-clock budget that refills at {@code maxGenFraction}
 * ms per ms (default 0.5, i.e. at most half the server's time); each promotion start costs
 * {@code chunkCostMs}. Ordinary linger promotions need the budget and are also blocked while the smoothed
 * tick time is above {@code hardGateMs}. Immediate (touch/write/opt-out) promotions bypass the budget
 * check and the hard gate but still debit the bucket, slowing ordinary promotion down. {@code maxInFlight}
 * bounds how many regenerations run concurrently for every class.</p>
 *
 * <p><b>Per-dimension.</b> The queue/linger/budget state lives in a {@link State} per {@code ServerLevel};
 * scalar config stays global. Every method resolves the state from the {@link ServerLevel} in scope, so an
 * overworld position is never treated as a proxy in the nether/end.</p>
 *
 * <p><b>Bounded queue.</b> The pending-promotion queue is capped at {@code (2*range+1)^2 / 4}, clamped to
 * [256, 4096], where {@code range} is {@link #effectiveMaxRange}. When a non-urgent entry is added at the
 * cap, the oldest non-urgent entry (FIFO) is evicted, together with its linger counters. Urgent entries
 * (touch, block write, opted-out leftovers) are never evicted by the cap: they may exceed it briefly, and a
 * non-urgent addition is refused outright while the queue is full of urgent work, so a deferred block write
 * can never be silently lost. Separately, a periodic sweep drops any non-urgent entry no player has been
 * within {@code range} of for {@link #QUEUE_GRACE_TICKS}; a chunk with a pending deferred write is exempt
 * (the write must be replayed). This keeps a departed player's backlog from accumulating forever.</p>
 *
 * <p><b>In-flight safety.</b> A promotion handed to {@link ProxyChunkGate#promote} is timed: if its
 * completion callback never fires within {@link #IN_FLIGHT_TIMEOUT_TICKS}, the slot is reclaimed (with a
 * warn), so one stuck future can never stall all promotion. A timed-out chunk with pending deferred writes
 * is re-queued urgent; otherwise it is dropped (the gate has already allowed the chunk, so vanilla will
 * generate it).</p>
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
    private static final int MAX_RANGE = 64;
    private static final byte NO_DISTANCE = (byte) 127;
    /** how long (ticks) a queued chunk may go with no player within range before it is dropped. */
    private static final int QUEUE_GRACE_TICKS = 200;
    /** how often the queue proximity/eviction sweep runs (ticks). */
    private static final int QUEUE_SWEEP_INTERVAL = 20;
    /** a promotion whose completion never fires is reclaimed after this many ticks (15 s). */
    private static final int IN_FLIGHT_TIMEOUT_TICKS = 300;
    /** cap on queue entries examined per {@link #pickNext} non-urgent scan (urgent work is scanned fully). */
    private static final int PICK_SCAN_CAP = 512;
    private static final int MIN_QUEUE_CAP = 256;
    private static final int MAX_QUEUE_CAP = 4096;

    private static final Map<ResourceKey<Level>, State> STATES = new HashMap<>();

    private static int maxRange = -1;
    private static int lingerMinIdleTicks = 200;
    private static int lingerMinBusyTicks = 400;
    private static int lingerMaxIdleTicks = 600;
    private static int lingerMaxBusyTicks = 1200;
    private static double landingMargin = 8.0;
    private static int maxInFlight = 2;
    private static double maxGenFraction = 0.5;
    private static double chunkCostMs = 100.0;
    private static double burstMs = 200.0;
    private static double hardGateMs = 60.0;
    private static double idleTickMs = 10.0;
    private static double busyTickMs = 45.0;
    private static boolean enabled = true;
    private static long lastStatusLog;

    /** per-{@code ServerLevel} promotion state. */
    private static final class State {
        final ServerLevel level;
        /** chunk -> ticks of accumulated player proximity; seen = last tick it was occupied. */
        final Long2IntOpenHashMap lingerTicks = new Long2IntOpenHashMap();
        final Long2IntOpenHashMap lingerSeen = new Long2IntOpenHashMap();
        /** per-tick scratch: chunk -> minimum Chebyshev distance to an opt-in player (this tick only). */
        final Long2ByteOpenHashMap occupied = new Long2ByteOpenHashMap();
        /** queued chunks awaiting a free slot, keyed by chunk pos. */
        final Long2ObjectOpenHashMap<Promotion> queue = new Long2ObjectOpenHashMap<>();
        /** FIFO insertion order of {@link #queue}, used for oldest-first eviction and bounded nearest scans. */
        final ArrayDeque<Promotion> order = new ArrayDeque<>();
        /** chunks handed to {@link ProxyChunkGate#promote} whose completion callback has not fired yet. */
        final LongOpenHashSet inFlight = new LongOpenHashSet();
        /** in-flight chunk -> tick it was started, so a stuck future can be timed out. */
        final Long2LongOpenHashMap inFlightSince = new Long2LongOpenHashMap();
        /** chunk -> writes cancelled while it was a proxy, to be replayed once it is real. */
        final Long2ObjectOpenHashMap<List<DeferredWrite>> deferredWrites = new Long2ObjectOpenHashMap<>();
        /** reused per tick so the tick path does not allocate a fresh player list. */
        final List<ServerPlayer> playersScratch = new ArrayList<>();
        /** number of queued entries with {@code urgent == true} (urgent is never cap-evicted). */
        int urgentCount;
        /** effective queue cap for this level, refreshed each tick. */
        int queueCap = MIN_QUEUE_CAP;

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
        maxInFlight = cfg.maxInFlight.get();
        maxGenFraction = cfg.maxGenFraction.get();
        chunkCostMs = cfg.chunkCostMs.get();
        burstMs = cfg.burstMs.get();
        hardGateMs = cfg.hardGateMs.get();
        idleTickMs = cfg.idleTickMs.get();
        busyTickMs = cfg.busyTickMs.get();
        State state = new State(lvl);
        state.queueCap = queueCap(effectiveMaxRange(lvl.getServer()));
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

    /** the linger range actually in use (resolves the auto value). */
    public static int effectiveMaxRange(MinecraftServer server) {
        if (maxRange > 0) {
            return Math.min(maxRange, MAX_RANGE);
        }
        int view = server == null ? 10 : server.getPlayerList().getViewDistance();
        return Math.min(view + FULL_SLACK, MAX_RANGE);
    }

    /** true while any chunk in any dimension is mid-linger (adaptive enforcement must not stop). */
    public static boolean hasLinger() {
        for (State state : STATES.values()) {
            if (!state.lingerTicks.isEmpty()) {
                return true;
            }
        }
        return false;
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
        int urgent = 0;
        int inFlight = 0;
        for (State state : STATES.values()) {
            queued += state.queue.size();
            inFlight += state.inFlight.size();
            for (Promotion promotion : state.queue.values()) {
                if (promotion.urgent) {
                    urgent++;
                }
            }
        }
        return "promote: " + (enabled ? "on" : "off")
            + ", range " + range + "ch, linger " + lingerMinIdleSeconds() + ".." + lingerMaxIdleSeconds() + "s idle / "
            + lingerMinBusySeconds() + ".." + lingerMaxBusySeconds() + "s busy"
            + " (now d0 " + fmt(thresholdTicks(0, range, tickMs) / 20.0) + "s, d" + range + " "
            + fmt(thresholdTicks(range, range, tickMs) / 20.0) + "s @ " + fmt(tickMs) + " ms)"
            + ", landing " + fmt(landingMargin) + " blocks"
            + ", concurrency " + maxInFlight + ", budget " + fmt(budgetOfFirst()) + "/" + (int) (maxGenFraction * 1000) + " ms/s"
            + ", cost " + fmt(chunkCostMs) + " ms, hard gate " + fmt(hardGateMs) + " ms"
            + ", queued " + queued + " (" + urgent + " immediate), in flight " + inFlight;
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
        for (State state : STATES.values()) {
            tickState(server, state);
        }
    }

    private static void tickState(MinecraftServer server, State state) {
        state.tickCounter++;
        if (!enabled) {
            if (state.tickCounter % 100 == 0) {
                pruneLinger(state);
            }
            return;
        }
        float tickMs = server.getCurrentSmoothedTickTime();
        int range = effectiveMaxRange(server);
        state.queueCap = queueCap(range);
        double load = loadFactor(tickMs);
        double minTicks = lingerMinIdleTicks + (lingerMinBusyTicks - lingerMinIdleTicks) * load;
        double maxTicks = lingerMaxIdleTicks + (lingerMaxBusyTicks - lingerMaxIdleTicks) * load;
        List<ServerPlayer> players = state.playersScratch;
        levelPlayers(server, state.level, players);
        refillBudget(state);
        sweepInFlight(state);
        // nothing to linger over when the level has no proxies and no player is opting in: skip the
        // (2*range+1)^2 per-player scan entirely.
        if (!players.isEmpty() && ProxyChunkGate.mayHaveProxy(state.level)) {
            accumulateLinger(state, players, range, minTicks, maxTicks);
        }
        if (state.tickCounter % 100 == 0) {
            pruneLinger(state);
            if (System.currentTimeMillis() - lastStatusLog > 30_000) {
                lastStatusLog = System.currentTimeMillis();
                LOGGER.info(status(server, tickMs));
            }
        }
        if (state.tickCounter % QUEUE_SWEEP_INTERVAL == 0) {
            sweepQueue(state, players, range);
        }
        processQueue(state, players, tickMs);
    }

    /** effective queue cap derived from the linger range: a quarter of the in-range area, clamped. */
    private static int queueCap(int range) {
        long area = (long) (2 * range + 1) * (2 * range + 1);
        return Math.clamp(area / 4, MIN_QUEUE_CAP, MAX_QUEUE_CAP);
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
     * One pass over the range of every player. Opt-in players (or everyone while the server is enforcing)
     * accrue linger; opted-out players instead force their nearby leftovers to real terrain.
     */
    private static void accumulateLinger(State state, List<ServerPlayer> players, int range,
        double minTicks, double maxTicks) {
        state.occupied.clear();
        for (ServerPlayer player : players) {
            ChunkPos center = player.chunkPosition();
            if (!AdaptiveMode.isOptIn(player)) {
                // opted out: behave as vanilla here — force nearby leftovers to real terrain
                for (int dx = -range; dx <= range; dx++) {
                    for (int dz = -range; dz <= range; dz++) {
                        long pos = ChunkPos.asLong(center.x + dx, center.z + dz);
                        if (ProxyChunkGate.isProxy(state.level, pos)) {
                            enqueue(state, pos, true);
                        }
                    }
                }
                continue;
            }
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
                            state.lingerTicks.addTo(pos, 1);
                        }
                    }
                    state.lingerSeen.put(pos, state.tickCounter);
                }
            }
        }
        for (var entry : state.occupied.long2ByteEntrySet()) {
            long pos = entry.getLongKey();
            int distance = entry.getByteValue();
            if (state.lingerTicks.get(pos) >= thresholdTicks(distance, range, minTicks, maxTicks)) {
                enqueue(state, pos, false);
            }
        }
    }

    /** drop linger state for chunks no player has been near recently, so the maps cannot grow unbounded. */
    private static void pruneLinger(State state) {
        var it = state.lingerSeen.long2IntEntrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            if (state.tickCounter - entry.getIntValue() > LINGER_GRACE_TICKS) {
                state.lingerTicks.remove(entry.getLongKey());
                it.remove();
            }
        }
    }

    /**
     * Reclaim in-flight slots whose completion callback never fired. Without this a single stuck future
     * leaves {@code inFlight} at {@code maxInFlight} and all promotion stops forever. A chunk with pending
     * deferred writes is re-queued urgent (the write must not be lost); a linger-only chunk is dropped —
     * the gate already allowed it, so vanilla will generate it.
     */
    private static void sweepInFlight(State state) {
        if (state.inFlightSince.isEmpty()) {
            return;
        }
        var it = state.inFlightSince.long2LongEntrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            long pos = entry.getLongKey();
            if (state.tickCounter - entry.getLongValue() <= IN_FLIGHT_TIMEOUT_TICKS) {
                continue;
            }
            it.remove();
            state.inFlight.remove(pos);
            List<DeferredWrite> writes = state.deferredWrites.get(pos);
            if (writes != null && !writes.isEmpty()) {
                LOGGER.warn("promotion of proxy chunk {} stalled for {} ticks; re-queueing with {} deferred write(s)",
                    new ChunkPos(pos), IN_FLIGHT_TIMEOUT_TICKS, writes.size());
                enqueue(state, pos, true, true);
            } else {
                LOGGER.warn("promotion of proxy chunk {} stalled for {} ticks; dropping it (vanilla will generate)",
                    new ChunkPos(pos), IN_FLIGHT_TIMEOUT_TICKS);
                state.lingerTicks.remove(pos);
                state.lingerSeen.remove(pos);
            }
        }
    }

    /**
     * Drop queued chunks no player has been within {@code range} of for {@link #QUEUE_GRACE_TICKS}. A chunk
     * with a pending deferred write is never dropped (the edit must be replayed); its proximity clock is
     * refreshed instead. Runs periodically, not every tick.
     */
    private static void sweepQueue(State state, List<ServerPlayer> players, int range) {
        if (state.queue.isEmpty()) {
            return;
        }
        long now = state.tickCounter;
        var it = state.queue.long2ObjectEntrySet().iterator();
        while (it.hasNext()) {
            Promotion promotion = it.next().getValue();
            if (nearestDistance(promotion.pos, players) <= range) {
                promotion.lastNearTick = now;
                continue;
            }
            if (now - promotion.lastNearTick <= QUEUE_GRACE_TICKS) {
                continue;
            }
            if (state.deferredWrites.containsKey(promotion.pos)) {
                promotion.lastNearTick = now;
                continue;
            }
            it.remove();
            forget(state, promotion);
            state.lingerTicks.remove(promotion.pos);
            state.lingerSeen.remove(promotion.pos);
        }
    }

    /** touch trigger: called from the streamer's player tick once the proxy surface is known. */
    public static void onPlayerNearSurface(ServerPlayer player, long chunkPos, double surface) {
        State state = state(player.level());
        if (!enabled || state == null || player.isSpectator()) {
            return;
        }
        if (player.getY() - surface <= landingMargin && player.getDeltaMovement().y <= 0.08) {
            enqueue(state, chunkPos, true);
        }
    }

    /**
     * Runtime block-write guard. Called from {@code Level.setBlock} for every server-side write; returns
     * true when the write must be cancelled because it targets a proxy (the write is remembered and
     * replayed after promotion). The fast bail keeps the dormant case to one state lookup + one volatile
     * flag read.
     */
    public static boolean tryDeferWrite(ServerLevel serverLevel, BlockPos pos, BlockState state, int flags) {
        if (!enabled) {
            return false;
        }
        State promotion = state(serverLevel);
        if (promotion == null || !ProxyChunkGate.mayHaveProxy(serverLevel)) {
            return false;
        }
        long chunkPos = ChunkPos.asLong(pos);
        if (!isStubTarget(promotion, chunkPos)) {
            return false;
        }
        List<DeferredWrite> writes = promotion.deferredWrites.computeIfAbsent(chunkPos, k -> new ArrayList<>());
        if (writes.size() < MAX_WRITE_LOG) {
            writes.add(new DeferredWrite(pos.immutable(), state, flags));
        } else {
            LOGGER.warn("proxy chunk {} has more than {} deferred writes; dropping the newest", new ChunkPos(chunkPos), MAX_WRITE_LOG);
        }
        enqueue(promotion, chunkPos, true);
        return true;
    }

    /** true while the position is a proxy placeholder (tagged, or untagged-but-air) or mid-promotion. */
    private static boolean isStubTarget(State state, long chunkPos) {
        return state.inFlight.contains(chunkPos) || ProxyChunkGate.isProxy(state.level, chunkPos);
    }

    /** test hook: queue a chunk as if it had lingered (headless stress probe). */
    static void debugEnqueue(ServerLevel lvl, long chunkPos) {
        State state = state(lvl);
        if (state != null) {
            enqueue(state, chunkPos, false);
        }
    }

    private static void enqueue(State state, long chunkPos, boolean urgent) {
        enqueue(state, chunkPos, urgent, false);
    }

    /**
     * Queue a chunk for promotion. {@code force} bypasses the "is it still a proxy" check, for positions the
     * gate has already allowed (mid-promotion retries and deferred-write replays). Urgent entries are never
     * cap-evicted; when the queue is full of urgent work a new non-urgent entry is refused rather than
     * dropping an urgent one.
     */
    private static void enqueue(State state, long chunkPos, boolean urgent, boolean force) {
        if (!enabled || state.inFlight.contains(chunkPos)) {
            return;
        }
        Promotion existing = state.queue.get(chunkPos);
        if (existing != null) {
            if (urgent) {
                markUrgent(state, existing);
            }
            return;
        }
        if (!force && !ProxyChunkGate.isProxy(state.level, chunkPos)) {
            return; // already real (or the proxy is inactive)
        }
        if (state.queue.size() >= state.queueCap) {
            boolean evicted = evictOldestNonUrgent(state);
            if (!evicted && !urgent) {
                return; // full of urgent work: do not add more non-urgent backlog
            }
        }
        Promotion promotion = new Promotion(chunkPos, urgent);
        promotion.lastNearTick = state.tickCounter;
        if (urgent) {
            state.urgentCount++;
        }
        state.queue.put(chunkPos, promotion);
        state.order.addLast(promotion);
    }

    /** evict the oldest non-urgent queue entry; returns false when there is none. */
    private static boolean evictOldestNonUrgent(State state) {
        var it = state.order.iterator();
        while (it.hasNext()) {
            Promotion promotion = it.next();
            if (promotion.removed) {
                it.remove(); // lazy cleanup of stale FIFO entries
                continue;
            }
            if (promotion.urgent) {
                continue; // urgent entries are never evicted by the cap
            }
            if (state.deferredWrites.containsKey(promotion.pos)) {
                markUrgent(state, promotion); // a pending write makes it urgent; keep it
                continue;
            }
            it.remove();
            state.queue.remove(promotion.pos);
            promotion.removed = true;
            // clear the linger counters too, or the still-near chunk would immediately re-qualify and thrash
            state.lingerTicks.remove(promotion.pos);
            state.lingerSeen.remove(promotion.pos);
            return true;
        }
        return false;
    }

    private static void markUrgent(State state, Promotion promotion) {
        if (!promotion.urgent) {
            promotion.urgent = true;
            state.urgentCount++;
        }
    }

    /** unlink a promotion from the queue/FIFO and clear its flags (caller must not be iterating the FIFO). */
    private static void removeQueued(State state, Promotion promotion) {
        state.queue.remove(promotion.pos);
        forget(state, promotion);
    }

    /** FIFO/flag cleanup without touching the queue map (safe while iterating the map). */
    private static void forget(State state, Promotion promotion) {
        state.order.remove(promotion);
        if (promotion.urgent) {
            state.urgentCount--;
        }
        promotion.removed = true;
    }

    private static void processQueue(State state, List<ServerPlayer> players, float tickMs) {
        if (state.queue.isEmpty() || state.inFlight.size() >= maxInFlight) {
            return;
        }
        Promotion promotion = pickNext(state, players);
        if (promotion == null) {
            return; // nothing with a loaded holder; try again next tick
        }
        if (promotion.urgent) {
            // immediate work ignores the budget and the hard gate, but still debits the bucket so ordinary
            // linger promotion is slowed down by it.
            state.budgetMs -= chunkCostMs;
        } else {
            if (hardGateMs > 0.0 && tickMs >= hardGateMs) {
                return; // server already over budget: queue, do not start ordinary work
            }
            if (state.budgetMs < chunkCostMs) {
                return; // no generation time left this window
            }
            state.budgetMs -= chunkCostMs;
        }
        long pos = promotion.pos;
        removeQueued(state, promotion);
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
            state.budgetMs += chunkCostMs; // the promotion never ran: refund the budget
            enqueue(state, pos, promotion.urgent, true);
        }
    }

    /**
     * Immediate first, then nearest-to-a-player within a bounded FIFO window. Urgent work (touch / write /
     * opt-out) is scanned in full because it is rare and must not be starved; non-urgent work is scanned up
     * to {@link #PICK_SCAN_CAP} entries from the front of the FIFO. The tradeoff: this is not a strict
     * global-nearest pick, but FIFO order already approximates near-first (closer chunks have a lower linger
     * threshold and enqueue earlier), and the bound keeps a large queue from costing a full scan every tick.
     * A non-urgent chunk beyond the window is simply considered on a later tick.
     */
    private static Promotion pickNext(State state, List<ServerPlayer> players) {
        ChunkMap chunkMap = state.level.getChunkSource().chunkMap;
        if (state.urgentCount > 0) {
            Promotion best = null;
            int bestDist = Integer.MAX_VALUE;
            for (Promotion promotion : state.order) {
                if (promotion.removed || !promotion.urgent) {
                    continue;
                }
                if (chunkMap.getVisibleChunkIfPresent(promotion.pos) == null) {
                    continue; // holder not loaded yet; leave it queued
                }
                int distance = nearestDistance(promotion.pos, players);
                if (best == null || distance < bestDist) {
                    best = promotion;
                    bestDist = distance;
                }
            }
            if (best != null) {
                return best;
            }
        }
        Promotion best = null;
        int bestDist = Integer.MAX_VALUE;
        int scanned = 0;
        for (Promotion promotion : state.order) {
            if (promotion.removed || promotion.urgent) {
                continue;
            }
            if (++scanned > PICK_SCAN_CAP) {
                break;
            }
            if (chunkMap.getVisibleChunkIfPresent(promotion.pos) == null) {
                continue; // holder not loaded yet; leave it queued
            }
            int distance = nearestDistance(promotion.pos, players);
            if (best == null || distance < bestDist) {
                best = promotion;
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
        state.lingerTicks.remove(pos);
        state.lingerSeen.remove(pos);
        replayWrites(state, pos);
    }

    private static void replayWrites(State state, long pos) {
        List<DeferredWrite> writes = state.deferredWrites.get(pos);
        if (writes == null || writes.isEmpty()) {
            state.deferredWrites.remove(pos);
            return;
        }
        LevelChunk chunk = state.level.getChunkSource().getChunkNow(ChunkPos.getX(pos), ChunkPos.getZ(pos));
        if (chunk == null) {
            // regeneration did not actually produce real terrain (or the chunk unloaded): keep the writes
            // queued for a retry rather than silently dropping the player's edit. The gate already allowed
            // this position, so the normal enqueue guard would refuse it; force the retry through.
            LOGGER.warn("proxy chunk {} not real after promotion; keeping {} deferred write(s)", new ChunkPos(pos), writes.size());
            enqueue(state, pos, true, true);
            return;
        }
        state.deferredWrites.remove(pos);
        for (DeferredWrite write : writes) {
            state.level.setBlock(write.pos(), write.state(), write.flags());
        }
        LOGGER.info("replayed {} deferred write(s) into promoted chunk {}", writes.size(), new ChunkPos(pos));
    }

    private static double loadFactor(float tickMs) {
        return Math.clamp((tickMs - idleTickMs) / (busyTickMs - idleTickMs), 0.0, 1.0);
    }

    private static int clamp(int value, int min, int max) {
        return Math.clamp(value, min, max);
    }

    private record DeferredWrite(BlockPos pos, BlockState state, int flags) {
    }

    private static final class Promotion {
        final long pos;
        boolean urgent;
        /** set when the entry is unlinked, so lazy FIFO/sweep scans can skip it. */
        boolean removed;
        /** last tick a player was within {@code maxRange} of this chunk (for departure eviction). */
        long lastNearTick;

        Promotion(long pos, boolean urgent) {
            this.pos = pos;
            this.urgent = urgent;
        }
    }
}
