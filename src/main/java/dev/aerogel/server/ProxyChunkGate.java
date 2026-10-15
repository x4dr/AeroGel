package dev.aerogel.server;

import dev.aerogel.common.AeroGelConfig;
import dev.aerogel.mixin.ChunkHolderAccessor;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkType;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The virtual-proxy state machine (see {@code plans/proxy-chunk-state-machine.md}).
 *
 * <p><b>Own-task gate.</b> A never-generated chunk must not create its own FULL generation task from its
 * player ticket: {@code ChunkHolder.updateFutures} is gated so the proxy's {@code prepareAccessibleChunk}/
 * {@code prepareTickingChunk}/{@code prepareEntityTickingChunk} calls never run. Its FULL future is therefore
 * never created, {@code getChunkNow} returns null, and it is never block/entity-ticking. Dependency
 * generation is untouched ({@code applyStep}/{@code scheduleChunkGenerationTask} stay vanilla), so when a
 * real (promoted) neighbour generates, the proxy is pulled forward through the normal pyramid as a live
 * partial {@code ProtoChunk}.</p>
 *
 * <p><b>Classification.</b> At {@code updateFutures} time the persisted status is unknown, so a candidate
 * schedules the cheap EMPTY probe ({@code scheduleChunkGenerationTask(EMPTY)} = one async disk read, radius
 * 0). On completion a disk-FULL chunk is marked {@code real} and vanilla prepare runs forward; anything else
 * is {@code proxy} and stays gated. Results are cached per session (the disk/region header is the source of
 * truth; no persistent DB).</p>
 *
 * <p><b>Promotion.</b> Add to the allow-list and force {@code updateFutures} to build the FULL future; the
 * chunk then generates forward from its persisted status (features already written by neighbours survive).
 * No backwards reset.</p>
 *
 * <p><b>Per-dimension.</b> All state lives in a {@link State} per {@code ServerLevel} (keyed by
 * {@link ResourceKey} and, for the hot {@code updateFutures} path, by {@link ChunkMap} identity). Positions
 * are dimension-agnostic, so a single global set would treat an overworld proxy (x,z) as a proxy in the
 * nether/end. Every method that touches a position therefore takes the level (or resolves it from the
 * {@link ChunkMap}/{@link Level} in scope).</p>
 *
 * <p>All state is touched on the server thread (the probe callback is queued and drained from the tick).</p>
 */
public final class ProxyChunkGate {
    private static final Logger LOGGER = LoggerFactory.getLogger("aerogel.gate");

    /** wall-time budget for draining classification completions each tick (always runs at least one). */
    private static final long PROBE_DRAIN_BUDGET_NANOS = 2_000_000L;
    /** wall-time budget for draining deferred chunk resends each tick (always runs at least one). */
    private static final long RESEND_DRAIN_BUDGET_NANOS = 1_000_000L;
    private static final int PRUNE_INTERVAL_TICKS = 200;
    /** prune tracked positions only once a set exceeds this, so the scan cost is bounded. */
    private static final int PRUNE_THRESHOLD = 8192;
    /** a position farther than this (chunks) from every player and not loaded may be forgotten. */
    private static final int PRUNE_DISTANCE = 96;
    /** cap on entries examined per set per prune, so a large set cannot stall the tick. */
    private static final int PRUNE_SCAN_CAP = 16_384;
    /** how often the rescue sweep for externally-generated chunks runs (ticks). */
    private static final int RESCUE_INTERVAL_TICKS = 20;
    /** how many proxy positions one rescue sweep may examine, so the tick stays cheap. */
    private static final int RESCUE_SCAN_CAP = 256;
    /**
     * How far a rescued entity may be lifted. Deeper than this and it is underground on purpose, so it is
     * left alone rather than flung to the surface.
     */
    private static final int MAX_PUSH_STEPS = 12;
    private static final long[] NO_CENTERS = new long[0];

    private static final Map<ResourceKey<Level>, State> STATES = new HashMap<>();
    /** {@code ChunkMap} identity -> state, for the hot {@code ChunkHolder.updateFutures} dispatch. */
    private static final Map<ChunkMap, State> BY_CHUNK_MAP = new HashMap<>();

    private static MinecraftServer server;
    private static boolean active;
    /** bisect switch: when off, nothing is gated and reads fall through to vanilla (vanilla generation). */
    private static boolean gateEnabled = true;
    /** bisect switch: when off, promotion still runs but the manual chunk packet resend is skipped. */
    private static boolean resendEnabled = true;

