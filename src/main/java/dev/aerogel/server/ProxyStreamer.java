package dev.aerogel.server;

import dev.aerogel.common.AeroGelConfig;
import dev.aerogel.common.MeshMath;
import dev.aerogel.common.ProxyTopology;
import dev.aerogel.net.MeshBatchPayload;
import dev.aerogel.net.MeshRequestPayload;
import dev.aerogel.proxy.OffThreadSampler;
import dev.aerogel.proxy.SamplerPool;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.shorts.ShortArrayList;
import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server side of the demo: samples lattice heights per wanted chunk on a worker pool and streams them to
 * each player. The virtual-proxy gate lives in {@link ProxyChunkGate}. Also does the crude proxy-collision
 * PoC: clamps players up onto the sampled surface.
 *
 * <p><b>Detail tiers.</b> Chunks within {@code nearDistance} are sampled at {@code nearTier} (the finest
 * the user asked for); chunks beyond are sampled at {@code farTier} (cheapest). A chunk promoted from far
 * to near is re-sampled at the finer tier and re-sent; the client always bakes at its own fixed render tier
 * and upsamples, so promotion only changes heights. Biomes add one probe per chunk (packed colour +
 * representative block).</p>
 *
 * <p>All stream state is touched on the server thread only (tick events); the worker pool owns the
 * sampling threads.</p>
 */
public final class ProxyStreamer {
    private static final Logger LOGGER = LoggerFactory.getLogger("aerogel.stream");
    private static final int MIN_RADIUS = 4;
    /** testing knob; the practical ceiling is far lower (see AGENTS radius forecast). */
    private static final int MAX_RADIUS_LIMIT = 1000;
    /** cap on queued-but-unsent chunks per player, so a huge radius cannot grow the queue without bound. */
    private static final int MAX_WANTED_QUEUE = 200_000;
    private static final int MAX_SAMPLER_THREADS = 64;
    /** a single-tick jump beyond this many chunks is treated as a teleport (full wanted rebuild); normal
     *  fast flight uses the incremental leading-edge path. */
    private static final int FRESH_JUMP_THRESHOLD = 16;
    /** how often the near-band promotion sweep runs (ticks); promotion is not latency-critical. */
    private static final int PROMOTE_INTERVAL = 20;

    private static int radius = 20;
    private static final int MAX_SUBMIT_PER_TICK = 512;
    private static final int MAX_PENDING = 4096;
    private static final int MAX_DRAIN_PER_TICK = 1024;

    private static ServerLevel overworld;
    private static SamplerPool worker;
    /** the finest tier, used within {@link #nearDistance} chunks of a player. */
    private static byte nearTier = ProxyTopology.DEFAULT;
    /** the cheapest tier, used beyond {@link #nearDistance}. */
    private static byte farTier = ProxyTopology.FIVE;
    private static int nearDistance = 32;
    private static boolean biomeColors = true;
    /** bisect switch: when off, proxy meshes are neither sampled nor sent (the gate still runs). */
    private static boolean streamEnabled = true;
    private static boolean bypassMoveCheck = true;

    /** init-time state kept so a live thread-count change can rebuild the pool. */
    private static NoiseGeneratorSettings workerSettings;
    private static BiomeSource workerBiomes;
    private static long workerSeed;
    private static int samplerThreads = 1;

    /** one chunk's best streamed data; tier is the detail it was sampled at. */
    private record Grid(byte tier, short[] heights, int biome, int block) {
    }

    private static final Long2ObjectOpenHashMap<Grid> grids = new Long2ObjectOpenHashMap<>();
    /** chunk -> highest tier currently requested from the pool (0 = not pending). */
    private static final Long2ByteOpenHashMap pending = new Long2ByteOpenHashMap();
    private static final Map<UUID, Stream> streams = new HashMap<>();

    private static final long[] completed = new long[MAX_DRAIN_PER_TICK];
    private static int statTicks;
    private static long statTickNanos;
    private static int statChunksSent;
    private static int pruneTicks;
    private static int promoteTicks;

