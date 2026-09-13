package dev.aerogel.server;

import dev.aerogel.common.AeroGelConfig;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
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
        /** chunks handed to {@link ProxyChunkGate#promote} whose completion callback has not fired yet. */
        final LongOpenHashSet inFlight = new LongOpenHashSet();
        /** chunk -> writes cancelled while it was a proxy, to be replayed once it is real. */
        final Long2ObjectOpenHashMap<List<DeferredWrite>> deferredWrites = new Long2ObjectOpenHashMap<>();

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
        STATES.put(lvl.dimension(), new State(lvl));
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
        List<ServerPlayer> players = levelPlayers(server, state.level);
        refillBudget(state);
        if (!players.isEmpty()) {
            accumulateLinger(state, players, server, tickMs);
        }
        if (state.tickCounter % 100 == 0) {
            pruneLinger(state);
            if (System.currentTimeMillis() - lastStatusLog > 30_000) {
                lastStatusLog = System.currentTimeMillis();
                LOGGER.info(status(server, tickMs));
            }
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

    private static List<ServerPlayer> levelPlayers(MinecraftServer server, ServerLevel level) {
        List<ServerPlayer> players = new ArrayList<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.level() == level && !player.hasDisconnected()) {
                players.add(player);
            }
        }
        return players;
    }

    /**
     * One pass over the range of every player. Opt-in players (or everyone while the server is enforcing)
     * accrue linger; opted-out players instead force their nearby leftovers to real terrain.
     */
    private static void accumulateLinger(State state, List<ServerPlayer> players, MinecraftServer server, float tickMs) {
        int range = effectiveMaxRange(server);
        boolean enforce = AdaptiveMode.enforcing();
        state.occupied.clear();
        for (ServerPlayer player : players) {
            ChunkPos center = player.chunkPosition();
            if (!enforce && !AdaptiveMode.isOptIn(player)) {
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
            if (state.lingerTicks.get(pos) >= thresholdTicks(distance, range, tickMs)) {
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
        if (!enabled || state.inFlight.contains(chunkPos)) {
            return;
        }
        Promotion existing = state.queue.get(chunkPos);
        if (existing != null) {
            existing.urgent |= urgent;
            return;
        }
        if (!ProxyChunkGate.isProxy(state.level, chunkPos)) {
            return; // already real (or the proxy is inactive)
        }
        state.queue.put(chunkPos, new Promotion(chunkPos, urgent));
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
        state.queue.remove(pos);
        state.inFlight.add(pos);
        boolean started;
        try {
            started = ProxyChunkGate.promote(state.level, new ChunkPos(pos), () -> onComplete(state, pos));
        } catch (Throwable t) {
            LOGGER.error("failed to start regeneration of proxy chunk {}", new ChunkPos(pos), t);
            started = false;
        }
        if (!started) {
            state.inFlight.remove(pos);
            state.budgetMs += chunkCostMs; // the promotion never ran: refund the budget
            enqueue(state, pos, promotion.urgent);
        }
    }

    /** immediate first, then nearest-to-a-player (vanilla-like fan-out). */
    private static Promotion pickNext(State state, List<ServerPlayer> players) {
        ChunkMap chunkMap = state.level.getChunkSource().chunkMap;
        Promotion best = null;
        int bestDist = Integer.MAX_VALUE;
        for (Promotion promotion : state.queue.values()) {
            if (chunkMap.getVisibleChunkIfPresent(promotion.pos) == null) {
                continue; // holder not loaded yet; leave it queued
            }
            int distance = nearestDistance(promotion.pos, players);
            if (best == null
                || (promotion.urgent && !best.urgent)
                || (promotion.urgent == best.urgent && distance < bestDist)) {
                best = promotion;
                bestDist = distance;
            }
        }
        return best;
    }

    private static int nearestDistance(long pos, List<ServerPlayer> players) {
        if (players.isEmpty()) {
            return 0;
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
            // queued for a retry rather than silently dropping the player's edit.
            LOGGER.warn("proxy chunk {} not real after promotion; keeping {} deferred write(s)", new ChunkPos(pos), writes.size());
            enqueue(state, pos, true);
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

        Promotion(long pos, boolean urgent) {
            this.pos = pos;
            this.urgent = urgent;
        }
    }
}