    private static net.minecraft.core.Registry<Biome> biomeRegistry;
    private static Holder<Biome> fallbackBiome;

    private ProxyChunkGate() {
    }

    /** per-{@code ServerLevel} gate state. */
    private static final class State {
        final ServerLevel level;
        final ChunkMap chunkMap;
        /** promotion allow-list: the only positions permitted to create their own FULL task. */
        final LongOpenHashSet allowed = new LongOpenHashSet();
        /** positions confirmed disk-FULL this session: vanilla prepare runs, never gated. */
        final LongOpenHashSet real = new LongOpenHashSet();
        /** positions confirmed non-FULL: gated until promoted. */
        final LongOpenHashSet proxy = new LongOpenHashSet();
        /** positions with an EMPTY classification probe in flight. */
        final LongOpenHashSet probing = new LongOpenHashSet();
        /**
         * promoted positions whose FULL future has not completed yet. Reads keep getting the placeholder until
         * it has, so a main-thread read never {@code managedBlock}s on the generation (#13).
         */
        final LongOpenHashSet promoting = new LongOpenHashSet();
        /** in-memory read-only placeholders handed to chunk reads while the proxy is active. */
        final Long2ObjectOpenHashMap<EmptyLevelChunk> placeholders = new Long2ObjectOpenHashMap<>();
        /** classification completions that must not run inline inside {@code updateFutures}. */
        final ConcurrentLinkedQueue<Runnable> deferred = new ConcurrentLinkedQueue<>();
        /** promoted chunks whose client resend is deferred off the promotion callback. */
        final ArrayDeque<ChunkPos> resendQueue = new ArrayDeque<>();
        /**
         * promoted chunks whose post-promotion work (push-out-of-terrain, deferred-write replay, resend) is
         * deferred to the tick. It must never run inside the chunk-task completion callback:
         * {@code noCollision}/{@code setBlock} force-load neighbour chunks via {@code managedBlock}, which
         * deadlocks the very task queue it is running on.
         */
        final ArrayDeque<Completed> completedQueue = new ArrayDeque<>();
        /** cheap fast-bail flag: true once any position in this level was classified a proxy. */
        volatile boolean anyProxy;

        // adaptive zone state, pushed per level each tick by AdaptiveMode.
        boolean enforcing;
        long[] optInCenters = NO_CENTERS;
        int zoneRadius;
        boolean adaptiveEnabled = true;

        int pruneTicks;
        /** EMA of the main-thread post-promotion work per chunk (ns); drives the promotion budget. */
        long postCostEmaNanos = 5_000_000L;

        State(ServerLevel level) {
            this.level = level;
            this.chunkMap = level.getChunkSource().chunkMap;
        }
    }

    /** a promotion that finished on the chunk-task thread; its main-thread work runs on the next tick. */
    private record Completed(ChunkPos pos, boolean push, Runnable onComplete) {
    }

    /** installs the gate for one level (called from {@link ProxyStreamer#init} for every noise-based level). */
    public static void init(ServerLevel lvl) {
        active = true;
        server = lvl.getServer();
        biomeRegistry = lvl.registryAccess().registryOrThrow(Registries.BIOME);
        fallbackBiome = biomeRegistry.getHolderOrThrow(Biomes.PLAINS);
        gateEnabled = AeroGelConfig.CFG.gateEnabled.get();
        resendEnabled = AeroGelConfig.CFG.resendEnabled.get();
        State state = new State(lvl);
        STATES.put(lvl.dimension(), state);
        BY_CHUNK_MAP.put(state.chunkMap, state);
    }

    public static void shutdown() {
        active = false;
        STATES.clear();
        BY_CHUNK_MAP.clear();
        server = null;
    }

    public static boolean isActive() {
        return active && gateEnabled;
    }

    /** true when this level has an installed gate. */
    public static boolean isActive(ServerLevel lvl) {
        return active && gateEnabled && state(lvl) != null;
    }

    public static boolean gateEnabled() {
        return gateEnabled;
    }

    public static void setGateEnabled(boolean on) {
        gateEnabled = on;
        AeroGelConfig.CFG.gateEnabled.set(on);
        AeroGelConfig.SPEC.save();
    }