    private ProxyStreamer() {
    }

    /**
     * Vanilla rejects a move when {@code d10 - d9 > 100} (10 blocks/packet) and teleports the player back —
     * which only spares the singleplayer owner. On a dedicated server that rubber-bands every fast-flight
     * packet. When true we exempt fast creative flight / elytra (see ServerGamePacketListenerImplMixin).
     */
    public static boolean bypassMoveCheck() {
        return bypassMoveCheck;
    }

    public static void setBypassMoveCheck(boolean on) {
        bypassMoveCheck = on;
        AeroGelConfig.CFG.bypassMoveCheck.set(on);
        AeroGelConfig.SPEC.save();
    }

    /**
     * Starts the streamer. The overworld generator is left REAL: generation only runs when a proxy is
     * promoted, and a leaked FULL task then simply generates real terrain (a safety net). The virtual-proxy
     * gate ({@link ProxyChunkGate}) is what stops a never-generated chunk's ticket from building its own task.
     */
    public static void init(MinecraftServer server) {
        overworld = server.overworld();
        if (!(overworld.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator noiseGen)) {
            LOGGER.info("overworld generator is not noise-based ({}); proxy streaming inactive",
                overworld.getChunkSource().getGenerator());
            return;
        }
        AeroGelConfig cfg = AeroGelConfig.CFG;
        streamEnabled = cfg.streamEnabled.get();
        radius = cfg.streamRadius.get();
        nearTier = (byte) (int) cfg.nearTier.get();
        farTier = (byte) (int) cfg.farTier.get();
        if (!ProxyTopology.valid(nearTier)) {
            nearTier = ProxyTopology.DEFAULT;
        }
        if (!ProxyTopology.valid(farTier)) {
            farTier = ProxyTopology.FIVE;
        }
        nearDistance = cfg.nearDistance.get();
        biomeColors = cfg.biomeColors.get();
        samplerThreads = cfg.samplerThreads.get();
        bypassMoveCheck = cfg.bypassMoveCheck.get();
        workerSettings = noiseGen.generatorSettings().value();
        workerBiomes = noiseGen.getBiomeSource();
        workerSeed = overworld.getSeed();
        worker = createPool();
        ProxyChunkGate.init(overworld);
        PromotionManager.init(overworld);
        AdaptiveMode.init();
        LOGGER.info("virtual proxy chunks active for {}; {} sampler thread(s), near {} (<= {} chunks), far {}, colours {}",
            overworld.dimension().location(), worker.threads(),
            ProxyTopology.name(nearTier), nearDistance, ProxyTopology.name(farTier), biomeColors);
    }

    private static SamplerPool createPool() {
        List<RandomState> states = new ArrayList<>(samplerThreads);
        var noise = overworld.registryAccess().lookupOrThrow(Registries.NOISE);
        for (int i = 0; i < samplerThreads; i++) {
            // each worker gets its OWN RandomState: the router's interpolator/cache wrappers are stateful,
            // so sharing would race. Same seed + settings => identical heights.
            states.add(RandomState.create(workerSettings, noise, workerSeed));
        }
        return new SamplerPool(states, workerSettings, workerBiomes,
            overworld.registryAccess().registryOrThrow(Registries.BIOME), samplerThreads);
    }

    /** the finest (near) tier currently configured. */
    public static byte topology() {
        return nearTier;
    }

    public static byte farTier() {
        return farTier;
    }

    public static int nearDistance() {
        return nearDistance;
    }

    public static int threads() {
        return worker == null ? samplerThreads : worker.threads();
    }

    public static boolean biomeColors() {
        return biomeColors;
    }

    public static boolean streamEnabled() {
        return streamEnabled;
    }

    public static void setStreamEnabled(boolean on) {
        streamEnabled = on;
        AeroGelConfig.CFG.streamEnabled.set(on);
        AeroGelConfig.SPEC.save();
    }

