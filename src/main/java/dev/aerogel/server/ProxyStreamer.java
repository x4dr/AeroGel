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
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
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
 * <p><b>Per-dimension.</b> Each noise-based {@code ServerLevel} gets its own {@link State}: a worker pool
 * (its own {@code RandomState}s, settings, biomes and seed), grid/pending caches and per-player streams.
 * Scalar config stays global. Every position-taking method resolves the state from the level in scope, so a
 * nether read never consults an overworld grid.</p>
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
     *  fast flight uses the incremental leading-edge path. Raised from 16 to 24: the incremental band cost
     *  ~2*delta*(2r+1) stays below the fresh ring cost (2r+1)^2 while delta < r, so the extra slack keeps
     *  moderate flight jumps on the cheaper incremental path at the default r=20. */
    private static final int FRESH_JUMP_THRESHOLD = 24;
    /** how often the near-band detail-tier refinement sweep runs (ticks); it is not latency-critical. */
    private static final int REFINE_INTERVAL = 20;

    private static int radius = 20;
    private static final int MAX_SUBMIT_PER_TICK = 512;
    private static final int MAX_PENDING = 4096;
    private static final int MAX_DRAIN_PER_TICK = 1024;

    private static MinecraftServer server;
    private static final Map<ResourceKey<Level>, State> STATES = new HashMap<>();

    /** the finest tier, used within {@link #nearDistance} chunks of a player. */
    private static byte nearTier = ProxyTopology.DEFAULT;
    /** the cheapest tier, used beyond {@link #nearDistance}. */
    private static byte farTier = ProxyTopology.FIVE;
    private static int nearDistance = 32;
    private static boolean biomeColors = true;
    /** bisect switch: when off, proxy meshes are neither sampled nor sent (the gate still runs). */
    private static boolean streamEnabled = true;
    private static boolean bypassMoveCheck = true;
    private static int samplerThreads = 1;

    private static int statTicks;
    private static long statTickNanos;
    private static int statChunksSent;
    /** cumulative chunks streamed since init (never reset); read by the flight probe. */
    private static long totalChunksSent;
    private static int haltTick = -1;

    /** one chunk's best streamed data; tier is the detail it was sampled at. */
    private record Grid(byte tier, short[] heights, int biome, int block) {
    }

    /** per-{@code ServerLevel} stream state. */
    private static final class State {
        final ServerLevel level;
        SamplerPool worker;
        NoiseGeneratorSettings workerSettings;
        BiomeSource workerBiomes;
        long workerSeed;

        final Long2ObjectOpenHashMap<Grid> grids = new Long2ObjectOpenHashMap<>();
        /** chunk -> highest tier currently requested from the pool (0 = not pending). */
        final Long2ByteOpenHashMap pending = new Long2ByteOpenHashMap();
        final Map<UUID, Stream> streams = new HashMap<>();
        /** scratch for finished samples drained this tick. */
        final long[] completed = new long[MAX_DRAIN_PER_TICK];
        /** reused per-player packet accumulator; cleared/reset after each {@link #sendAndSubmit}. */
        final Map<Byte, Batch> scratchBatches = new HashMap<>();
        int pruneTicks;
        int refineTicks;

        State(ServerLevel level) {
            this.level = level;
        }
    }

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
     * Starts the streamer for every noise-based {@link ServerLevel}. The generators are left REAL:
     * generation only runs when a proxy is promoted, and a leaked FULL task then simply generates real
     * terrain (a safety net). The virtual-proxy gate ({@link ProxyChunkGate}) is what stops a
     * never-generated chunk's ticket from building its own task.
     */
    public static void init(MinecraftServer srv) {
        server = srv;
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

        int count = 0;
        for (ServerLevel level : srv.getAllLevels()) {
            if (!(level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator noiseGen)) {
                LOGGER.info("dimension {} generator is not noise-based ({}); proxy streaming skipped",
                    level.dimension().location(), level.getChunkSource().getGenerator());
                continue;
            }
            State state = new State(level);
            state.workerSettings = noiseGen.generatorSettings().value();
            state.workerBiomes = noiseGen.getBiomeSource();
            state.workerSeed = level.getSeed();
            state.worker = createPool(state);
            STATES.put(level.dimension(), state);
            ProxyChunkGate.init(level);
            PromotionManager.init(level);
            count++;
            LOGGER.info("virtual proxy chunks active for {}; {} sampler thread(s), near {} (<= {} chunks), far {}, colours {}",
                level.dimension().location(), state.worker.threads(),
                ProxyTopology.name(nearTier), nearDistance, ProxyTopology.name(farTier), biomeColors);
        }
        if (count == 0) {
            LOGGER.info("no noise-based dimensions; proxy streaming inactive");
            return;
        }
        AdaptiveMode.init();
        LOGGER.info("virtual proxy chunks active for {} dimension(s)", count);
    }

    private static SamplerPool createPool(State state) {
        List<RandomState> states = new ArrayList<>(samplerThreads);
        var noise = state.level.registryAccess().lookupOrThrow(Registries.NOISE);
        for (int i = 0; i < samplerThreads; i++) {
            // each worker gets its OWN RandomState: the router's interpolator/cache wrappers are stateful,
            // so sharing would race. Same seed + settings => identical heights.
            states.add(RandomState.create(state.workerSettings, noise, state.workerSeed));
        }
        return new SamplerPool(states, state.workerSettings, state.workerBiomes,
            state.level.registryAccess().registryOrThrow(Registries.BIOME), samplerThreads);
    }

    private static State state(Level lvl) {
        return lvl == null ? null : STATES.get(lvl.dimension());
    }

    private static State state(ServerLevel lvl) {
        return lvl == null ? null : STATES.get(lvl.dimension());
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
        return samplerThreads;
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
     * Live near-tier switch. The worker pools are not rebuilt (tier travels per task); we drop every
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

    /** live sampler-thread count change; rebuilds every pool (caches are lost) and re-streams. */
    public static void setThreads(int threads) {
        int clamped = Math.clamp(threads, 1, MAX_SAMPLER_THREADS);
        if (clamped == samplerThreads) {
            return;
        }
        samplerThreads = clamped;
        AeroGelConfig.CFG.samplerThreads.set(clamped);
        AeroGelConfig.SPEC.save();
        for (State state : STATES.values()) {
            if (state.worker != null) {
                state.worker.close();
                state.worker = createPool(state);
            }
        }
        clearStreamState();
    }

    private static void clearStreamState() {
        for (State state : STATES.values()) {
            state.grids.clear();
            state.pending.clear();
            for (Stream stream : state.streams.values()) {
                stream.sent.clear();
                stream.wanted.clear();
                stream.queued.clear();
                stream.wantedIndex = 0;
                stream.center = Long.MIN_VALUE;
            }
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
        for (State state : STATES.values()) {
            if (state.worker != null) {
                state.worker.close();
            }
        }
        STATES.clear();
        PromotionManager.shutdown();
        ProxyChunkGate.shutdown();
        AdaptiveMode.shutdown();
        server = null;
    }

    /** true while the position is a virtual proxy (the write guard and promotion queue use this). */
    public static boolean isProxyPlaceholder(ServerLevel lvl, long pos) {
        return ProxyChunkGate.isProxy(lvl, pos);
    }

    /** true once the virtual-proxy gate has been installed for any dimension. */
    public static boolean isActive() {
        return ProxyChunkGate.isActive();
    }

    /** cumulative proxy chunks streamed since init (flight-probe metric). */
    public static long totalChunksSent() {
        return totalChunksSent;
    }

    /** the sampled biome holder for a chunk, or null when none has been sampled yet. */
    public static Holder<Biome> biomeHolder(ServerLevel lvl, long pos) {
        State state = state(lvl);
        if (state == null) {
            return null;
        }
        Grid grid = state.grids.get(pos);
        if (grid == null || grid.biome() < 0) {
            return null;
        }
        return lvl.registryAccess().registryOrThrow(Registries.BIOME).getHolder(grid.biome()).orElse(null);
    }

    /** dump the stored mesh points for one chunk (local x,z and height). */
    public static String dumpGrid(ServerLevel lvl, long pos) {
        int cx = ChunkPos.getX(pos);
        int cz = ChunkPos.getZ(pos);
        State state = state(lvl);
        Grid grid = state == null ? null : state.grids.get(pos);
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
    public static String probeSampleBlocking(ServerLevel lvl, ChunkPos pos, byte tier) {
        State state = state(lvl);
        if (state == null || state.worker == null) {
            return "worker null (proxy inactive)";
        }
        state.worker.submit(pos.toLong(), tier, true);
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            OffThreadSampler.Result r = state.worker.poll();
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
    private static void pruneGrids(State state) {
        if (state.grids.isEmpty()) {
            return;
        }
        List<ChunkPos> centers = new ArrayList<>();
        for (ServerPlayer player : state.level.getServer().getPlayerList().getPlayers()) {
            if (player.level() == state.level && !player.hasDisconnected()) {
                centers.add(player.chunkPosition());
            }
        }
        var it = state.grids.long2ObjectEntrySet().iterator();
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
        if (server != null) {
            haltTick = server.getTickCount() + delayTicks;
        }
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        if (STATES.isEmpty()) {
            return;
        }
        long t0 = System.nanoTime();
        MinecraftServer srv = event.getServer();
        if (haltTick >= 0 && srv.getTickCount() >= haltTick) {
            haltTick = -1;
            srv.halt(false);
            return;
        }
        // 0. adaptive enforcement: decide whether the gate is on at all this tick
        AdaptiveMode.tick(srv);
        // 0a. automatic promotion: linger/touch/write -> real chunk, paced by server load
        PromotionManager.tick(srv);
        // 0b. classification completions + placeholder cache pruning
        ProxyChunkGate.tick(srv);
        if (!streamEnabled) {
            return; // bisect switch: no proxy sampling/sending; the gate and promotion still run
        }
        int sentThisTick = 0;
        for (State state : STATES.values()) {
            if (state.worker != null) {
                sentThisTick += drainAndStream(srv, state);
            }
        }
        statTickNanos += System.nanoTime() - t0;
        statChunksSent += sentThisTick;
        totalChunksSent += sentThisTick;
        if (++statTicks % 200 == 0) {
            LOGGER.info("stream perf: {} ms/tick avg over {} ticks, {} chunks sent, {} dimensions",
                String.format("%.2f", statTickNanos / 1e6 / statTicks), statTicks, statChunksSent, STATES.size());
            statTicks = 0;
            statTickNanos = 0;
            statChunksSent = 0;
        }
    }

    /** move finished samples out of one level's worker queue and stream them to that level's players. */
    private static int drainAndStream(MinecraftServer srv, State state) {
        int drained = 0;
        OffThreadSampler.Result result;
        while (drained < MAX_DRAIN_PER_TICK && (result = state.worker.poll()) != null) {
            long pos = result.chunkPos();
            byte requested = state.pending.get(pos);
            if (requested == 0) {
                continue; // stale: state was reset / the chunk is no longer wanted
            }
            // a coarse result that arrives while a finer one is still in flight must not clear the request,
            // or the finer result would be dropped when it lands
            if (result.topology() >= requested) {
                state.pending.remove(pos);
            }
            Grid prev = state.grids.get(pos);
            if (prev != null && prev.tier() > result.topology()) {
                continue; // never downgrade a chunk that was already sampled finer
            }
            state.grids.put(pos, new Grid(result.topology(), result.grid(), result.biome(), result.block()));
            state.completed[drained++] = pos;
        }
        int sentThisTick = 0;
        boolean refineDue = ++state.refineTicks % REFINE_INTERVAL == 0;
        for (ServerPlayer player : srv.getPlayerList().getPlayers()) {
            if (player.level() != state.level || player.hasDisconnected()) {
                continue;
            }
            if (!AdaptiveMode.renders(player)) {
                continue; // this client is not rendering proxies: no need to stream to it
            }
            Stream stream = state.streams.computeIfAbsent(player.getUUID(), id -> new Stream());
            ChunkPos center = player.chunkPosition();
            if (center.toLong() != stream.center) {
                rebuildWanted(stream, center);
            }
            sentThisTick += sendAndSubmit(state, stream, player, center, drained, refineDue);
        }
        if (++state.pruneTicks % 200 == 0) {
            pruneGrids(state);
        }
        return sentThisTick;
    }

    /** Crude proxy collision: clamp players up onto the sampled surface. */
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        State state = state(player.level());
        if (state == null || state.worker == null || player.isSpectator()) {
            return;
        }
        long chunkPos = player.chunkPosition().toLong();
        Grid grid = state.grids.get(chunkPos);
        if (grid == null) {
            return; // no proxy data here -> real (pre-existing) chunk handles it
        }
        // a promoted/real chunk at the player's position has real collision; clamping there would fight it
        int cx = ChunkPos.getX(chunkPos);
        int cz = ChunkPos.getZ(chunkPos);
        boolean realHere = !ProxyChunkGate.isProxy(state.level, chunkPos);
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
        UUID id = event.getEntity().getUUID();
        for (State state : STATES.values()) {
            state.streams.remove(id);
        }
        AdaptiveMode.removePlayer(id);
    }

    private static void rebuildWanted(Stream stream, ChunkPos center) {
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
            // login / teleport: queue the whole window nearest-ring-first (no O(r^2 log r) sort). Reset the
            // queue and its membership mirror; the cap check in the outer loop stops a huge radius walking
            // the whole O(r^2) window once the queue is full.
            stream.wanted = new LongArrayList();
            stream.wantedIndex = 0;
            stream.queued.clear();
            for (int k = 0; k <= r; k++) {
                if (stream.queued.size() >= MAX_WANTED_QUEUE) {
                    break;
                }
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
            // old and new windows no longer overlap. The x-band and z-band overlap in the corner on a
            // diagonal move; enqueue()'s membership set suppresses that duplicate. Each outer loop bails
            // once the queue cap is reached.
            int dx = center.x - old.x;
            int dz = center.z - old.z;
            if (dx > 0) {
                int x0 = Math.max(old.x + r + 1, center.x - r);
                for (int x = x0; x <= center.x + r; x++) {
                    if (stream.queued.size() >= MAX_WANTED_QUEUE) {
                        break;
                    }
                    for (int z = center.z - r; z <= center.z + r; z++) {
                        enqueue(stream, x, z);
                    }
                }
            } else if (dx < 0) {
                int x0 = Math.min(old.x - r - 1, center.x + r);
                for (int x = x0; x >= center.x - r; x--) {
                    if (stream.queued.size() >= MAX_WANTED_QUEUE) {
                        break;
                    }
                    for (int z = center.z - r; z <= center.z + r; z++) {
                        enqueue(stream, x, z);
                    }
                }
            }
            if (dz > 0) {
                int z0 = Math.max(old.z + r + 1, center.z - r);
                for (int z = z0; z <= center.z + r; z++) {
                    if (stream.queued.size() >= MAX_WANTED_QUEUE) {
                        break;
                    }
                    for (int x = center.x - r; x <= center.x + r; x++) {
                        enqueue(stream, x, z);
                    }
                }
            } else if (dz < 0) {
                int z0 = Math.min(old.z - r - 1, center.z + r);
                for (int z = z0; z >= center.z - r; z--) {
                    if (stream.queued.size() >= MAX_WANTED_QUEUE) {
                        break;
                    }
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

    /**
     * Append a chunk to this player's wanted queue unless already sent, already queued (the membership set
     * dedups both the diagonal x/z band overlap and re-enqueues across rebuilds), or the queue cap is
     * reached.
     */
    private static void enqueue(Stream stream, int x, int z) {
        if (stream.queued.size() >= MAX_WANTED_QUEUE) {
            return;
        }
        long pos = ChunkPos.asLong(x, z);
        if (stream.sent.get(pos) == 0 && stream.queued.add(pos)) {
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
    private static int sendAndSubmit(State state, Stream stream, ServerPlayer player, ChunkPos center, int drained, boolean refineDue) {
        // reuse the per-state accumulator instead of allocating a HashMap per player per tick; every Batch is
        // reset after it is sent, so the map (and its ArrayLists' backing arrays) survive across ticks.
        Map<Byte, Batch> batches = state.scratchBatches;
        for (int i = 0; i < drained; i++) {
            long pos = state.completed[i];
            if (!inRadius(pos, center, stream.radius)) {
                continue;
            }
            if (ProxyChunkGate.isLoadedReal(state.level, pos)) {
                continue; // vanilla already gives the client this chunk
            }
            Grid grid = state.grids.get(pos);
            if (grid != null && stream.sent.get(pos) < grid.tier()) {
                addToBatch(batches, stream, pos, grid);
            }
        }
        int submitted = 0;
        while (stream.wantedIndex < stream.wanted.size() && submitted < MAX_SUBMIT_PER_TICK) {
            long pos = stream.wanted.getLong(stream.wantedIndex);
            stream.wantedIndex++;
            stream.queued.remove(pos);
            if (ProxyChunkGate.isLoadedReal(state.level, pos)) {
                continue; // real chunk: the client gets it from vanilla
            }
            byte want = desiredTier(pos, center);
            Grid grid = state.grids.get(pos);
            if (grid != null && grid.tier() >= want) {
                if (stream.sent.get(pos) < grid.tier()) {
                    addToBatch(batches, stream, pos, grid);
                }
                continue;
            }
            if (state.pending.size() >= MAX_PENDING) {
                stream.wantedIndex--; // worker queue full; resume here next tick
                stream.queued.add(pos);
                break;
            }
            if (state.pending.get(pos) >= want) {
                continue; // already in flight at an equal-or-finer tier
            }
            state.pending.put(pos, want);
            state.worker.submit(pos, want, false);
            submitted++;
        }
        // detail-tier refinement is only meaningful once the level actually has proxies; mayHaveProxy is the
        // WS-01 per-level fast bail (false while dormant with no opt-in), so a proxy-free level never scans.
        if (refineDue && ProxyChunkGate.mayHaveProxy(state.level)) {
            refineNearBand(state, batches, stream, center);
        }
        int sent = 0;
        for (Batch batch : batches.values()) {
            if (batch.pos.isEmpty()) {
                continue; // tier retained from an earlier tick, already reset
            }
            MeshBatchPayload packet = new MeshBatchPayload(batch.tier, batch.pos.toLongArray(),
                batch.heights.toShortArray(), batch.biomes.toIntArray(), batch.blocks.toIntArray());
            int n = batch.pos.size();
            batch.reset();
            PacketDistributor.sendToPlayer(player, packet);
            sent += n;
        }
        return sent;
    }

    /**
     * Detail-tier refinement sweep — <b>not</b> real-terrain promotion (that is {@link PromotionManager}).
     * Every {@link #REFINE_INTERVAL} ticks it makes sure every chunk in the near band is sampled/sent at
     * {@link #nearTier}: a chunk sampled coarse while far is re-submitted at the finer tier (the point cache
     * already holds its corners, so only the new points cost anything), and a chunk already fine but sent
     * coarse is queued for resend. The caller gates it on {@link ProxyChunkGate#mayHaveProxy}; this also
     * bails when the level has neither a stored grid nor an in-flight sample, so a proxy-free level never
     * pays the O(nearDistance^2) scan.
     */
    private static void refineNearBand(State state, Map<Byte, Batch> batches, Stream stream, ChunkPos center) {
        if (state.grids.isEmpty() && state.pending.isEmpty()) {
            return;
        }
        int r = Math.min(nearDistance, stream.radius);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                long pos = ChunkPos.asLong(center.x + dx, center.z + dz);
                if (ProxyChunkGate.isLoadedReal(state.level, pos)) {
                    continue; // real chunk: the client gets it from vanilla
                }
                Grid grid = state.grids.get(pos);
                if (grid != null) {
                    if (grid.tier() >= nearTier) {
                        if (stream.sent.get(pos) < grid.tier()) {
                            addToBatch(batches, stream, pos, grid);
                        }
                        continue;
                    }
                }
                if (state.pending.get(pos) >= nearTier) {
                    continue;
                }
                if (state.pending.size() >= MAX_PENDING) {
                    return;
                }
                state.pending.put(pos, nearTier);
                state.worker.submit(pos, nearTier, true);
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
        /** membership mirror of {@code wanted[wantedIndex..]}: O(1) dedup without scanning the queue. */
        final LongOpenHashSet queued = new LongOpenHashSet();
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

        /** empty the lists but keep their backing arrays for reuse next tick. */
        void reset() {
            pos.clear();
            heights.clear();
            biomes.clear();
            blocks.clear();
        }
    }

    /**
     * Client reconciliation: the client asks for meshes it is missing (join drops, reconnects, ...).
     * Cached grids go out immediately (one reply batch per tier), uncached ones jump the wanted queue via
     * `urgent`.
     */
    public static void onMeshRequest(ServerPlayer player, MeshRequestPayload payload) {
        State state = state(player.level());
        if (state == null || state.worker == null) {
            return;
        }
        Stream stream = state.streams.computeIfAbsent(player.getUUID(), id -> new Stream());
        ChunkPos center = player.chunkPosition();
        Map<Byte, Batch> batches = new HashMap<>();
        int urgentAdded = 0;
        for (long pos : payload.chunkPos()) {
            int dx = ChunkPos.getX(pos) - center.x;
            int dz = ChunkPos.getZ(pos) - center.z;
            if (Math.max(Math.abs(dx), Math.abs(dz)) > radius + 2) {
                continue; // sanity: only serve chunks near the player
            }
            Grid grid = state.grids.get(pos);
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
                if (urgentAdded < 64 && state.pending.size() < MAX_PENDING && state.pending.get(pos) < want) {
                    state.pending.put(pos, want);
                    state.worker.submit(pos, want, true);
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