    public static boolean resendEnabled() {
        return resendEnabled;
    }

    public static void setResendEnabled(boolean on) {
        resendEnabled = on;
        AeroGelConfig.CFG.resendEnabled.set(on);
        AeroGelConfig.SPEC.save();
    }

    private static State state(Level lvl) {
        return lvl == null ? null : STATES.get(lvl.dimension());
    }

    private static State state(ServerLevel lvl) {
        return lvl == null ? null : STATES.get(lvl.dimension());
    }

    /**
     * Refresh one level's adaptive zone state; called from {@link AdaptiveMode} each tick. {@code optIn} is
     * every opted-in player's chunk position in this dimension; the array is owned by the caller and is never
     * mutated after this call, so the gate can hold the reference until the next push.
     */
    public static void setAdaptive(ServerLevel lvl, boolean enforcingNow, long[] optIn, int radius, boolean enabled) {
        State state = state(lvl);
        if (state == null) {
            return;
        }
        state.enforcing = enforcingNow;
        state.optInCenters = optIn;
        state.zoneRadius = radius;
        state.adaptiveEnabled = enabled;
    }

    /** set the global enforcement/adaptive state on every installed level (headless probe hook). */
    public static void setAdaptiveAll(boolean enforcingNow, boolean enabled) {
        for (State state : STATES.values()) {
            state.enforcing = enforcingNow;
            state.optInCenters = NO_CENTERS;
            state.zoneRadius = 0;
            state.adaptiveEnabled = enabled;
        }
    }

    public static boolean isEnforcing(ServerLevel lvl) {
        State state = state(lvl);
        return state != null && state.enforcing;
    }

    /**
     * True when the gate should apply at this position, ignoring the allowed/real classification. While the
     * server is enforcing this is global -- no zone scan at all; otherwise it is the union of the opted-in
     * players' zones. Generation is shared, so a player who declined {@code travel} and stands inside one of
     * those zones is gated too ({@code AdaptiveMode} carries why there is no opt-out subtraction).
     */
    public static boolean isEngaged(ServerLevel lvl, long pos) {
        return isEngaged(state(lvl), pos);
    }

    private static boolean isEngaged(State state, long pos) {
        if (!active || !gateEnabled || state == null) {
            return false;
        }
        if (!state.adaptiveEnabled || state.enforcing) {
            return true;
        }
        int x = ChunkPos.getX(pos);
        int z = ChunkPos.getZ(pos);
        return nearAny(state.optInCenters, x, z, state.zoneRadius);
    }

    private static boolean nearAny(long[] centers, int x, int z, int radius) {
        for (long center : centers) {
            int dx = Math.abs(ChunkPos.getX(center) - x);
            int dz = Math.abs(ChunkPos.getZ(center) - z);
            if (Math.max(dx, dz) <= radius) {
                return true;
            }
        }
        return false;
    }

    /**
     * Cheap per-level fast bail for the hot read/write/fluid paths: true when this level could possibly have
     * a proxy (or is engaged and may create one). Does not touch any position, so it is safe before the
     * expensive classification chain.
     */
    public static boolean mayHaveProxy(ServerLevel lvl) {
        State state = state(lvl);
        if (state == null || !active || !gateEnabled) {
            return false;
        }
        if (state.anyProxy || !state.promoting.isEmpty()) {
            return true;
        }
        if (!state.adaptiveEnabled || state.enforcing) {
            return true;
        }
        return state.optInCenters.length > 0;
    }

    /** true when the position must NOT build its own FULL future (engaged and not promoted/known-real). */
    public static boolean isGated(ServerLevel lvl, long pos) {
        return isGated(state(lvl), pos);
    }

    /** dispatch for the {@code updateFutures} redirects, which only have the {@link ChunkMap}. */
    public static boolean isGated(ChunkMap chunkMap, long pos) {
        return isGated(BY_CHUNK_MAP.get(chunkMap), pos);
    }

    private static boolean isGated(State state, long pos) {
        return isEngaged(state, pos)
            && !state.allowed.contains(pos)
            && !state.real.contains(pos);
    }