    /**
     * Live near-tier switch. The worker pool is not rebuilt (tier travels per task); we drop every
     * per-player sent set and the grid cache so the new tier re-streams.
     */
    public static void setTopology(byte newTier) {
        if (!ProxyTopology.valid(newTier) || newTier == nearTier) {
            return;
        }
        nearTier = newTier;
        AeroGelConfig.CFG.nearTier.set((int) newTier);
        AeroGelConfig.SPEC.save();
        clearStreamState();
    }

    public static void setFarTier(byte newTier) {
        if (!ProxyTopology.valid(newTier) || newTier == farTier) {
            return;
        }
        farTier = newTier;
        AeroGelConfig.CFG.farTier.set((int) newTier);
        AeroGelConfig.SPEC.save();
        clearStreamState();
    }

    public static void setNearDistance(int chunks) {
        nearDistance = Math.clamp(chunks, 1, MAX_RADIUS_LIMIT);
        AeroGelConfig.CFG.nearDistance.set(nearDistance);
        AeroGelConfig.SPEC.save();
        clearStreamState();
    }

    /** toggles only the tint colour on the wire; the representative block (texture) is always sampled. */
    public static void setBiomeColors(boolean on) {
        if (biomeColors == on) {
            return;
        }
        biomeColors = on;
        AeroGelConfig.CFG.biomeColors.set(on);
        AeroGelConfig.SPEC.save();
        clearStreamState(); // re-stream so existing chunks pick up the new tint
    }

    /** live sampler-thread count change; rebuilds the pool (caches are lost) and re-streams. */
    public static void setThreads(int threads) {
        if (worker == null) {
            samplerThreads = Math.clamp(threads, 1, MAX_SAMPLER_THREADS);
            AeroGelConfig.CFG.samplerThreads.set(samplerThreads);
            AeroGelConfig.SPEC.save();
            return;
        }
        int clamped = Math.clamp(threads, 1, MAX_SAMPLER_THREADS);
        if (clamped == threads()) {
            return;
        }
        samplerThreads = clamped;
        AeroGelConfig.CFG.samplerThreads.set(clamped);
        AeroGelConfig.SPEC.save();
        worker.close();
        worker = createPool();
        clearStreamState();
    }

    private static void clearStreamState() {
        grids.clear();
        pending.clear();
        for (Stream stream : streams.values()) {
            stream.sent.clear();
            stream.center = Long.MIN_VALUE;
        }
    }

    public static int radius() {
        return radius;
    }

    /** live proxy view radius; clears the streamed/sent state so the new range re-streams. */
    public static void setRadius(int newRadius) {
        newRadius = Math.clamp(newRadius, MIN_RADIUS, MAX_RADIUS_LIMIT);
        if (newRadius == radius) {
            return;
        }
        radius = newRadius;
        AeroGelConfig.CFG.streamRadius.set(newRadius);
        AeroGelConfig.SPEC.save();
        clearStreamState();
    }

    /** stops the sampling threads on server shutdown. */
    public static void shutdown() {
        if (worker != null) {
            worker.close();
            worker = null;
        }
        PromotionManager.shutdown();
        ProxyChunkGate.shutdown();
        AdaptiveMode.shutdown();
    }

    /** true while the position is a virtual proxy (the write guard and promotion queue use this). */
    public static boolean isProxyPlaceholder(long pos) {
        return ProxyChunkGate.isProxy(pos);
    }

    /** true once the virtual-proxy gate has been installed for the overworld. */
    public static boolean isActive() {
        return ProxyChunkGate.isActive();
    }

    /** the sampled biome holder for a chunk, or null when none has been sampled yet. */
    public static Holder<Biome> biomeHolder(long pos) {
        Grid grid = grids.get(pos);
        if (grid == null || grid.biome() < 0 || overworld == null) {
            return null;
        }
        return overworld.registryAccess().registryOrThrow(Registries.BIOME).getHolder(grid.biome()).orElse(null);
    }

