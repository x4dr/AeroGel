package dev.aerogel.client;

import dev.aerogel.net.MeshBatchPayload;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.world.level.ChunkPos;

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
     */
    private static final java.util.Set<Long> proxyPositions = java.util.concurrent.ConcurrentHashMap.newKeySet();
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
        for (int i = 0; i < payload.chunkPos().length; i++) {
            short[] grid = new short[per];
            System.arraycopy(payload.heights(), i * per, grid, 0, per);
            long pos = payload.chunkPos()[i];
            meshes.put(pos, new Mesh(topology, grid, payload.biomes()[i], payload.blocks()[i]));
            proxyPositions.add(pos);
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
        return proxyPositions.contains(chunkPos);
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
        if (!meshes.isEmpty()) {
            meshes.clear();
            proxyPositions.clear();
            dirty = true;
        }
    }

    public static void prune(ChunkPos center, int radius) {
        boolean removed = false;
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
        if (removed) {
            dirty = true;
        }
    }
}