    /**
     * true while the position is a proxy placeholder (engaged, or a leftover from a previous engaged period).
     * Cheap set lookups run before the {@code loadedNow} query so a dormant level costs only two set probes.
     */
    public static boolean isProxy(ServerLevel lvl, long pos) {
        State state = state(lvl);
        if (state == null || !active || state.allowed.contains(pos) || state.real.contains(pos)) {
            return false;
        }
        boolean engaged = isEngaged(state, pos);
        if (!engaged && !state.proxy.contains(pos)) {
            return false; // cheap: no loadedNow() needed
        }
        if (loadedNow(state, pos)) {
            return false; // a vanilla-loaded chunk is real regardless of classification
        }
        return true;
    }

    /**
     * true while a promotion of this position is generating (FULL future not complete). It is no longer a
     * proxy, but reads and writes must still treat it as one: vanilla would block the main thread on it.
     */
    public static boolean isPromoting(ServerLevel lvl, long pos) {
        State state = state(lvl);
        return state != null && state.promoting.contains(pos);
    }

    /** true when the holder already has this position in memory at {@code status} (no generation needed). */
    public static boolean isAvailable(ServerLevel lvl, long pos, ChunkStatus status) {
        State state = state(lvl);
        ChunkHolder holder = state == null ? null : state.chunkMap.getVisibleChunkIfPresent(pos);
        return holder != null && holder.getChunkIfPresent(status) != null;
    }

    /**
     * Classifies a not-loaded, unclassified position from disk without blocking: one async IOWorker read
     * (pending writes included, never generates); on the next tick it lands in {@code real} (FULL on disk:
     * loading is not generation) or {@code proxy}. Until then reads get the placeholder (Maric 2026-10-13: a
     * tick or two is fine). A synchronous EMPTY load here queued behind worldgen on the main thread: 1-8 s
     * stalls per new position during fast flight.
     */
    public static void probeDisk(ServerLevel lvl, int x, int z) {
        State state = state(lvl);
        long pos = ChunkPos.asLong(x, z);
        if (state == null || state.proxy.contains(pos) || state.probing.contains(pos)) {
            return;
        }
        state.probing.add(pos);
        lvl.getChunkSource().chunkMap.read(new ChunkPos(pos)).whenComplete((tag, error) -> state.deferred.add(() -> {
            state.probing.remove(pos);
            if (tag != null && tag.isPresent()
                && ChunkSerializer.getChunkTypeFromTag(tag.get()) == ChunkType.LEVELCHUNK) {
                state.real.add(pos);
                // a ticket that arrived meanwhile was gated (probing): rebuild its futures, as finishProbe does
                ChunkHolder holder = state.chunkMap.getVisibleChunkIfPresent(pos);
                if (holder != null) {
                    forcePrepare(holder, state.chunkMap);
                }
            } else {
                state.proxy.add(pos);
                state.anyProxy = true;
            }
        }));
    }

    /**
     * #14: true unless the position is loaded or known disk-FULL this session. Set lookups and
     * {@code getChunkNow} only: the async {@link #probeDisk} cannot answer before a teleport moves the player, so
     * an unclassified position counts as maybe-ungenerated.
     */
    public static boolean mayBeUngenerated(ServerLevel lvl, long pos) {
        State state = state(lvl);
        return isActive(lvl) && !state.real.contains(pos) && !state.allowed.contains(pos) && !loadedNow(state, pos);
    }

    /** test hook (dev probe): forget a position's classification, as if this session never touched it. */
    public static void debugForget(ServerLevel lvl, long pos) {
        State state = state(lvl);
        if (state != null) {
            state.allowed.remove(pos);
            state.real.remove(pos);
            state.proxy.remove(pos);
            state.probing.remove(pos);
            state.promoting.remove(pos);
        }
    }

    private static boolean loadedNow(State state, long pos) {
        return state != null
            && state.level.getChunkSource().getChunkNow(ChunkPos.getX(pos), ChunkPos.getZ(pos)) != null;
    }

    /** true when vanilla has this position loaded as a real chunk (the client gets it from vanilla). */
    public static boolean isLoadedReal(ServerLevel lvl, long pos) {
        return loadedNow(state(lvl), pos);
    }

    /** true when this level has a holder for the position whose ticket reaches FULL (a promotion can start). */
    public static boolean hasHolder(ServerLevel lvl, long pos) {
        State state = state(lvl);
        return state != null && fullHolder(state, pos) != null;
    }

