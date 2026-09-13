package dev.aerogel.proxy;

import java.util.List;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * A pool of {@link OffThreadSampler} workers. Each worker owns its own {@link RandomState}, point cache and
 * {@link BiomeTint}; chunks are routed by a coarse region so neighbouring chunks (which share lattice
 * corners) land on the same worker and reuse its cache. A worker pool is what makes radius >~32
 * comfortable: one sampler thread saturates near there.
 *
 * <p>This is the same surface the streamer used when there was a single worker, plus {@link #threads()}.</p>
 */
public final class SamplerPool implements AutoCloseable {
    /** chunks per routing region (log2). 8x8 keeps cache locality without making the pool lopsided. */
    private static final int REGION_SHIFT = 3;

    private final OffThreadSampler[] workers;
    private int cursor;

    public SamplerPool(List<RandomState> states, NoiseGeneratorSettings settings, BiomeSource biomeSource,
            net.minecraft.core.Registry<net.minecraft.world.level.biome.Biome> biomeRegistry, int threads) {
        int n = Math.max(1, Math.min(threads, states.size()));
        workers = new OffThreadSampler[n];
        for (int i = 0; i < n; i++) {
            workers[i] = new OffThreadSampler(states.get(i), settings, biomeSource, biomeRegistry);
        }
    }

    private int route(long pos) {
        int rx = ChunkPos.getX(pos) >> REGION_SHIFT;
        int rz = ChunkPos.getZ(pos) >> REGION_SHIFT;
        int h = (rx * 0x9E3779B1) ^ (rz * 0x85EBCA6B);
        return Math.floorMod(h, workers.length);
    }

    public void submit(long pos, byte topology, boolean urgent) {
        workers[route(pos)].submit(pos, topology, urgent);
    }

    public OffThreadSampler.Result poll() {
        int n = workers.length;
        for (int i = 0; i < n; i++) {
            int idx = (cursor + i) % n;
            OffThreadSampler.Result r = workers[idx].poll();
            if (r != null) {
                cursor = (idx + 1) % n;
                return r;
            }
        }
        return null;
    }

    public int backlog() {
        int s = 0;
        for (OffThreadSampler w : workers) {
            s += w.backlog();
        }
        return s;
    }

    public long sampledCount() {
        long s = 0;
        for (OffThreadSampler w : workers) {
            s += w.sampledCount();
        }
        return s;
    }

    public long sampledNanos() {
        long s = 0;
        for (OffThreadSampler w : workers) {
            s += w.sampledNanos();
        }
        return s;
    }

    public double avgMillis() {
        long c = sampledCount();
        return c == 0 ? 0.0 : sampledNanos() / 1e6 / c;
    }

    public int threads() {
        return workers.length;
    }

    @Override
    public void close() {
        for (OffThreadSampler w : workers) {
            w.close();
        }
    }
}
