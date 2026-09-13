package dev.aerogel.proxy;

import dev.aerogel.common.ProxyTopology;
import it.unimi.dsi.fastutil.longs.Long2ShortLinkedOpenHashMap;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * Sparse, hint-driven surface sampler: evaluates the density function only at the handful of mesh points a
 * chunk actually needs, instead of filling a whole {@code NoiseChunk} lattice.
 *
 * <p>Every mesh point sits on the density cell lattice in xz (chunk corners are multiples of 16, edge
 * midpoints multiples of 8, the centre {@code +8} — all multiples of {@code cellW=4}). At an exact lattice
 * xz corner vanilla's trilinear interpolation reduces to the single corner value, so the only interpolation
 * is vertical and it is linear between two {@code cellH=8} y-levels. A pointwise scan at those y-levels and
 * a linear crossing therefore reproduces vanilla's interpolated height, with no {@link
 * net.minecraft.world.level.levelgen.NoiseChunk}.</p>
 *
 * <p>Points are cached globally by world (x,z): a chunk corner is shared by up to four chunks and evaluated
 * once. Each scan starts from a nearby already-known height (a neighbour corner / the chunk's own points) so
 * it only probes a couple of y-levels. Thread-confined.</p>
 */
public final class PointSurfaceSampler {
    private static final short NONE = Short.MIN_VALUE;

    /**
     * Total point-cache budget for one worker pool, shared across its threads. A
     * {@link Long2ShortLinkedOpenHashMap} entry costs ~24 B (8 B key + 2 B value + 8 B FIFO link,
     * inflated by the ~0.75 load factor), so 2M entries bound a pool at ~48 MB regardless of the
     * worker count. The old per-worker 512k cap reached ~512 MB at 64 threads.
     */
    private static final int TOTAL_CACHE_BUDGET = 2 * 1024 * 1024;
    /** a single worker still wants a usable cache; the budget is never split below this. */
    private static final int MIN_WORKER_CACHE = 16 * 1024;
    /** no worker may hoard the whole budget. */
    private static final int MAX_WORKER_CACHE = 256 * 1024;

    /** the per-worker FIFO cap for a pool with {@code threads} workers. */
    public static int workerCacheCap(int threads) {
        return Math.clamp(TOTAL_CACHE_BUDGET / Math.max(1, threads), MIN_WORKER_CACHE, MAX_WORKER_CACHE);
    }

    private final DensityFunction density;
    private final int minY;
    private final int cellH;
    private final int seaLevel;
    private final int jMax;
    private final int maxCache;
    private final Long2ShortLinkedOpenHashMap cache = new Long2ShortLinkedOpenHashMap();
    private long heightSum;
    private long heightCount;

    public PointSurfaceSampler(RandomState randomState, NoiseGeneratorSettings settings, int maxCache) {
        this.density = randomState.router().finalDensity();
        NoiseSettings base = settings.noiseSettings();
        this.minY = base.minY();
        this.cellH = base.getCellHeight();
        this.seaLevel = settings.seaLevel();
        // Scan the dimension's own column: its top is minY + height, not the overworld's old 191 cap,
        // so the nether (128 tall) and end (256) reach their real surface. Overworld terrain below the
        // old cap is unaffected; only terrain above y=184 now samples correctly instead of clamping.
        int maxY = minY + base.height();
        this.jMax = Math.max(0, (maxY - 1 - minY) / cellH);
        this.maxCache = Math.max(1, maxCache);
        this.cache.defaultReturnValue(NONE);
    }

    /**
     * Heights for one chunk in the requested tier. Five-point order: (0,0),(0,16),(16,0),(16,16),(8,8).
     * Square-grid tiers are row-major i=x, k=z at {@code (i*spacing, k*spacing)}.
     */
    public short[] sample(int chunkX, int chunkZ, byte topology) {
        int x0 = chunkX << 4;
        int z0 = chunkZ << 4;
        if (topology == ProxyTopology.FIVE) {
            short c00 = get(x0, z0, seed());
            short c01 = get(x0, z0 + 16, c00);
            short c10 = get(x0 + 16, z0, c00);
            short c11 = get(x0 + 16, z0 + 16, avg(c01, c10));
            int hi = Math.max(Math.max(c00, c01), Math.max(c10, c11));
            short cc = get(x0 + 8, z0 + 8, hi);
            return new short[] { c00, c01, c10, c11, cc };
        }
        int side = ProxyTopology.side(topology);
        int spacing = ProxyTopology.spacing(topology);
        short[] h = new short[side * side];
        for (int i = 0; i < side; i++) {
            for (int k = 0; k < side; k++) {
                int x = x0 + i * spacing;
                int z = z0 + k * spacing;
                int sum = 0;
                int n = 0;
                if (i > 0) {
                    sum += h[(i - 1) * side + k];
                    n++;
                }
                if (k > 0) {
                    sum += h[i * side + k - 1];
                    n++;
                }
                h[i * side + k] = get(x, z, n == 0 ? seed() : sum / n);
            }
        }
        return h;
    }

    private short get(int x, int z, int hint) {
        long key = ((long) x << 32) | (z & 0xFFFFFFFFL);
        short v = cache.get(key);
        if (v != NONE) {
            return v;
        }
        if (cache.size() >= maxCache) {
            // FIFO-evict the oldest eighth: keeps the recent flight corridor (shared corners stay warm)
            // and amortises the sweep to O(1) per insert. A full clear dropped every shared corner and
            // forced an eval burst; a true LRU would pay a move-to-front on every cache hit.
            int target = maxCache - Math.max(1, maxCache >> 3);
            while (cache.size() > target) {
                short evicted = cache.removeFirstShort();
                heightSum -= evicted;
                heightCount--;
            }
        }
        short h = scan(x, z, hint);
        cache.put(key, h);
        heightSum += h;
        heightCount++;
        return h;
    }

    /**
     * Topmost y where the interpolated density crosses zero, starting at the lattice level nearest
     * {@code hintY} and expanding in whichever direction the surface actually lies (so a good hint costs
     * one or two evals).
     */
    private short scan(int x, int z, int hintY) {
        int jHint = Math.max(0, Math.min(jMax, (hintY - minY) / cellH));
        double v = eval(x, minY + jHint * cellH, z);
        if (v > 0.0) {
            // solid at the hint -> surface is at or above it; climb to the first air level.
            double solid = v;
            for (int j = jHint + 1; j <= jMax; j++) {
                double up = eval(x, minY + j * cellH, z);
                if (up <= 0.0) {
                    double yLow = minY + (j - 1) * cellH;
                    double yCross = yLow + cellH * (solid / (solid - up));
                    return (short) Math.max((int) Math.ceil(yCross) - 1, seaLevel);
                }
                solid = up;
            }
            return (short) Math.max(minY + jMax * cellH, seaLevel);
        }
        // air at the hint -> surface is below; descend to the first solid level.
        double prev = v;
        for (int j = jHint - 1; j >= 0; j--) {
            double down = eval(x, minY + j * cellH, z);
            if (down > 0.0) {
                double yLow = minY + j * cellH;
                double yCross = yLow + cellH * (down / (down - prev));
                return (short) Math.max((int) Math.ceil(yCross) - 1, seaLevel);
            }
            prev = down;
        }
        return (short) seaLevel;
    }

    private double eval(int x, int y, int z) {
        return density.compute(new DensityFunction.SinglePointContext(x, y, z));
    }

    private int seed() {
        return heightCount == 0 ? seaLevel : (int) (heightSum / heightCount);
    }

    private static int avg(int a, int b) {
        return (a + b) >> 1;
    }
}