    /**
     * The visible holder, only if its ticket level reaches FULL. A lower holder (a border chunk, or the EMPTY
     * load of a read) cannot build a FULL future: {@link #forcePrepare} would leave the unloaded sentinel,
     * which completes at once and reads as a finished promotion.
     */
    private static ChunkHolder fullHolder(State state, long pos) {
        ChunkHolder holder = state.chunkMap.getVisibleChunkIfPresent(pos);
        return holder != null && ChunkLevel.fullStatus(holder.getTicketLevel()).isOrAfter(FullChunkStatus.FULL)
            ? holder : null;
    }

    /** EMA of the main-thread cost of finishing one promotion (push + replay + resend), in ms. */
    public static double postCostMs(ServerLevel lvl) {
        State state = state(lvl);
        return state == null ? 5.0 : state.postCostEmaNanos / 1_000_000.0;
    }

    // ---- classification ----

    /** called at {@code ChunkHolder.updateFutures} HEAD on the server thread. */
    public static void onUpdateFutures(ChunkHolder holder, ChunkMap map) {
        State state = BY_CHUNK_MAP.get(map);
        if (!active || state == null) {
            return;
        }
        long pos = holder.getPos().toLong();
        if (!isEngaged(state, pos)) {
            return; // gate off here: let vanilla build the FULL future
        }
        if (state.allowed.contains(pos) || state.real.contains(pos)
            || state.proxy.contains(pos) || state.probing.contains(pos)) {
            return;
        }
        state.probing.add(pos);
        CompletableFuture<ChunkResult<ChunkAccess>> future;
        try {
            future = holder.scheduleChunkGenerationTask(ChunkStatus.EMPTY, map);
        } catch (Throwable t) {
            LOGGER.error("proxy classification probe failed to schedule for {}", holder.getPos(), t);
            state.probing.remove(pos);
            state.proxy.add(pos);
            state.anyProxy = true;
            return;
        }
        future.whenComplete((result, error) -> state.deferred.add(() -> finishProbe(state, pos, holder, map)));
    }

    private static void finishProbe(State state, long pos, ChunkHolder holder, ChunkMap map) {
        state.probing.remove(pos);
        if (!active || !isEngaged(state, pos)) {
            return; // disengaged while the probe was in flight: leave it unclassified
        }
        ChunkStatus persisted = holder.getPersistedStatus();
        if (persisted != null && persisted.isOrAfter(ChunkStatus.FULL)) {
            state.real.add(pos);
            forcePrepare(holder, map);
        } else {
            state.proxy.add(pos);
            state.anyProxy = true;
        }
    }

    /**
     * Force a holder to rebuild its cached full/ticking/entity-ticking futures from its current status
     * futures. The cached futures are reset to the exact unloaded sentinel first: {@code updateFutures}'s
     * ENTITY_TICKING branch throws unless {@code entityTickingChunkFuture} is that same object by identity,
     * and a throw there is swallowed, leaving the holder half-demoted and crashing the next vanilla
     * {@code DistanceManager.runAllUpdates}.
     */
    static void forcePrepare(ChunkHolder holder, ChunkMap map) {
        ChunkHolderAccessor access = (ChunkHolderAccessor) (Object) holder;
        CompletableFuture<ChunkResult<LevelChunk>> unloaded = ChunkHolderAccessor.unloadedFuture();
        access.setFullChunkFuture(unloaded);
        access.setTickingChunkFuture(unloaded);
        access.setEntityTickingChunkFuture(unloaded);
        access.setOldTicketLevel(ChunkLevel.MAX_LEVEL);
        access.invokeUpdateFutures(map, server);
    }

    // ---- promotion ----

    /** manual promotion entry point ({@code /aerogel generate}); may block on a missing holder. */
    public static boolean promote(ServerLevel lvl, ChunkPos pos) {
        return promote(lvl, pos, null, true);
    }

    /**
     * Promotion = "stop blocking". Add the position to the allow-list and force its FULL future; the chunk
     * generates forward from whatever status it already reached (no backwards reset). {@code onComplete} runs
     * on the server thread once the vanilla pipeline has finished (or failed). The caller
     * ({@link PromotionManager}) pre-checks the holder, so this never blocks.
     */
    public static boolean promote(ServerLevel lvl, ChunkPos pos, Runnable onComplete) {
        return promote(lvl, pos, onComplete, false);
    }

