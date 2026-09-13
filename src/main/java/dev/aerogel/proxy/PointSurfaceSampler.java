package dev.aerogel.proxy;

import dev.aerogel.common.ProxyTopology;
import it.unimi.dsi.fastutil.longs.Long2ShortOpenHashMap;
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
    private static final int CEILING = 191;
    /** point-cache cap (~8 MB): clear rather than grow without bound during a long flight. */
    private static final int MAX_CACHE = 512 * 1024;

    private final DensityFunction density;
    private final int minY;
    private final int cellH;
    private final int seaLevel;
    private final int jMax;
    private final Long2ShortOpenHashMap cache = new Long2ShortOpenHashMap();
    private long evals;
    private long heightSum;
    private long heightCount;

    public PointSurfaceSampler(RandomState randomState, NoiseGeneratorSettings settings) {
        this.density = randomState.router().finalDensity();
        NoiseSettings base = settings.noiseSettings();
        this.minY = base.minY();
        this.cellH = base.getCellHeight();
        this.seaLevel = settings.seaLevel();
        this.jMax = Math.max(0, Math.min((CEILING - minY) / cellH, base.height() / cellH - 1));
        this.cache.defaultReturnValue(NONE);
    }

    /** number of top-level density evaluations performed since the last {@link #resetStats()}. */
    public long evalCount() {
        return evals;
    }

    public void resetStats() {
        evals = 0;
    }

    public void clearCache() {
        cache.clear();
        heightSum = 0;
        heightCount = 0;
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
        if (cache.size() >= MAX_CACHE) {
            cache.clear();
            heightSum = 0;
            heightCount = 0;
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
        evals++;
        return density.compute(new DensityFunction.SinglePointContext(x, y, z));
    }

    private int seed() {
        return heightCount == 0 ? seaLevel : (int) (heightSum / heightCount);
    }

    private static int avg(int a, int b) {
        return (a + b) >> 1;
    }
}
