package dev.aerogel.common;

/**
 * Proxy mesh detail tiers: how many surface heights a chunk carries and where they sit.
 *
 * <p>The tier id is its sample count, and the tiers are <b>nested</b>: a coarser tier's points are a
 * subset of every finer tier's points, so a chunk can be sampled coarsely far away and refined as it
 * approaches without ever moving an already-known point. The client bakes each chunk at exactly the tier
 * the server sent (so {@code /aerogel detail near 81} really renders 81 and the debug wireframe matches
 * the textured geometry); a promotion therefore changes both heights and triangulation — a pop.</p>
 *
 * <ul>
 *   <li>{@link #FIVE} — 4 chunk corners + centre (4 triangles fanning the centre). Cheapest: corners are
 *       shared by up to four chunks.</li>
 *   <li>{@link #NINE} — 3x3 grid at 8-block spacing (corners + edge midpoints + centre). FIVE is a subset
 *       (indices 0,2,6,8,4).</li>
 *   <li>{@link #GRID25} — 5x5 grid at 4-block spacing (NINE is a subset).</li>
 *   <li>{@link #GRID81} — 9x9 grid at 2-block spacing (GRID25 is a subset).</li>
 * </ul>
 *
 * <p>All points sit on the density cell lattice in xz (multiples of {@code cellW=4}), which is what lets
 * {@code PointSurfaceSampler} reproduce vanilla's interpolated height exactly.</p>
 */
public final class ProxyTopology {
    public static final byte FIVE = 5;
    public static final byte NINE = 9;
    public static final byte GRID25 = 25;
    public static final byte GRID81 = 81;
    public static final byte DEFAULT = NINE;

    private ProxyTopology() {
    }

    public static boolean valid(byte topology) {
        return topology == FIVE || topology == NINE || topology == GRID25 || topology == GRID81;
    }

    /** heights stored per chunk for this tier (the tier id IS the count). */
    public static int count(byte topology) {
        return topology;
    }

    /** points per axis for a square-grid tier (FIVE has no uniform side; report 3 for its fan layout). */
    public static int side(byte topology) {
        return switch (topology) {
            case NINE -> 3;
            case GRID25 -> 5;
            case GRID81 -> 9;
            default -> 3; // FIVE expands to a 3x3
        };
    }

    /** blocks between adjacent grid points for a square-grid tier (FIVE uses its corner spacing). */
    public static int spacing(byte topology) {
        return switch (topology) {
            case GRID25 -> 4;
            case GRID81 -> 2;
            default -> 8;
        };
    }

    /** local block offset in x of point {@code idx} (0..count-1). */
    public static int localX(byte topology, int idx) {
        if (topology == FIVE) {
            return idx == 0 || idx == 1 ? 0 : idx == 4 ? 8 : 16;
        }
        return (idx / side(topology)) * spacing(topology);
    }

    /** local block offset in z of point {@code idx} (0..count-1). */
    public static int localZ(byte topology, int idx) {
        if (topology == FIVE) {
            return idx == 0 || idx == 2 ? 0 : idx == 4 ? 8 : 16;
        }
        return (idx % side(topology)) * spacing(topology);
    }

    /**
     * Upsample {@code src} (a {@code from} grid) to a {@code to} grid. Nested tiers mean this is a pure
     * refinement: known points are kept, new points are bilinearly interpolated. FIVE is first expanded to
     * its 3x3 form (edge midpoints = average of the two adjacent corners). Returns {@code src} unchanged
     * when {@code from == to}.
     */
    public static short[] upsample(byte from, short[] src, byte to) {
        if (from == to) {
            return src;
        }
        byte base = from;
        short[] baseGrid = src;
        if (from == FIVE) {
            base = NINE;
            baseGrid = new short[9];
            baseGrid[0] = src[0];
            baseGrid[2] = src[1];
            baseGrid[6] = src[2];
            baseGrid[8] = src[3];
            baseGrid[4] = src[4];
            baseGrid[1] = avg(src[0], src[1]);
            baseGrid[3] = avg(src[0], src[2]);
            baseGrid[5] = avg(src[1], src[3]);
            baseGrid[7] = avg(src[2], src[3]);
        }
        if (base == to) {
            return baseGrid;
        }
        int fromSide = side(base);
        int toSide = side(to);
        double step = (double) (fromSide - 1) / (toSide - 1);
        short[] out = new short[toSide * toSide];
        for (int i = 0; i < toSide; i++) {
            double vx = i * step;
            int i0 = Math.min((int) vx, fromSide - 2);
            double fx = vx - i0;
            for (int k = 0; k < toSide; k++) {
                double vz = k * step;
                int k0 = Math.min((int) vz, fromSide - 2);
                double fz = vz - k0;
                double h00 = baseGrid[i0 * fromSide + k0];
                double h10 = baseGrid[(i0 + 1) * fromSide + k0];
                double h01 = baseGrid[i0 * fromSide + k0 + 1];
                double h11 = baseGrid[(i0 + 1) * fromSide + k0 + 1];
                double a = h00 + (h10 - h00) * fx;
                double b = h01 + (h11 - h01) * fx;
                out[i * toSide + k] = (short) Math.round(a + (b - a) * fz);
            }
        }
        return out;
    }

    private static short avg(short a, short b) {
        return (short) ((a + b) >> 1);
    }

    /** parse a command/property value ("five"/"5", "nine"/"9", "25", "81"), or -1 if unknown. */
    public static byte parse(String s) {
        if (s == null) {
            return -1;
        }
        return switch (s.toLowerCase(java.util.Locale.ROOT)) {
            case "five", "5", "corners", "corner" -> FIVE;
            case "nine", "9", "grid", "grid3", "3x3" -> NINE;
            case "25", "grid5", "5x5" -> GRID25;
            case "81", "grid9", "9x9" -> GRID81;
            default -> -1;
        };
    }

    public static String name(byte topology) {
        return switch (topology) {
            case FIVE -> "5";
            case NINE -> "9";
            case GRID25 -> "25";
            case GRID81 -> "81";
            default -> "?" + topology;
        };
    }
}