    private static boolean promote(ServerLevel lvl, ChunkPos pos, Runnable onComplete, boolean manual) {
        State state = state(lvl);
        if (!active || state == null) {
            return false;
        }
        long p = pos.toLong();
        state.allowed.add(p);
        state.proxy.remove(p);
        state.anyProxy = !state.proxy.isEmpty();
        ChunkHolder holder = fullHolder(state, p);
        if (holder == null) {
            if (manual) {
                lvl.getChunk(pos.x, pos.z);
                if (onComplete != null) {
                    onComplete.run();
                }
                return true;
            }
            // non-manual callers pre-check the holder; a miss means it unloaded between check and promote.
            state.allowed.remove(p);
            LOGGER.warn("promotion holder missing for {} in {}; leaving it gated", pos, lvl.dimension().location());
            return false;
        }
        forcePrepare(holder, state.chunkMap);
        state.promoting.add(p);
        holder.getFullChunkFuture().whenComplete((result, error) -> server.execute(() -> {
            // This runs inline on the chunk-task completion (main thread). Do NOT read/collide/load chunks
            // here: Level.getChunk would managedBlock the task queue it is running on (watchdog deadlock).
            // Only enqueue the post-promotion work; ProxyChunkGate.tick runs it as a normal tick task.
            state.promoting.remove(p);
            if (error != null) {
                LOGGER.error("proxy chunk {} promotion failed", pos, error);
            } else {
                LOGGER.info("promoted proxy chunk {} to real terrain (vanilla pipeline)", pos);
            }
            state.completedQueue.add(new Completed(pos, error == null, onComplete));
        }));
        return true;
    }

    /**
     * A chunk can generate with real blocks where an entity already is (the proxy surface was a diagonal
     * approximation). Push anything overlapping the new geometry straight up until its bounding box is free.
     */
    public static void pushOutOfTerrain(ServerLevel lvl, ChunkPos pos) {
        AABB area = new AABB(pos.getMinBlockX(), lvl.getMinBuildHeight(), pos.getMinBlockZ(),
            pos.getMaxBlockX() + 1, lvl.getMaxBuildHeight(), pos.getMaxBlockZ() + 1);
        List<Entity> targets = new ArrayList<>(lvl.getEntitiesOfClass(Entity.class, area));
        targets.addAll(lvl.getChunkSource().chunkMap.getPlayers(pos, false));
        for (Entity entity : targets) {
            if (entity.isSpectator()) {
                continue;
            }
            AABB box = entity.getBoundingBox();
            if (!chunksLoaded(lvl, box)) {
                continue; // never force-load a neighbour chunk on the main thread (deadlocks the task queue)
            }
            if (lvl.noCollision(entity, box)) {
                continue;
            }
            double y = entity.getY();
            int steps = 0;
            while (!lvl.noCollision(entity, box) && steps++ < MAX_PUSH_STEPS) {
                y += 1.0;
                box = box.move(0.0, 1.0, 0.0);
            }
            entity.teleportTo(entity.getX(), y, entity.getZ());
            entity.setDeltaMovement(0.0, 0.0, 0.0);
            LOGGER.info("pushed {} out of freshly generated terrain at {} to y={}",
                entity.getName().getString(), pos, String.format("%.1f", y));
        }
    }

    /** true when every chunk overlapping {@code box} (plus a 1-block margin) is already loaded. */
    private static boolean chunksLoaded(ServerLevel lvl, AABB box) {
        int minX = (int) Math.floor(box.minX) - 1;
        int maxX = (int) Math.floor(box.maxX) + 1;
        int minZ = (int) Math.floor(box.minZ) - 1;
        int maxZ = (int) Math.floor(box.maxZ) + 1;
        for (int cx = minX >> 4; cx <= (maxX >> 4); cx++) {
            for (int cz = minZ >> 4; cz <= (maxZ >> 4); cz++) {
                if (lvl.getChunkSource().getChunkNow(cx, cz) == null) {
                    return false;
                }
            }
        }
        return true;
    }

    private static void resend(ServerLevel lvl, ChunkPos pos) {
        LevelChunk chunk = lvl.getChunkSource().getChunkNow(pos.x, pos.z);
        if (chunk == null) {
            LOGGER.warn("proxy chunk {} promoted but is no longer loaded", pos);
            return;
        }
        // Light was computed by the vanilla generation pipeline, so the packet can be built directly from
        // the light engine. Re-running updateSectionStatus + propagateLightSources here was the dominant
        // cost of the promotion stall (hundreds of ms for a freshly generated chunk).
        net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket packet =
            new net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket(chunk, lvl.getLightEngine(), null, null);
        for (ServerPlayer player : lvl.getChunkSource().chunkMap.getPlayers(pos, false)) {
            player.connection.send(packet);
        }
    }

