package dev.aerogel.server;

import dev.aerogel.common.AeroGelConfig;
import dev.aerogel.mixin.ChunkHolderAccessor;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
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
 * <p>All state is touched on the server thread (the probe callback is queued and drained from the tick).</p>
 */
public final class ProxyChunkGate {
    private static final Logger LOGGER = LoggerFactory.getLogger("aerogel.gate");

    private static ServerLevel level;
    private static ChunkMap chunkMap;
    private static MinecraftServer server;
    private static boolean active;
    /** bisect switch: when off, nothing is gated and reads fall through to vanilla (vanilla generation). */
    private static boolean gateEnabled = true;
    /** bisect switch: when off, promotion still runs but the manual chunk packet resend is skipped. */
    private static boolean resendEnabled = true;

    /** adaptive: when true the gate applies globally (the server is struggling). */
    private static boolean enforcing;
    /** adaptive: when false the gate behaves as always-on (legacy behaviour). */
    private static boolean adaptiveEnabled = true;
    /** opt-in / opt-out player chunk centres (packed longs) and the zone radius, refreshed each tick. */
    private static long[] optInCenters = new long[0];
    private static long[] optOutCenters = new long[0];
    private static int zoneRadius;

    /** promotion allow-list: the only positions permitted to create their own FULL task. */
    private static final LongOpenHashSet allowed = new LongOpenHashSet();
    /** positions confirmed disk-FULL this session: vanilla prepare runs, never gated. */
    private static final LongOpenHashSet real = new LongOpenHashSet();
    /** positions confirmed non-FULL: gated until promoted. */
    private static final LongOpenHashSet proxy = new LongOpenHashSet();
    /** positions with an EMPTY classification probe in flight. */
    private static final LongOpenHashSet probing = new LongOpenHashSet();

    /** in-memory read-only placeholders handed to chunk reads while the proxy is active. */
    private static final Long2ObjectOpenHashMap<EmptyLevelChunk> placeholders = new Long2ObjectOpenHashMap<>();
    private static net.minecraft.core.Registry<Biome> biomeRegistry;
    private static Holder<Biome> fallbackBiome;

    /** classification completions that must not run inline inside {@code updateFutures}. */
    private static final ConcurrentLinkedQueue<Runnable> deferred = new ConcurrentLinkedQueue<>();

    private static int pruneTicks;

    private ProxyChunkGate() {
    }

    public static void init(ServerLevel overworld) {
        level = overworld;
        chunkMap = overworld.getChunkSource().chunkMap;
        server = overworld.getServer();
        biomeRegistry = overworld.registryAccess().registryOrThrow(Registries.BIOME);
        fallbackBiome = biomeRegistry.getHolderOrThrow(Biomes.PLAINS);
        allowed.clear();
        real.clear();
        proxy.clear();
        probing.clear();
        placeholders.clear();
        deferred.clear();
        active = true;
        gateEnabled = AeroGelConfig.CFG.gateEnabled.get();
        resendEnabled = AeroGelConfig.CFG.resendEnabled.get();
    }

    public static void shutdown() {
        active = false;
        level = null;
        chunkMap = null;
        server = null;
        allowed.clear();
        real.clear();
        proxy.clear();
        probing.clear();
        placeholders.clear();
        deferred.clear();
    }