    /** dump the stored mesh points for one chunk (local x,z and height). */
    public static String dumpGrid(long pos) {
        int cx = ChunkPos.getX(pos);
        int cz = ChunkPos.getZ(pos);
        Grid grid = grids.get(pos);
        if (grid == null) {
            return "AeroGel: no proxy grid stored for chunk (" + cx + "," + cz + ")";
        }
        byte tier = grid.tier();
        StringBuilder sb = new StringBuilder("AeroGel grid (" + cx + "," + cz + ") tier " + ProxyTopology.name(tier) + ":");
        int count = ProxyTopology.count(tier);
        for (int idx = 0; idx < count; idx++) {
            sb.append('\n').append(String.format("(%2d,%2d) %4d",
                ProxyTopology.localX(tier, idx), ProxyTopology.localZ(tier, idx), grid.heights()[idx]));
        }
        return sb.toString();
    }

    /**
     * Headless probe hook: sample one chunk at a tier on the pool and block until the worker returns (the
     * caller is the server thread, so the normal tick drain cannot run and steal the result). Exercises the
     * pool routing, the worker and the biome probe without a client.
     */
    public static String probeSampleBlocking(ChunkPos pos, byte tier) {
        if (worker == null) {
            return "worker null (proxy inactive)";
        }
        worker.submit(pos.toLong(), tier, true);
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            OffThreadSampler.Result r = worker.poll();
            if (r != null && r.chunkPos() == pos.toLong() && r.topology() == tier) {
                return "tier=" + ProxyTopology.name(r.topology()) + " heights=" + r.grid().length
                    + " first=" + r.grid()[0] + " biome=" + r.biome()
                    + " block=" + r.block() + " in " + String.format("%.3f", r.nanos() / 1e6) + " ms";
            }
            try {
                Thread.sleep(1L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return "TIMEOUT waiting for sampler result";
    }

    /** drop cached grids no player could still need, so a long fast flight cannot leak the cache. */
    private static void pruneGrids(MinecraftServer server) {
        if (grids.isEmpty()) {
            return;
        }
        List<ChunkPos> centers = new ArrayList<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.level() == overworld && !player.hasDisconnected()) {
                centers.add(player.chunkPosition());
            }
        }
        var it = grids.long2ObjectEntrySet().iterator();
        while (it.hasNext()) {
            long pos = it.next().getLongKey();
            boolean keep = false;
            for (ChunkPos center : centers) {
                int dx = ChunkPos.getX(pos) - center.x;
                int dz = ChunkPos.getZ(pos) - center.z;
                if (Math.max(Math.abs(dx), Math.abs(dz)) <= radius + 4) {
                    keep = true;
                    break;
                }
            }
            if (!keep) {
                it.remove();
            }
        }
    }

    /** headless probe hook: halt the server after {@code delayTicks} real ticks (TickTask can't delay when idle). */
    public static void scheduleHalt(int delayTicks) {
        if (overworld != null) {
            haltTick = overworld.getServer().getTickCount() + delayTicks;
        }
    }

    private static int haltTick = -1;