    // ---- placeholder ----

    /** read-only void-air placeholder for a proxy position (cached; carries the sampled biome for F3). */
    public static ChunkAccess placeholder(Level lvl, int x, int z) {
        State state = state(lvl);
        long pos = ChunkPos.asLong(x, z);
        if (state == null) {
            return new EmptyLevelChunk(lvl, new ChunkPos(x, z), fallbackBiome);
        }
        EmptyLevelChunk chunk = state.placeholders.get(pos);
        if (chunk == null) {
            chunk = new EmptyLevelChunk(lvl, new ChunkPos(x, z), biomeFor(lvl, pos));
            state.placeholders.put(pos, chunk);
        }
        return chunk;
    }

    private static Holder<Biome> biomeFor(Level lvl, long pos) {
        if (!(lvl instanceof ServerLevel serverLevel)) {
            return fallbackBiome;
        }
        Holder<Biome> holder = ProxyStreamer.biomeHolder(serverLevel, pos);
        return holder != null ? holder : fallbackBiome;
    }

    // ---- tick ----

    /** drain classification completions/resends and prune the per-level caches; called from the server tick. */
    public static void tick(MinecraftServer srv) {
        if (!active) {
            return;
        }
        drainDeferred(srv);
        drainCompletions(srv);
        drainResends(srv);
        for (State state : STATES.values()) {
            if (++state.pruneTicks % RESCUE_INTERVAL_TICKS == 0) {
                rescueGenerated(state);
            }
            if (state.pruneTicks % PRUNE_INTERVAL_TICKS == 0) {
                prunePlaceholders(srv, state);
                pruneSets(srv, state);
            }
        }
    }

    /**
     * Rescue sweep for chunks that became real <em>without</em> a promotion of ours — dependency generation
     * and vanilla's own pipeline. Terrain can appear around a player who is standing on a proxy surface, or
     * flying into chunks being generated ahead of them, sealing them in; nothing else runs
     * {@link #pushOutOfTerrain} for those, so they stay stuck inside a mountain.
     *
     * <p>Only positions this gate classified as proxies are considered, so a player who digs themselves into
     * ordinary terrain is never touched, and {@link #MAX_PUSH_STEPS} leaves anyone genuinely deep underground
     * where they are instead of throwing them to the surface.</p>
     */
    private static void rescueGenerated(State state) {
        if (state.proxy.isEmpty()) {
            return;
        }
        int scanned = 0;
        var it = state.proxy.iterator();
        while (it.hasNext() && scanned++ < RESCUE_SCAN_CAP) {
            long pos = it.nextLong();
            if (!loadedNow(state, pos)) {
                continue; // still a proxy: nothing was generated
            }
            it.remove();
            state.real.add(pos);
            pushOutOfTerrain(state.level, new ChunkPos(pos));
        }
        state.anyProxy = !state.proxy.isEmpty();
    }

    /**
     * Time-budgeted drain of classification completions. A burst of disk-FULL probes each run
     * {@code forcePrepare -> updateFutures}; the old raw 4096 cap could stall a tick. Always runs at least
     * one task so progress is never starved.
     */
    private static void drainDeferred(MinecraftServer srv) {
        long deadline = System.nanoTime() + PROBE_DRAIN_BUDGET_NANOS;
        boolean ran = false;
        for (State state : STATES.values()) {
            Runnable task;
            while ((task = state.deferred.poll()) != null) {
                task.run();
                ran = true;
                if (System.nanoTime() >= deadline) {
                    break;
                }
            }
            if (ran && System.nanoTime() >= deadline) {
                break;
            }
        }
    }

    /** Time-budgeted drain of deferred chunk resends (see {@link #resend}). */
    private static void drainResends(MinecraftServer srv) {
        if (!resendEnabled) {
            for (State state : STATES.values()) {
                state.resendQueue.clear();
            }
            return;
        }
        long deadline = System.nanoTime() + RESEND_DRAIN_BUDGET_NANOS;
        boolean ran = false;
        for (State state : STATES.values()) {
            ChunkPos pos;
            while ((pos = state.resendQueue.poll()) != null) {
                long t0 = System.nanoTime();
                resend(state.level, pos);
                state.postCostEmaNanos = ema(state.postCostEmaNanos, System.nanoTime() - t0);
                ran = true;
                if (System.nanoTime() >= deadline) {
                    break;
                }
            }
            if (ran && System.nanoTime() >= deadline) {
                break;
            }
        }
    }

