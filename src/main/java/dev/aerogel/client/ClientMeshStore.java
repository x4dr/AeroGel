package dev.aerogel.client;

import dev.aerogel.net.MeshBatchPayload;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Client-side store of streamed proxy meshes. Pure data (no client-only classes) so it is safe to
 * reference from the common payload handler. Touched on the render/client thread only (enqueueWork).
 *
 * <p>Each chunk keeps the tier it was sampled at, its heights, and its biome colour/representative block.
 * The renderer always bakes at its fixed render tier, upsampling a coarse mesh, so a tier promotion just
 * replaces the stored grid.</p>
 */
public final class ClientMeshStore {
    /** one chunk's streamed proxy data. */
    public record Mesh(byte topology, short[] grid, int biome, int block) {
    }

    private static final Long2ObjectOpenHashMap<Mesh> meshes = new Long2ObjectOpenHashMap<>();
    /**
     * Thread-safe mirror of {@link #meshes}' keys. The vanilla section-compile worker calls
     * {@code RenderSection.doesChunkExistAt} for neighbours, and AeroGel must report a proxy neighbour as
     * present; the main {@code meshes} map is not safe to read off the client thread.
     *
     * <p>A primitive {@link LongOpenHashSet} guarded by a read/write lock replaces the old
     * {@code ConcurrentHashMap.newKeySet()} of boxed {@code Long}s: reads (the worker threads) share the
     * read lock and never block each other, writes (payload/prune/clear, client thread) are rare. At the
     * 256-chunk store radius the boxed set cost roughly 5-7x the memory of the primitive set.</p>
     */
    private static final LongOpenHashSet proxyPositions = new LongOpenHashSet();
    private static final ReentrantReadWriteLock positionsLock = new ReentrantReadWriteLock();
    private static boolean dirty;

    /** render-perf accumulators: payload work done since the last frame (read + reset by ProxyRenderer). */
    private static long handleNanosAccum;
    private static int handleChunksAccum;

    private ClientMeshStore() {
    }

    public static void handle(MeshBatchPayload payload) {
        if (!ClientFeatures.store) {
            return;
        }
        long t0 = System.nanoTime();
        int per = payload.heightsPerChunk();
        byte topology = payload.topology();
        // Guard the ids that arrived over the wire. Block.stateById maps an out-of-range id to air (and some
        // mappings return null), and an invalid representative block resolves to the missing/black sprite;
        // clamp to a sane default here so the renderer never sees a bogus id. Biome ids < -1 are clamped to
        // -1 ("no tint"); BiomeColors additionally null-checks the registry lookup.
        int maxBlockId = Block.BLOCK_STATE_REGISTRY.size();
        int defaultBlockId = Block.getId(Blocks.GRASS_BLOCK.defaultBlockState());
        positionsLock.writeLock().lock();
        try {
            for (int i = 0; i < payload.chunkPos().length; i++) {
                short[] grid = new short[per];
                System.arraycopy(payload.heights(), i * per, grid, 0, per);
                long pos = payload.chunkPos()[i];
                int biome = payload.biomes()[i];
                int block = payload.blocks()[i];
                if (block <= 0 || block >= maxBlockId) {
                    block = defaultBlockId;
                }
                if (biome < -1) {
                    biome = -1;
                }
                meshes.put(pos, new Mesh(topology, grid, biome, block));
                proxyPositions.add(pos);
            }
        } finally {
            positionsLock.writeLock().unlock();
        }
        dirty = true;
        handleNanosAccum += System.nanoTime() - t0;
        handleChunksAccum += payload.chunkPos().length;
    }

    /** nanos spent handling mesh payloads since the last call; resets the accumulator. */
    public static long consumeHandleNanos() {
        long v = handleNanosAccum;
        handleNanosAccum = 0;
        return v;
    }

    /** chunks received in mesh payloads since the last call; resets the accumulator. */
    public static int consumeHandleChunks() {
        int v = handleChunksAccum;
        handleChunksAccum = 0;
        return v;
    }

    public static Mesh get(long chunkPos) {
        return meshes.get(chunkPos);
    }

    public static boolean has(long chunkPos) {
        return meshes.containsKey(chunkPos);
    }

    /** thread-safe: true when the position has a proxy mesh (callable from the section-compile workers). */
    public static boolean isProxy(long chunkPos) {
        positionsLock.readLock().lock();
        try {
            return proxyPositions.contains(chunkPos);
        } finally {
            positionsLock.readLock().unlock();
        }
    }

    public static Long2ObjectOpenHashMap<Mesh> meshes() {
        return meshes;
    }

    public static int size() {
        return meshes.size();
    }

    public static boolean isDirty() {
        return dirty;
    }

    public static boolean consumeDirty() {
        if (!dirty) {
            return false;
        }
        dirty = false;
        return true;
    }

    public static void clear() {
        positionsLock.writeLock().lock();
        try {
            if (!meshes.isEmpty()) {
                meshes.clear();
                proxyPositions.clear();
                dirty = true;
            }
        } finally {
            positionsLock.writeLock().unlock();
        }
    }

    public static void prune(ChunkPos center, int radius) {
        boolean removed = false;
        positionsLock.writeLock().lock();
        try {
            var it = meshes.long2ObjectEntrySet().iterator();
            while (it.hasNext()) {
                Long2ObjectMap.Entry<Mesh> entry = it.next();
                long pos = entry.getLongKey();
                int dx = ChunkPos.getX(pos) - center.x;
                int dz = ChunkPos.getZ(pos) - center.z;
                if (Math.max(Math.abs(dx), Math.abs(dz)) > radius) {
                    it.remove();
                    proxyPositions.remove(pos);
                    removed = true;
                }
            }
        } finally {
            positionsLock.writeLock().unlock();
        }
        if (removed) {
            dirty = true;
        }
    }
}