    public static boolean isActive() {
        return active && gateEnabled;
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

    /** refresh the adaptive zone state; called from {@link AdaptiveMode} each tick. */
    public static void setAdaptive(boolean enforcingNow, long[] optIn, long[] optOut, int radius, boolean enabled) {
        enforcing = enforcingNow;
        optInCenters = optIn;
        optOutCenters = optOut;
        zoneRadius = radius;
        adaptiveEnabled = enabled;
    }

    public static boolean isEnforcing() {
        return enforcing;
    }

    /**
     * True when the gate should apply at this position, ignoring the allowed/real classification. While the
     * server is enforcing this is global; otherwise it is the union of opt-in player zones minus opt-out
     * player zones (generation is shared, so an opted-out player forces vanilla locally).
     */
    public static boolean isEngaged(long pos) {
        if (!active || !gateEnabled) {
            return false;
        }
        if (!adaptiveEnabled || enforcing) {
            return true;
        }
        int x = ChunkPos.getX(pos);
        int z = ChunkPos.getZ(pos);
        return nearAny(optInCenters, x, z) && !nearAny(optOutCenters, x, z);
    }

    private static boolean nearAny(long[] centers, int x, int z) {
        for (long center : centers) {
            int dx = Math.abs(ChunkPos.getX(center) - x);
            int dz = Math.abs(ChunkPos.getZ(center) - z);
            if (Math.max(dx, dz) <= zoneRadius) {
                return true;
            }
        }
        return false;
    }

    /** true when the position must NOT build its own FULL future (engaged and not promoted/known-real). */
    public static boolean isGated(long pos) {
        return isEngaged(pos) && !allowed.contains(pos) && !real.contains(pos);
    }

    /** true when vanilla may load/generate this position (promoted, or a known disk-FULL chunk). */
    public static boolean isRealOrAllowed(long pos) {
        return allowed.contains(pos) || real.contains(pos);
    }

    /** true while the position is a proxy placeholder (engaged, or a leftover from a previous engaged period). */
    public static boolean isProxy(long pos) {
        if (!active || allowed.contains(pos) || real.contains(pos)) {
            return false;
        }
        if (loadedNow(pos)) {
            return false; // a vanilla-loaded chunk is real regardless of classification
        }
        return isEngaged(pos) || proxy.contains(pos);
    }

    private static boolean loadedNow(long pos) {
        return level != null
            && level.getChunkSource().getChunkNow(ChunkPos.getX(pos), ChunkPos.getZ(pos)) != null;
    }

    /** true when vanilla has this position loaded as a real chunk (the client gets it from vanilla). */
    public static boolean isLoadedReal(long pos) {
        return loadedNow(pos);
    }

    /** true while any chunk is classified as a proxy (used to skip idle work). */
    public static boolean hasProxies() {
        return !proxy.isEmpty();
    }

    public static boolean isAllowed(long pos) {
        return allowed.contains(pos);
    }

    // ---- classification ----

    /** called at {@code ChunkHolder.updateFutures} HEAD on the server thread. */
    public static void onUpdateFutures(ChunkHolder holder, ChunkMap map) {
        if (!active) {
            return;
        }
        long pos = holder.getPos().toLong();
        if (!isEngaged(pos)) {
            return; // gate off here: let vanilla build the FULL future
        }
        if (allowed.contains(pos) || real.contains(pos) || proxy.contains(pos) || probing.contains(pos)) {
            return;
        }
        probing.add(pos);
        CompletableFuture<ChunkResult<ChunkAccess>> future;
        try {
            future = holder.scheduleChunkGenerationTask(ChunkStatus.EMPTY, map);
        } catch (Throwable t) {
            LOGGER.error("proxy classification probe failed to schedule for {}", holder.getPos(), t);
            probing.remove(pos);
            proxy.add(pos);
            return;
        }
        future.whenComplete((result, error) -> deferred.add(() -> finishProbe(pos, holder, map)));
    }

    private static void finishProbe(long pos, ChunkHolder holder, ChunkMap map) {
        probing.remove(pos);
        if (!active || !isEngaged(pos)) {
            return; // disengaged while the probe was in flight: leave it unclassified
        }
        ChunkStatus persisted = holder.getPersistedStatus();
        if (persisted != null && persisted.isOrAfter(ChunkStatus.FULL)) {
            real.add(pos);
            forcePrepare(holder, map);
        } else {
            proxy.add(pos);
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

    /** manual promotion entry point ({@code /aerogel generate}). */
    public static boolean promote(ServerLevel lvl, ChunkPos pos) {
        return promote(lvl, pos, null);
    }

    /**
     * Promotion = "stop blocking". Add the position to the allow-list and force its FULL future; the chunk
     * generates forward from whatever status it already reached (no backwards reset). {@code onComplete} runs
     * on the server thread once the vanilla pipeline has finished (or failed).
     */
    public static boolean promote(ServerLevel lvl, ChunkPos pos, Runnable onComplete) {
        if (!active || lvl != level) {
            return false;
        }
        long p = pos.toLong();
        allowed.add(p);
        proxy.remove(p);
        ChunkHolder holder = chunkMap.getVisibleChunkIfPresent(p);
        if (holder == null) {
            lvl.getChunk(pos.x, pos.z);
            if (onComplete != null) {
                onComplete.run();
            }
            return true;
        }
        forcePrepare(holder, chunkMap);
        holder.getFullChunkFuture().whenComplete((result, error) -> server.execute(() -> {
            try {
                if (error != null) {
                    LOGGER.error("proxy chunk {} promotion failed", pos, error);
                    return;
                }
                pushOutOfTerrain(lvl, pos);
                if (resendEnabled) {
                    resend(lvl, pos);
                }
                LOGGER.info("promoted proxy chunk {} to real terrain (vanilla pipeline)", pos);
            } catch (Throwable t) {
                LOGGER.error("proxy chunk {} promotion callback failed", pos, t);
            } finally {
                if (onComplete != null) {
                    onComplete.run();
                }
            }
        }));
        return true;
    }

    /**
     * A chunk can generate with real blocks where an entity already is (the proxy surface was a diagonal
     * approximation). Push anything overlapping the new geometry straight up until its bounding box is free.
     */
    static void pushOutOfTerrain(ServerLevel lvl, ChunkPos pos) {
        AABB area = new AABB(pos.getMinBlockX(), lvl.getMinBuildHeight(), pos.getMinBlockZ(),
            pos.getMaxBlockX() + 1, lvl.getMaxBuildHeight(), pos.getMaxBlockZ() + 1);
        List<Entity> targets = new ArrayList<>(lvl.getEntitiesOfClass(Entity.class, area));
        targets.addAll(lvl.getChunkSource().chunkMap.getPlayers(pos, false));
        for (Entity entity : targets) {
            if (entity.isSpectator()) {
                continue;
            }
            AABB box = entity.getBoundingBox();
            if (lvl.noCollision(entity, box)) {
                continue;
            }
            double y = entity.getY();
            int steps = 0;
            while (!lvl.noCollision(entity, box) && steps++ < lvl.getHeight()) {
                y += 1.0;
                box = box.move(0.0, 1.0, 0.0);
            }
            entity.teleportTo(entity.getX(), y, entity.getZ());
            entity.setDeltaMovement(0.0, 0.0, 0.0);
            LOGGER.info("pushed {} out of freshly generated terrain at {} to y={}",
                entity.getName().getString(), pos, String.format("%.1f", y));
        }
    }

    private static void resend(ServerLevel lvl, ChunkPos pos) {
        LevelChunk chunk = lvl.getChunkSource().getChunkNow(pos.x, pos.z);
        if (chunk == null) {
            LOGGER.warn("proxy chunk {} promoted but is no longer loaded", pos);
            return;
        }
        for (int sectionY = chunk.getMinSection(); sectionY < chunk.getMaxSection(); sectionY++) {
            lvl.getLightEngine().updateSectionStatus(net.minecraft.core.SectionPos.of(pos, sectionY), false);
        }
        lvl.getLightEngine().propagateLightSources(pos);
        net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket packet =
            new net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket(chunk, lvl.getLightEngine(), null, null);
        for (ServerPlayer player : lvl.getChunkSource().chunkMap.getPlayers(pos, false)) {
            player.connection.send(packet);
        }
    }

    // ---- placeholder ----

    /** read-only void-air placeholder for a proxy position (cached; carries the sampled biome for F3). */
    public static ChunkAccess placeholder(net.minecraft.world.level.Level lvl, int x, int z) {
        long pos = ChunkPos.asLong(x, z);
        EmptyLevelChunk chunk = placeholders.get(pos);
        if (chunk == null) {
            chunk = new EmptyLevelChunk(lvl, new ChunkPos(x, z), biomeFor(pos));
            placeholders.put(pos, chunk);
        }
        return chunk;
    }

    private static Holder<Biome> biomeFor(long pos) {
        Holder<Biome> holder = ProxyStreamer.biomeHolder(pos);
        return holder != null ? holder : fallbackBiome;
    }

    // ---- tick ----

    /** drain classification completions and prune the placeholder cache; called from the server tick. */
    public static void tick(MinecraftServer srv) {
        if (!active) {
            return;
        }
        Runnable task;
        int drained = 0;
        while (drained++ < 4096 && (task = deferred.poll()) != null) {
            task.run();
        }
        if (++pruneTicks % 200 == 0) {
            prunePlaceholders(srv);
        }
    }

    private static void prunePlaceholders(MinecraftServer srv) {
        if (placeholders.isEmpty()) {
            return;
        }
        List<ChunkPos> centers = new ArrayList<>();
        for (ServerPlayer player : srv.getPlayerList().getPlayers()) {
            if (player.level() == level && !player.hasDisconnected()) {
                centers.add(player.chunkPosition());
            }
        }
        int margin = ProxyStreamer.radius() + 4;
        var it = placeholders.long2ObjectEntrySet().iterator();
        while (it.hasNext()) {
            long pos = it.next().getLongKey();
            boolean keep = false;
            for (ChunkPos center : centers) {
                int dx = ChunkPos.getX(pos) - center.x;
                int dz = ChunkPos.getZ(pos) - center.z;
                if (Math.max(Math.abs(dx), Math.abs(dz)) <= margin) {
                    keep = true;
                    break;
                }
            }
            if (!keep) {
                it.remove();
            }
        }
    }
}