    /**
     * Drain finished promotions on the server tick (a normal main-thread task, not nested inside
     * chunk-task processing): push entities out of the new terrain, replay deferred writes, queue the
     * client resend. {@link #pushOutOfTerrain} additionally guards against unloaded neighbour chunks.
     */
    private static void drainCompletions(MinecraftServer srv) {
        for (State state : STATES.values()) {
            Completed completed;
            while ((completed = state.completedQueue.poll()) != null) {
                long t0 = System.nanoTime();
                try {
                    if (completed.push()) {
                        pushOutOfTerrain(state.level, completed.pos());
                        if (resendEnabled) {
                            state.resendQueue.add(completed.pos());
                        }
                    }
                } catch (Throwable t) {
                    LOGGER.error("post-promotion work failed for {}", completed.pos(), t);
                } finally {
                    if (completed.onComplete() != null) {
                        completed.onComplete().run();
                    }
                    state.postCostEmaNanos = ema(state.postCostEmaNanos, System.nanoTime() - t0);
                }
            }
        }
    }

    private static long ema(long current, long sample) {
        return (current * 7 + sample * 3) / 10;
    }

    private static void prunePlaceholders(MinecraftServer srv, State state) {
        if (state.placeholders.isEmpty()) {
            return;
        }
        List<ChunkPos> centers = playerCenters(srv, state.level);
        int margin = ProxyStreamer.radius() + 4;
        var it = state.placeholders.long2ObjectEntrySet().iterator();
        while (it.hasNext()) {
            long pos = it.next().getLongKey();
            if (!nearAnyCenter(centers, pos, margin)) {
                it.remove();
            }
        }
    }

    /**
     * Bounded pruning of the tracked sets. Without this a long session grows {@code real}/{@code allowed}/
     * {@code proxy} forever. We only drop positions that are (a) not currently loaded and (b) farther than
     * {@link #PRUNE_DISTANCE} from every player in the level, so an active chunk is never forgotten. Runs
     * only when a set exceeds {@link #PRUNE_THRESHOLD} so the scan stays cheap. {@code anyProxy} is
     * recomputed after the proxy set shrinks.
     */
    private static void pruneSets(MinecraftServer srv, State state) {
        if (state.proxy.size() <= PRUNE_THRESHOLD
            && state.real.size() <= PRUNE_THRESHOLD
            && state.allowed.size() <= PRUNE_THRESHOLD) {
            return;
        }
        List<ChunkPos> centers = playerCenters(srv, state.level);
        pruneSet(state.real, state, centers);
        pruneSet(state.allowed, state, centers);
        if (pruneSet(state.proxy, state, centers)) {
            state.anyProxy = !state.proxy.isEmpty();
        }
    }

    private static boolean pruneSet(LongOpenHashSet set, State state, List<ChunkPos> centers) {
        if (set.isEmpty()) {
            return false;
        }
        boolean removed = false;
        int scanned = 0;
        var it = set.iterator();
        while (it.hasNext() && scanned++ < PRUNE_SCAN_CAP) {
            long pos = it.nextLong();
            if (loadedNow(state, pos) || nearAnyCenter(centers, pos, PRUNE_DISTANCE)) {
                continue;
            }
            it.remove();
            removed = true;
        }
        return removed;
    }

    private static List<ChunkPos> playerCenters(MinecraftServer srv, ServerLevel level) {
        List<ChunkPos> centers = new ArrayList<>();
        for (ServerPlayer player : srv.getPlayerList().getPlayers()) {
            if (player.level() == level && !player.hasDisconnected()) {
                centers.add(player.chunkPosition());
            }
        }
        return centers;
    }

    private static boolean nearAnyCenter(List<ChunkPos> centers, long pos, int margin) {
        int x = ChunkPos.getX(pos);
        int z = ChunkPos.getZ(pos);
        for (ChunkPos center : centers) {
            if (Math.max(Math.abs(x - center.x), Math.abs(z - center.z)) <= margin) {
                return true;
            }
        }
        return false;
    }
}