    public static void onServerTick(ServerTickEvent.Post event) {
        if (worker == null) {
            return;
        }
        long t0 = System.nanoTime();
        MinecraftServer server = event.getServer();
        if (haltTick >= 0 && server.getTickCount() >= haltTick) {
            haltTick = -1;
            server.halt(false);
            return;
        }
        // 0. adaptive enforcement: decide whether the gate is on at all this tick
        AdaptiveMode.tick(server);
        // 0a. automatic promotion: linger/touch/write -> real chunk, paced by server load
        PromotionManager.tick(server);
        // 0b. classification completions + placeholder cache pruning
        ProxyChunkGate.tick(server);
        if (!streamEnabled) {
            return; // bisect switch: no proxy sampling/sending; the gate and promotion still run
        }
        // 1. move finished samples out of the worker queue (cheap; NO density work on this thread)
        int drained = 0;
        OffThreadSampler.Result result;
        while (drained < MAX_DRAIN_PER_TICK && (result = worker.poll()) != null) {
            long pos = result.chunkPos();
            byte requested = pending.get(pos);
            if (requested == 0) {
                continue; // stale: state was reset / the chunk is no longer wanted
            }
            // a coarse result that arrives while a finer one is still in flight must not clear the request,
            // or the finer result would be dropped when it lands
            if (result.topology() >= requested) {
                pending.remove(pos);
            }
            Grid prev = grids.get(pos);
            if (prev != null && prev.tier() > result.topology()) {
                continue; // never downgrade a chunk that was already sampled finer
            }
            grids.put(pos, new Grid(result.topology(), result.grid(), result.biome(), result.block()));
            completed[drained++] = pos;
        }
        // 2. per player: send completed/cached wanted chunks, submit the rest to the pool
        int sentThisTick = 0;
        boolean promoteDue = ++promoteTicks % PROMOTE_INTERVAL == 0;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.level() != overworld || player.hasDisconnected()) {
                continue;
            }
            if (!AdaptiveMode.renders(player)) {
                continue; // this client is not rendering proxies: no need to stream to it
            }
            Stream stream = streams.computeIfAbsent(player.getUUID(), id -> new Stream());
            ChunkPos center = player.chunkPosition();
            if (center.toLong() != stream.center) {
                rebuildWanted(stream, center, player);
            }
            sentThisTick += sendAndSubmit(stream, player, center, drained, promoteDue);
        }
        if (++pruneTicks % 200 == 0) {
            pruneGrids(server);
        }
        statTickNanos += System.nanoTime() - t0;
        statChunksSent += sentThisTick;
        if (++statTicks % 200 == 0) {
            LOGGER.info("stream perf: {} ms/tick avg over {} ticks, {} chunks sent, worker {} samples avg {} ms, backlog {}, threads {}, radius {}, near {}<= {}, far {}",
                String.format("%.2f", statTickNanos / 1e6 / statTicks), statTicks, statChunksSent,
                worker.sampledCount(), String.format("%.2f", worker.avgMillis()), worker.backlog(),
                worker.threads(), radius, ProxyTopology.name(nearTier), nearDistance, ProxyTopology.name(farTier));
            statTicks = 0;
            statTickNanos = 0;
            statChunksSent = 0;
        }
    }

    /** Crude proxy collision: clamp players up onto the sampled surface. */
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (worker == null || !(event.getEntity() instanceof ServerPlayer player) || player.level() != overworld) {
            return;
        }
        if (player.isSpectator()) {
            return;
        }
        long chunkPos = player.chunkPosition().toLong();
        Grid grid = grids.get(chunkPos);
        if (grid == null) {
            return; // no proxy data here -> real (pre-existing) chunk handles it
        }
        // a promoted/real chunk at the player's position has real collision; clamping there would fight it
        int cx = ChunkPos.getX(chunkPos);
        int cz = ChunkPos.getZ(chunkPos);
        boolean realHere = !ProxyChunkGate.isProxy(chunkPos);
        double surface = MeshMath.surfaceAt(chunkPos, grid.tier(), grid.heights(), player.getX(), player.getZ());
        if (realHere) {
            return;
        }
        // descent trigger: about to reach the proxy surface -> materialise the ground before impact
        PromotionManager.onPlayerNearSurface(player, chunkPos, surface);
        // the CLIENT clamp owns standing (snaps + claims onGround); a per-tick server teleport would send a
        // position packet every tick and rubber-band the player (no sprint/jump). Server = through-floor net.
        if (player.getY() < surface - 2.0) {
            LOGGER.info("clamp: teleporting {} from y {} to proxy surface {} at chunk ({},{})",
                player.getGameProfile().getName(), String.format("%.1f", player.getY()), String.format("%.1f", surface), cx, cz);
            player.teleportTo(player.getX(), surface, player.getZ());
            player.setDeltaMovement(player.getDeltaMovement().x, 0.0, player.getDeltaMovement().z);
        }
    }

    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        streams.remove(event.getEntity().getUUID());
        AdaptiveMode.removePlayer(event.getEntity().getUUID());
    }

    private static void rebuildWanted(Stream stream, ChunkPos center, ServerPlayer player) {
        int r = radius;
        ChunkPos old = stream.center == Long.MIN_VALUE ? null : new ChunkPos(stream.center);
        stream.center = center.toLong();
        stream.radius = r;
        // forget chunks outside the wanted radius: the client prunes meshes it can no longer see, so
        // re-approaching them must RESEND (otherwise previously-flown chunks turn into permanent holes)
        var sit = stream.sent.long2ByteEntrySet().iterator();
        while (sit.hasNext()) {
            long pos = sit.next().getLongKey();
            int dx = ChunkPos.getX(pos) - center.x;
            int dz = ChunkPos.getZ(pos) - center.z;
            if (Math.max(Math.abs(dx), Math.abs(dz)) > r) {
                sit.remove();
            }
        }
        boolean fresh = old == null
            || Math.max(Math.abs(center.x - old.x), Math.abs(center.z - old.z)) > FRESH_JUMP_THRESHOLD;
        if (fresh) {
            // login / teleport: queue the whole window nearest-ring-first (no O(r^2 log r) sort)
            stream.wanted = new LongArrayList();
            stream.wantedIndex = 0;
            for (int k = 0; k <= r; k++) {
                for (int x = center.x - k; x <= center.x + k; x++) {
                    enqueue(stream, x, center.z - k);
                    if (k > 0) {
                        enqueue(stream, x, center.z + k);
                    }
                }
                for (int z = center.z - k + 1; z <= center.z + k - 1; z++) {
                    enqueue(stream, center.x - k, z);
                    enqueue(stream, center.x + k, z);
                }
            }
        } else {
            // incremental: only the band that entered the window is new — O(r * |delta|), not O(r^2).
            // A fast flight can cross more than one chunk per tick, so enqueue EVERY column/row between
            // the old and new leading edge. Enqueuing only the final edge leaves the intermediate rows
            // unqueued; they then only heal via the client's ~1 s mesh_request reconciliation (the
            // "row missing orthogonal to travel" bug). The lower clamp also covers a jump so large the
            // old and new windows no longer overlap.
            int dx = center.x - old.x;
            int dz = center.z - old.z;
            if (dx > 0) {
                int x0 = Math.max(old.x + r + 1, center.x - r);
                for (int x = x0; x <= center.x + r; x++) {
                    for (int z = center.z - r; z <= center.z + r; z++) {
                        enqueue(stream, x, z);
                    }
                }
            } else if (dx < 0) {
                int x0 = Math.min(old.x - r - 1, center.x + r);
                for (int x = x0; x >= center.x - r; x--) {
                    for (int z = center.z - r; z <= center.z + r; z++) {
                        enqueue(stream, x, z);
                    }
                }
            }
            if (dz > 0) {
                int z0 = Math.max(old.z + r + 1, center.z - r);
                for (int z = z0; z <= center.z + r; z++) {
                    for (int x = center.x - r; x <= center.x + r; x++) {
                        enqueue(stream, x, z);
                    }
                }
            } else if (dz < 0) {
                int z0 = Math.min(old.z - r - 1, center.z + r);
                for (int z = z0; z >= center.z - r; z--) {
                    for (int x = center.x - r; x <= center.x + r; x++) {
                        enqueue(stream, x, z);
                    }
                }
            }
        }
        // drop the consumed prefix so the queue does not grow for the life of the connection
        if (stream.wantedIndex > 8192) {
            LongArrayList rest = new LongArrayList(stream.wanted.size() - stream.wantedIndex);
            for (int i = stream.wantedIndex; i < stream.wanted.size(); i++) {
                rest.add(stream.wanted.getLong(i));
            }
            stream.wanted = rest;
            stream.wantedIndex = 0;
        }
    }

    /** append a chunk to this player's wanted queue unless already sent or the queue cap is reached. */
    private static void enqueue(Stream stream, int x, int z) {
        if (stream.wanted.size() - stream.wantedIndex >= MAX_WANTED_QUEUE) {
            return;
        }
        long pos = ChunkPos.asLong(x, z);
        if (stream.sent.get(pos) == 0) {
            stream.wanted.add(pos);
        }
    }

    /** the tier a chunk should be sampled at, given its distance from the player. */
    private static byte desiredTier(long pos, ChunkPos center) {
        int dx = Math.abs(ChunkPos.getX(pos) - center.x);
        int dz = Math.abs(ChunkPos.getZ(pos) - center.z);
        return Math.max(dx, dz) <= nearDistance ? nearTier : farTier;
    }

    /**
     * Sends this player every completed/cached wanted chunk (grouped into one packet per tier) and submits
     * the remainder of the wanted list to the pool. All of this is bookkeeping — no density evaluation.
     */
    private static int sendAndSubmit(Stream stream, ServerPlayer player, ChunkPos center, int drained, boolean promoteDue) {
        Map<Byte, Batch> batches = new HashMap<>();
        for (int i = 0; i < drained; i++) {
            long pos = completed[i];
            if (!inRadius(pos, center, stream.radius)) {
                continue;
            }
            if (ProxyChunkGate.isLoadedReal(pos)) {
                continue; // vanilla already gives the client this chunk
            }
            Grid grid = grids.get(pos);
            if (grid != null && stream.sent.get(pos) < grid.tier()) {
                addToBatch(batches, stream, pos, grid);
            }
        }
        int submitted = 0;
        while (stream.wantedIndex < stream.wanted.size() && submitted < MAX_SUBMIT_PER_TICK) {
            long pos = stream.wanted.getLong(stream.wantedIndex);
            stream.wantedIndex++;
            if (ProxyChunkGate.isLoadedReal(pos)) {
                continue; // real chunk: the client gets it from vanilla
            }
            byte want = desiredTier(pos, center);
            Grid grid = grids.get(pos);
            if (grid != null && grid.tier() >= want) {
                if (stream.sent.get(pos) < grid.tier()) {
                    addToBatch(batches, stream, pos, grid);
                }
                continue;
            }
            if (pending.size() >= MAX_PENDING) {
                stream.wantedIndex--; // worker queue full; resume here next tick
                break;
            }
            if (pending.get(pos) >= want) {
                continue; // already in flight at an equal-or-finer tier
            }
            pending.put(pos, want);
            worker.submit(pos, want, false);
            submitted++;
        }
        if (promoteDue) {
            promoteNearBand(batches, stream, center);
        }
        int sent = 0;
        for (Batch batch : batches.values()) {
            PacketDistributor.sendToPlayer(player, new MeshBatchPayload(batch.tier, batch.pos.toLongArray(),
                batch.heights.toShortArray(), batch.biomes.toIntArray(), batch.blocks.toIntArray()));
            sent += batch.pos.size();
        }
        return sent;
    }

    /**
     * Promotion sweep: every {@link #PROMOTE_INTERVAL} ticks, make sure every chunk in the near band is
     * sampled/sent at {@link #nearTier}. Chunks sampled coarse while far are re-submitted (the point cache
     * already holds their corners, so only the new points cost anything); chunks already fine but sent
     * coarse are queued for resend. O(nearDistance^2), but only every 20 ticks.
     */
    private static void promoteNearBand(Map<Byte, Batch> batches, Stream stream, ChunkPos center) {
        int r = Math.min(nearDistance, stream.radius);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                long pos = ChunkPos.asLong(center.x + dx, center.z + dz);
                if (ProxyChunkGate.isLoadedReal(pos)) {
                    continue; // real chunk: the client gets it from vanilla
                }
                Grid grid = grids.get(pos);
                if (grid != null) {
                    if (grid.tier() >= nearTier) {
                        if (stream.sent.get(pos) < grid.tier()) {
                            addToBatch(batches, stream, pos, grid);
                        }
                        continue;
                    }
                }
                if (pending.get(pos) >= nearTier) {
                    continue;
                }
                if (pending.size() >= MAX_PENDING) {
                    return;
                }
                pending.put(pos, nearTier);
                worker.submit(pos, nearTier, true);
            }
        }
    }

    private static void addToBatch(Map<Byte, Batch> batches, Stream stream, long pos, Grid grid) {
        Batch batch = batches.computeIfAbsent(grid.tier(), Batch::new);
        batch.pos.add(pos);
        for (short h : grid.heights()) {
            batch.heights.add(h);
        }
        batch.biomes.add(biomeColors ? grid.biome() : -1);
        batch.blocks.add(grid.block());
        stream.sent.put(pos, grid.tier());
    }

    private static boolean inRadius(long pos, ChunkPos center, int radius) {
        int dx = ChunkPos.getX(pos) - center.x;
        int dz = ChunkPos.getZ(pos) - center.z;
        return Math.max(Math.abs(dx), Math.abs(dz)) <= radius;
    }

    private static final class Stream {
        long center = Long.MIN_VALUE;
        int radius = ProxyStreamer.radius;
        /** chunk -> tier already sent (0 = not sent). */
        final Long2ByteOpenHashMap sent = new Long2ByteOpenHashMap();
        /** queue of not-yet-submitted chunks, nearest-first on the initial fill then leading edges. */
        LongArrayList wanted = new LongArrayList();
        int wantedIndex;

        Stream() {
            sent.defaultReturnValue((byte) 0);
        }
    }

    /** per-tier accumulation for one outgoing packet. */
    private static final class Batch {
        final byte tier;
        final LongArrayList pos = new LongArrayList();
        final ShortArrayList heights = new ShortArrayList();
        final IntArrayList biomes = new IntArrayList();
        final IntArrayList blocks = new IntArrayList();

        Batch(byte tier) {
            this.tier = tier;
        }
    }

    /**
     * Client reconciliation: the client asks for meshes it is missing (join drops, reconnects, ...).
     * Cached grids go out immediately (one reply batch per tier), uncached ones jump the wanted queue via
     * `urgent`.
     */
    public static void onMeshRequest(ServerPlayer player, MeshRequestPayload payload) {
        if (worker == null || player.level() != overworld) {
            return;
        }
        Stream stream = streams.computeIfAbsent(player.getUUID(), id -> new Stream());
        ChunkPos center = player.chunkPosition();
        Map<Byte, Batch> batches = new HashMap<>();
        int urgentAdded = 0;
        for (long pos : payload.chunkPos()) {
            int dx = ChunkPos.getX(pos) - center.x;
            int dz = ChunkPos.getZ(pos) - center.z;
            if (Math.max(Math.abs(dx), Math.abs(dz)) > radius + 2) {
                continue; // sanity: only serve chunks near the player
            }
            Grid grid = grids.get(pos);
            if (grid != null) {
                Batch batch = batches.computeIfAbsent(grid.tier(), Batch::new);
                if (batch.pos.size() < 128) {
                    batch.pos.add(pos);
                    for (short h : grid.heights()) {
                        batch.heights.add(h);
                    }
                    batch.biomes.add(biomeColors ? grid.biome() : -1);
                    batch.blocks.add(grid.block());
                    stream.sent.put(pos, grid.tier());
                }
            } else {
                byte want = desiredTier(pos, center);
                if (urgentAdded < 64 && pending.size() < MAX_PENDING && pending.get(pos) < want) {
                    pending.put(pos, want);
                    worker.submit(pos, want, true);
                    urgentAdded++;
                }
            }
        }
        for (Batch batch : batches.values()) {
            PacketDistributor.sendToPlayer(player, new MeshBatchPayload(batch.tier, batch.pos.toLongArray(),
                batch.heights.toShortArray(), batch.biomes.toIntArray(), batch.blocks.toIntArray()));
        }
    }
}
